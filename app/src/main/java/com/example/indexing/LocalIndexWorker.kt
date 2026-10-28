package com.example.indexing

import android.content.Context
import androidx.work.*
import com.example.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class LocalIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = LibraryRepository.getInstance(applicationContext)
        val indexer = LocalIndexer(applicationContext, repository)
        var afterId: String? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val ids = repository.pendingLocalIndexIds(afterId)
            if (ids.isEmpty()) break
            for (id in ids) {
                currentCoroutineContext().ensureActive()
                val item = repository.dao.getItemById(id) ?: continue
                if (item.isTrash || item.aiStatus != "PENDING" && item.localIndexCoverage != "PENDING") continue
                try { indexer.indexItem(item) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { repository.dao.updateAiStatus(item.id, "NEEDS_RETRY") }
            }
            afterId = ids.last()
        }
        indexer.enqueuePendingAnalysisJobs()
        return Result.success()
    }
    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("local_index_pending",
            ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<LocalIndexWorker>().build())
    }
}
