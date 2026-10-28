package com.example.indexing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.personallibrary.core.ExtractedText
import com.personallibrary.core.ExtractedPage
import com.personallibrary.core.TextExtraction
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

object AndroidTextExtractor {
    const val MAX_EDGE = 1800
    const val MAX_PDF_PAGES = 100

    suspend fun image(file: File, mime: String): ExtractedText {
        var sampled = mime in setOf("image/gif", "image/webp")
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            require(longest > 0)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (longest > MAX_EDGE) {
                val scale = MAX_EDGE.toDouble() / longest
                decoder.setTargetSize(max(1, (info.size.width * scale).roundToInt()), max(1, (info.size.height * scale).roundToInt()))
            }
            sampled = sampled || info.isAnimated
        }
        val recognizer = try { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
        catch (failure: Throwable) { bitmap.recycle(); throw failure }
        return try { bounded(recognize(bitmap, recognizer), sampled) }
        finally { recognizer.close() }
    }

    suspend fun pdf(file: File, onProgress: suspend (ExtractedText) -> Unit = {}): ExtractedText {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { document ->
                require(document.pageCount > 0) { "PDF has no readable pages" }
                val text = StringBuilder()
                val pages = mutableListOf<ExtractedPage>()
                var bytes = 0
                var checkpointPages = 0
                var checkpointTime = SystemClock.elapsedRealtime()
                fun snapshot(partial: Boolean) = ExtractedText(text.toString(), if (partial) "partial" else "complete", pages.toList())
                val count = minOf(document.pageCount, MAX_PDF_PAGES)
                val recognizer = lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
                try {
                    for (number in 0 until count) {
                        currentCoroutineContext().ensureActive()
                        if (bytes >= TextExtraction.MAX_BYTES) return snapshot(true)
                        document.openPage(number).use { page ->
                            val embedded = if (Build.VERSION.SDK_INT >= 35) {
                                try {
                                    val textContentsMethod = page.javaClass.getMethod("getTextContents")
                                    val list = textContentsMethod.invoke(page) as? List<*>
                                    list?.joinToString("\n") { elem ->
                                        val getTextMethod = elem?.javaClass?.getMethod("getText")
                                        getTextMethod?.invoke(elem)?.toString() ?: ""
                                    } ?: ""
                                } catch (_: Throwable) { "" }
                            } else ""
                            val content = if (embedded.isNotBlank()) embedded else {
                                val scale = MAX_EDGE.toDouble() / max(page.width, page.height).coerceAtLeast(1)
                                val bitmap = Bitmap.createBitmap(
                                    max(1, (page.width * scale).roundToInt()),
                                    max(1, (page.height * scale).roundToInt()),
                                    Bitmap.Config.ARGB_8888
                                )
                                bitmap.eraseColor(Color.WHITE)
                                try {
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                } catch (error: Exception) {
                                    bitmap.recycle()
                                    throw error
                                }
                                val client = try { recognizer.value }
                                catch (failure: Throwable) { bitmap.recycle(); throw failure }
                                recognize(bitmap, client)
                            }
                            val remaining = TextExtraction.MAX_BYTES - bytes
                            val boundedPage = TextExtraction.read(content.byteInputStream(), remaining)
                            pages.add(ExtractedPage(number + 1, boundedPage.text))
                            text.append(boundedPage.text)
                            bytes += boundedPage.text.toByteArray(Charsets.UTF_8).size
                            if (bytes < TextExtraction.MAX_BYTES) { text.append('\n'); bytes++ }
                            if (boundedPage.coverage != "complete") return snapshot(true)
                        }
                        val processed = number + 1
                        val sinceCheckpoint = processed - checkpointPages
                        val now = SystemClock.elapsedRealtime()
                        // Preserve recoverable partial text without rewriting the growing FTS body per page.
                        if (processed < count && (sinceCheckpoint >= 10 || sinceCheckpoint >= 5 && now - checkpointTime >= 750)) {
                            onProgress(snapshot(true))
                            checkpointPages = processed
                            checkpointTime = now
                        }
                    }
                } finally { if (recognizer.isInitialized()) recognizer.value.close() }
                return snapshot(document.pageCount > count)
            }
        }
    }

    private fun bounded(text: String, sampled: Boolean): ExtractedText {
        val result = TextExtraction.read(text.toByteArray(Charsets.UTF_8).inputStream())
        return if (sampled && result.coverage == "complete") result.copy(coverage = "partial") else result
    }

    private suspend fun recognize(bitmap: Bitmap, recognizer: TextRecognizer): String = suspendCancellableCoroutine { continuation ->
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (error: Exception) {
            bitmap.recycle()
            continuation.resumeWithException(error)
            return@suspendCancellableCoroutine
        }
        task.addOnCompleteListener { completed ->
            try {
                if (continuation.isActive) {
                    if (completed.isSuccessful) continuation.resume(completed.result.text)
                    else continuation.resumeWithException(completed.exception ?: IllegalStateException("OCR unavailable"))
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
