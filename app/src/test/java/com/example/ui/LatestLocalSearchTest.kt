package com.example.ui

import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
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
}
