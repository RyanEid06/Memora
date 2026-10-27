package com.example.ui

import androidx.annotation.MainThread
import com.example.data.ItemWithCollections
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
    val sections: List<GroupedLibrarySection>
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
    private val onRetained: (LocalSearchResult) -> Unit = onResult
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
            val wanted = retained.items.mapTo(HashSet()) { it.item.id }
            val current = context.sourceItems.filter { it.item.id in wanted }
            retained = projectLocalSearch(current, requireNotNull(displayedContext))
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
                val rows = if (context.query.isBlank()) context.sourceItems else search(context.query)
                val result = projectLocalSearch(rows, context)
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) {
                        displayedContext = context.copy(sourceItems = emptyList())
                        _state.value = LocalSearchState(result, context.query, searchInFlight = false)
                        onResult(result)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(publicationDispatcher) {
                    if (capturedGeneration == generation.get()) {
                        _state.value = _state.value.copy(queryPending = false, searchInFlight = false, showProgress = false, searchError = true)
                        onError()
                    }
                }
            } finally { progress.cancel() }
        }
    }
}

/** Also applies the visible UI constraints to additive AI candidates. It never calls a provider. */
internal fun projectLocalSearch(rows: List<ItemWithCollections>, context: LocalSearchContext): LocalSearchResult {
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
            when (context.sort) {
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
