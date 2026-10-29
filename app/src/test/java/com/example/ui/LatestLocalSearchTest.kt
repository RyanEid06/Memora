package com.example.ui

import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.data.LibrarySearchCursor
import com.example.data.LibrarySearchPage
import com.example.data.LibrarySearchRequest
import com.example.model.DateFilter
import com.example.model.DateGrouping
import com.example.model.ItemKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestLocalSearchTest {
    @Test fun searchFailureEmitsSafeOperationTimingAndClassWithoutPrivatePayload() = runTest {
        val records = mutableListOf<java.util.logging.LogRecord>()
        val logger = java.util.logging.Logger.getLogger("MemoraDiagnostics")
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val dispatcher = StandardTestDispatcher(testScheduler)
            var failed = false
            val runner = LatestLocalSearch(backgroundScope,
                { throw IllegalStateException("PRIVATE_PROVIDER_QUERY_BODY") }, {}, {}, { failed = true }, dispatcher, dispatcher)
            runner.refresh(LocalSearchContext(query = "PRIVATE_OWNER_QUERY"))
            runCurrent()
            assertTrue(failed)
            assertTrue("Failed search must emit privacy-safe operation diagnostics", records.any { it.message.startsWith("operation=local_search ") })
            val record = records.single { it.message.startsWith("operation=local_search ") }
            assertTrue(record.message.contains("error_class=IllegalStateException"))
            assertTrue(record.message.contains(Regex("duration_ms=\\d+")))
            assertTrue(record.message.contains("build="))
            assertFalse(record.message.contains("PRIVATE_"))
            assertNull("Diagnostics must not retain the exception/message/stack", record.thrown)

            val cursor = LibrarySearchCursor(LibrarySearchRequest(query = "PRIVATE_OWNER_QUERY"), 0, 100, "first", "first")
            val paging = LatestLocalSearch(backgroundScope, { emptyList() }, {}, {}, {}, dispatcher, dispatcher,
                pageSearch = { _, after ->
                    if (after == null) LibrarySearchPage(listOf(row("first")), emptyMap(), cursor)
                    else throw java.io.IOException("PRIVATE_PAGE_BODY")
                })
            paging.refresh(LocalSearchContext(query = "PRIVATE_OWNER_QUERY"))
            runCurrent()
            paging.loadMore()
            runCurrent()
            assertTrue(paging.state.value.searchError)
            assertEquals(listOf("first"), paging.state.value.result!!.items.map { it.item.id })
            val pageRecord = records.single { it.message.startsWith("operation=local_search_page ") }
            assertTrue(pageRecord.message.contains("error_class=IOException"))
            assertTrue(pageRecord.message.contains("count=1"))
            assertFalse(pageRecord.message.contains("PRIVATE_"))
            assertNull(pageRecord.thrown)

            val cancelled = LatestLocalSearch(backgroundScope,
                { throw kotlinx.coroutines.CancellationException("PRIVATE_CANCEL") }, {}, {},
                { fail("Cancellation must not become a search error") }, dispatcher, dispatcher)
            cancelled.refresh(LocalSearchContext(query = "PRIVATE_CANCEL_QUERY"))
            runCurrent()
            assertEquals("Cancellation is not a diagnostic failure", 2, records.size)
        } finally { logger.removeHandler(handler) }
    }

    private fun row(id: String, kind: String = "NOTE", collection: String? = null, saved: Long = 100) =
        ItemWithCollections(
            ItemEntity(id, id, "synthetic birthday evidence", kind, createdAt = saved),
            collection?.let { listOf(CollectionEntity(it, it)) } ?: emptyList()
        )

    @Test fun olderCancellationResistantSearchCannotReplaceLatestQueryOrGroups() = runTest {
        val old = CompletableDeferred<List<ItemWithCollections>>()
        val current = CompletableDeferred<List<ItemWithCollections>>()
        val publications = mutableListOf<LocalSearchResult>()
        var ready = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { query ->
            withContext(NonCancellable) { if (query == "bi") old.await() else current.await() }
        }, { ready = false }, { publications += it; ready = true }, { fail("Unexpected error") }, dispatcher, dispatcher)

        runner.refresh(LocalSearchContext(query = "bi", grouping = DateGrouping.YEAR))
        runCurrent()
        runner.refresh(LocalSearchContext(query = "birth", grouping = DateGrouping.DAY))
        assertFalse(ready)
        runCurrent()
        current.complete(listOf(row("latest")))
        runCurrent()
        assertTrue(ready)
        assertEquals(listOf("latest"), publications.last().items.map { it.item.id })
        assertEquals(publications.last().items, publications.last().sections.flatMap { it.items })
        val expectedSections = publications.last().sections
        old.complete(listOf(row("obsolete")))
        runCurrent()
        assertEquals(1, publications.size)
        assertEquals(expectedSections, publications.last().sections)
    }

    @Test fun filterCollectionAndDataChangeInvalidatePendingSearchImmediately() = runTest {
        val pending = mutableListOf<CompletableDeferred<List<ItemWithCollections>>>()
        val publications = mutableListOf<LocalSearchResult>()
        var ready = true
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, {
            val result = CompletableDeferred<List<ItemWithCollections>>()
            pending += result
            withContext(NonCancellable) { result.await() }
        }, { ready = false }, { publications += it; ready = true }, { fail("Unexpected error") }, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "b"))
        runCurrent()
        runner.refresh(LocalSearchContext(query = "b", kind = ItemKind.LINK, collectionId = "chosen", dateFilter = DateFilter.CUSTOM, customStart = 90, customEnd = 110))
        assertFalse(ready)
        runCurrent()
        // A Room source emission replaces both earlier operations, even for the same query.
        runner.refresh(LocalSearchContext(query = "b", kind = ItemKind.LINK, collectionId = "chosen", sourceItems = listOf(row("changed"))))
        assertFalse(ready)
        runCurrent()
        pending[2].complete(listOf(row("keep", "LINK", "chosen"), row("wrong-kind", "NOTE", "chosen"), row("wrong-collection", "LINK", "other")))
        runCurrent()
        assertEquals(listOf("keep"), publications.single().items.map { it.item.id })
        pending[1].complete(listOf(row("older-context", "LINK", "chosen")))
        pending[0].complete(listOf(row("old-query")))
        runCurrent()
        assertTrue(ready)
        assertEquals(1, publications.size)
    }

    @Test fun rapidTypingDebouncesOneLocalQueryAndContextChangesExecuteImmediately() = runTest {
        val queries = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { queries += it; emptyList() }, {}, {}, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "b"), debounceQuery = true)
        runCurrent()
        advanceTimeBy(100)
        runner.refresh(LocalSearchContext(query = "bi"), debounceQuery = true)
        runCurrent()
        advanceTimeBy(149)
        runCurrent()
        assertTrue(queries.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("bi"), queries)
        runner.refresh(LocalSearchContext(query = "bi", collectionId = "chosen"))
        runCurrent()
        assertEquals(listOf("bi", "bi"), queries)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, queries.size)
    }

    /** Pre-remediation characterization; immediate invalidation is measured, not endorsed. */
    @Test fun twentyKeystrokesInvalidateImmediatelyButExecuteOneDebouncedQuery() = runTest {
        val queries = mutableListOf<String>()
        var invalidations = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { queries += it; emptyList() },
            { invalidations++ }, {}, {}, dispatcher, dispatcher)
        val text = "abcdefghijklmnopqrst"
        repeat(20) { index ->
            runner.refresh(LocalSearchContext(query = text.take(index + 1)), debounceQuery = true)
            runCurrent()
            advanceTimeBy(25)
        }
        assertEquals(20, invalidations)
        assertTrue(queries.isEmpty())
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf(text), queries)
    }

    @Test fun failedLocalQueryHasDistinctErrorAndNextQueryRecovers() = runTest {
        var failed = false
        var ready = false
        val publications = mutableListOf<LocalSearchResult>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, {
            if (it == "broken") error("synthetic SQLite failure") else listOf(row("recovered"))
        }, { failed = false; ready = false }, { publications += it; ready = true }, { failed = true }, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "broken"))
        runCurrent()
        assertTrue(failed)
        assertFalse(ready)
        assertTrue(publications.isEmpty())
        runner.refresh(LocalSearchContext(query = "good"))
        assertFalse(failed)
        runCurrent()
        assertTrue(ready)
        assertFalse(failed)
        assertEquals("recovered", publications.single().items.single().item.id)
    }

    @Test fun blankQueryUsesCapturedOfflineItemsWithoutSearchOrAiDependency() = runTest {
        val publications = mutableListOf<LocalSearchResult>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { fail("Blank browse must not query"); emptyList() }, {}, { publications += it }, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(sourceItems = listOf(row("offline"))))
        runCurrent()
        assertEquals("offline", publications.single().items.single().item.id)
    }

    @Test fun compatibleResultsRemainVisibleThroughTwentyKeystrokesAndReplacementFailure() = runTest {
        var visible = emptyList<ItemWithCollections>()
        var failures = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { error("Synthetic search failure") },
            { visible = emptyList() }, { visible = it.items }, { failures++ }, dispatcher, dispatcher)
        val source = listOf(row("retained"))
        runner.refresh(LocalSearchContext(sourceItems = source))
        runCurrent()
        val text = "abcdefghijklmnopqrst"
        repeat(20) { index ->
            runner.refresh(LocalSearchContext(query = text.take(index + 1), sourceItems = source), debounceQuery = true)
            runCurrent()
            advanceTimeBy(25)
            assertEquals(listOf("retained"), visible.map { it.item.id })
            assertTrue(runner.state.value.queryPending)
            assertFalse(runner.state.value.initialLoad)
            assertFalse(runner.state.value.showProgress)
        }
        advanceTimeBy(150)
        runCurrent()
        assertEquals(1, failures)
        assertEquals(listOf("retained"), visible.map { it.item.id })
        assertTrue(runner.state.value.searchError)
        assertFalse(runner.state.value.searchInFlight)
        assertFalse(runner.state.value.showProgress)
    }

    @Test fun slowReplacementUsesDelayedProgressAndKeepsItsDisplayedQueryUntilPublication() = runTest {
        val replacement = CompletableDeferred<List<ItemWithCollections>>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = listOf(row("old"))
        val runner = LatestLocalSearch(backgroundScope, { replacement.await() }, {}, {}, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(sourceItems = source))
        runCurrent()
        runner.refresh(LocalSearchContext(query = "new", sourceItems = source), debounceQuery = true)
        runCurrent()
        advanceTimeBy(274)
        runCurrent()
        assertFalse(runner.state.value.showProgress)
        assertEquals("", runner.state.value.displayedQuery)
        assertTrue(runner.state.value.searchInFlight)
        assertFalse(runner.state.value.queryPending)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(runner.state.value.showProgress)
        assertEquals(listOf("old"), runner.state.value.result!!.items.map { it.item.id })
        replacement.complete(listOf(row("new")))
        runCurrent()
        assertFalse(runner.state.value.showProgress)
        assertEquals("new", runner.state.value.displayedQuery)
        assertEquals(listOf("new"), runner.state.value.result!!.items.map { it.item.id })
    }

    @Test fun roomBurstsCoalesceReplacementQueriesWithoutClearingResults() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        var invalidations = 0
        val source = listOf(row("retained"))
        val runner = LatestLocalSearch(backgroundScope, { calls++; source }, { invalidations++ }, {}, {}, dispatcher, dispatcher)
        val context = LocalSearchContext(query = "search", sourceItems = source)
        runner.refresh(context)
        runCurrent()
        repeat(10) {
            runner.refresh(context, sourceRefresh = true)
            runCurrent()
            advanceTimeBy(10)
            assertTrue(runner.state.value.hasPreviousResults)
            assertFalse(runner.state.value.showProgress)
        }
        assertEquals(1, calls)
        advanceTimeBy(75)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, invalidations)
    }

    @Test fun collectionChangeClearsPreviousResultsBeforeSuspending() = runTest {
        var visible = emptyList<ItemWithCollections>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { CompletableDeferred<List<ItemWithCollections>>().await() },
            { visible = emptyList() }, { visible = it.items }, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(sourceItems = listOf(row("outside"))))
        runCurrent()
        runner.refresh(LocalSearchContext(query = "search", collectionId = "other"))
        assertTrue(visible.isEmpty())
    }

    @Test fun roomRefreshImmediatelyRemovesUnavailableRowsButRetainsOtherMatches() = runTest {
        var visible = emptyList<ItemWithCollections>()
        var suspended = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        val kept = row("kept", collection = "folder")
        val removed = row("removed", collection = "folder")
        val runner = LatestLocalSearch(backgroundScope, {
            if (suspended) CompletableDeferred<List<ItemWithCollections>>().await() else listOf(kept, removed)
        }, { visible = emptyList() }, { visible = it.items }, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "search", collectionId = "folder", sourceItems = listOf(kept, removed)))
        runCurrent()
        suspended = true
        runner.refresh(LocalSearchContext(query = "search", collectionId = "folder", sourceItems = listOf(kept)))
        assertEquals(listOf("kept"), visible.map { it.item.id })
    }

    @Test fun typedOfflineQueryUsesOnlyTheSuppliedLocalSearchDependency() = runTest {
        val queries = mutableListOf<String>()
        val publications = mutableListOf<LocalSearchResult>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { queries += it; listOf(row("offline-match")) }, {}, { publications += it }, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "b"), debounceQuery = true)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("b"), queries)
        assertEquals("offline-match", publications.single().items.single().item.id)
    }

    @Test fun sharedFilteringHonorsDateRangeAndComputedCollections() {
        val rows = listOf(row("inside", saved = 100), row("outside", saved = 200))
        val result = projectLocalSearch(rows, LocalSearchContext(dateFilter = DateFilter.CUSTOM, customStart = 90, customEnd = 110, collectionId = "computed_unfiled"))
        assertEquals(listOf("inside"), result.items.map { it.item.id })
    }

    @Test fun loadedPagesAppendOnlyOnceAndSourceRefreshResetsTheirCursor() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var calls = 0
        val pending = CompletableDeferred<LibrarySearchPage>()
        val request = LibrarySearchRequest(query = "needle")
        val cursor = LibrarySearchCursor(request, 0, 100, "first", "first")
        val source = listOf(row("first"), row("second"))
        val runner = LatestLocalSearch(backgroundScope, { error("Legacy query path") }, {}, {}, {}, dispatcher, dispatcher,
            pageSearch = { _, after ->
                calls++
                if (after == null) LibrarySearchPage(listOf(source[0]), mapOf("first" to "needle"), cursor)
                else pending.await()
            })
        val context = LocalSearchContext(query = "needle", sourceItems = source)
        runner.refresh(context)
        runCurrent()
        runner.loadMore()
        runner.loadMore()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(runner.state.value.searchInFlight)
        pending.complete(LibrarySearchPage(source, mapOf("second" to "needle too"), null))
        runCurrent()
        assertEquals(listOf("first", "second"), runner.state.value.result!!.items.map { it.item.id })
        assertEquals(setOf("first", "second"), runner.state.value.result!!.snippets.keys)
        assertNull(runner.state.value.result!!.nextCursor)
        runner.refresh(context.copy(sourceItems = source.toList()), sourceRefresh = true)
        assertNull(runner.state.value.result!!.nextCursor)
        advanceTimeBy(75)
        runCurrent()
        assertEquals(listOf("first"), runner.state.value.result!!.items.map { it.item.id })
        assertEquals(cursor, runner.state.value.result!!.nextCursor)
    }

    @Test fun cancellationResistantOldPageCannotAppendAfterQueryChanges() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = CompletableDeferred<LibrarySearchPage>()
        val cursor = LibrarySearchCursor(LibrarySearchRequest(query = "old"), 0, 100, "old", "old")
        val runner = LatestLocalSearch(backgroundScope, { error("Legacy query path") }, {}, {}, {}, dispatcher, dispatcher,
            pageSearch = { context, after ->
                if (after != null) withContext(NonCancellable) { pending.await() }
                else LibrarySearchPage(listOf(row(context.query)), emptyMap(), if (context.query == "old") cursor else null)
            })
        runner.refresh(LocalSearchContext(query = "old"))
        runCurrent()
        runner.loadMore()
        runCurrent()
        runner.refresh(LocalSearchContext(query = "current"))
        runCurrent()
        pending.complete(LibrarySearchPage(listOf(row("obsolete-page")), emptyMap(), null))
        runCurrent()
        assertEquals("current", runner.state.value.displayedQuery)
        assertEquals(listOf("current"), runner.state.value.result!!.items.map { it.item.id })
        assertFalse(runner.state.value.searchInFlight)
    }
}
