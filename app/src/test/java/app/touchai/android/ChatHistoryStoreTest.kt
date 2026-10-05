package app.touchai.android

import app.touchai.core.openai.*
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatHistoryStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = ApiKeyCipher { key }
    private val turn = ChatTurn(
        ChatMessage(MessageRole.User, "Explain this image", listOf(OpenAIImage("data:image/png;base64," + "A".repeat(3_000_000)))),
        AiConfiguration(OpenAIModelConfig(apiKey = "history-secret", model = "test-model", protocol = ApiProtocol.Responses,
            reasoningEffort = "high", webSearch = false, timeoutMillis = 90_000), "Be concise"),
        presetName = "Explain", answer = "The answer", status = TurnStatus.Completed,
        citations = listOf(WebCitation("https://example.com/source", "Source", 0, 4)),
        generatedImages = listOf(GeneratedImage("generated", OpenAIImage("data:image/png;base64," + "B".repeat(3_000_000)))),
    )

    @Test fun recreationPreservesLargeAttachmentsAndConfigurationWithoutPlaintextKeys() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val chat = SavedChat("chat-one", 10, "explain", listOf(turn))
        ChatHistoryStore(directory, cipher).save(chat)
        val reopened = ChatHistoryStore(directory, cipher)
        assertEquals(chat, reopened.load(chat.id))
        assertEquals(listOf(chat.entry), reopened.list())
        assertFalse(directory.resolve("chat-one.jsonl").readText().contains("history-secret"))
    }

    @Test fun updatesKeepOneEntryAndDeletionRemovesMessagesAndImages() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val store = ChatHistoryStore(directory, cipher)
        val first = SavedChat("first", 10, null, listOf(turn))
        val second = first.copy(id = "second", updatedAt = 20)
        store.save(first); store.save(second)
        assertEquals(listOf("second", "first"), store.list().map { it.id })
        store.save(first.copy(updatedAt = 30, turns = listOf(turn, turn.copy(answer = "Follow-up"))))
        assertEquals(listOf("first", "second"), store.list().map { it.id })
        assertEquals(2, store.load("first").turns.size)
        store.delete("first")
        assertFalse(directory.resolve("first.jsonl").exists())
        assertEquals(listOf("second"), store.list().map { it.id })
    }

    @Test fun pendingRequestsRetainRecoveryStateAcrossReopening() = runBlocking {
        val store = ChatHistoryStore(temporaryFolder.newFolder(), cipher)
        store.save(SavedChat("interrupted", 10, null, listOf(turn.copy(status = TurnStatus.Streaming, answer = "Partial", responseId = "resp_test"))))
        assertTrue(store.list().single().pending)
        assertEquals(TurnStatus.Streaming, store.load("interrupted").turns.single().status)
        assertEquals("resp_test", store.load("interrupted").turns.single().responseId)
        assertEquals("Partial", store.load("interrupted").turns.single().answer)
    }
}
