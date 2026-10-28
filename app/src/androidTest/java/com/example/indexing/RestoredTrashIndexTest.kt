package com.example.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.example.data.LibraryRepository
import com.personallibrary.core.backup.*
import com.personallibrary.core.model.ItemKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoredTrashIndexTest {
    @Test fun restoredTrashOriginalIndexesWhenMadeActiveWithoutRestart() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = LibraryRepository.getInstance(context)
        val prefs = context.getSharedPreferences("personal_library_settings", 0)
        val wasPaused = prefs.getBoolean("ai_paused", false)
        prefs.edit().putBoolean("ai_paused", true).commit()
        val id = "synthetic-restored-trash-${UUID.randomUUID()}"
        val bytes = "Synthetic restored original with chemistry provenance".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val stage = File.createTempFile("synthetic-trash-restore-", "", context.cacheDir).apply { delete(); mkdirs() }
        File(stage, "assets").mkdirs()
        File(stage, "assets/$hash").writeBytes(bytes)
        val item = BackupItemRecord(id, "Synthetic restored Trash", "", ItemKind.FILE, false, true,
            System.currentTimeMillis(), 1, null, null, false, contentHash = hash,
            originalFileName = "synthetic-restored.txt", mimeType = "text/plain", fileSizeBytes = bytes.size.toLong(),
            aiDescription = null, aiConcepts = null, aiStatus = "READY")
        val manifest = BackupManifest(items = listOf(item), collections = emptyList(), memberships = emptyList(), reminders = emptyList(),
            assets = listOf(BackupAssetRecord(hash, "blobs/$hash", bytes.size.toLong(), "text/plain", "synthetic-restored.txt", "FILE")))
        try {
            assertEquals(1, repository.commitRestore(RestorePreview(manifest, stage, 1, 0, 1, 0)))
            withTimeout(30_000) { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("local_index_pending")
                .first { work -> work.all { it.state.isFinished } } }
            assertEquals("PENDING", repository.dao.getItemById(id)!!.localIndexCoverage)
            repository.restoreFromTrash(id)
            val indexed = withTimeoutOrNull(15_000) { repository.dao.observeItemWithCollections(id)
                .first { it?.item?.localIndexCoverage.equals("complete", ignoreCase = true) } }
            assertNotNull("Restoring a pending original must enqueue extraction without an app restart; " +
                "coverage=${repository.dao.getItemById(id)?.localIndexCoverage}", indexed)
            assertTrue(indexed!!.item.extractedText!!.contains("chemistry"))
            assertTrue(repository.dao.searchFts("chemistry").any { it.id == id })
        } finally {
            repository.deletePermanently(id)
            prefs.edit().putBoolean("ai_paused", wasPaused).commit()
            stage.deleteRecursively()
        }
    }
}
