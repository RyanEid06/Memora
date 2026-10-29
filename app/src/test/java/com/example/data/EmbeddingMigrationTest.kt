package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmbeddingMigrationTest {
    @Test fun version8PreservesTextAndConvertsVectors() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "vectors_${UUID.randomUUID()}.db"
        val sourceVectors = listOf(0f, 1f, -2.5f) + List(765) { (it % 17 - 8) / 8f }
        val validCsv = sourceVectors.joinToString(",")
        val invalid = listOf(
            "null" to null,
            "short" to List(767) { "0.25" }.joinToString(","),
            "long" to List(769) { "0.25" }.joinToString(","),
            "malformed" to (List(767) { "0.25" } + "oops").joinToString(","),
            "nan" to (List(767) { "0.25" } + "NaN").joinToString(","),
            "infinity" to (List(767) { "0.25" } + "Infinity").joinToString(","),
            "oversized" to "9".repeat(100_000),
            "wrong_model" to validCsv,
            "wrong_dimension" to validCsv
        )
        val schema = Json.parseToJsonElement(File("schemas/com.example.data.LibraryRoomDatabase/8.json").readText())
            .jsonObject.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { value ->
                            val entity = value.jsonObject
                            val table = entity.getValue("tableName").jsonPrimitive.content
                            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            entity["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            }
                        }
                        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        helper.writableDatabase.apply {
            execSQL("INSERT INTO items(id,title,content,kind,isFavorite,isTrash,createdAt,isReminderDone,isReminderExact,isReminderDateOnly,fileSizeBytes,localIndexCoverage,localIndexVersion,aiStatus) VALUES('item','Vector owner','Owner text','TEXT',1,0,123,0,1,0,0,'COMPLETE',8,'READY')")
            execSQL("INSERT INTO search_documents(id,itemId,chunkIndex,pageNumber,textContent,embedding,embeddingDimension,modelVersion,coverage) VALUES('valid','item',4,7,'valid text',?,768,'gemini-embedding-2','COMPLETE')", arrayOf(validCsv))
            invalid.forEach { (id, csv) ->
                val model = if (id == "wrong_model") "other-model" else "gemini-embedding-2"
                val dimension = if (id == "wrong_dimension") 767 else 768
                execSQL("INSERT INTO search_documents(id,itemId,chunkIndex,pageNumber,textContent,embedding,embeddingDimension,modelVersion,coverage) VALUES(?, 'item', ?, ?, ?, ?, ?, ?, ?)", arrayOf(id, invalid.indexOfFirst { it.first == id }, 10 + invalid.indexOfFirst { it.first == id }, "text:$id", csv, dimension, model, "COMPLETE"))
            }
            execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, LibraryRoomDatabase::class.java, name)
            .addMigrations(MIGRATION_7_8, MIGRATION_8_9).build()
        try {
            val db = migrated.openHelper.writableDatabase
            val columns = mutableMapOf<String, Pair<String, String?>>()
            db.query("PRAGMA table_info(search_documents)").use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                val typeColumn = cursor.getColumnIndexOrThrow("type")
                val defaultColumn = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) columns[cursor.getString(nameColumn)] = cursor.getString(typeColumn) to if (cursor.isNull(defaultColumn)) null else cursor.getString(defaultColumn)
            }
            assertEquals("BLOB", columns["embedding"]?.first)
            assertEquals("INTEGER", columns["embeddingFormatVersion"]?.first)
            assertEquals("2", columns["embeddingFormatVersion"]?.second)

            val expected = ByteBuffer.allocate(768 * 4).order(ByteOrder.LITTLE_ENDIAN).apply { sourceVectors.forEach(::putFloat) }.array()
            val vectors = mutableMapOf<String, ByteArray?>()
            db.query("SELECT id, embedding, embeddingFormatVersion, itemId, chunkIndex, pageNumber, textContent, embeddingDimension, modelVersion, coverage FROM search_documents ORDER BY id").use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow("id")
                val embeddingCol = cursor.getColumnIndexOrThrow("embedding")
                val formatCol = cursor.getColumnIndexOrThrow("embeddingFormatVersion")
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idCol)
                    val blob = if (cursor.isNull(embeddingCol)) null else cursor.getBlob(embeddingCol)
                    vectors[id] = blob
                    if (id == "valid") {
                        assertArrayEquals(expected, blob)
                        assertEquals(2, cursor.getInt(formatCol))
                        assertEquals("item", cursor.getString(cursor.getColumnIndexOrThrow("itemId")))
                        assertEquals(4, cursor.getInt(cursor.getColumnIndexOrThrow("chunkIndex")))
                        assertEquals(7, cursor.getInt(cursor.getColumnIndexOrThrow("pageNumber")))
                        assertEquals("valid text", cursor.getString(cursor.getColumnIndexOrThrow("textContent")))
                        assertEquals(768, cursor.getInt(cursor.getColumnIndexOrThrow("embeddingDimension")))
                        assertEquals("gemini-embedding-2", cursor.getString(cursor.getColumnIndexOrThrow("modelVersion")))
                        assertEquals("COMPLETE", cursor.getString(cursor.getColumnIndexOrThrow("coverage")))
                    } else {
                        assertNull("Invalid embedding should be discarded: $id", blob)
                        assertEquals("text:$id", cursor.getString(cursor.getColumnIndexOrThrow("textContent")))
                    }
                    if (id != "valid") {
                        val index = invalid.indexOfFirst { it.first == id }
                        assertEquals("item", cursor.getString(cursor.getColumnIndexOrThrow("itemId")))
                        assertEquals(index, cursor.getInt(cursor.getColumnIndexOrThrow("chunkIndex")))
                        assertEquals(10 + index, cursor.getInt(cursor.getColumnIndexOrThrow("pageNumber")))
                        assertEquals(if (id == "wrong_dimension") 767 else 768, cursor.getInt(cursor.getColumnIndexOrThrow("embeddingDimension")))
                        assertEquals(if (id == "wrong_model") "other-model" else "gemini-embedding-2", cursor.getString(cursor.getColumnIndexOrThrow("modelVersion")))
                        assertEquals("COMPLETE", cursor.getString(cursor.getColumnIndexOrThrow("coverage")))
                    }
                }
            }
            assertEquals(1 + invalid.size, vectors.size)
            assertEquals("Owner text", migrated.libraryDao().getItemById("item")!!.content)
            assertTrue(migrated.libraryDao().searchFts("Owner text*").any { it.id == "item" })
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
