package com.example.data

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EmbeddingCodecTest {
    @Test fun roundTripIsExactFloat32LittleEndian() {
        val source = FloatArray(768) { (it - 384) * .125f }.also { it[0] = 1f; it[1] = -0f }
        val bytes = requireNotNull(EmbeddingCodec.encode(source))
        assertEquals(3072, bytes.size)
        assertArrayEquals(byteArrayOf(0, 0, -128, 63, 0, 0, 0, -128), bytes.copyOf(8))
        assertArrayEquals(source, EmbeddingCodec.decode(bytes, 2, 768, "gemini-embedding-2"), 0f)
        assertArrayEquals(bytes, EmbeddingCodec.migrateCsv(source.joinToString(","), 768, "gemini-embedding-2"))
    }

    @Test fun incompatibleAndNonfiniteVectorsAreRejectedWithoutThrowing() {
        val valid = requireNotNull(EmbeddingCodec.encode(FloatArray(768) { .25f }))
        assertNull(EmbeddingCodec.encode(FloatArray(767)))
        assertNull(EmbeddingCodec.encode(FloatArray(769)))
        assertNull(EmbeddingCodec.decode(null, 2, 768, "gemini-embedding-2"))
        for (size in listOf(0, 4, 3071, 3073)) assertNull(EmbeddingCodec.decode(ByteArray(size), 2, 768, "gemini-embedding-2"))
        assertNull(EmbeddingCodec.decode(valid, 1, 768, "gemini-embedding-2"))
        assertNull(EmbeddingCodec.decode(valid, 2, 767, "gemini-embedding-2"))
        assertNull(EmbeddingCodec.decode(valid, 2, 768, "old-model"))
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(EmbeddingCodec.encode(FloatArray(768).also { it[100] = bad }))
            val corrupt = valid.copyOf()
            ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putFloat(100 * 4, bad)
            assertNull(EmbeddingCodec.decode(corrupt, 2, 768, "gemini-embedding-2"))
        }
    }
}
