package app.touchai.android

import app.touchai.core.openai.OpenAIImage
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class ChatHistoryArchive(private val history: ChatHistoryRepository) {
    suspend fun exportTo(output: OutputStream): Int = withContext(Dispatchers.IO) {
        output.bufferedWriter().use { writer ->
            val entries = history.list()
            writer.append("{\"format\":\"touchai-chat-history\",\"chats\":[")
            entries.forEachIndexed { index, entry ->
                val chat = history.load(entry.id)
                val body = encodeChatBody(chat.selectedPreset, chat.turns, chat.tree, chat.pairedChatId, chat.pairSlot) { null }
                val value = buildJsonObject {
                    put("id", chat.id)
                    put("updatedAt", chat.updatedAt)
                    body.forEach { (key, element) -> put(key, element) }
                }
                if (index > 0) writer.append(',')
                writer.append(value.toString())
            }
            writer.append("]}")
            entries.size
        }
    }

    suspend fun importFrom(input: InputStream, validateImage: suspend (OpenAIImage) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val archive = input.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
            require(archive.getValue("format").jsonPrimitive.content == "touchai-chat-history") { "Choose a TouchAI history export." }
            // Decode and validate the entire external file before writing any conversations.
            val idMap = HashMap<String, String>()
            val rawChats = archive.getValue("chats").jsonArray.map { item ->
                val value = item.jsonObject
                val sourceId = value["id"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                val newId = UUID.randomUUID().toString()
                if (sourceId != null) idMap[sourceId] = newId
                val decodedBody = decodeChatBody(value) { "" }
                suspend fun sanitizeTurn(decoded: ChatTurn): ChatTurn {
                    val turn = decoded.copy(responseId = null, cancelRequested = false,
                        ai = decoded.ai.copy(api = decoded.ai.api.copy(chatGptAccountId = null)),
                        status = if (decoded.isRunning) TurnStatus.Interrupted else decoded.status)
                    require(apiError(turn.ai.api, requireCredentials = false) == null) { "A chat contains an invalid AI endpoint." }
                    require(turn.ai.api.timeoutMillis > 0) { "A chat contains an invalid request timeout." }
                    (turn.user.images + turn.generatedImages.map { it.image }).forEach { image ->
                        require(listOf("png", "jpeg", "webp").any { image.url.startsWith("data:image/$it;base64,") }) {
                            "A chat contains an unsupported image."
                        }
                        require(image.detail in listOf("auto", "low", "high")) { "A chat contains an invalid image detail." }
                        Base64.getDecoder().decode(image.url.substringAfter(','))
                        validateImage(image)
                    }
                    return turn
                }
                val tree = decodedBody.tree?.let { rawTree ->
                    val sanitizedNodes = rawTree.nodes.map { node -> node.copy(turn = sanitizeTurn(node.turn)) }
                    ChatTree(sanitizedNodes, rawTree.activeLeafId)
                }
                val turns = tree?.activeTurns() ?: decodedBody.turns.map { sanitizeTurn(it) }
                require(turns.isNotEmpty()) { "A chat contains no messages." }
                SavedChat(
                    id = newId,
                    updatedAt = value.getValue("updatedAt").jsonPrimitive.long,
                    selectedPreset = decodedBody.selectedPreset,
                    turns = turns,
                    tree = tree?.takeIf { it.hasBranches },
                    pairedChatId = decodedBody.pairedChatId,
                    pairSlot = decodedBody.pairSlot,
                )
            }
            val chats = rawChats.map { chat ->
                val mappedPartner = chat.pairedChatId?.let { idMap[it] }
                chat.copy(
                    pairedChatId = mappedPartner,
                    pairSlot = if (mappedPartner != null) chat.pairSlot else null,
                )
            }
            chats.forEach { history.save(it) }
            chats.size
        }
}
