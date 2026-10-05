package app.touchai.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.touchai.core.openai.*
import java.io.File
import java.util.UUID
import java.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatFlowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private val historyDirectory by lazy { File(compose.activity.cacheDir, "test-chats-${UUID.randomUUID()}") }
    private val history by lazy { ChatHistoryStore(historyDirectory, ApiKeyCipher()) }
    private lateinit var viewModel: OpenAIChatViewModel
    private val events = Channel<OpenAIStreamEvent>(Channel.UNLIMITED)
    private var request: OpenAIRequest? = null
    private var configuration: OpenAIModelConfig? = null
    private val custom = AiConfiguration(OpenAIModelConfig(apiKey = "test-only", model = "custom-model", webSearch = false))

    private fun setup() {
        var settings = AppSettings(
            api = OpenAIModelConfig(apiKey = "test-only", model = "default-model"),
            presets = listOf(PromptPreset("custom", "Custom", "Explain this image", custom)),
            lastPresetId = "custom", imageQuality = ImageQuality.Original,
        )
        val repository = object : SettingsRepository {
            override suspend fun load() = settings
            override suspend fun save(settings: AppSettings) = Unit
            override suspend fun rememberPreset(id: String?) { settings = settings.copy(lastPresetId = id) }
        }
        val client = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, value: OpenAIRequest) = events.receiveAsFlow().also {
                request = value; configuration = config
            }
        }
        compose.runOnUiThread { viewModel = OpenAIChatViewModel(history, repository, client); store.put("test", viewModel) }
        compose.setContent { TouchAiTheme { OpenAIChatScreen(viewModel, (compose.activity.application as TouchAiApplication).quickAccess) } }
        compose.waitUntil(5_000) { viewModel.uiState.value.ready }
    }

    @After fun cleanup() { compose.runOnUiThread { store.clear() }; events.close(); historyDirectory.deleteRecursively() }

    @Test fun cropPresetAndStreamingWorkTogether() {
        setup()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        compose.runOnUiThread { viewModel.attachImage(bitmap) }
        compose.onNodeWithText("Review screenshot").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Explain this image")
        compose.onNodeWithContentDescription("Crop image").performClick()
        compose.onNodeWithText("100 × 100 px").assertIsDisplayed()
        compose.onNodeWithText("Apply", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.image != null }
        compose.onNodeWithText("Custom", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        compose.onNodeWithContentDescription("Attached image").assertExists().performClick()
        compose.onNodeWithContentDescription("Attached image preview").assertExists()
        compose.onNodeWithContentDescription("Close image").performClick()
        assertEquals(custom.api, configuration)
        val bytes = Base64.getDecoder().decode(request!!.messages.last().images.single().url.substringAfter(','))
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(100, image.width)
        assertEquals(100, image.height)
        events.trySend(OpenAIStreamEvent.TextDelta("Visible before completion"))
        compose.waitUntil(5_000) { viewModel.uiState.value.turns.last().answer.isNotBlank() }
        assertTrue(viewModel.uiState.value.isStreaming)
        compose.onNodeWithText("Visible before completion", substring = true).assertExists()
        compose.onNodeWithContentDescription("Stop").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
        compose.onNodeWithText("Stopped").assertExists()
        assertEquals("Visible before completion", viewModel.uiState.value.turns.last().answer)
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.ready }
        compose.onNodeWithContentDescription("Chat history").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.historyLoading }
        compose.onNodeWithText("Explain this image").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.historyOpen }
        compose.onNodeWithContentDescription("Attached image").assertExists()
        compose.onNodeWithText("Visible before completion", substring = true).assertExists()
    }

    @Test fun recroppingUsesOriginalAndCancelKeepsAppliedCrop() {
        setup()
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { viewModel.captureOnInvocation { bitmap } }
        compose.waitUntil(5_000) { viewModel.uiState.value.image != null }
        assertEquals("Explain this image", viewModel.uiState.value.prompt)
        compose.runOnUiThread { viewModel.applyCrop(ImageCrop(0.2f, 0.2f, 0.8f, 0.8f)) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImage }
        assertEquals(60, viewModel.uiState.value.imagePreview!!.width)
        compose.onNodeWithContentDescription("Crop image").performClick()
        compose.onNodeWithText("60 × 120 px").assertIsDisplayed()
        compose.onNodeWithText("Reset", useUnmergedTree = true).performClick()
        compose.onNodeWithText("100 × 200 px").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(60, viewModel.uiState.value.imagePreview!!.width)
        compose.onNodeWithContentDescription("Crop image").performClick()
        compose.onNodeWithText("Reset", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Apply", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImage }
        assertEquals(100, viewModel.uiState.value.imagePreview!!.width)
        assertEquals(200, viewModel.uiState.value.imagePreview!!.height)
    }

    @Test fun withoutImageSendsNoImageContent() {
        setup()
        compose.runOnUiThread { viewModel.attachImage(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)) }
        compose.onNodeWithContentDescription("Remove image").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Only text")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        assertEquals("Only text", request!!.messages.last().text)
        assertTrue(request!!.messages.last().images.isEmpty())
        events.trySend(OpenAIStreamEvent.TextDelta("A complete answer."))
        events.trySend(OpenAIStreamEvent.Completed(JsonObject(emptyMap())))
        events.close()
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
        compose.onNodeWithText("A complete answer.", substring = true).assertExists()
    }
}
