package com.example.data

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.model.DateBasis
import com.example.model.SortOrder
import com.personallibrary.core.model.SearchQuery

data class LibrarySearchRequest(
    val query: String = "",
    val kind: String? = null,
    val collectionId: String? = null,
    val basis: DateBasis = DateBasis.SAVED_DATE,
    val startInclusive: Long? = null,
    val endExclusive: Long? = null,
    val invalidDateRange: Boolean = false,
    val sort: SortOrder = SortOrder.RELEVANCE,
    val trash: Boolean = false
)

data class LibrarySearchCursor(
    val request: LibrarySearchRequest,
    val rank: Long,
    val number: Long,
    val title: String,
    val id: String
)

data class LocalSearchRow(
    @Embedded val item: ItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ItemCollectionCrossRef::class,
            parentColumn = "itemId",
            entityColumn = "collectionId"
        )
    )
    val collections: List<CollectionEntity>,
    val searchRank: Long,
    val matchSnippet: String?
)

data class LibrarySearchPage(
    val items: List<ItemWithCollections>,
    val snippets: Map<String, String>,
    val nextCursor: LibrarySearchCursor?
)

object LocalSearchQueries {
    const val PAGE_SIZE = 50

    const val SUMMARY_COLUMNS = """
        i.id, i.title, substr(i.content, 1, 512) AS content, i.kind,
        i.isFavorite, i.isTrash, i.trashTimestamp, i.createdAt, i.sourceDate,
        i.reminderTime, i.isReminderDone, i.isReminderExact, i.isReminderDateOnly,
        i.reminderLocalDate, i.contentHash, i.originalFileName, i.mimeType, i.fileSizeBytes,
        NULL AS extractedText, i.localIndexCoverage, i.localIndexVersion,
        substr(i.aiDescription, 1, 512) AS aiDescription, NULL AS aiConcepts, i.aiStatus
    """

    fun page(
        request: LibrarySearchRequest,
        cursor: LibrarySearchCursor? = null,
        limit: Int = PAGE_SIZE + 1
    ): SupportSQLiteQuery {
        require(limit in 1..PAGE_SIZE + 1) { "Search pages allow at most one lookahead row" }
        require(cursor == null || cursor.request == request) { "Search cursor belongs to another request" }
        val args = mutableListOf<Any>()
        val fts = SearchQuery.fts(request.query)
        val sourceSort = request.sort == SortOrder.SOURCE_NEWEST || request.sort == SortOrder.SOURCE_OLDEST
        val rank = when {
            // The opaque rank also carries source-date nullness for exact paging without sentinels.
            sourceSort -> "CASE WHEN i.sourceDate IS NULL THEN 1 ELSE 0 END"
            fts != null && request.sort == SortOrder.RELEVANCE -> {
                val anyToken = SearchQuery.tokens(request.query).joinToString(" OR ") { SearchQuery.fts(it)!! }
                fun hit(column: String): String {
                    args += anyToken
                    return "i.rowid IN (SELECT docid FROM items_fts WHERE $column MATCH ?)"
                }
                // Each tier dominates all lower tiers together; only indexed column hits are read.
                "(CASE WHEN ${hit("title")} THEN 8 ELSE 0 END + " +
                    "CASE WHEN ${hit("originalFileName")} THEN 4 ELSE 0 END + " +
                    "CASE WHEN (${hit("content")} OR ${hit("extractedText")}) THEN 2 ELSE 0 END + " +
                    "CASE WHEN (${hit("aiDescription")} OR ${hit("aiConcepts")}) THEN 1 ELSE 0 END)"
            }
            else -> "0"
        }
        val where = mutableListOf("i.isTrash = ?")
        args += if (request.trash) 1 else 0
        if (fts != null) {
            where += "items_fts MATCH ?"
            args += fts
        } else if (request.query.isNotBlank()) {
            where += "0"
        }
        request.kind?.let { where += "i.kind = ?"; args += it }
        when (request.collectionId) {
            null -> Unit
            "computed_favorites" -> where += "i.isFavorite = 1"
            "computed_due" -> where += "i.reminderTime IS NOT NULL AND i.isReminderDone = 0"
            "computed_unfiled" -> where += "NOT EXISTS (SELECT 1 FROM item_collection_cross_ref r WHERE r.itemId = i.id)"
            else -> {
                where += "EXISTS (SELECT 1 FROM item_collection_cross_ref r WHERE r.itemId = i.id AND r.collectionId = ?)"
                args += request.collectionId
            }
        }
        val dateColumn = if (request.basis == DateBasis.SOURCE_DATE) "i.sourceDate" else "i.createdAt"
        request.startInclusive?.let { where += "$dateColumn >= ?"; args += it }
        request.endExclusive?.let { where += "$dateColumn < ?"; args += it }
        if (request.invalidDateRange) where += "0"

        val order = order(request, fts != null)
        val seek = cursor?.let { "WHERE ${seek(request, it, fts != null, args)}" }.orEmpty()
        args += limit
        val snippet = if (fts == null) "NULL" else "substr(snippet(items_fts, '', '', '…', -1, 24), 1, 512)"
        // FTS4 otherwise restarts the entire MATCH once per card. Keep FTS outside the
        // bounded ID join so one MATCH cursor supplies snippets for the selected page.
        val from = if (fts == null) "page_ids p JOIN items i ON i.rowid = p.itemRowId" else {
            args += fts
            "items_fts CROSS JOIN page_ids p ON p.itemRowId = items_fts.docid " +
                "JOIN items i ON i.rowid = p.itemRowId WHERE items_fts MATCH ?"
        }
        return SimpleSQLiteQuery(
            """
            WITH ranked AS (
                SELECT i.rowid AS itemRowId, i.id, i.title, i.createdAt, i.sourceDate, $rank AS searchRank
                FROM items i ${if (fts == null) "" else "JOIN items_fts ON items_fts.docid = i.rowid"}
                WHERE ${where.joinToString(" AND ")}
            ), page_ids AS (
                SELECT * FROM ranked $seek ORDER BY $order LIMIT ?
            )
            SELECT $SUMMARY_COLUMNS, p.searchRank, $snippet AS matchSnippet
            FROM $from
            ORDER BY ${order.split(", ").joinToString(", ") { "p.$it" }}
            """.trimIndent(), args.toTypedArray()
        )
    }

    fun cursor(request: LibrarySearchRequest, row: LocalSearchRow): LibrarySearchCursor = LibrarySearchCursor(
        request = request,
        rank = row.searchRank,
        number = when (request.sort) {
            SortOrder.SOURCE_NEWEST, SortOrder.SOURCE_OLDEST -> row.item.sourceDate ?: 0L
            else -> row.item.createdAt
        },
        title = row.item.title,
        id = row.item.id
    )

    private fun order(request: LibrarySearchRequest, hasTerms: Boolean): String = when (request.sort) {
        SortOrder.RELEVANCE -> if (hasTerms) "searchRank DESC, createdAt DESC, id ASC" else "createdAt DESC, id ASC"
        SortOrder.SAVED_NEWEST -> "createdAt DESC, id ASC"
        SortOrder.SAVED_OLDEST -> "createdAt ASC, id ASC"
        SortOrder.SOURCE_NEWEST -> "searchRank ASC, sourceDate DESC, id ASC"
        SortOrder.SOURCE_OLDEST -> "searchRank ASC, sourceDate ASC, id ASC"
        SortOrder.TITLE_AZ -> "title COLLATE NOCASE ASC, id ASC"
    }

    private fun seek(
        request: LibrarySearchRequest,
        cursor: LibrarySearchCursor,
        hasTerms: Boolean,
        args: MutableList<Any>
    ): String {
        fun numeric(column: String, ascending: Boolean): String {
            args.addAll(listOf(cursor.number, cursor.number, cursor.id))
            return "($column ${if (ascending) ">" else "<"} ? OR ($column = ? AND id > ?))"
        }
        return when (request.sort) {
            SortOrder.RELEVANCE -> if (!hasTerms) numeric("createdAt", false) else {
                args.addAll(listOf(cursor.rank, cursor.rank))
                "(searchRank < ? OR (searchRank = ? AND ${numeric("createdAt", false)}))"
            }
            SortOrder.SAVED_NEWEST -> numeric("createdAt", false)
            SortOrder.SAVED_OLDEST -> numeric("createdAt", true)
            SortOrder.SOURCE_NEWEST, SortOrder.SOURCE_OLDEST -> if (cursor.rank == 1L) {
                args += cursor.id
                "(searchRank = 1 AND id > ?)"
            } else {
                "(searchRank = 1 OR (searchRank = 0 AND ${numeric("sourceDate", request.sort == SortOrder.SOURCE_OLDEST)}))"
            }
            SortOrder.TITLE_AZ -> {
                args.addAll(listOf(cursor.title, cursor.title, cursor.id))
                "(title COLLATE NOCASE > ? OR (title COLLATE NOCASE = ? AND id > ?))"
            }
        }
    }
}
