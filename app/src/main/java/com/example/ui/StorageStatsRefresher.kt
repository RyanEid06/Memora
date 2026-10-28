package com.example.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
internal class StorageStatsRefresher(
    scope: CoroutineScope,
    private val refresh: suspend () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(dispatcher) {
            requests.receiveAsFlow().debounce(500).collect {
                try { refresh() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    android.util.Log.w("MemoraStorage", "storage_stats_failed ${failure.javaClass.simpleName}")
                }
            }
        }
    }

    fun request() { requests.trySend(Unit) }
}
