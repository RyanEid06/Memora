package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.example.model.DateBasis
import com.example.model.SortOrder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSearchQueryContractTest {
    private lateinit var db: LibraryRoomDatabase
    private val dao get() = db.libraryDao()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LibraryRoomDatabase::class.java)
            .allowMainThreadQueries().addCallback(DATABASE_CALLBACK).build()
    }

    @After fun close() = db.close()

    private fun rows(request: LibrarySearchRequest, cursor: LibrarySearchCursor? = null, limit: Int = 51): List<LocalSearchRow> =
        db.openHelper.readableDatabase.query(LocalSearchQueries.page(request, cursor, limit)).use { result ->
            buildList {
                while (result.moveToNext()) {
                    fun text(name: String): String? = result.getColumnIndexOrThrow(name).let { if (result.isNull(it)) null else result.getString(it) }
                    fun number(name: String): Long? = result.getColumnIndexOrThrow(name).let { if (result.isNull(it)) null else result.getLong(it) }
                    add(LocalSearchRow(ItemEntity(
                        id = text("id")!!, title = text("title")!!, content = text("content")!!, kind = text("kind")!!,
                        createdAt = number("createdAt")!!, sourceDate = number("sourceDate"),
                        extractedText = text("extractedText"), aiDescription = text("aiDescription"), aiConcepts = text("aiConcepts")
                    ), emptyList(), number("searchRank")!!, text("matchSnippet")))
                }
            }
        }

    private fun ids(request: LibrarySearchRequest): List<String> = rows(request).map { it.item.id }

    @Test fun unicodePunctuationUsesTheSameTokenBoundariesInIndexAndQuery() = runBlocking {
        dao.insertItem(ItemEntity("arabic", "مرحبا،بالعالم", "", "TEXT"))
        dao.insertItem(ItemEntity("english", "first—second", "", "TEXT"))
        assertEquals(listOf("arabic"), ids(LibrarySearchRequest("مرحبا،بالعالم")))
        assertEquals(listOf("english"), ids(LibrarySearchRequest("first—second")))
    }

    @Test fun browseAndTrashSortsUseAnOrderedIndexRatherThanSortingFullRows() {
        val browse = LocalSearchQueries.page(LibrarySearchRequest())
        val browsePlan = db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${browse.sql}", bindings(browse))).use { result ->
            buildList { while (result.moveToNext()) add(result.getString(3)) }.joinToString("\n")
        }
        val trashPlan = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT id FROM items WHERE isTrash=1 ORDER BY trashTimestamp DESC, id ASC LIMIT 50").use { result ->
            buildList { while (result.moveToNext()) add(result.getString(3)) }.joinToString("\n")
        }
        java.io.File("../artifacts/remediation-20261027/phase3-query-plans.txt").appendText("browse:\n$browsePlan\ntrash:\n$trashPlan\n")
        assertTrue(browsePlan, browsePlan.contains("index_items_isTrash_createdAt_id"))
        assertTrue(trashPlan, trashPlan.contains("index_items_isTrash_trashTimestamp_id"))
    }

    @Test fun indexedTiersDominateLowerTiersAndTermsCanSpanColumns() = runBlocking {
        val base = ItemEntity("base", "Archive", "", "TEXT", createdAt = 100)
        dao.insertItem(base.copy(id = "title", title = "needle", createdAt = 1))
        dao.insertItem(base.copy(id = "filename", originalFileName = "needle.pdf", createdAt = 2))
        dao.insertItem(base.copy(id = "content", content = "needle", createdAt = 4))
        dao.insertItem(base.copy(id = "ocr", extractedText = "needle", createdAt = 3))
        dao.insertItem(base.copy(id = "ai", aiDescription = "needle", aiConcepts = "needle", createdAt = 5))
        dao.insertItem(base.copy(id = "lower-combined", originalFileName = "needle", content = "needle", aiConcepts = "needle"))
        val ranked = rows(LibrarySearchRequest("nee"))
        assertEquals(listOf("title", "lower-combined", "filename", "content", "ocr", "ai"), ranked.map { it.item.id })
        assertEquals(listOf(8L, 7L, 4L, 2L, 2L, 1L), ranked.map { it.searchRank })
        dao.insertItem(base.copy(id = "split", title = "first", extractedText = "second"))
        assertEquals(listOf("split"), ids(LibrarySearchRequest("first second")))
    }

    @Test fun everyScopeAndHalfOpenDateRangeFiltersBeforeTheLimit() = runBlocking {
        dao.insertCollection(CollectionEntity("folder", "Synthetic"))
        val base = ItemEntity("keep", "needle", "", "PHOTO", createdAt = 100, sourceDate = 200,
            isFavorite = true, reminderTime = 500)
        dao.insertItem(base)
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("keep", "folder"))
        dao.insertItem(base.copy(id = "boundary", createdAt = 101, sourceDate = 201))
        dao.insertItem(base.copy(id = "missing", sourceDate = null, isFavorite = false, reminderTime = null))
        dao.insertItem(base.copy(id = "done", kind = "TEXT", isReminderDone = true))
        dao.insertItem(base.copy(id = "trash", isTrash = true))
        val request = LibrarySearchRequest("needle", kind = "PHOTO", collectionId = "folder",
            basis = DateBasis.SOURCE_DATE, startInclusive = 200, endExclusive = 201)
        assertEquals(listOf("keep"), ids(request))
        assertEquals(listOf("trash"), ids(LibrarySearchRequest("needle", trash = true)))
        assertFalse(ids(LibrarySearchRequest("needle", collectionId = "computed_favorites")).contains("missing"))
        assertEquals(setOf("keep", "boundary"), ids(LibrarySearchRequest("needle", collectionId = "computed_due")).toSet())
        assertFalse(ids(LibrarySearchRequest("needle", collectionId = "computed_unfiled")).contains("keep"))
        assertTrue(ids(request.copy(invalidDateRange = true)).isEmpty())
        assertTrue(ids(request.copy(startInclusive = 201)).isEmpty())
    }

    @Test fun keysetPagesAreBoundedStableAndDoNotRepeatAfterNewerInsert() = runBlocking {
        repeat(125) { dao.insertItem(ItemEntity("id-${it.toString().padStart(3, '0')}", "needle", "", "TEXT", createdAt = 100)) }
        val request = LibrarySearchRequest("needle")
        val first = rows(request)
        assertEquals(51, first.size)
        val loaded = first.take(50).toMutableList()
        var cursor = LocalSearchQueries.cursor(request, loaded.last())
        dao.insertItem(ItemEntity("new", "needle", "", "TEXT", createdAt = 200))
        while (true) {
            val page = rows(request, cursor)
            loaded += page.take(50)
            if (page.size <= 50) break
            cursor = LocalSearchQueries.cursor(request, page[49])
        }
        assertEquals(125, loaded.size)
        assertEquals(125, loaded.map { it.item.id }.distinct().size)
        assertTrue(first.all { it.matchSnippet?.contains("needle") == true })
        assertEquals((0 until 125).map { "id-${it.toString().padStart(3, '0')}" }, loaded.map { it.item.id })
        assertTrue(runCatching { LocalSearchQueries.page(request.copy(query = "other"), cursor) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LocalSearchQueries.page(request, limit = 52) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { LocalSearchQueries.page(request, limit = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(1, rows(request, limit = 1).size)
    }

    @Test fun allExplicitSortsPageTiesAndKeepMissingSourceDatesLast() = runBlocking {
        val values = listOf(
            ItemEntity("a", "beta", "", "TEXT", createdAt = 2, sourceDate = null),
            ItemEntity("b", "Alpha", "", "TEXT", createdAt = 1, sourceDate = Long.MIN_VALUE),
            ItemEntity("c", "alpha", "", "TEXT", createdAt = 1, sourceDate = Long.MAX_VALUE),
            ItemEntity("d", "zeta", "", "TEXT", createdAt = 3, sourceDate = null)
        )
        values.forEach { dao.insertItem(it) }
        val expected = mapOf(
            SortOrder.RELEVANCE to listOf("d", "a", "b", "c"),
            SortOrder.SAVED_NEWEST to listOf("d", "a", "b", "c"),
            SortOrder.SAVED_OLDEST to listOf("b", "c", "a", "d"),
            SortOrder.SOURCE_NEWEST to listOf("c", "b", "a", "d"),
            SortOrder.SOURCE_OLDEST to listOf("b", "c", "a", "d"),
            SortOrder.TITLE_AZ to listOf("b", "c", "a", "d")
        )
        expected.forEach { (sort, orderedIds) ->
            val request = LibrarySearchRequest(sort = sort)
            var cursor: LibrarySearchCursor? = null
            val paged = mutableListOf<String>()
            repeat(values.size) {
                val row = rows(request, cursor, 1).single()
                paged += row.item.id
                cursor = LocalSearchQueries.cursor(request, row)
            }
            assertEquals(sort.name, orderedIds, paged)
            assertTrue(rows(request, cursor).isEmpty())
        }
    }

    @Test fun projectionBoundsBodiesAndSnippetsWhileRetainingMetadata() = runBlocking {
        val item = ItemEntity("large", "Original title", "preview ".repeat(1000), "PHOTO",
            contentHash = "synthetic-hash", fileSizeBytes = 123, reminderLocalDate = "2026-10-27",
            extractedText = "background ".repeat(10000) + "needle", aiDescription = "description ".repeat(1000),
            aiConcepts = "concept ".repeat(1000), localIndexCoverage = "COMPLETE", localIndexVersion = 7)
        dao.insertItem(item)
        val row = rows(LibrarySearchRequest("needle")).single()
        assertEquals(512, row.item.content.length)
        assertEquals(512, row.item.aiDescription!!.length)
        assertNull(row.item.extractedText)
        assertNull(row.item.aiConcepts)
        assertTrue(row.matchSnippet!!.contains("needle"))
        assertTrue(row.matchSnippet.length <= 512)
        db.openHelper.readableDatabase.query(LocalSearchQueries.page(LibrarySearchRequest())).use { result ->
            assertTrue(result.moveToFirst())
            assertEquals(item.contentHash, result.getString(result.getColumnIndexOrThrow("contentHash")))
            assertEquals(item.fileSizeBytes, result.getLong(result.getColumnIndexOrThrow("fileSizeBytes")))
            assertEquals(item.reminderLocalDate, result.getString(result.getColumnIndexOrThrow("reminderLocalDate")))
            assertEquals(item.localIndexVersion, result.getInt(result.getColumnIndexOrThrow("localIndexVersion")))
            assertTrue(result.isNull(result.getColumnIndexOrThrow("matchSnippet")))
        }
    }

    @Test fun punctuationAndNoHitStayEmptyAndPlanUsesTheFtsIndexWithBoundValues() = runBlocking {
        dao.insertItem(ItemEntity("one", "antidisestablishmentarianism", "", "TEXT"))
        assertTrue(ids(LibrarySearchRequest("establish")).isEmpty())
        assertTrue(ids(LibrarySearchRequest("unfindable")).isEmpty())
        assertTrue(ids(LibrarySearchRequest("+++ : \" ()")).isEmpty())
        val request = LibrarySearchRequest("needle ' OR 1=1 --", kind = "PHOTO", collectionId = "folder'")
        val query = LocalSearchQueries.page(request)
        assertFalse(query.sql.contains(request.query))
        assertFalse(query.sql.contains(request.collectionId!!))
        assertFalse(query.sql.contains(" LIKE ", ignoreCase = true))
        assertFalse(query.sql.contains("OFFSET", ignoreCase = true))
        val plan = db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN ${query.sql}", bindings(query))).use { result ->
            buildList { while (result.moveToNext()) add(result.getString(3)) }.joinToString("\n")
        }
        assertTrue(plan, plan.contains("VIRTUAL TABLE INDEX", ignoreCase = true))
        assertTrue(plan, plan.contains("items_fts", ignoreCase = true))
    }

    private fun bindings(query: SupportSQLiteQuery): Array<Any?> {
        val values = arrayOfNulls<Any>(query.argCount)
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { values[index - 1] = null }
            override fun bindLong(index: Int, value: Long) { values[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { values[index - 1] = value }
            override fun bindString(index: Int, value: String) { values[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { values[index - 1] = value }
            override fun clearBindings() { values.fill(null) }
            override fun close() = Unit
        })
        return values
    }
}
