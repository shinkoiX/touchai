package app.touchai.android

import app.touchai.core.openai.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatHistoryArchiveTest {
    private val history = MemoryChatHistoryRepository()
    private val image = OpenAIImage("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGDkAAAAASUVORK5CYII=")
    private val turn = ChatTurn(ChatMessage(MessageRole.User, "Image question", listOf(image)),
        AiConfiguration(OpenAIModelConfig(apiKey = "never-export-this-secret", model = "test-model"), "Be concise"),
        answer = "Saved answer", status = TurnStatus.Completed, citations = listOf(WebCitation("https://example.com", "Source")))
    private val chat = SavedChat("existing-chat", 1234, "explain", listOf(turn))

    private suspend fun archive(): String {
        history.save(chat)
        val output = ByteArrayOutputStream()
        assertEquals(1, ChatHistoryArchive(history).exportTo(output))
        return output.toString(Charsets.UTF_8)
    }

    @Test fun exportAndImportPreserveContentWithoutCredentialsOrOverwritingExistingChats() = runTest {
        val json = archive()
        assertFalse(json.contains("never-export-this-secret"))
        assertFalse(json.contains("encryptedApiKey"))
        var validatedImages = 0
        val count = ChatHistoryArchive(history).importFrom(json.byteInputStream()) {
            assertEquals(image, it)
            assertTrue(Base64.getDecoder().decode(it.url.substringAfter(',')).isNotEmpty())
            validatedImages++
        }
        assertEquals(1, count)
        assertEquals(1, validatedImages)
        assertEquals(2, history.chats.size)
        assertEquals(chat, history.chats.getValue(chat.id))
        val imported = history.chats.values.single { it.id != chat.id }
        assertEquals(imported.id, UUID.fromString(imported.id).toString())
        assertEquals(chat.copy(id = imported.id, turns = listOf(turn.copy(ai = turn.ai.copy(api = turn.ai.api.copy(apiKey = ""))))), imported)
    }

    @Test fun malformedLaterChatDoesNotPartiallyImportEarlierChats() = runTest {
        val original = Json.parseToJsonElement(archive()).jsonObject
        val invalid = JsonObject(original + ("chats" to JsonArray(original.getValue("chats").jsonArray + buildJsonObject {
            put("id", "../../outside"); put("updatedAt", 1); put("selectedPreset", JsonNull); putJsonArray("turns") { }
        })))
        try {
            ChatHistoryArchive(history).importFrom(invalid.toString().byteInputStream()) { }
            fail("An empty conversation should be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(chat), history.chats.values.toList())
    }

    @Test fun invalidImagesAreRejectedBeforeAnyChatIsSaved() = runTest {
        val invalid = archive().replace(image.url, "data:image/png;base64,not base64")
        try {
            ChatHistoryArchive(history).importFrom(invalid.byteInputStream()) { fail("Invalid base64 reached the image decoder") }
            fail("Invalid image should be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, history.chats.size)
    }

    @Test fun archiveIdsAndCredentialFieldsAreNeverTrusted() = runTest {
        val json = archive().replace("existing-chat", "../../outside")
            .replace("\"model\":", "\"encryptedApiKey\":\"untrusted-secret\",\"apiKey\":\"untrusted-secret\",\"model\":")
        val destination = MemoryChatHistoryRepository()
        ChatHistoryArchive(destination).importFrom(json.byteInputStream()) { }
        val imported = destination.chats.values.single()
        assertEquals(imported.id, UUID.fromString(imported.id).toString())
        assertEquals("", imported.turns.single().ai.api.apiKey)
    }
}
