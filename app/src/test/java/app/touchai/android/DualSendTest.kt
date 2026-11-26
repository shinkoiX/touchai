package app.touchai.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.touchai.core.openai.ChatClient
import app.touchai.core.openai.ChatMessage
import app.touchai.core.openai.MessageRole
import app.touchai.core.openai.OpenAIModelConfig
import app.touchai.core.openai.OpenAIRequest
import app.touchai.core.openai.OpenAIStreamEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `dual send creates two linked independent chats that continue along their own histories even after model change`() = runTest(dispatcher) {
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
        val releaseB1 = CompletableDeferred<Unit>()
        val capturedRequests = mutableListOf<Pair<String, OpenAIRequest>>()
        val client = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent> = flow {
                capturedRequests += config.model to request
                val turnNumber = request.messages.count { it.role == MessageRole.User }
                when (config.model) {
                    "model-a" -> {
                        emit(OpenAIStreamEvent.TextDelta("A-$turnNumber"))
                        emit(OpenAIStreamEvent.Completed(buildJsonObject {}))
                    }
                    "model-a-v2" -> {
                        emit(OpenAIStreamEvent.TextDelta("A2-$turnNumber"))
                        emit(OpenAIStreamEvent.Completed(buildJsonObject {}))
                    }
                    "model-b" -> {
                        if (turnNumber == 1) {
                            emit(OpenAIStreamEvent.TextDelta("B-1-part1"))
                            releaseB1.await()
                            emit(OpenAIStreamEvent.TextDelta("+part2"))
                        } else {
                            emit(OpenAIStreamEvent.TextDelta("B-$turnNumber"))
                        }
                        emit(OpenAIStreamEvent.Completed(buildJsonObject {}))
                    }
                }
            }
        }

        val viewModel = testChatViewModel(history, repo, client)
        advanceUntilIdle()

        // Turn 1: dual send starts two independent chats concurrently.
        viewModel.setPrompt("Q1")
        viewModel.submit()
        advanceUntilIdle()

        val chatAId = viewModel.uiState.value.primaryPane.chatId
        val chatBId = viewModel.uiState.value.secondaryPane!!.chatId
        assertNotEquals(chatAId, chatBId)
        assertTrue(viewModel.uiState.value.isStreaming)
        assertEquals(1, viewModel.uiState.value.runningCount)
        assertEquals("A-1", viewModel.uiState.value.primaryPane.turns.single().answer)
        assertEquals(TurnStatus.Completed, viewModel.uiState.value.primaryPane.turns.single().status)
        assertEquals(TurnStatus.Streaming, viewModel.uiState.value.secondaryPane!!.turns.single().status)

        // Switch to Tab B live while streaming!
        viewModel.selectPairSlot(1)
        assertEquals(chatBId, viewModel.uiState.value.chatId)
        assertEquals("B-1-part1", viewModel.uiState.value.turns.single().answer)

        // Finish B's Turn 1.
        releaseB1.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isStreaming)
        assertEquals("B-1-part1+part2", viewModel.uiState.value.turns.single().answer)

        // Verify both independent chats are persisted and mutually linked.
        val savedA = history.load(chatAId)
        val savedB = history.load(chatBId)
        assertEquals(chatBId, savedA.pairedChatId)
        assertEquals(0, savedA.pairSlot)
        assertEquals(chatAId, savedB.pairedChatId)
        assertEquals(1, savedB.pairSlot)

        // Turn 2: modify model on side A ONLY, then send Q2 in dual mode.
        val primaryConnId = viewModel.uiState.value.selectedAi.connectionId
        viewModel.setTemporaryAi(AiConfiguration(primaryApi.copy(model = "model-a-v2"), connectionId = primaryConnId))
        viewModel.setPrompt("Q2")
        viewModel.submit()
        advanceUntilIdle()

        // Same chat IDs are preserved; side A followed A's history with model-a-v2, side B followed B's history with model-b.
        assertEquals(chatAId, viewModel.uiState.value.primaryPane.chatId)
        assertEquals(chatBId, viewModel.uiState.value.secondaryPane!!.chatId)
        assertEquals(2, viewModel.uiState.value.primaryPane.turns.size)
        assertEquals(2, viewModel.uiState.value.secondaryPane!!.turns.size)
        assertEquals("model-a-v2", viewModel.uiState.value.primaryPane.turns.last().ai.api.model)
        assertEquals("model-b", viewModel.uiState.value.secondaryPane!!.turns.last().ai.api.model)

        val turn2ReqA = capturedRequests.first { it.first == "model-a-v2" }.second
        val turn2ReqB = capturedRequests.last { it.first == "model-b" }.second
        assertEquals(listOf("Q1", "A-1", "Q2"), turn2ReqA.messages.map { it.text })
        assertEquals(listOf("Q1", "B-1-part1+part2", "Q2"), turn2ReqB.messages.map { it.text })

        // History list collapses the pair into 1 entry, while search returns BOTH matching conversations.
        viewModel.showHistory(true)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.historyEntries.size)
        assertNotNull(viewModel.uiState.value.historyEntries.single().pairedChatId)

        viewModel.setHistoryQuery("Q2")
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.historyEntries.size)
        assertEquals(setOf(chatAId, chatBId), viewModel.uiState.value.historyEntries.map { it.id }.toSet())
        assertEquals(setOf(0, 1), viewModel.uiState.value.historyEntries.map { it.pairSlot }.toSet())

        // Opening the B search result opens the paired chat directly on slot B!
        viewModel.openChat(chatBId)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isPaired)
        assertEquals(1, viewModel.uiState.value.activePairSlot)
        assertEquals(chatBId, viewModel.uiState.value.chatId)
        assertEquals(2, viewModel.uiState.value.primaryPane.turns.size)
        assertEquals(2, viewModel.uiState.value.secondaryPane!!.turns.size)
        assertEquals("model-a-v2", viewModel.uiState.value.selectedAi.api.model)
        assertEquals("model-b", viewModel.uiState.value.resolvedSecondaryAi.api.model)

        viewModel.showHistory(true)
        advanceUntilIdle()
        val openedId = viewModel.uiState.value.historyEntries.single().id
        viewModel.deleteChat(openedId)
        advanceUntilIdle()
        assertTrue(history.chats.isEmpty())
    }

    @Test
    fun `enabling dual mode mid-conversation forks prior history into linked chat B`() = runTest(dispatcher) {
        val primaryApi = OpenAIModelConfig(apiKey = "key-a", model = "model-a")
        val secondaryApi = OpenAIModelConfig(apiKey = "key-a", model = "model-b")
        val settings = AppSettings(api = primaryApi, presets = emptyList(), lastPresetId = null).withConnections()
        val history = MemoryChatHistoryRepository()
        val captured = mutableListOf<Pair<String, OpenAIRequest>>()
        val client = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent> = flow {
                captured += config.model to request
                emit(OpenAIStreamEvent.TextDelta("${config.model}-reply"))
                emit(OpenAIStreamEvent.Completed(buildJsonObject {}))
            }
        }

        val viewModel = testChatViewModel(history, InMemorySettingsRepo(settings), client)
        advanceUntilIdle()

        // Single-send Turn 1
        viewModel.setPrompt("Intro")
        viewModel.submit()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isPaired)

        // Enable dual mode and configure B, then send Turn 2
        viewModel.setDualMode(true)
        viewModel.setTemporarySecondaryAi(AiConfiguration(secondaryApi, connectionId = settings.connectionId))
        viewModel.setPrompt("Compare now")
        viewModel.submit()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isPaired)
        assertEquals(2, viewModel.uiState.value.primaryPane.turns.size)
        assertEquals(2, viewModel.uiState.value.secondaryPane!!.turns.size)
        val reqB = captured.last { it.first == "model-b" }.second
        assertEquals(listOf("Intro", "model-a-reply", "Compare now"), reqB.messages.map { it.text })
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

        assertTrue(viewModel.toggleDualModeByLongPress())
        assertTrue(viewModel.uiState.value.isDualMode)

        viewModel.setTemporarySecondaryAi(AiConfiguration(secondaryApi, connectionId = settings.connectionId))
        assertEquals("model-b", viewModel.uiState.value.resolvedSecondaryAi.api.model)

        assertFalse(viewModel.toggleDualModeByLongPress())
        assertFalse(viewModel.uiState.value.isDualMode)

        assertFalse(viewModel.toggleDualModeByLongPress())
        assertTrue(viewModel.uiState.value.isDualMode)
        assertEquals("model-b", viewModel.uiState.value.resolvedSecondaryAi.api.model)
    }

    @Test
    fun `archive export and import remaps paired chat IDs`() = runBlocking {
        val source = MemoryChatHistoryRepository()
        val user = ChatMessage(MessageRole.User, "Hello")
        val turnA = ChatTurn(user, AiConfiguration(OpenAIModelConfig(apiKey = "k", model = "a")), answer = "A", status = TurnStatus.Completed)
        val turnB = ChatTurn(user, AiConfiguration(OpenAIModelConfig(apiKey = "k", model = "b")), answer = "B", status = TurnStatus.Completed)
        source.save(SavedChat("old-a", 10L, null, listOf(turnA), pairedChatId = "old-b", pairSlot = 0))
        source.save(SavedChat("old-b", 11L, null, listOf(turnB), pairedChatId = "old-a", pairSlot = 1))

        val out = ByteArrayOutputStream()
        assertEquals(2, ChatHistoryArchive(source).exportTo(out))

        val target = MemoryChatHistoryRepository()
        assertEquals(2, ChatHistoryArchive(target).importFrom(ByteArrayInputStream(out.toByteArray())) {})

        val imported = target.chats.values.toList()
        val importedA = imported.first { it.pairSlot == 0 }
        val importedB = imported.first { it.pairSlot == 1 }
        assertNotEquals("old-a", importedA.id)
        assertNotEquals("old-b", importedB.id)
        assertEquals(importedB.id, importedA.pairedChatId)
        assertEquals(importedA.id, importedB.pairedChatId)
    }

    private class InMemorySettingsRepo(private var current: AppSettings) : SettingsRepository {
        override suspend fun load(): AppSettings = current
        override suspend fun save(settings: AppSettings) { current = settings.withConnections() }
        override suspend fun rememberPreset(id: String?) { current = current.copy(lastPresetId = id) }
    }
}
