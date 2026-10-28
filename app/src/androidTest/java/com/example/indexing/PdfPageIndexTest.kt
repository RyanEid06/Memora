package com.example.indexing

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfPageIndexTest {
    @Test fun scannedPdfRecognizesBothPagesInOneExtractionJob() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("synthetic-scanned-pages-", ".pdf", context.cacheDir)
        val bitmaps = mutableListOf<android.graphics.Bitmap>()
        try {
            val document = PdfDocument()
            try {
                for ((index, word) in listOf("chemistry", "biology").withIndex()) {
                    val bitmap = android.graphics.Bitmap.createBitmap(600, 800, android.graphics.Bitmap.Config.ARGB_8888)
                    bitmaps += bitmap
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    android.graphics.Canvas(bitmap).drawText("Synthetic $word", 40f, 100f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 36f })
                    val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, index + 1).create())
                    // A raster-only PDF has no embedded text; both pages must use the OCR session.
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(page)
                }
                source.outputStream().use(document::writeTo)
            } finally { document.close() }
            val result = AndroidTextExtractor.pdf(source)
            assertEquals(listOf(1, 2), result.pages.map { it.number })
            assertTrue(result.pages[0].text.contains("chemistry", true))
            assertTrue(result.pages[1].text.contains("biology", true))
            assertEquals("complete", result.coverage)
        } finally { bitmaps.forEach { it.recycle() }; source.delete() }
    }

    @Test fun hundredPageExtractionBoundsCheckpointsAndPreservesPageText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("synthetic-hundred-pages-", ".pdf", context.cacheDir)
        try {
            val document = PdfDocument()
            try {
                for (number in 1..100) {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(600, 800, number).create())
                    page.canvas.drawText("Synthetic page $number preserved", 40f, 80f, Paint().apply { textSize = 28f })
                    document.finishPage(page)
                }
                source.outputStream().use(document::writeTo)
            } finally { document.close() }
            val checkpoints = mutableListOf<Int>()
            val result = AndroidTextExtractor.pdf(source) { progress ->
                checkpoints += progress.pages.size
                assertTrue(progress.text.toByteArray(Charsets.UTF_8).size <= com.personallibrary.core.TextExtraction.MAX_BYTES)
            }
            assertEquals((1..100).toList(), result.pages.map { it.number })
            assertTrue(result.pages.all { "Synthetic page ${it.number} preserved" in it.text })
            assertEquals("complete", result.coverage)
            assertTrue("Expected bounded checkpoints; got ${checkpoints.size}", checkpoints.size in 1..20)
        } finally { source.delete() }
    }

    @Test fun excludedPdfRetainsPageReferencesAndLocalFtsWithoutRemoteAi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("pages-", ".pdf", context.cacheDir)
        val pdf = PdfDocument()
        try {
            for (number in 1..2) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, number).create())
                page.canvas.drawText(if (number == 1) "Chemistry on page one" else "Biology on page two", 40f, 80f, Paint().apply { textSize = 28f })
                pdf.finishPage(page)
            }
            source.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        val repository = LibraryRepository(context)
        val item = repository.captureStream(source.inputStream(), "synthetic-pages.pdf", "application/pdf")
        try {
            context.getSharedPreferences("personal_library_settings", 0).edit().putStringSet("excluded_items", setOf(item.id)).commit()
            val extraction = LocalIndexer(context, repository).indexItem(item)
            assertEquals(listOf(1, 2), extraction.pages.map { it.number })
            val documents = repository.dao.getSearchDocumentsForItem(item.id)
            assertEquals(setOf(1, 2), documents.mapNotNull { it.pageNumber }.toSet())
            assertTrue(documents.filter { it.pageNumber == 1 }.any { it.textContent.contains("Chemistry", true) })
            assertTrue(repository.dao.searchFts("chemistry").any { it.id == item.id })
            assertEquals("EXCLUDED", repository.dao.getItemById(item.id)!!.aiStatus)
            // A legacy item has flat extracted text but no numbered chunks. Rebuild locally before AI.
            val current = repository.dao.getItemById(item.id)!!
            repository.dao.deleteSearchDocumentsForItem(item.id)
            repository.dao.updateExtractedText(item.id, "Legacy flattened chemistry and biology", "COMPLETE", current.localIndexVersion)
            val worker = androidx.work.testing.TestListenableWorkerBuilder<DurableAiWorker>(context,
                inputData = androidx.work.workDataOf("item_id" to item.id, "content_version" to current.localIndexVersion)).build()
            assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
            assertEquals(current.localIndexVersion + 1, repository.dao.getItemById(item.id)!!.localIndexVersion)
            assertEquals(setOf(1, 2), repository.dao.getSearchDocumentsForItem(item.id).mapNotNull { it.pageNumber }.toSet())
            assertEquals("EXCLUDED", repository.dao.getItemById(item.id)!!.aiStatus)
        } finally { repository.deletePermanently(item.id); source.delete() }
    }
}
