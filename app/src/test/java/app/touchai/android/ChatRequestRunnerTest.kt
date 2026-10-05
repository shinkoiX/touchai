package app.touchai.android

import app.touchai.core.openai.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatRequestRunnerTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val history = MemoryChatHistoryRepository()
    private val api = OpenAIModelConfig(apiKey = "test-only", model = "test", protocol = ApiProtocol.Responses, backgroundResponses = true)
    private fun chat(id: String = "chat") = SavedChat(id, 1, null,
        listOf(ChatTurn(ChatMessage(MessageRole.User, "Question"), AiConfiguration(api))))
    private fun runner(client: ChatClient, cancel: suspend (OpenAIModelConfig, String) -> JsonObject = { _, _ ->
        buildJsonObject { put("status", "cancelled"); putJsonArray("output") {} }
    }) = ChatRequestRunner(history, client, cancel, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), {}, {})

    @Test fun interruptionPersistsIdAndNewRunnerResumesWithoutCreatingAnotherGeneration() = runTest {
        var cancels = 0
        val first = runner(object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
                emit(OpenAIStreamEvent.ResponseCheckpoint("resp_saved"))
                emit(OpenAIStreamEvent.TextDelta("Partial"))
                awaitCancellation()
            }
        }, { _, _ -> cancels++; JsonObject(emptyMap()) })
        first.start(chat()); advanceTimeBy(100); runCurrent()
        assertEquals("resp_saved", history.chats.getValue("chat").turns.last().responseId)
        first.pauseForSystem(); advanceUntilIdle()
        assertEquals(0, cancels)
        assertEquals(TurnStatus.Recovering, history.chats.getValue("chat").turns.last().status)
        val requests = mutableListOf<OpenAIRequest>()
        val second = runner(object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
                requests += request
                emit(OpenAIStreamEvent.TextSnapshot("Whole answer"))
                emit(OpenAIStreamEvent.Completed(JsonObject(emptyMap())))
            }
        })
        second.restorePending(); advanceUntilIdle()
        assertEquals("resp_saved", requests.single().resumeResponseId)
        assertEquals(RequestPurpose.Resume, requests.single().purpose)
        assertEquals("Whole answer", history.chats.getValue("chat").turns.last().answer)
        assertEquals(TurnStatus.Completed, history.chats.getValue("chat").turns.last().status)
    }

    @Test fun stopWaitsForTheResponseIdAndCancelsRemotelyBeforeClosingTheStream() = runTest {
        val releaseId = CompletableDeferred<Unit>()
        var connectionClosed = false
        val cancelledIds = mutableListOf<String>()
        val runner = runner(object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
                try { releaseId.await(); emit(OpenAIStreamEvent.ResponseCheckpoint("resp_saved")); awaitCancellation() }
                finally { connectionClosed = true }
            }
        }, { _, id ->
            assertFalse(connectionClosed)
            cancelledIds += id
            buildJsonObject { put("status", "cancelled"); putJsonArray("output") {} }
        })
        val handle = runner.start(chat()); runCurrent()
        runner.stop("chat"); runCurrent()
        assertTrue(cancelledIds.isEmpty())
        assertFalse(connectionClosed)
        assertTrue(history.chats.getValue("chat").turns.last().cancelRequested)
        releaseId.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("resp_saved"), cancelledIds)
        assertTrue(connectionClosed)
        assertEquals(TurnStatus.Stopped, handle.state.value.turns.last().status)
    }

    @Test fun offlineCancellationIsRetriedAndSavedUntilAcknowledged() = runTest {
        var cancels = 0
        val runner = runner(object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
                emit(OpenAIStreamEvent.ResponseCheckpoint("resp_saved")); awaitCancellation()
            }
        }, { _, _ ->
            if (++cancels == 1) throw IOException("Offline")
            buildJsonObject { put("status", "cancelled"); putJsonArray("output") {} }
        })
        val handle = runner.start(chat()); runCurrent()
        runner.stop("chat"); runCurrent()
        assertEquals(TurnStatus.Cancelling, handle.state.value.turns.last().status)
        assertTrue(history.chats.getValue("chat").turns.last().cancelRequested)
        advanceUntilIdle()
        assertEquals(2, cancels)
        assertEquals(TurnStatus.Stopped, history.chats.getValue("chat").turns.last().status)
    }

    @Test fun aSavedStopRequestStillCancelsAfterRestart() = runTest {
        val pending = chat().let { it.copy(turns = listOf(it.turns.single().copy(responseId = "resp_saved", cancelRequested = true, status = TurnStatus.Cancelling))) }
        history.save(pending)
        var cancelled = false
        val runner = runner(object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow<OpenAIStreamEvent> {
                assertEquals("resp_saved", request.resumeResponseId)
                awaitCancellation()
            }
        }, { _, id ->
            assertEquals("resp_saved", id); cancelled = true
            buildJsonObject { put("status", "cancelled"); putJsonArray("output") {} }
        })
        runner.restorePending(); advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(TurnStatus.Stopped, history.chats.getValue("chat").turns.last().status)
    }

    @Test fun pendingRequestsWithoutAnIdAreNotAutomaticallyResubmitted() = runTest {
        history.save(chat())
        var calls = 0
        val runner = runner(object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow<OpenAIStreamEvent> { calls++ }
        })
        runner.restorePending(); advanceUntilIdle()
        assertEquals(0, calls)
        assertEquals(TurnStatus.Interrupted, history.chats.getValue("chat").turns.last().status)
    }
}
