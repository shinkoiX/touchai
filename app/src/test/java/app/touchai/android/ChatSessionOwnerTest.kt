package app.touchai.android

import androidx.lifecycle.ViewModelStore
import app.touchai.core.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionOwnerTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val settings = AppSettings(api = OpenAIModelConfig(apiKey = "test-only", model = "test-model"))
    private val repository = object : SettingsRepository {
        override suspend fun load() = settings
        override suspend fun save(settings: AppSettings) = Unit
        override suspend fun rememberPreset(id: String?) = Unit
    }
    private val client = object : ChatClient {
        override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
            emit(OpenAIStreamEvent.TextDelta("Still streaming"))
            awaitCancellation()
        }
    }

    @Test fun discardingACollapsedChatReleasesItAndRestoringTransfersOwnershipOnce() = runTest {
        val collapsed = ChatSessionTransfer()
        val first = ViewModelStore()
        var firstClosed = 0
        first.put("chat", OpenAIChatViewModel(MemoryChatHistoryRepository(), repository, client, closeClient = { firstClosed++ }))
        collapsed.offer(first)
        collapsed.clear()
        assertEquals(1, firstClosed)
        assertNull(collapsed.take())
        val next = ViewModelStore()
        var nextClosed = 0
        next.put("chat", OpenAIChatViewModel(MemoryChatHistoryRepository(), repository, client, closeClient = { nextClosed++ }))
        collapsed.offer(next)
        val restored = collapsed.take()!!
        collapsed.clear()
        assertEquals(0, nextClosed)
        assertSame(next, restored)
        assertNull(collapsed.take())
        restored.clear()
        assertEquals(1, nextClosed)
        advanceUntilIdle()
    }

    @Test fun handoffKeepsDraftAndAttachmentWhenCaptureActivityIsDestroyed() = runTest {
        val capture = ChatSessionOwner()
        val captureActivity = ViewModelStore().apply { put("session", capture) }
        var closed = 0
        val chat = OpenAIChatViewModel(MemoryChatHistoryRepository(), repository, client, closeClient = { closed++ })
        capture.viewModelStore.put("chat", chat)
        runCurrent()
        chat.setPrompt("Unsent draft")
        chat.setImage(OpenAIImage("data:image/png;base64,test"))
        val before = chat.uiState.value
        val transfer = ChatSessionTransfer()
        transfer.offer(capture.detach())
        captureActivity.clear()
        assertEquals(0, closed)
        val main = ChatSessionOwner()
        val mainActivity = ViewModelStore().apply { put("session", main) }
        main.adopt(transfer.take()!!)
        assertSame(chat, main.viewModelStore["chat"])
        assertEquals(before, chat.uiState.value)
        assertNull(transfer.take())
        mainActivity.clear()
        assertEquals(1, closed)
    }

    @Test fun handoffPreservesAnActiveResponseAndReleasesThePreviousMainChat() = runTest {
        val capture = ChatSessionOwner()
        val captureActivity = ViewModelStore().apply { put("session", capture) }
        var capturedClosed = 0
        val chat = OpenAIChatViewModel(MemoryChatHistoryRepository(), repository, client, closeClient = { capturedClosed++ })
        capture.viewModelStore.put("chat", chat)
        runCurrent()
        chat.setPrompt("Keep answering"); chat.submit()
        advanceTimeBy(100); runCurrent()
        assertTrue(chat.uiState.value.isStreaming)
        val main = ChatSessionOwner()
        val mainActivity = ViewModelStore().apply { put("session", main) }
        var previousClosed = 0
        main.viewModelStore.put("chat", OpenAIChatViewModel(MemoryChatHistoryRepository(), repository, client,
            closeClient = { previousClosed++ }))
        val transfer = ChatSessionTransfer()
        transfer.offer(capture.detach())
        captureActivity.clear()
        main.adopt(transfer.take()!!)
        runCurrent()
        assertEquals(1, previousClosed)
        assertEquals(0, capturedClosed)
        assertTrue(chat.uiState.value.isStreaming)
        assertEquals("Still streaming", chat.uiState.value.turns.single().answer)
        mainActivity.clear(); advanceUntilIdle()
        assertEquals(1, capturedClosed)
        assertEquals(TurnStatus.Stopped, chat.uiState.value.turns.single().status)
    }
}
