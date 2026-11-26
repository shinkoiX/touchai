package app.touchai.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.touchai.core.openai.ChatClient
import app.touchai.core.openai.ChatMessage
import app.touchai.core.openai.MessageRole
import app.touchai.core.openai.OpenAIModelConfig
import app.touchai.core.openai.OpenAIRequest
import app.touchai.core.openai.OpenAIStreamEvent
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DualSendTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `preset dual configuration persists and migrates connections`() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("dual-settings.preferences_pb")
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = ApiKeyCipher { key }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val primaryApi = OpenAIModelConfig(apiKey = "key-a", model = "model-a", reasoningEffort = "high")
            val secondaryApi = OpenAIModelConfig(
                apiKey = "key-b",
                model = "model-b",
                baseUrl = "https://second.example.com/v1",
                reasoningEffort = "low",
            )
            val settings = AppSettings(
                api = primaryApi,
                presets = listOf(
                    PromptPreset(
                        id = "compare",
                        name = "Compare",
                        prompt = "Compare answers",
                        customAi = AiConfiguration(primaryApi),
                        secondaryAi = AiConfiguration(secondaryApi, "Be brief"),
                    ),
                ),
                lastPresetId = "compare",
            )
            val repo = DataStoreSettingsRepository(store, cipher)
            repo.save(settings)
            val loaded = repo.load()

            assertEquals("model-a", loaded.aiFor("compare").api.model)
            val loadedSecondary = loaded.secondaryAiFor("compare")
            assertNotNull(loadedSecondary)
            assertEquals("model-b", loadedSecondary!!.api.model)
            assertEquals("low", loadedSecondary.api.reasoningEffort)
            assertEquals("Be brief", loadedSecondary.instructions)
            assertEquals("key-b", loadedSecondary.api.apiKey)
            assertEquals(2, loaded.connections.size)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
        }
    }

    @Test
    fun `dual send streams two configs concurrently, switches tabs live, and continues from chosen branch`() = runTest(dispatcher) {
        val primaryApi = OpenAIModelConfig(apiKey = "key-a", model = "model-a", reasoningEffort = "high")
        val secondaryApi = OpenAIModelConfig(apiKey = "key-b", model = "model-b", reasoningEffort = "low")
        val settings = AppSettings(
            api = primaryApi,
            presets = listOf(
                PromptPreset(
                    id = "dual",
                    name = "Dual",
                    prompt = "",
                    customAi = AiConfiguration(primaryApi),
                    secondaryAi = AiConfiguration(secondaryApi),
                ),
            ),
            lastPresetId = "dual",
        ).withConnections()
        val repo = InMemorySettingsRepo(settings)
        val history = MemoryChatHistoryRepository()
        val releaseB = CompletableDeferred<Unit>()
        val capturedRequests = mutableListOf<Pair<String, OpenAIRequest>>()
        val client = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent> = flow {
                capturedRequests += config.model to request
                when (config.model) {
                    "model-a" -> {
                        emit(OpenAIStreamEvent.TextDelta("Answer from A"))
                        emit(OpenAIStreamEvent.Completed(buildJsonObject {}))
                    }
                    "model-b" -> {
                        emit(OpenAIStreamEvent.TextDelta("Part 1 from B"))
                        releaseB.await()
                        emit(OpenAIStreamEvent.TextDelta(" + Part 2"))
                        emit(OpenAIStreamEvent.Completed(buildJsonObject {}))
                    }
                }
            }
        }

        val viewModel = testChatViewModel(history, repo, client)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isDualMode)
        viewModel.setPrompt("Explain quantum tunneling")
        viewModel.submit()
        advanceUntilIdle()

        // Model A is done while Model B is still streaming.
        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(1, viewModel.uiState.value.runningCount)
        val branchWhileStreaming = viewModel.uiState.value.branchInfo(0)
        assertNotNull(branchWhileStreaming)
        assertEquals(2, branchWhileStreaming!!.compareTabs.size)
        assertEquals("model-a", branchWhileStreaming.compareTabs[0].turn.ai.api.model)
        assertEquals(TurnStatus.Completed, branchWhileStreaming.compareTabs[0].turn.status)
        assertEquals("model-b", branchWhileStreaming.compareTabs[1].turn.ai.api.model)
        assertEquals(TurnStatus.Streaming, branchWhileStreaming.compareTabs[1].turn.status)

        // Switch to Tab B live while streaming!
        val tabBId = branchWhileStreaming.compareTabs[1].nodeId
        viewModel.selectBranchNode(tabBId)
        advanceUntilIdle()
        assertEquals("model-b", viewModel.uiState.value.turns.first().ai.api.model)
        assertEquals("Part 1 from B", viewModel.uiState.value.turns.first().answer)

        // Finish Model B stream.
        releaseB.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals("Part 1 from B + Part 2", viewModel.uiState.value.turns.first().answer)
        // Dual mode stays enabled until manually disabled!
        assertTrue(viewModel.uiState.value.isDualMode)

        // Turn off dual mode manually and send a follow-up from Tab B.
        viewModel.setDualMode(false)
        assertFalse(viewModel.uiState.value.isDualMode)
        viewModel.setPrompt("Follow up")
        viewModel.submit()
        advanceUntilIdle()

        val followUpRequest = capturedRequests.last().second
        val assistantContext = followUpRequest.messages.firstOrNull { it.role == MessageRole.Assistant }
        assertEquals("Part 1 from B + Part 2", assistantContext?.text)
    }

    @Test
    fun `long press toggles dual mode and prompts for slot B only when unconfigured`() = runTest(dispatcher) {
        val primaryApi = OpenAIModelConfig(apiKey = "key-a", model = "model-a")
        val secondaryApi = OpenAIModelConfig(apiKey = "key-a", model = "model-b")
        val settings = AppSettings(api = primaryApi, presets = emptyList(), lastPresetId = null).withConnections()
        val viewModel = testChatViewModel(
            MemoryChatHistoryRepository(),
            InMemorySettingsRepo(settings),
            object : ChatClient {
                override fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent> = flow {
                    emit(OpenAIStreamEvent.Completed(buildJsonObject {}))
                }
            },
        )
        advanceUntilIdle()

        // First long-press: no distinct 2nd AI yet -> enables dual mode and asks UI to open slot B.
        assertTrue(viewModel.toggleDualModeByLongPress())
        assertTrue(viewModel.uiState.value.isDualMode)

        // Pick a distinct 2nd AI.
        viewModel.setTemporarySecondaryAi(AiConfiguration(secondaryApi, connectionId = settings.connectionId))
        assertEquals("model-b", viewModel.uiState.value.selectedSecondaryAi?.api?.model)

        // Long-press again -> turns dual mode OFF without opening sheet.
        assertFalse(viewModel.toggleDualModeByLongPress())
        assertFalse(viewModel.uiState.value.isDualMode)

        // Long-press again -> turns dual mode back ON immediately with remembered model-b.
        assertFalse(viewModel.toggleDualModeByLongPress())
        assertTrue(viewModel.uiState.value.isDualMode)
        assertEquals("model-b", viewModel.uiState.value.selectedSecondaryAi?.api?.model)
    }

    @Test
    fun `compareGroupId survives chat history encoding and decoding`() {
        val user = ChatMessage(MessageRole.User, "Hello")
        val groupId = "group-123"
        val turnA = ChatTurn(user, AiConfiguration(OpenAIModelConfig(apiKey = "k", model = "a")), answer = "A", status = TurnStatus.Completed, compareGroupId = groupId)
        val turnB = ChatTurn(user, AiConfiguration(OpenAIModelConfig(apiKey = "k", model = "b")), answer = "B", status = TurnStatus.Completed, compareGroupId = groupId)
        val (tree, _) = ChatTree().appendDualTurns(turnA, turnB, now = 100L)

        val encoded = encodeChatBody("dual", tree.activeTurns(), tree) { it.ai.api.apiKey }
        val decoded = decodeChatBody(encoded) { turnObj ->
            turnObj.getValue("ai").jsonPrimitiveOrNull("encryptedApiKey") ?: "k"
        }
        assertNotNull(decoded.tree)
        assertEquals(2, decoded.tree!!.nodes.size)
        assertTrue(decoded.tree!!.nodes.all { it.turn.compareGroupId == groupId })
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(key: String): String? =
        (this as? kotlinx.serialization.json.JsonObject)?.get(key)?.jsonPrimitive?.content

    private class InMemorySettingsRepo(private var current: AppSettings) : SettingsRepository {
        override suspend fun load(): AppSettings = current
        override suspend fun save(settings: AppSettings) { current = settings.withConnections() }
        override suspend fun rememberPreset(id: String?) { current = current.copy(lastPresetId = id) }
    }
}
