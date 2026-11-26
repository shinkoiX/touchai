package app.touchai.android

import app.touchai.core.openai.ChatClient
import app.touchai.core.openai.ChatMessage
import app.touchai.core.openai.MessageRole
import app.touchai.core.openai.OpenAIModelConfig
import app.touchai.core.openai.OpenAIRequest
import app.touchai.core.openai.OpenAIStreamEvent
import app.touchai.core.openai.WebCitation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSearchLocatorTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `findChatSearchMatches locates hits in reading order across user, reasoning, answer, and citations`() {
        val ai = AiConfiguration(OpenAIModelConfig(apiKey = "k", model = "gpt-5.4"))
        val turns = listOf(
            ChatTurn(
                user = ChatMessage(MessageRole.User, "What is quantum tunneling?"),
                ai = ai,
                reasoning = "  Consider quantum barriers carefully.  ",
                answer = "Quantum tunneling lets particles cross a barrier.\n\nIn quantum physics this is common.",
                status = TurnStatus.Completed,
                citations = listOf(WebCitation("https://example.com/q", "Quantum Mechanics Primer", null, null)),
            ),
            ChatTurn(
                user = ChatMessage(MessageRole.User, "Give another example"),
                ai = ai,
                answer = "Scanning tunneling microscopes rely on quantum effects.",
                status = TurnStatus.Completed,
            ),
        )

        val matches = findChatSearchMatches(turns, "quantum")
        assertEquals(6, matches.size)
        assertEquals(
            listOf(
                0 to SearchMatchSection.User,
                0 to SearchMatchSection.Reasoning,
                0 to SearchMatchSection.Answer,
                0 to SearchMatchSection.Answer,
                0 to SearchMatchSection.Citation,
                1 to SearchMatchSection.Answer,
            ),
            matches.map { it.turnIndex to it.section },
        )
        assertEquals("T1 · Thoughts", matches[1].locationLabel())
        assertEquals("T2 · Reply", matches[5].locationLabel())
        // Reasoning offsets align with trimmed reasoning text.
        val trimmedReasoning = turns[0].reasoning.trim()
        assertEquals("quantum", trimmedReasoning.substring(matches[1].start, matches[1].end).lowercase())
    }

    @Test
    fun `opening chat from history search preserves highlight query and supports stepping and clearing`() = runTest(dispatcher) {
        val api = OpenAIModelConfig(apiKey = "key", model = "model-a")
        val settings = AppSettings(api = api, presets = emptyList(), lastPresetId = null).withConnections()
        val history = MemoryChatHistoryRepository()
        history.save(
            SavedChat(
                id = "chat-1",
                updatedAt = 100L,
                selectedPreset = null,
                turns = listOf(
                    ChatTurn(
                        user = ChatMessage(MessageRole.User, "Tell me about Rust ownership"),
                        ai = AiConfiguration(api),
                        answer = "Rust uses ownership and borrowing. Rust prevents data races.",
                        status = TurnStatus.Completed,
                    ),
                ),
            ),
        )
        val viewModel = testChatViewModel(
            history,
            object : SettingsRepository {
                override suspend fun load(): AppSettings = settings
                override suspend fun save(settings: AppSettings) {}
                override suspend fun rememberPreset(id: String?) {}
            },
            object : ChatClient {
                override fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent> = flow {}
            },
        )
        advanceUntilIdle()

        viewModel.showHistory(true)
        advanceUntilIdle()
        viewModel.setHistoryQuery("Rust")
        advanceUntilIdle()
        viewModel.openChat("chat-1")
        advanceUntilIdle()

        assertEquals("Rust", viewModel.uiState.value.searchHighlightQuery)
        assertEquals(0, viewModel.uiState.value.searchMatchIndex)

        val matches = findChatSearchMatches(viewModel.uiState.value.turns, viewModel.uiState.value.searchHighlightQuery)
        assertEquals(3, matches.size)

        viewModel.stepSearchMatch(1, matches.size)
        assertEquals(1, viewModel.uiState.value.searchMatchIndex)
        viewModel.stepSearchMatch(1, matches.size)
        assertEquals(2, viewModel.uiState.value.searchMatchIndex)
        // Wraps around cleanly
        viewModel.stepSearchMatch(1, matches.size)
        assertEquals(0, viewModel.uiState.value.searchMatchIndex)
        viewModel.stepSearchMatch(-1, matches.size)
        assertEquals(2, viewModel.uiState.value.searchMatchIndex)

        viewModel.clearSearchHighlight()
        assertTrue(viewModel.uiState.value.searchHighlightQuery.isEmpty())
        assertEquals(0, viewModel.uiState.value.searchMatchIndex)
    }
}
