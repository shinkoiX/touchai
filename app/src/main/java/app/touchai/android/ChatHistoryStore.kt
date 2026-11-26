package app.touchai.android

import app.touchai.core.openai.*
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

data class ChatHistoryEntry(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val turnCount: Int,
    val pending: Boolean = false,
    val pairedChatId: String? = null,
    val pairSlot: Int? = null,
)
data class SavedChat(
    val id: String,
    val updatedAt: Long,
    val selectedPreset: String?,
    val turns: List<ChatTurn>,
    val tree: ChatTree? = null,
    val pairedChatId: String? = null,
    val pairSlot: Int? = null,
) {
    val resolvedTree: ChatTree get() = tree ?: ChatTree.fromLinear(turns)
    val latestTurns: List<ChatTurn> get() = tree?.latestTurns()?.takeIf { it.isNotEmpty() } ?: turns
    val entry: ChatHistoryEntry
        get() {
            val latest = latestTurns
            return ChatHistoryEntry(
                id = id,
                title = latest.first().user.text.replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Image chat" },
                updatedAt = updatedAt,
                turnCount = latest.size,
                pending = tree?.hasRunningNodes == true || turns.any { it.isRunning } || latest.last().isRunning,
                pairedChatId = pairedChatId,
                pairSlot = pairSlot,
            )
        }

    fun updateActiveLeaf(updatedAt: Long, transform: (ChatTurn) -> ChatTurn): SavedChat {
        val currentTree = tree
        if (currentTree != null) {
            val updatedTree = currentTree.updateActiveLeaf(transform)
            return copy(updatedAt = updatedAt, turns = updatedTree.activeTurns(), tree = updatedTree.takeIf { it.hasBranches })
        }
        return copy(updatedAt = updatedAt, turns = turns.dropLast(1) + transform(turns.last()))
    }

    fun updateNode(nodeId: String?, updatedAt: Long, transform: (ChatTurn) -> ChatTurn): SavedChat {
        val currentTree = tree
        if (currentTree != null && nodeId != null) {
            val updatedTree = currentTree.updateNode(nodeId, transform)
            return copy(updatedAt = updatedAt, turns = updatedTree.activeTurns(), tree = updatedTree.takeIf { it.hasBranches })
        }
        return updateActiveLeaf(updatedAt, transform)
    }

    fun selectBranch(nodeId: String): SavedChat {
        val currentTree = tree ?: return this
        val updatedTree = currentTree.selectBranch(nodeId)
        return copy(turns = updatedTree.activeTurns(), tree = updatedTree.takeIf { it.hasBranches })
    }
}

interface ChatHistoryRepository {
    suspend fun save(chat: SavedChat)
    suspend fun list(): List<ChatHistoryEntry>
    suspend fun search(query: String): List<ChatHistorySearchResult>
    suspend fun load(id: String): SavedChat
    suspend fun delete(id: String)
}

class ChatHistoryStore(private val directory: File, private val cipher: ApiKeyCipher) : ChatHistoryRepository {
    private val mutex = Mutex()
    private val index = ChatHistorySearchIndex()
    @Volatile private var indexLoaded = false
    private var journalLines = 0
    private val pendingPersisted = HashSet<String>()

    override suspend fun save(chat: SavedChat) = access {
        val entry = chat.entry
        val header = buildJsonObject {
            put("id", entry.id); put("title", entry.title); put("updatedAt", entry.updatedAt); put("turnCount", entry.turnCount)
            put("pending", entry.pending)
            entry.pairedChatId?.let { put("pairedChatId", it) }
            entry.pairSlot?.let { put("pairSlot", it) }
        }
        val body = encodeChatBody(chat.selectedPreset, chat.turns, chat.tree, chat.pairedChatId, chat.pairSlot) { turn ->
            cipher.encrypt(turn.ai.api.apiKey)
        }
        // Keep the small list entry on its own line so browsing history never loads image data.
        // One atomic replacement publishes the metadata, messages, and attachments together.
        val temporary = File(directory, "${chat.id}.tmp")
        temporary.bufferedWriter().use { it.appendLine(header.toString()); it.append(body.toString()) }
        val target = file(chat.id)
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        val record = IndexedChatRecord(entry, target.length(), target.lastModified(), extractSearchText(chat))
        if (indexLoaded) index.upsert(record)
        // Avoid rewriting the search index on every 1 Hz streaming save; persist on transitions and final saves.
        val shouldPersist = if (entry.pending) pendingPersisted.add(chat.id) else { pendingPersisted.remove(chat.id); true }
        if (shouldPersist) appendIndexLine(encodeIndexUpsert(record))
        Unit
    }

    override suspend fun list(): List<ChatHistoryEntry> = access {
        ensureIndexLoaded()
        index.listEntries()
    }

    override suspend fun search(query: String): List<ChatHistorySearchResult> {
        if (!indexLoaded) access { ensureIndexLoaded() }
        return withContext(Dispatchers.Default) {
            mutex.withLock { index.search(query) }
        }
    }

    override suspend fun load(id: String): SavedChat = access {
        file(id).bufferedReader().use { reader ->
            val header = Json.parseToJsonElement(reader.readLine()).jsonObject
            val body = Json.parseToJsonElement(reader.readText()).jsonObject
            val decoded = decodeChatBody(body) { turnObj ->
                cipher.decrypt(turnObj.getValue("ai").jsonObject.string("encryptedApiKey"))
            }
            val pairedChatId = header["pairedChatId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: decoded.pairedChatId
            val pairSlot = header["pairSlot"]?.jsonPrimitive?.intOrNull ?: decoded.pairSlot
            SavedChat(id, header.getValue("updatedAt").jsonPrimitive.long, decoded.selectedPreset, decoded.turns, decoded.tree, pairedChatId, pairSlot)
        }
    }

    override suspend fun delete(id: String) = access {
        Files.delete(file(id).toPath())
        pendingPersisted.remove(id)
        if (indexLoaded) index.remove(id)
        if (indexFile().exists()) appendIndexLine(encodeIndexDelete(id))
    }

    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        index.clear()
        journalLines = 0
        var needsCompact = false
        val persisted = indexFile()
        if (persisted.exists()) {
            persisted.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    journalLines++
                    if (!applyIndexLine(line)) needsCompact = true
                }
            }
            if (journalLines > COMPACT_MIN_LINES && journalLines > index.size * 2) needsCompact = true
        }
        val diskIds = HashSet<String>()
        Files.list(directory.toPath()).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".jsonl") }.forEach { path ->
                val id = path.fileName.toString().removeSuffix(".jsonl")
                diskIds.add(id)
                val target = path.toFile()
                val size = target.length()
                val modified = target.lastModified()
                val cached = index.get(id)
                if (cached != null && !cached.entry.pending && cached.fileSize == size && cached.fileModified == modified) {
                    return@forEach
                }
                val record = indexFromChatFile(target, size, modified)
                index.upsert(record)
                if (record.entry.pending) pendingPersisted.add(record.entry.id)
                needsCompact = true
            }
        }
        for (staleId in index.ids()) {
            if (staleId !in diskIds) {
                index.remove(staleId)
                needsCompact = true
            }
        }
        if (needsCompact || (!persisted.exists() && index.size > 0)) compactIndex()
        indexLoaded = true
    }

    private fun indexFromChatFile(file: File, size: Long, modified: Long): IndexedChatRecord =
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val header = Json.parseToJsonElement(reader.readLine()).jsonObject
            val entry = ChatHistoryEntry(
                header.string("id"),
                header.string("title"),
                header.getValue("updatedAt").jsonPrimitive.long,
                header.getValue("turnCount").jsonPrimitive.int,
                header["pending"]?.jsonPrimitive?.boolean ?: false,
                header["pairedChatId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                header["pairSlot"]?.jsonPrimitive?.intOrNull,
            )
            IndexedChatRecord(entry, size, modified, extractSearchTextFromBody(reader))
        }

    private fun applyIndexLine(line: String): Boolean = try {
        val obj = Json.parseToJsonElement(line).jsonObject
        if (obj["v"]?.jsonPrimitive?.intOrNull != INDEX_VERSION) return false
        when (obj["op"]?.jsonPrimitive?.contentOrNull) {
            "u" -> {
                val entry = ChatHistoryEntry(
                    obj.string("id"),
                    obj.string("title"),
                    obj.getValue("updatedAt").jsonPrimitive.long,
                    obj.getValue("turnCount").jsonPrimitive.int,
                    obj["pending"]?.jsonPrimitive?.boolean ?: false,
                    obj["pairedChatId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                    obj["pairSlot"]?.jsonPrimitive?.intOrNull,
                )
                index.upsert(IndexedChatRecord(
                    entry,
                    obj.getValue("fileSize").jsonPrimitive.long,
                    obj.getValue("fileModified").jsonPrimitive.long,
                    obj.string("text"),
                ))
                true
            }
            "d" -> {
                index.remove(obj.string("id"))
                true
            }
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun appendIndexLine(line: String) {
        FileOutputStream(indexFile(), true).bufferedWriter(Charsets.UTF_8).use { it.appendLine(line) }
        journalLines++
        if (indexLoaded && journalLines > COMPACT_MIN_LINES && journalLines > index.size * 2) {
            compactIndex()
        }
    }

    private fun compactIndex() {
        val temporary = File(directory, "search.index.tmp")
        temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
            index.allRecords().forEach { writer.appendLine(encodeIndexUpsert(it)) }
        }
        Files.move(temporary.toPath(), indexFile().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        journalLines = index.size
    }

    private fun encodeIndexUpsert(record: IndexedChatRecord): String = buildJsonObject {
        put("v", INDEX_VERSION)
        put("op", "u")
        put("id", record.entry.id)
        put("title", record.entry.title)
        put("updatedAt", record.entry.updatedAt)
        put("turnCount", record.entry.turnCount)
        put("pending", record.entry.pending)
        record.entry.pairedChatId?.let { put("pairedChatId", it) }
        record.entry.pairSlot?.let { put("pairSlot", it) }
        put("fileSize", record.fileSize)
        put("fileModified", record.fileModified)
        put("text", record.searchText)
    }.toString()

    private fun encodeIndexDelete(id: String): String = buildJsonObject {
        put("v", INDEX_VERSION)
        put("op", "d")
        put("id", id)
    }.toString()

    private fun file(id: String) = File(directory, "$id.jsonl")
    private fun indexFile() = File(directory, "search.index")
    private suspend fun <T> access(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { Files.createDirectories(directory.toPath()); block() }
    }

    private companion object {
        const val INDEX_VERSION = 1
        const val COMPACT_MIN_LINES = 64
    }
}


private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String) = getValue(key).jsonPrimitive.contentOrNull
