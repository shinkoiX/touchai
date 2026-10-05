package app.touchai.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import app.touchai.core.openai.RequestLogRecord
import app.touchai.core.openai.RequestLogSink
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class RequestLogStore(context: Context, name: String = "request_logs.db") : RequestLogSink {
    private val revisionState = MutableStateFlow(0L)
    val revision = revisionState.asStateFlow()
    private val errorState = MutableStateFlow<String?>(null)
    val error = errorState.asStateFlow()
    private val mutex = Mutex()
    private val outputDirectory = File(context.filesDir, "$name.output")
    private val helper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE requests (sequence INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, started_at INTEGER NOT NULL, status TEXT NOT NULL, record TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        override fun onOpen(db: SQLiteDatabase) {
            // A running record from a previous process did not receive a terminal event.
            db.query("requests", arrayOf("id", "record"), "status = ?", arrayOf("Running"), null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = Json.parseToJsonElement(cursor.getString(1)).jsonObject
                    val interrupted = JsonObject(record + ("status" to JsonPrimitive("Interrupted")))
                    db.update("requests", ContentValues().apply {
                        put("status", "Interrupted"); put("record", interrupted.toString())
                    }, "id = ?", arrayOf(cursor.getString(0)))
                }
            }
        }
    }

    override suspend fun started(record: RequestLogRecord) = write {
        helper.writableDatabase.insertOrThrow("requests", null, values(record))
    }

    override suspend fun finished(record: RequestLogRecord) = write {
        // Update, rather than insert, so clearing a running request does not resurrect it.
        val db = helper.writableDatabase
        val exists = db.query("requests", arrayOf("id"), "id = ?", arrayOf(record.id), null, null, null).use { it.moveToFirst() }
        if (!exists) return@write
        record.data["output"]?.let { output ->
            Files.createDirectories(outputDirectory.toPath())
            val temporary = File(outputDirectory, "${record.id}.tmp")
            temporary.writeText(output.toString())
            Files.move(temporary.toPath(), outputFile(record.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        db.update("requests", values(record), "id = ?", arrayOf(record.id))
    }

    private fun values(record: RequestLogRecord) = ContentValues().apply {
        put("id", record.id); put("started_at", record.startedAt)
        val summary = if (record.data.containsKey("output")) JsonObject((record.data - "output") + ("hasOutput" to JsonPrimitive(true))) else record.data
        put("status", record.status); put("record", summary.toString())
    }

    private suspend fun write(block: () -> Unit) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try { block(); revisionState.value++ }
                catch (_: SQLiteException) { errorState.value = "Some request logs could not be saved. Device storage may be unavailable." }
                catch (_: IOException) { errorState.value = "Some request output could not be saved. Device storage may be unavailable." }
            }
        }
    }

    suspend fun readDetail(id: String): RequestLogRecord = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.readableDatabase.query("requests", arrayOf("started_at", "record"), "id = ?", arrayOf(id), null, null, null).use { cursor ->
                if (!cursor.moveToFirst()) throw IOException("The request log was deleted.")
                val summary = Json.parseToJsonElement(cursor.getString(1)).jsonObject
                val data = if (summary["hasOutput"]?.jsonPrimitive?.boolean == true)
                    JsonObject(summary + ("output" to Json.parseToJsonElement(outputFile(id).readText()))) else summary
                RequestLogRecord(id, cursor.getLong(0), data)
            }
        }
    }

    private fun outputFile(id: String) = File(outputDirectory, "$id.json")

    suspend fun read(limit: Int): List<RequestLogRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.readableDatabase.query("requests", arrayOf("id", "started_at", "record"), null, null, null, null,
                "sequence DESC", limit.toString()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(RequestLogRecord(cursor.getString(0), cursor.getLong(1),
                        Json.parseToJsonElement(cursor.getString(2)).jsonObject))
                }
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.writableDatabase.delete("requests", null, null)
            if (outputDirectory.exists() && !outputDirectory.deleteRecursively()) throw IOException("Could not delete saved request output.")
            errorState.value = null
            revisionState.value++
        }
    }

    internal fun close() = helper.close()
}
