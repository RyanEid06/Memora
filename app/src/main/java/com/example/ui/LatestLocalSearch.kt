package com.example.ui

import androidx.annotation.MainThread
import com.example.data.ItemWithCollections
import com.example.data.LibrarySearchCursor
import com.example.data.LibrarySearchPage
import com.example.data.LibrarySearchRequest
import com.example.diagnostics.OperationDiagnostics
import com.example.model.DateBasis
import com.example.model.DateFilter
import com.example.model.DateGrouping
import com.example.model.ItemKind
import com.example.model.SortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal const val LOCAL_SEARCH_ERROR = "Could not search your library. Try again."

/** One immutable snapshot is used for both flat results and grouped results. */
internal data class LocalSearchContext(
    val query: String = "",
    val sourceItems: List<ItemWithCollections> = emptyList(),
    val kind: ItemKind? = null,
    val collectionId: String? = null,
    val dateFilter: DateFilter = DateFilter.ALL_TIME,
    val basis: DateBasis = DateBasis.SAVED_DATE,
    val sort: SortOrder = SortOrder.SAVED_NEWEST,
    val customStart: Long? = null,
    val customEnd: Long? = null,
    val grouping: DateGrouping = DateGrouping.NONE
) {
    fun hasSameScope(other: LocalSearchContext): Boolean =
        kind == other.kind && collectionId == other.collectionId && dateFilter == other.dateFilter &&
            basis == other.basis && customStart == other.customStart && customEnd == other.customEnd
}

internal data class LocalSearchResult(
    val items: List<ItemWithCollections>,
    val sections: List<GroupedLibrarySection>,
    val snippets: Map<String, String> = emptyMap(),
    val nextCursor: LibrarySearchCursor? = null
)

/** Display continuity never implies that selection or a request is current. */
internal data class LocalSearchState(
    val result: LocalSearchResult? = null,
    val displayedQuery: String = "",
    val queryPending: Boolean = false,
    val searchInFlight: Boolean = true,
    val showProgress: Boolean = false,
    val searchError: Boolean = false
) {
    val hasPreviousResults: Boolean get() = result != null
    val initialLoad: Boolean get() = result == null && (queryPending || searchInFlight)
}

/** Cancellation reduces wasted work; the generation check also rejects non-cooperative work. */
internal class LatestLocalSearch(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> List<ItemWithCollections>,
    private val onInvalidated: () -> Unit,
    private val onResult: (LocalSearchResult) -> Unit,
    private val onError: () -> Unit,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val publicationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onPending: () -> Unit = {},
    private val onRetained: (LocalSearchResult) -> Unit = onResult,
    private val pageSearch: (suspend (LocalSearchContext, LibrarySearchCursor?) -> LibrarySearchPage)? = null
) {
    private val generation = AtomicLong()
    private var job: Job? = null
    val currentGeneration: Long get() = generation.get()
    private val _state = MutableStateFlow(LocalSearchState())
    val state = _state.asStateFlow()
    private var displayedContext: LocalSearchContext? = null
    private var lastSourceItems: List<ItemWithCollections>? = null

    // Called by the ViewModel's Main-thread setters and Room collectors. Publication uses the
    // same dispatcher, so checking the generation and changing visible state cannot interleave.
    @MainThread
    fun refresh(context: LocalSearchContext, debounceQuery: Boolean = false, sourceRefresh: Boolean = false) {
        val capturedGeneration = generation.incrementAndGet()
        val previous = _state.value
        var retained = previous.result.takeIf { displayedContext?.hasSameScope(context) == true }
        // Room has already supplied the authoritative active rows. Remove deleted/moved rows
        // synchronously, before debounce, even while retaining the other displayed matches.
        if (retained != null && lastSourceItems != null && lastSourceItems !== context.sourceItems) {
            val available = context.sourceItems.associateBy { it.item.id }
            val current = retained.items.mapNotNull { available[it.item.id] }
            retained = projectLocalSearch(current, requireNotNull(displayedContext), preserveOrder = true)
                .copy(snippets = retained.snippets.filterKeys { id -> current.any { it.item.id == id } })
        }
        lastSourceItems = context.sourceItems
        val pending = debounceQuery && context.query.isNotBlank()
        _state.value = LocalSearchState(retained,
            if (retained == null) context.query else previous.displayedQuery,
            queryPending = pending, searchInFlight = !pending)
        if (retained == null) onInvalidated()
        else if (retained != previous.result) onRetained(retained)
        onPending()
        job?.cancel()
        job = scope.launch(workerDispatcher) {
            val startedNanos = System.nanoTime()
            val progress = launch {
                delay(275)
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) _state.value = _state.value.copy(showProgress = true)
                }
            }
            try {
                if (pending) delay(150) else if (sourceRefresh) delay(75)
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get())
                        _state.value = _state.value.copy(queryPending = false, searchInFlight = true)
                }
                val page = pageSearch?.invoke(context, null)
                val rows = page?.items ?: if (context.query.isBlank()) context.sourceItems else search(context.query)
                val result = projectLocalSearch(rows, context, preserveOrder = page != null)
                    .copy(snippets = page?.snippets ?: emptyMap(), nextCursor = page?.nextCursor)
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) {
                        displayedContext = context.copy(sourceItems = emptyList())
                        _state.value = LocalSearchState(result, context.query, searchInFlight = false)
                        onResult(result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OperationDiagnostics.failure("local_search", startedNanos, error, 1)
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) {
                        _state.value = _state.value.copy(queryPending = false, searchInFlight = false, showProgress = false, searchError = true)
                        onError()
                    }
                }
            } finally { progress.cancel() }
        }
    }

    @MainThread
    fun loadMore() {
        val execute = pageSearch ?: return
        val context = displayedContext ?: return
        val previous = _state.value
        val result = previous.result ?: return
        val cursor = result.nextCursor ?: return
        if (previous.queryPending || previous.searchInFlight) return
        val capturedGeneration = generation.get()
        onPending()
        _state.value = previous.copy(searchInFlight = true, searchError = false)
        job = scope.launch(workerDispatcher) {
            val startedNanos = System.nanoTime()
            try {
                val page = execute(context, cursor)
                val rows = (result.items + page.items).distinctBy { it.item.id }
                val appended = projectLocalSearch(rows, context, preserveOrder = true)
                    .copy(snippets = result.snippets + page.snippets, nextCursor = page.nextCursor)
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) {
                        _state.value = LocalSearchState(appended, context.query, searchInFlight = false)
                        onResult(appended)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                OperationDiagnostics.failure("local_search_page", startedNanos, error, result.items.size)
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) {
                        _state.value = _state.value.copy(searchInFlight = false, searchError = true)
                        onError()
                    }
                }
            }
        }
    }
}

/** Also applies the visible UI constraints to additive AI candidates. It never calls a provider. */
internal fun projectLocalSearch(rows: List<ItemWithCollections>, context: LocalSearchContext, preserveOrder: Boolean = false): LocalSearchResult {
    val calendar = Calendar.getInstance()
    val currentYear = calendar.get(Calendar.YEAR)
    val currentMonth = calendar.get(Calendar.MONTH)
    val filtered = rows.asSequence()
        .filter { context.kind == null || it.item.kind == context.kind.name }
        .filter { row ->
            when (context.collectionId) {
                null -> true
                "computed_favorites" -> row.item.isFavorite
                "computed_unfiled" -> row.collections.isEmpty()
                "computed_due" -> row.item.reminderTime != null && !row.item.isReminderDone
                else -> row.collections.any { it.id == context.collectionId }
            }
        }
        .filter { row ->
            val timestamp = if (context.basis == DateBasis.SOURCE_DATE) row.item.sourceDate else row.item.createdAt
            when (context.dateFilter) {
                DateFilter.ALL_TIME -> true
                DateFilter.THIS_MONTH -> timestamp != null && run {
                    calendar.timeInMillis = timestamp
                    calendar.get(Calendar.YEAR) == currentYear && calendar.get(Calendar.MONTH) == currentMonth
                }
                DateFilter.THIS_YEAR -> timestamp != null && run {
                    calendar.timeInMillis = timestamp
                    calendar.get(Calendar.YEAR) == currentYear
                }
                DateFilter.CUSTOM -> timestamp != null && context.customStart != null && context.customEnd != null &&
                    timestamp >= context.customStart && timestamp <= context.customEnd
            }
        }
        .sortedWith { a, b ->
            if (preserveOrder) return@sortedWith 0
            when (context.sort) {
                SortOrder.RELEVANCE -> 0
                SortOrder.SAVED_NEWEST -> b.item.createdAt.compareTo(a.item.createdAt)
                SortOrder.SAVED_OLDEST -> a.item.createdAt.compareTo(b.item.createdAt)
                SortOrder.SOURCE_NEWEST -> (b.item.sourceDate ?: Long.MIN_VALUE).compareTo(a.item.sourceDate ?: Long.MIN_VALUE)
                SortOrder.SOURCE_OLDEST -> (a.item.sourceDate ?: Long.MAX_VALUE).compareTo(b.item.sourceDate ?: Long.MAX_VALUE)
                SortOrder.TITLE_AZ -> a.item.title.compareTo(b.item.title, true)
            }
        }.toList()
    val sections = if (context.grouping == DateGrouping.NONE) {
        listOf(GroupedLibrarySection("", filtered))
    } else {
        val formatter = SimpleDateFormat(when (context.grouping) {
            DateGrouping.DAY -> "EEEE, MMMM d, yyyy"
            DateGrouping.MONTH -> "MMMM yyyy"
            DateGrouping.YEAR -> "yyyy"
            DateGrouping.NONE -> ""
        }, Locale.getDefault())
        filtered.groupBy { row ->
            val ts = if (context.basis == DateBasis.SOURCE_DATE) row.item.sourceDate else row.item.createdAt
            if (ts == null) "No ${context.basis.label}" else formatter.format(Date(ts))
        }.map { (title, items) -> GroupedLibrarySection(title, items) }
    }
    return LocalSearchResult(filtered, sections)
}

internal fun LocalSearchContext.toLibrarySearchRequest(): LibrarySearchRequest {
    var start: Long? = null
    var end: Long? = null
    var invalid = false
    when (dateFilter) {
        DateFilter.ALL_TIME -> Unit
        DateFilter.CUSTOM -> {
            start = customStart
            end = customEnd?.takeUnless { it == Long.MAX_VALUE }?.plus(1)
            invalid = customStart == null || customEnd == null || customStart > customEnd
        }
        DateFilter.THIS_MONTH, DateFilter.THIS_YEAR -> {
            val calendar = Calendar.getInstance()
            val year = calendar.get(Calendar.YEAR)
            val month = if (dateFilter == DateFilter.THIS_YEAR) Calendar.JANUARY else calendar.get(Calendar.MONTH)
            calendar.clear()
            calendar.set(year, month, 1)
            start = calendar.timeInMillis
            calendar.add(if (dateFilter == DateFilter.THIS_YEAR) Calendar.YEAR else Calendar.MONTH, 1)
            end = calendar.timeInMillis
        }
    }
    return LibrarySearchRequest(query, kind?.name, collectionId, basis, start, end, invalid, sort)
}
