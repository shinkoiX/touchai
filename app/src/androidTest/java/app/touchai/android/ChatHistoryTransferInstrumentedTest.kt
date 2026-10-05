package app.touchai.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.touchai.core.openai.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryTransferInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private val directory by lazy { File(compose.activity.cacheDir, "test-transfer-${UUID.randomUUID()}") }

    @After fun cleanup() { compose.runOnUiThread { models.clear() }; directory.deleteRecursively() }

    @Test fun menuExportsAndImportsChatsWithDecodableImages() {
        val history = ChatHistoryStore(directory, ApiKeyCipher())
        val settings = AppSettings(api = OpenAIModelConfig(apiKey = "local-test-secret", model = "test-model"))
        runBlocking {
            val bitmap = Bitmap.createBitmap(48, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            val image = ImageProcessor.prepare(bitmap, ImageCrop.Full, ImageQuality.Original).input
            history.save(SavedChat("source", 1234, null, listOf(ChatTurn(ChatMessage(MessageRole.User, "Saved image", listOf(image)),
                AiConfiguration(settings.api), answer = "Saved response", status = TurnStatus.Completed))))
        }
        val repository = object : SettingsRepository {
            override suspend fun load() = settings
            override suspend fun save(settings: AppSettings) = Unit
            override suspend fun rememberPreset(id: String?) = Unit
        }
        val client = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = emptyFlow<OpenAIStreamEvent>()
        }
        lateinit var viewModel: OpenAIChatViewModel
        compose.runOnUiThread {
            viewModel = OpenAIChatViewModel(history, repository, client)
            models.put("transfer", viewModel)
            viewModel.showHistory(true)
        }
        val output = ByteArrayOutputStream()
        compose.setContent {
            TouchAiTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                if (state.historyOpen) ChatHistoryScreen(state, viewModel::openChat, viewModel::deleteChat, viewModel::newChat,
                    { viewModel.showHistory(true) }, { viewModel.exportHistory { output } },
                    { viewModel.importHistory { output.toByteArray().inputStream() } }, { viewModel.showHistory(false) })
                else OpenAIChatScreen(viewModel, (compose.activity.application as TouchAiApplication).quickAccess)
            }
        }
        compose.waitUntil(5_000) { !viewModel.uiState.value.historyLoading && viewModel.uiState.value.historyEntries.size == 1 }
        compose.onNodeWithContentDescription("History options").performClick()
        compose.onNodeWithText("Export history").performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.historyNotice == "Exported 1 chat" }
        assertFalse(output.toString(Charsets.UTF_8).contains("local-test-secret"))
        compose.onNodeWithContentDescription("Delete chat").performClick()
        compose.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.historyLoading && viewModel.uiState.value.historyEntries.isEmpty() }
        compose.onNodeWithContentDescription("History options").performClick()
        compose.onNodeWithText("Import history").performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.historyNotice == "Imported 1 chat" }
        compose.onNodeWithText("Saved image").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.historyOpen }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Attached image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Saved response", substring = true).assertExists()
        compose.onNodeWithContentDescription("Attached image").assertExists()
        assertEquals("", viewModel.uiState.value.turns.single().ai.api.apiKey)
    }
}
