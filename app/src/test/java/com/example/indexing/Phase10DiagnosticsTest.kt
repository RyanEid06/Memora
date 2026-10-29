package com.example.indexing

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.PersonalLibraryApp
import com.example.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.IOException
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PersonalLibraryApp::class, shadows = [Phase10DiagnosticsTest.FailingRetrieverShadow::class])
class Phase10DiagnosticsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setup() {
        context.getSharedPreferences("personal_library_settings", 0).edit().clear().putBoolean("ai_paused", true).commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @Test fun localIndexWorkerDiagnosesPublicationFailureAndMarksRetry() = runBlocking {
        val repository = LibraryRepository.getInstance(context)
        val item = repository.createNote("Synthetic note", "synthetic body", emptyList())
        repository.dao.updateItem(item.copy(localIndexCoverage = "PENDING"))
        context.openOrCreateDatabase("personal_library_room.db", 0, null).use { rawDb ->
            rawDb.execSQL("CREATE TRIGGER phase10_fail_index BEFORE UPDATE OF extractedText ON items BEGIN SELECT RAISE(ABORT, 'PRIVATE_DB_DETAIL'); END")
        }

        try {
            val records = captureDiagnostics {
                val result = TestListenableWorkerBuilder<LocalIndexWorker>(context).build().doWork()
                assertEquals(androidx.work.ListenableWorker.Result.success(), result)
            }
            assertEquals("NEEDS_RETRY", repository.dao.getItemById(item.id)!!.aiStatus)
            val record = records.single { it.message.startsWith("operation=local_index ") }
            assertSafeRecord(record, "PRIVATE_DB_DETAIL", "count=1")
        } finally {
            context.openOrCreateDatabase("personal_library_room.db", 0, null).use { rawDb ->
                rawDb.execSQL("DROP TRIGGER IF EXISTS phase10_fail_index")
            }
        }
    }

    @Test fun videoExtractionFailureIsDiagnosedWithoutThrowableOrPath() = runBlocking {
        FailingRetrieverShadow.failure = IOException("PRIVATE_MEDIA_PATH")
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context).build()
        val file = java.io.File(context.cacheDir, "PRIVATE_MEDIA_PATH.mp4")
        val records = captureDiagnostics {
            val method = DurableAiWorker::class.java.getDeclaredMethod("extractBoundedVideoFrames", java.io.File::class.java)
            method.isAccessible = true
            assertTrue((method.invoke(worker, file) as List<*>).isEmpty())
        }
        val record = records.single { it.message.startsWith("operation=video_extract ") }
        assertSafeRecord(record, "PRIVATE_MEDIA_PATH", "count=0")
    }

    @Test fun videoExtractionMustPropagateCancellationInsteadOfTreatingItAsPartialSuccess() = runBlocking {
        FailingRetrieverShadow.failure = CancellationException("PRIVATE_CANCEL")
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context).build()
        val method = DurableAiWorker::class.java.getDeclaredMethod("extractBoundedVideoFrames", java.io.File::class.java).apply { isAccessible = true }
        val records = captureDiagnostics {
            val thrown = try { method.invoke(worker, java.io.File(context.cacheDir, "cancelled.mp4")); null }
            catch (error: java.lang.reflect.InvocationTargetException) { error.cause }
            assertTrue("Cancellation must propagate", thrown is CancellationException)
        }
        assertTrue("Cancellation must not emit a diagnostic", records.none { it.message.startsWith("operation=video_extract ") })
    }

    private suspend fun captureDiagnostics(block: suspend () -> Unit): List<LogRecord> {
        val logger = Logger.getLogger("MemoraDiagnostics")
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        return try { block(); records } finally { logger.removeHandler(handler) }
    }

    private fun assertSafeRecord(record: LogRecord, sentinel: String, count: String) {
        assertTrue(record.message.contains(Regex("duration_ms=\\d+")))
        assertTrue(record.message.contains(count))
        assertTrue(record.message.contains("build="))
        assertFalse(record.message.contains(sentinel))
        assertNull(record.thrown)
    }

    @Implements(MediaMetadataRetriever::class)
    class FailingRetrieverShadow {
        companion object { var failure: Exception = IOException("injected") }
        @Implementation fun setDataSource(path: String) { throw failure }
        @Implementation fun release() = Unit
    }
}
