package app.touchai.android

import app.touchai.core.openai.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class ChatHistoryEntry(val id: String, val title: String, val updatedAt: Long, val turnCount: Int)
data class SavedChat(val id: String, val updatedAt: Long, val selectedPreset: String?, val turns: List<ChatTurn>) {
    val entry: ChatHistoryEntry get() = ChatHistoryEntry(id,
        turns.first().user.text.replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Image chat" }, updatedAt, turns.size)
}

interface ChatHistoryRepository {
    suspend fun save(chat: SavedChat)
    suspend fun list(): List<ChatHistoryEntry>
    suspend fun load(id: String): SavedChat
    suspend fun delete(id: String)
}

class ChatHistoryStore(private val directory: File, private val cipher: ApiKeyCipher) : ChatHistoryRepository {
    private val mutex = Mutex()

    override suspend fun save(chat: SavedChat) = access {
        val entry = chat.entry
        val header = buildJsonObject {
            put("id", entry.id); put("title", entry.title); put("updatedAt", entry.updatedAt); put("turnCount", entry.turnCount)
        }
        val body = buildJsonObject {
            put("selectedPreset", chat.selectedPreset)
            putJsonArray("turns") { chat.turns.forEach { add(encodeChatTurn(it, cipher.encrypt(it.ai.api.apiKey))) } }
        }
        // Keep the small list entry on its own line so browsing history never loads image data.
        // One atomic replacement publishes the metadata, messages, and attachments together.
        val temporary = File(directory, "${chat.id}.tmp")
        temporary.bufferedWriter().use { it.appendLine(header.toString()); it.append(body.toString()) }
        Files.move(temporary.toPath(), file(chat.id).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    override suspend fun list(): List<ChatHistoryEntry> = access {
        Files.list(directory.toPath()).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".jsonl") }.map { path ->
                path.toFile().bufferedReader().use { reader ->
                    val header = Json.parseToJsonElement(reader.readLine()).jsonObject
                    ChatHistoryEntry(header.string("id"), header.string("title"), header.getValue("updatedAt").jsonPrimitive.long,
                        header.getValue("turnCount").jsonPrimitive.int)
                }
            }.toArray().map { it as ChatHistoryEntry }.sortedByDescending { it.updatedAt }
        }
    }

    override suspend fun load(id: String): SavedChat = access {
        file(id).bufferedReader().use { reader ->
            val header = Json.parseToJsonElement(reader.readLine()).jsonObject
            val body = Json.parseToJsonElement(reader.readText()).jsonObject
            SavedChat(id, header.getValue("updatedAt").jsonPrimitive.long, body.optionalString("selectedPreset"),
                body.getValue("turns").jsonArray.map { decodeChatTurn(it.jsonObject, cipher.decrypt(it.jsonObject.getValue("ai").jsonObject.string("encryptedApiKey"))) })
        }
    }

    override suspend fun delete(id: String) = access { Files.delete(file(id).toPath()) }

    private fun file(id: String) = File(directory, "$id.jsonl")
    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { Files.createDirectories(directory.toPath()); block() }
    }

}


private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String) = getValue(key).jsonPrimitive.contentOrNull
