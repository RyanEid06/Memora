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
import com.example.model.DateFilter
import com.example.model.ItemKind
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
        report.appendLine("Fixture construction excluded; repository + relation loading + current Kotlin projection included.")
        report.appendLine("First query is NOT a cold-device measurement. Warm samples=20, nearest-rank percentiles; no latency threshold.")
        report.appendLine("No personal content, provider access, device operations or production database files.")
        report.appendLine("items,scenario,results,first_ms,warm_p50_ms,warm_p95_ms,warm_p99_ms,selects_per_call,like_queries_per_call")
        output.writeText(report.toString())

        val context = ApplicationProvider.getApplicationContext<Context>()
        for (count in listOf(1_000, 10_000, 50_000)) {
            val db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java)
                .addCallback(DATABASE_CALLBACK)
                .setQueryCallback({ sql, _ ->
                    if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) selects.incrementAndGet()
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
                    Triple("title", LocalSearchContext(query = "needletitle"), count / 100),
                    Triple("ocr", LocalSearchContext(query = "ocrneedle"), count / 100),
                    Triple("no-hit", LocalSearchContext(query = "unfindablezzzx"), 0),
                    Triple("one-character", LocalSearchContext(query = "s"), count),
                    Triple("scoped", scoped, (0 until count).count { it % 200 == 0 && it >= count / 2 })
                )
                for ((name, searchContext, expected) in cases) {
                    val selectStart = selects.get()
                    val fallbackStart = fallbacks.get()
                    val samples = mutableListOf<Double>()
                    repeat(21) {
                        val start = System.nanoTime()
                        val rows = repository.searchWithCollections(searchContext.query)
                        val result = projectLocalSearch(rows, searchContext)
                        samples += (System.nanoTime() - start) / 1_000_000.0
                        assertEquals("$count/$name", expected, result.items.size)
                        assertEquals(result.items, result.sections.flatMap { it.items })
                        if (name == "scoped") assertEquals(
                            (0 until count).filter { it % 200 == 0 && it >= count / 2 }.map { "baseline-$it" }.toSet(),
                            result.items.map { it.item.id }.toSet()
                        )
                    }
                    val warm = samples.drop(1).sorted()
                    fun percentile(fraction: Double) = warm[ceil(warm.size * fraction).toInt() - 1]
                    fun format(value: Double) = String.format(java.util.Locale.ROOT, "%.3f", value)
                    val line = listOf(count, name, expected, format(samples.first()), format(percentile(.50)),
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
}
