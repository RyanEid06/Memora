package com.example.ui

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.CollectionEntity
import com.example.data.DATABASE_CALLBACK
import com.example.data.ItemCollectionCrossRef
import com.example.data.ItemEntity
import com.example.data.LibraryRepository
import com.example.data.LibraryRoomDatabase
import com.example.data.SearchDocumentEntity
import com.example.model.DateFilter
import com.example.model.ItemKind
import com.example.model.SortOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import java.lang.management.ManagementFactory

/** Host characterization only: opt in explicitly; never enforce device latency from Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchPerformanceBaselineTest {
    @Test fun measureRealRepositoryOnSyntheticLibraries() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("memora.performanceBaseline"))
        val output = File(requireNotNull(System.getProperty("memora.performanceBaselineOutput")))
        output.parentFile?.mkdirs()
        val selects = AtomicInteger()
        val fallbacks = AtomicInteger()
        val report = StringBuilder()
        report.appendLine("# Synthetic search baseline (host JVM / Robolectric API 34 / in-memory Room)")
        report.appendLine("source=${System.getProperty("memora.performanceBaselineSource", "unrecorded")}")
        report.appendLine("java=${System.getProperty("java.version")}; os=${System.getProperty("os.name")}; arch=${System.getProperty("os.arch")}; processors=${Runtime.getRuntime().availableProcessors()}")
        report.appendLine("Bounded SQL-filtered first pages (max 50 rows); repository + relation loading + grouping included, fixture construction excluded.")
        report.appendLine("First query is NOT a cold-device measurement. Warm samples=20, nearest-rank percentiles; no latency threshold.")
        report.appendLine("No personal content, provider access, device operations or production database files.")
        report.appendLine("items,scenario,results,first_ms,warm_p50_ms,warm_p95_ms,warm_p99_ms,selects_per_call,like_queries_per_call")
        output.writeText(report.toString())

        val context = ApplicationProvider.getApplicationContext<Context>()
        for (count in listOf(1_000, 10_000, 50_000)) {
            val db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java)
                .addCallback(DATABASE_CALLBACK)
                .setQueryCallback({ sql, _ ->
                    if (sql.trimStart().let { it.startsWith("SELECT", true) || it.startsWith("WITH", true) }) selects.incrementAndGet()
                    if (sql.contains(" LIKE ", ignoreCase = true)) fallbacks.incrementAndGet()
                }, Executor { it.run() })
                .build()
            try {
                val dao = db.libraryDao()
                val repository = LibraryRepository(context, db)
                // 1% large OCR rows; controlled tokens keep match cardinality independently known.
                val pdfText = "ocrneedle " + "synthetic extracted page text ".repeat(2_500)
                db.withTransaction {
                    dao.insertCollection(CollectionEntity("baseline-selected", "Synthetic folder"))
                    repeat(count) { index ->
                        val target = index % 100 == 0
                        dao.insertItem(ItemEntity(
                            id = "baseline-$index",
                            title = if (target) "needletitle $index" else "archive $index",
                            content = "synthetic library body",
                            kind = if (target) "DOCUMENT" else "TEXT",
                            createdAt = index.toLong(),
                            extractedText = if (target) pdfText else null,
                            aiStatus = "READY", localIndexCoverage = "COMPLETE"
                        ))
                        if (index % 200 == 0) dao.insertItemCollectionCrossRef(
                            ItemCollectionCrossRef("baseline-$index", "baseline-selected")
                        )
                    }
                }
                val scoped = LocalSearchContext(
                    query = "needletitle", kind = ItemKind.DOCUMENT, collectionId = "baseline-selected",
                    dateFilter = DateFilter.CUSTOM, customStart = (count / 2).toLong(), customEnd = (count - 1).toLong()
                )
                val cases = listOf(
                    Triple("browse", LocalSearchContext(sort = SortOrder.RELEVANCE), count),
                    Triple("title", LocalSearchContext(query = "needletitle", sort = SortOrder.RELEVANCE), count / 100),
                    Triple("ocr", LocalSearchContext(query = "ocrneedle", sort = SortOrder.RELEVANCE), count / 100),
                    Triple("no-hit", LocalSearchContext(query = "unfindablezzzx", sort = SortOrder.RELEVANCE), 0),
                    Triple("one-character", LocalSearchContext(query = "s", sort = SortOrder.RELEVANCE), count),
                    Triple("one-character-saved", LocalSearchContext(query = "s"), count),
                    Triple("scoped", scoped.copy(sort = SortOrder.RELEVANCE), (0 until count).count { it % 200 == 0 && it >= count / 2 })
                )
                for ((name, searchContext, expected) in cases) {
                    val selectStart = selects.get()
                    val fallbackStart = fallbacks.get()
                    val samples = mutableListOf<Double>()
                    repeat(21) {
                        val start = System.nanoTime()
                        val page = repository.searchPage(searchContext.toLibrarySearchRequest())
                        val result = projectLocalSearch(page.items, searchContext, preserveOrder = true)
                        samples += (System.nanoTime() - start) / 1_000_000.0
                        assertEquals("$count/$name", minOf(50, expected), result.items.size)
                        assertEquals(result.items, result.sections.flatMap { it.items })
                        if (name == "scoped") assertEquals(
                            (0 until count).filter { it % 200 == 0 && it >= count / 2 }.reversed().take(50).map { "baseline-$it" }.toSet(),
                            result.items.map { it.item.id }.toSet()
                        )
                    }
                    val warm = samples.drop(1).sorted()
                    fun percentile(fraction: Double) = warm[ceil(warm.size * fraction).toInt() - 1]
                    fun format(value: Double) = String.format(java.util.Locale.ROOT, "%.3f", value)
                    val line = listOf(count, name, minOf(50, expected), format(samples.first()), format(percentile(.50)),
                        format(percentile(.95)), format(percentile(.99)),
                        format((selects.get() - selectStart) / 21.0), format((fallbacks.get() - fallbackStart) / 21.0))
                        .joinToString(",")
                    report.appendLine(line)
                    output.appendText("$line\n")
                    println("MEMORA_BASELINE $line")
                }
                // Exercise actual FTS invalidation caused by a synthetic PDF progress checkpoint.
                val pdf = dao.getItemById("baseline-0")!!
                dao.updateItem(pdf.copy(extractedText = "$pdfText progressneedle"))
                assertEquals(listOf("baseline-0"), repository.searchWithCollections("progressneedle").map { it.item.id })
                db.openHelper.writableDatabase.execSQL("INSERT INTO items_fts(items_fts) VALUES('integrity-check')")
            } finally {
                db.close()
            }
        }
        output.appendText("# Correctness and post-checkpoint FTS integrity passed for all three fixtures.\n")
    }
    @Test fun measureInteractiveRetrievalAndVectorDecoding() = runBlocking {
        assumeTrue(java.lang.Boolean.getBoolean("memora.performanceBaseline"))
        val searchOutput = File(requireNotNull(System.getProperty("memora.performanceBaselineOutput")))
        val output = File(searchOutput.parentFile, "${searchOutput.nameWithoutExtension}-ai-retrieval.csv")
        output.parentFile?.mkdirs()
        output.writeText("# Synthetic host JVM/Room retrieval plus selected-vector decoding; no device/provider acceptance.\n" +
            "# source=${System.getProperty("memora.performanceBaselineSource", "unrecorded")}; fixture setup and5warmups excluded;20samples.\n" +
            "items,chunks_per_item,documents,vectors,stored_vector_bytes,document_selects_per_call,p50_ms,p95_ms,p99_ms,allocated_bytes_per_call,gc_collections,gc_ms\n")
        val allocationBean = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)
            ?.takeIf { it.isThreadAllocatedMemorySupported }?.apply { isThreadAllocatedMemoryEnabled = true }
        fun allocated() = allocationBean?.let { bean ->
            bean.getThreadAllocatedBytes(bean.allThreadIds).filter { it >= 0 }.sum()
        }
        fun gcCount() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0, it.collectionCount) }
        fun gcTime() = ManagementFactory.getGarbageCollectorMXBeans().sumOf { maxOf(0, it.collectionTime) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("phase9_synthetic_baseline", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        for (count in listOf(10, 160, 1)) {
            val chunksPerItem = if (count == 1) 5_000 else 20
            val documentSelects = AtomicInteger()
            val db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java)
                .addCallback(DATABASE_CALLBACK)
                .setQueryCallback({ sql, _ -> if (sql.contains("FROM search_documents", true)) documentSelects.incrementAndGet() }, Executor { it.run() })
                .build()
            try {
                val dao = db.libraryDao()
                val vector = requireNotNull(com.example.data.EmbeddingCodec.encode(FloatArray(768) { .001f + (it % 17) * .0001234f }))
                db.withTransaction {
                    repeat(count) { index ->
                        val id = "ai-baseline-$index"
                        dao.insertItem(ItemEntity(id, "Birthday receipt $index", "Synthetic note", "DOCUMENT",
                            extractedText = "Synthetic OCR page text ".repeat(1_000)))
                        dao.insertSearchDocuments((0 until chunksPerItem).map { chunk ->
                            SearchDocumentEntity("doc_${id}_1_$chunk", id, chunk, chunk + 1,
                                if (count == 1 && chunk < chunksPerItem - 1) "Birthday unrelated page ".repeat(25)
                                else "Birthday receipt ".repeat(30), embedding = vector)
                        })
                    }
                }
                val retrieval = InteractiveAiRetrieval(dao, prefs)
                val query = LocalSearchContext("birthday receipt")
                var documents = 0
                var vectors = 0
                var storedBytes = 0
                suspend fun sample() {
                    val snapshot = retrieval.load(query)
                    documents = snapshot.documents.size
                    if (count == 1) assertEquals("Late PDF evidence must survive chunk ranking", chunksPerItem, snapshot.documents.first().pageNumber)
                    vectors = 0
                    storedBytes = 0
                    for (doc in snapshot.documents) {
                        doc.embedding?.let { encoded ->
                            storedBytes += encoded.size
                            val decoded = requireNotNull(com.example.data.EmbeddingCodec.decode(encoded, doc.embeddingFormatVersion, doc.embeddingDimension, doc.modelVersion))
                            assertEquals(768, decoded.size)
                            vectors++
                        }
                    }
                    assertEquals(minOf(480, count * 4), documents)
                    assertEquals(documents, vectors)
                }
                repeat(5) { sample() }
                val selectStart = documentSelects.get()
                val allocationStart = allocated()
                val collectionStart = gcCount()
                val gcTimeStart = gcTime()
                val samples = mutableListOf<Double>()
                repeat(20) {
                    val start = System.nanoTime()
                    sample()
                    samples += (System.nanoTime() - start) / 1_000_000.0
                }
                val allocatedBytes = allocated()?.let { after -> allocationStart?.let { (after - it) / 20 } } ?: -1
                val warm = samples.sorted()
                fun percentile(fraction: Double) = String.format(java.util.Locale.ROOT, "%.3f", warm[ceil(warm.size * fraction).toInt() - 1])
                val line = listOf(count, chunksPerItem, documents, vectors, storedBytes,
                    (documentSelects.get() - selectStart) / 20.0, percentile(.5), percentile(.95), percentile(.99),
                    allocatedBytes, gcCount() - collectionStart, gcTime() - gcTimeStart).joinToString(",")
                output.appendText("$line\n")
                println("MEMORA_AI_BASELINE $line")
            } finally { db.close() }
        }
    }
}
