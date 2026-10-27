package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSearchArchitectureTest {
    private lateinit var db: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    private val likeQueries = AtomicInteger()
    private val dao get() = db.libraryDao()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java)
            .addCallback(DATABASE_CALLBACK)
            .setQueryCallback({ sql, _ -> if (sql.contains(" LIKE ", true)) likeQueries.incrementAndGet() }, Executor { it.run() })
            .build()
        repository = LibraryRepository(context, db)
    }
    @After fun close() = db.close()

    @Test fun ordinaryNoHitDoesNotScanBodiesOrMatchMidTokenSubstrings() = runBlocking {
        dao.insertItem(ItemEntity("body", "Archive", "antidisestablishmentarianism", "TEXT"))
        assertTrue(repository.searchWithCollections("establish").isEmpty())
        assertTrue(repository.searchWithCollections("unfindable").isEmpty())
        assertEquals(0, likeQueries.get())
    }

    @Test fun broadFirstPageIsBoundedAndDoesNotHydrateOcrBodies() = runBlocking {
        repeat(125) { dao.insertItem(ItemEntity("id-$it", "prefix $it", "preview ".repeat(1000), "PHOTO",
            createdAt = it.toLong(), extractedText = "prefix " + "OCR ".repeat(10000))) }
        val rows = repository.searchWithCollections("pre")
        assertEquals(50, rows.size)
        assertTrue(rows.all { it.item.content.length <= 512 && it.item.extractedText == null })
        assertEquals(0, likeQueries.get())
    }

    @Test fun relevancePrefersTitleThenFilenameThenBodyThenAiMetadata() = runBlocking {
        val base = ItemEntity("base", "Archive", "", "TEXT")
        dao.insertItem(base.copy(id = "title", title = "needle", createdAt = 1))
        dao.insertItem(base.copy(id = "file", originalFileName = "needle.pdf", createdAt = 2))
        dao.insertItem(base.copy(id = "body", extractedText = "needle", createdAt = 3))
        dao.insertItem(base.copy(id = "ai", aiDescription = "needle", createdAt = 4))
        assertEquals(listOf("title", "file", "body", "ai"), repository.searchWithCollections("needle").map { it.item.id })
    }

    @Test fun unexpectedFtsFailurePropagatesInsteadOfHidingBehindLike() = runBlocking {
        dao.insertItem(ItemEntity("body", "needle", "", "TEXT"))
        db.openHelper.writableDatabase.execSQL("DROP TABLE items_fts")
        val failure = runCatching { repository.searchWithCollections("needle") }.exceptionOrNull()
        assertNotNull("Broken FTS must be visible to search error state", failure)
        assertEquals(0, likeQueries.get())
    }

    @Test fun fullDetailObservationPreservesBodiesAndEditsAfterSummaryProjection() = runBlocking {
        val original = ItemEntity("detail", "Full detail", "synthetic body ".repeat(1000), "TEXT",
            extractedText = "full OCR ".repeat(1000), aiDescription = "analysis ".repeat(1000))
        dao.insertItem(original)
        val card = repository.observeActiveItemSummaries().first().single().item
        assertEquals(512, card.content.length)
        assertNull(card.extractedText)
        assertEquals(original, dao.observeItemWithCollections(original.id).first()!!.item)
        val edited = original.copy(content = original.content + " preserved end", isFavorite = true)
        dao.updateItem(edited)
        assertEquals(edited, dao.observeItemWithCollections(original.id).first()!!.item)
    }
}
