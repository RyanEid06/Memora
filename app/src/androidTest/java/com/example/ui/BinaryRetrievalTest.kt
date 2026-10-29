package com.example.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ai.AiGatewayException
import com.example.data.*
import com.example.model.DateFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BinaryRetrievalTest {
    @Test fun nativeV8SearchDocumentTableMigratesCsvToBinaryAndKeepsMalformedPage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "v8-${UUID.randomUUID()}.db"
        var original: LibraryRoomDatabase? = null
        var migrated: LibraryRoomDatabase? = null
        try {
            original = Room.databaseBuilder(context, LibraryRoomDatabase::class.java, name)
                .addCallback(DATABASE_CALLBACK).build()
            original.libraryDao().insertItem(ItemEntity("migration-item", "Migration title", "MigrationReceipt citation proof", "TEXT",
                localIndexCoverage = "COMPLETE", localIndexVersion = 3))
            original.close(); original = null

            val valid = FloatArray(768) { if (it == 0) 1f else (it % 19 - 9) / 10f }
            val csv = valid.joinToString(",")
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE search_documents")
                db.execSQL("CREATE TABLE search_documents (id TEXT NOT NULL, itemId TEXT NOT NULL, chunkIndex INTEGER NOT NULL, pageNumber INTEGER, textContent TEXT NOT NULL, embedding TEXT, embeddingDimension INTEGER NOT NULL, modelVersion TEXT NOT NULL, coverage TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE INDEX index_search_documents_itemId ON search_documents (itemId)")
                db.execSQL("INSERT INTO search_documents VALUES(?,?,?,?,?,?,?,?,?)", arrayOf("valid", "migration-item", 0, 93, "Receipt citation on page 93", csv, 768, "gemini-embedding-2", "COMPLETE"))
                db.execSQL("INSERT INTO search_documents VALUES(?,?,?,?,?,?,?,?,?)", arrayOf("malformed", "migration-item", 1, 94, "Retain malformed page text", "not,a,vector", 768, "gemini-embedding-2", "COMPLETE"))
                db.version = 8
            }

            migrated = Room.databaseBuilder(context, LibraryRoomDatabase::class.java, name)
                .addMigrations(MIGRATION_8_9).addCallback(DATABASE_CALLBACK).build()
            val documents = migrated.libraryDao().getSearchDocumentsForItem("migration-item").associateBy { it.id }
            assertEquals(setOf("valid", "malformed"), documents.keys)
            val validDoc = documents.getValue("valid")
            assertEquals(93, validDoc.pageNumber)
            assertEquals("Receipt citation on page 93", validDoc.textContent)
            assertEquals("BLOB", migrated.openHelper.writableDatabase.query("SELECT typeof(embedding) FROM search_documents WHERE id='valid'").use {
                assertTrue(it.moveToFirst()); it.getString(0).uppercase()
            })
            val decoded = requireNotNull(EmbeddingCodec.decode(validDoc.embedding, validDoc.embeddingFormatVersion,
                validDoc.embeddingDimension, validDoc.modelVersion))
            assertEquals(768, decoded.size)
            assertArrayEquals(valid, decoded, 0f)
            val malformed = documents.getValue("malformed")
            assertNull(malformed.embedding)
            assertEquals(94, malformed.pageNumber)
            assertEquals("Retain malformed page text", malformed.textContent)
            assertEquals("migration-item", migrated.libraryDao().searchFts("MigrationReceipt*").single().id)
        } finally {
            original?.close()
            migrated?.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun retrievalUsesOneBoundedQueryKeepsLateCanonicalPageAndRejectsEditedSnapshot() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val preferences = app.getSharedPreferences("binary-retrieval-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val privateCollection = "private-${UUID.randomUUID()}"
        preferences.edit().putStringSet("excluded_collections", setOf(privateCollection)).commit()
        val documentSelects = AtomicInteger()
        val db = Room.inMemoryDatabaseBuilder(app, LibraryRoomDatabase::class.java)
            .addCallback(DATABASE_CALLBACK)
            .setQueryCallback({ sql, _ ->
                if (sql.contains("search_documents", ignoreCase = true) && sql.contains("SELECT", ignoreCase = true)) documentSelects.incrementAndGet()
            }, Executor { it.run() }).build()
        try {
            val dao = db.libraryDao()
            dao.insertCollection(CollectionEntity(privateCollection, "Private fixture"))
            val vector = requireNotNull(EmbeddingCodec.encode(FloatArray(768) { if (it == 0) 1f else .125f }))
            val items = (0 until InteractiveAiRetrieval.MAX_ITEMS).map { index ->
                ItemEntity("eligible-$index", "Receipt $index", "receipt proof synthetic content", "TEXT",
                    createdAt = index.toLong(), localIndexCoverage = "COMPLETE", localIndexVersion = 3)
            }
            items.forEach { dao.insertItem(it) }
            dao.insertItem(ItemEntity("private-decoy", "Receipt private", "receipt private synthetic content", "TEXT",
                localIndexCoverage = "COMPLETE", localIndexVersion = 3))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("private-decoy", privateCollection))
            dao.insertItem(ItemEntity("trash-decoy", "Receipt trash", "receipt trashed synthetic content", "TEXT", isTrash = true,
                localIndexCoverage = "COMPLETE", localIndexVersion = 3))
            fun docs(item: ItemEntity, latePage: Boolean = false) = (0 until 8).map { chunk ->
                val relevant = latePage && chunk == 7
                SearchDocumentEntity("doc_${item.id}_3_$chunk", item.id, chunk, if (relevant) 93 else chunk + 1,
                    if (relevant) "receipt proof on late page 93" else "unrelated archived passage $chunk", vector, coverage = "COMPLETE")
            }
            dao.insertSearchDocuments(items.flatMap { docs(it, latePage = true) } +
                docs(ItemEntity("private-decoy", "", "", "TEXT", localIndexCoverage = "COMPLETE", localIndexVersion = 3)) +
                docs(ItemEntity("trash-decoy", "", "", "TEXT", localIndexCoverage = "COMPLETE", localIndexVersion = 3)))

            val retrieval = InteractiveAiRetrieval(dao, preferences)
            documentSelects.set(0)
            val snapshot = retrieval.load(LocalSearchContext(query = "receipt", dateFilter = DateFilter.ALL_TIME))
            assertEquals(1, documentSelects.get())
            assertEquals(InteractiveAiRetrieval.MAX_ITEMS, snapshot.items.size)
            assertEquals(InteractiveAiRetrieval.MAX_DOCUMENTS, snapshot.documents.size)
            assertTrue(snapshot.documents.groupBy { it.itemId }.values.all { it.size <= InteractiveAiRetrieval.MAX_DOCUMENTS_PER_ITEM })
            assertTrue(snapshot.items.all { it.item.id.startsWith("eligible-") && !it.item.isTrash && it.item.localIndexVersion == 3 })
            assertTrue(snapshot.documents.all { doc ->
                snapshot.items.any { it.item.id == doc.itemId } && doc.id == "doc_${doc.itemId}_3_${doc.chunkIndex}"
            })
            assertEquals(snapshot.documents.groupBy { it.itemId }.size,
                snapshot.documents.count { it.pageNumber == 93 && it.textContent.contains("receipt proof") })

            val changed = snapshot.items.first().item.copy(localIndexVersion = 4)
            dao.updateItem(changed)
            var rejected = false
            try { retrieval.requireCurrent(snapshot) { true } }
            catch (_: AiGatewayException.RequestBlockedException) { rejected = true }
            assertTrue("A changed local-index version must invalidate the captured snapshot", rejected)
        } finally {
            db.close()
            preferences.edit().clear().commit()
        }
    }

}
