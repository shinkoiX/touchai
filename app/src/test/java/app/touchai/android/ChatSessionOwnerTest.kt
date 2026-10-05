package app.touchai.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import app.touchai.core.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    private class TrackedModel : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    @Test fun handoffTransfersOwnershipAndReleasesThePreviousStore() {
        val capture = ChatSessionOwner()
        val captureActivity = ViewModelStore().apply { put("session", capture) }
        val chat = TrackedModel()
        capture.viewModelStore.put("chat", chat)
        val transfer = ChatSessionTransfer()
        transfer.offer(capture.detach())
        captureActivity.clear()
        assertFalse(chat.cleared)
        val main = ChatSessionOwner()
        val mainActivity = ViewModelStore().apply { put("session", main) }
        val previous = TrackedModel()
        main.viewModelStore.put("chat", previous)
        main.adopt(transfer.take()!!)
        assertTrue(previous.cleared)
        assertSame(chat, main.viewModelStore["chat"])
        assertNull(transfer.take())
        mainActivity.clear()
        assertTrue(chat.cleared)
    }

    @Test fun closingTheUiDoesNotStopTheRequestButTheStopButtonDoes() = runTest {
        val history = MemoryChatHistoryRepository()
        var connectionClosed = false
        val client = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
                try { emit(OpenAIStreamEvent.TextDelta("Still streaming")); awaitCancellation() }
                finally { connectionClosed = true }
            }
        }
        val requests = ChatRequestRunner(history, client, { _, _ -> buildJsonObject { put("status", "cancelled") } },
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), {}, {})
        val model = OpenAIChatViewModel(history, repository, client, requests)
        val activity = ViewModelStore().apply { put("chat", model) }
        runCurrent(); model.setPrompt("Keep answering"); model.submit()
        advanceTimeBy(100); runCurrent()
        val id = model.uiState.value.chatId
        activity.clear(); runCurrent()
        assertFalse(connectionClosed)
        assertNotNull(requests.find(id))
        assertEquals("Still streaming", requests.find(id)!!.state.value.turns.last().answer)
        requests.stop(id); advanceUntilIdle()
        assertTrue(connectionClosed)
        assertEquals(TurnStatus.Stopped, history.chats.getValue(id).turns.last().status)
        assertTrue(requests.active.value.isEmpty())
    }

    @Test fun clearingACollapsedStoreDoesNotKeepItsUiAlive() {
        val collapsed = ChatSessionTransfer()
        val store = ViewModelStore()
        val model = TrackedModel()
        store.put("chat", model)
        collapsed.offer(store)
        collapsed.clear()
        assertTrue(model.cleared)
        assertNull(collapsed.take())
    }
}
