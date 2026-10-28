package com.example.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageStatsRefresherTest {
    @Test fun hundredImportSignalsProduceOneDelayedTraversal() = runTest {
        var calls = 0
        val refresher = StorageStatsRefresher(backgroundScope, { calls++ }, StandardTestDispatcher(testScheduler))
        repeat(100) { refresher.request() }
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test fun requestsDuringTraversalNeverOverlapAndScheduleOneFollowup() = runTest {
        var active = 0
        var peak = 0
        var calls = 0
        val release = CompletableDeferred<Unit>()
        val refresher = StorageStatsRefresher(backgroundScope, {
            calls++
            active++
            peak = maxOf(peak, active)
            if (calls == 1) release.await()
            active--
        }, StandardTestDispatcher(testScheduler))
        refresher.request()
        advanceTimeBy(500)
        runCurrent()
        repeat(100) { refresher.request() }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, peak)
        release.complete(Unit)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, peak)
    }

    @Test fun failedTraversalDoesNotDisableLaterRefreshes() = runTest {
        var calls = 0
        val refresher = StorageStatsRefresher(backgroundScope, {
            calls++
            if (calls == 1) throw java.io.IOException("Synthetic traversal failure")
        }, StandardTestDispatcher(testScheduler))
        refresher.request()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, calls)
        refresher.request()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, calls)
    }
}
