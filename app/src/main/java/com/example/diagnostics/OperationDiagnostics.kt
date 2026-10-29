package com.example.diagnostics

import com.example.BuildConfig
import java.util.logging.Logger

/** Never attach the throwable: its message, fields and stack can contain owner data. */
internal object OperationDiagnostics {
    private val logger = Logger.getLogger("MemoraDiagnostics")

    fun failure(operation: String, startedNanos: Long, error: Exception, count: Int) {
        val durationMs = ((System.nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)
        logger.warning("operation=$operation duration_ms=$durationMs count=$count " +
            "error_class=${error.javaClass.simpleName} build=${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}/${BuildConfig.BUILD_TYPE}")
    }
}
