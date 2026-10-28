package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.personallibrary.core.backup.*
import com.personallibrary.core.model.ItemKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BulkLibraryOperationsTest {
    private lateinit var context: Context
    private lateinit var db: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    private val statements = CopyOnWriteArrayList<String>()
    private var onStatement: ((String) -> Unit)? = null
    private val dao get() = db.libraryDao()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("personal_library_settings", 0).edit().clear().putBoolean("ai_paused", true).commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK)
            .setQueryCallback({ sql, _ -> statements += sql; onStatement?.invoke(sql) }, Executor { it.run() }).build()
        repository = LibraryRepository(context, db)
    }
    @After fun close() { db.close() }

    @Test fun backupReadsMembershipsOnceForSeventyFiveItems() = runBlocking {
        dao.insertCollection(CollectionEntity("folder", "Synthetic folder"))
        repeat(75) { index ->
            dao.insertItem(ItemEntity("item-$index", "Synthetic $index", "Body", "TEXT"))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("item-$index", "folder"))
        }
        val destination = File.createTempFile("bulk-backup-", ".plb", context.cacheDir)
        try {
            statements.clear()
            assertTrue(repository.exportEncryptedBackup(Uri.fromFile(destination), "synthetic-passphrase".toCharArray()))
            val reads = statements.count { it.trimStart().startsWith("SELECT", true) }
            assertTrue("Backup performed $reads reads", reads <= 12)
            val preview = repository.inspectBackup(Uri.fromFile(destination), "synthetic-passphrase".toCharArray())!!
            try {
                assertEquals(75, preview.manifest.items.size)
                assertEquals(75, preview.manifest.memberships.size)
                assertTrue(preview.manifest.memberships.all { it.collectionId == "folder" })
            } finally { repository.discardRestorePreview(preview) }
        } finally { destination.delete() }
    }

    @Test fun emptyTrashUsesBatchStatementsAndPreservesActiveRows() = runBlocking {
        dao.insertItem(ItemEntity("active", "Keep", "Owner content", "TEXT"))
        repeat(75) { index ->
            val id = "trash-$index"
            dao.insertItem(ItemEntity(id, "Delete", "Synthetic body", "TEXT", isTrash = true))
            dao.insertReminder(ReminderEntity("rem_$id", id, "Synthetic", 100L))
            dao.insertSearchDocuments(listOf(SearchDocumentEntity("chunk_$id", id, 0, textContent = "Synthetic")))
            dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(id, 0, 0))
        }
        statements.clear()
        repository.emptyTrash()
        val deletes = statements.count { it.trimStart().startsWith("DELETE", true) }
        assertTrue("Empty Trash performed $deletes deletes", deletes <= 12)
        assertEquals(listOf("active"), dao.getAllItemsIncludingTrash().map { it.id })
        assertTrue(dao.getAllReminders().isEmpty())
        assertTrue(dao.getSearchDocumentsForItem("trash-0").isEmpty())
        assertNull(dao.getAnalysisJob("trash-0"))
        assertTrue(repository.search("Synthetic").isEmpty())
    }

    private fun preview(count: Int): RestorePreview {
        val stage = File.createTempFile("bulk-restore-", "", context.cacheDir).apply { delete(); mkdirs() }
        val manifest = BackupManifest(
            items = (0 until count).map { index -> BackupItemRecord("restored-$index", "Synthetic $index", "Body", ItemKind.TEXT,
                false, false, null, 1L, null, null, false, contentHash = null, originalFileName = null,
                mimeType = null, fileSizeBytes = 0, aiDescription = null, aiConcepts = null, aiStatus = "READY") },
            collections = (0 until count).map { BackupCollectionRecord("folder-$it", "Folder $it", "folder", "#ffffff", 1L) },
            memberships = (0 until count).map { BackupMembershipRecord("restored-$it", "folder-$it") },
            assets = emptyList(), reminders = emptyList())
        return RestorePreview(manifest, stage, count, count, 0, 0)
    }

    @Test fun restoreLoadsCollectionStateOnceAndLeavesDurablePendingIndexing() = runBlocking {
        statements.clear()
        assertEquals(75, repository.commitRestore(preview(75)))
        val collectionReads = statements.count { it.contains("SELECT * FROM collections", true) ||
            it.contains("SELECT * FROM pending_collection_deletions", true) }
        assertTrue("Restore performed $collectionReads collection-state reads", collectionReads <= 2)
        val restored = dao.getAllActiveItems()
        assertEquals(75, restored.size)
        assertTrue("Restore must commit before local extraction", restored.all { it.localIndexCoverage == "PENDING" && it.localIndexVersion == 1 })
        assertEquals(listOf("folder-74"), dao.getCollectionIdsForItem("restored-74"))
    }

    @Test fun cancellationAfterRestoreCommitStillSchedulesItsReminder() = runBlocking {
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val incoming = preview(1)
        val due = System.currentTimeMillis() + 120_000
        val reminder = BackupReminderRecord("rem_restored-0", "restored-0", "Synthetic reminder", due, false, false)
        val withReminder = incoming.copy(manifest = incoming.manifest.copy(reminders = listOf(reminder)))
        var itemReads = 0
        lateinit var restoring: Job
        onStatement = { sql ->
            if (sql.startsWith("SELECT * FROM items WHERE id =", true) && ++itemReads == 2) restoring.cancel()
        }
        restoring = launch(start = CoroutineStart.LAZY) { repository.commitRestore(withReminder) }
        restoring.start()
        restoring.join()
        onStatement = null
        assertTrue("Cancellation must occur after metadata committed", restoring.isCancelled)
        assertNotNull(dao.getItemById("restored-0"))
        val alarms = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java)).scheduledAlarms
        assertEquals("A committed reminder must survive cancellation", 1, alarms.size)
        assertFalse(incoming.stagingDir.exists())
    }

    @Test fun cancellationDuringMetadataImportRollsBackAndDiscardsStage() = runBlocking {
        val incoming = preview(3)
        lateinit var restoring: Job
        var cancelled = false
        onStatement = { sql ->
            if (!cancelled && sql.contains("INTO `items`", true)) {
                cancelled = true
                restoring.cancel()
            }
        }
        restoring = launch(start = CoroutineStart.LAZY) { repository.commitRestore(incoming) }
        restoring.start()
        restoring.join()
        onStatement = null
        assertTrue(cancelled)
        assertTrue(restoring.isCancelled)
        assertTrue(dao.getAllItemsIncludingTrash().isEmpty())
        assertTrue(dao.getAllStoredCollections().isEmpty())
        assertFalse(incoming.stagingDir.exists())
    }

    @Test fun committedReminderCompletionDoesNotHoldAssetLockAgainstCapture() = runBlocking {
        val incoming = preview(1)
        val due = System.currentTimeMillis() + 120_000
        val withReminder = incoming.copy(manifest = incoming.manifest.copy(reminders = listOf(
            BackupReminderRecord("rem_restored-0", "restored-0", "Synthetic reminder", due, false, false))))
        val originalRead = java.util.concurrent.CountDownLatch(1)
        val input = object : java.io.ByteArrayInputStream("Concurrent original".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                originalRead.countDown()
                return super.read(buffer, offset, length)
            }
        }
        val importing = async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            repository.captureStream(input, "concurrent.txt", "text/plain")
        }
        var itemReads = 0
        var completedOutsideLock = false
        onStatement = { sql ->
            if (sql.startsWith("SELECT * FROM items WHERE id =", true) && ++itemReads == 2) {
                importing.start()
                completedOutsideLock = originalRead.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        try {
            assertEquals(1, repository.commitRestore(withReminder))
            val item = withTimeout(10_000) { importing.await() }
            assertTrue("Restore completion must release the asset lock before reading reminders", completedOutsideLock)
            assertEquals("Concurrent original", repository.getBlobFile(item.contentHash!!)!!.readText())
            assertEquals(2, dao.getAllActiveItems().size)
        } finally { onStatement = null; importing.cancel() }
    }

    @Test fun cancellationWaitingForAssetLockDiscardsOnlyItsRestoreStage() = runBlocking {
        val reading = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val input = object : java.io.ByteArrayInputStream("Synthetic held capture".toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reading.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                return super.read(buffer, offset, length)
            }
        }
        val capturing = async(Dispatchers.IO) { repository.captureStream(input, "held.txt", "text/plain") }
        try {
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val incoming = preview(1)
            withContext(Dispatchers.IO) {
                // Same-dispatcher undispatched start reaches the held mutex before returning here.
                val restoring = launch(start = CoroutineStart.UNDISPATCHED) { repository.commitRestore(incoming) }
                restoring.cancelAndJoin()
                assertFalse(incoming.stagingDir.exists())
                assertNull(dao.getItemById("restored-0"))
            }
        } finally { release.countDown() }
        val captured = withTimeout(10_000) { capturing.await() }
        assertEquals("Synthetic held capture", repository.getBlobFile(captured.contentHash!!)!!.readText())
        repository.deletePermanently(captured.id)
    }

    @Test fun collectionCountsFollowActiveMembershipAndHidePendingFolders() = runBlocking {
        dao.insertCollection(CollectionEntity("visible", "Visible"))
        dao.insertCollection(CollectionEntity("hidden", "Hidden"))
        for ((id, folder, trash) in listOf(Triple("one", "visible", false), Triple("two", "visible", false),
            Triple("trashed", "visible", true), Triple("hidden-item", "hidden", false))) {
            dao.insertItem(ItemEntity(id, "Synthetic", "", "TEXT", isTrash = trash))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, folder))
        }
        dao.insertCollectionDeletion(PendingCollectionDeletion("delete-hidden", "hidden", "Hidden", Long.MAX_VALUE, 1))
        assertEquals(mapOf("visible" to 2), repository.observeCollectionItemCounts().first())
        dao.moveToTrash("one", 1)
        assertEquals(mapOf("visible" to 1), repository.observeCollectionItemCounts().first())
        dao.removeAllCollectionsForItem("two")
        assertTrue(repository.observeCollectionItemCounts().first().isEmpty())
    }

    @Test fun bulkTrashDeletionRecountsActualActiveAndTrashBlobReferences() = runBlocking {
        val bytes = "Synthetic shared original".toByteArray()
        val active = repository.captureStream(bytes.inputStream(), "shared.bin", "application/octet-stream")
        repeat(405) { index -> dao.insertItem(active.copy(id = "trash-$index", isTrash = true)) }
        // Legacy inaccurate counters must never delete an original still referenced by an active item.
        dao.updateAsset(dao.getAssetByHash(active.contentHash!!)!!.copy(refCount = 1))
        repository.emptyTrash()
        assertEquals(listOf(active.id), dao.getAllItemsIncludingTrash().map { it.id })
        assertEquals(1, dao.getAssetByHash(active.contentHash)!!.refCount)
        assertArrayEquals(bytes, repository.getBlobFile(active.contentHash)!!.readBytes())
        repository.moveToTrash(active.id)
        repository.emptyTrash()
        assertNull(dao.getAssetByHash(active.contentHash))
        assertNull(repository.getBlobFile(active.contentHash))
    }

    @Test fun failedBulkDeletionRollsBackEveryBatchAndDependentRow() = runBlocking {
        repeat(405) { index ->
            dao.insertItem(ItemEntity("trash-$index", "Synthetic", "", "TEXT", isTrash = true))
            dao.insertReminder(ReminderEntity("rem_$index", "trash-$index", "Synthetic", 100))
        }
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_bulk_delete BEFORE DELETE ON items
            WHEN OLD.id = 'trash-0' BEGIN SELECT RAISE(ABORT, 'injected deletion failure'); END""")
        try { repository.emptyTrash(); fail("Expected injected transaction failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(405, dao.getAllItemsIncludingTrash().size)
        assertEquals(405, dao.getAllReminders().size)
    }
}
