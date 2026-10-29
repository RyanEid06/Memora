package com.example.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Version2 is exactly768 finite IEEE-754 Float32 values, little-endian, with no text/header. */
internal object EmbeddingCodec {
    const val FORMAT_VERSION = 2
    const val DIMENSION = 768
    const val MODEL = "gemini-embedding-2"
    const val BYTE_COUNT = DIMENSION * 4

    fun encode(vector: FloatArray): ByteArray? {
        if (vector.size != DIMENSION || vector.any { !it.isFinite() }) return null
        return ByteBuffer.allocate(BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach { putFloat(it) } }.array()
    }

    fun decode(bytes: ByteArray?, format: Int, dimension: Int, model: String): FloatArray? {
        if (bytes?.size != BYTE_COUNT || format != FORMAT_VERSION || dimension != DIMENSION || model != MODEL) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(DIMENSION)
        for (index in vector.indices) {
            val value = buffer.float
            if (!value.isFinite()) return null
            vector[index] = value
        }
        return vector
    }

    // Used only while streaming schema8 rows; ordinary reads never parse CSV.
    fun migrateCsv(csv: String?, dimension: Int, model: String): ByteArray? {
        if (csv == null || csv.length > 24_000 || dimension != DIMENSION || model != MODEL) return null
        val vector = FloatArray(DIMENSION)
        var start = 0
        for (index in vector.indices) {
            val comma = csv.indexOf(',', start)
            if ((comma == -1) != (index == DIMENSION - 1)) return null
            val end = if (comma == -1) csv.length else comma
            vector[index] = csv.substring(start, end).toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            start = end + 1
        }
        return encode(vector)
    }
}
