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
import kotlinx.coroutines.runBlocking
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

    private fun setup(attachScreenshots: Boolean = true, screenshotOverride: Boolean? = null,
        captureScreenshot: suspend () -> Bitmap = { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) }) {
        var storedSettings = AppSettings(
            api = OpenAIModelConfig(apiKey = "test-only", model = "default-model"),
            presets = listOf(PromptPreset("custom", "Custom", "Explain this image", custom, attachScreenshot = screenshotOverride)),
            lastPresetId = "custom", imageQuality = ImageQuality.Original,
            quickAccess = QuickAccessSettings(attachScreenshotAutomatically = attachScreenshots),
        )
        val repository = object : SettingsRepository {
            override suspend fun load() = storedSettings
            override suspend fun save(settings: AppSettings) { storedSettings = settings }
            override suspend fun rememberPreset(id: String?) { storedSettings = storedSettings.copy(lastPresetId = id) }
        }
        val client = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, value: OpenAIRequest) = events.receiveAsFlow().also {
                request = value; configuration = config
            }
        }
        compose.runOnUiThread { viewModel = testChatViewModel(history, repository, client); store.put("test", viewModel) }
        compose.setContent { TouchAiTheme {
            OpenAIChatScreen(viewModel, (compose.activity.application as TouchAiApplication).quickAccess, captureScreenshot)
        } }
        compose.waitUntil(5_000) { viewModel.uiState.value.ready }
    }

    @After fun cleanup() { compose.runOnUiThread { store.clear() }; events.close(); historyDirectory.deleteRecursively() }

    @Test fun photoTapsToggleInclusionAndNumbersFollowSendingOrderWhileCropUsesItsButton() {
        setup()
        val first = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val second = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        compose.runOnUiThread { viewModel.attachImage(first); viewModel.attachImage(second) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        compose.onNodeWithText("1").assertExists()
        compose.onNodeWithText("2").assertExists()
        val before = viewModel.uiState.value.attachments
        compose.onAllNodesWithContentDescription("Image preview").onFirst().performClick().assertIsNotSelected()
        assertNull(viewModel.uiState.value.croppingImageId)
        assertFalse(viewModel.uiState.value.attachments.first().attached)
        compose.onNodeWithText("2").assertDoesNotExist()
        compose.onNodeWithText("1").assertExists()
        compose.onAllNodesWithContentDescription("Crop image").onFirst().performClick()
        assertEquals(before.first().id, viewModel.uiState.value.croppingImageId)
        assertFalse(viewModel.uiState.value.attachments.first().attached)
        compose.onNodeWithContentDescription("Crop selection").assertExists()
        compose.onNodeWithContentDescription("Cancel").performClick()
        compose.onAllNodesWithContentDescription("Image preview").onFirst().performClick().assertIsSelected()
        compose.onNodeWithText("2").assertExists()
        compose.onAllNodesWithContentDescription("Remove image").onFirst().assertTextContains("2")
        compose.onAllNodesWithContentDescription("Remove image").onLast().assertTextContains("1")
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "selection-order.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        assertEquals(listOf(before.last().image, before.first().image), request!!.messages.last().images)
        events.trySend(OpenAIStreamEvent.Completed(JsonObject(emptyMap())))
        events.close()
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
    }

    @Test fun screenshotsAndGalleryImagesShareTheLimitAndSendInOrder() {
        val screenshots = listOf(
            Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) },
            Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) },
        )
        val gallery = listOf(
            Bitmap.createBitmap(50, 70, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) },
            Bitmap.createBitmap(70, 90, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) },
        )
        var captures = 0
        setup(captureScreenshot = { screenshots[captures++] })
        compose.onNodeWithContentDescription("Attach image").performClick()
        compose.onNodeWithText("Attach screenshot").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages && viewModel.uiState.value.attachments.size == 1 }
        compose.runOnUiThread { viewModel.attachImage(gallery[0]) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        val firstGalleryId = viewModel.uiState.value.attachments.last().id
        val crop = ImageCrop(0.2f, 0.2f, 0.8f, 0.8f)
        compose.runOnUiThread { viewModel.applyCrop(firstGalleryId, crop) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        compose.onNodeWithContentDescription("Attach image").performClick()
        compose.onNodeWithText("Attach screenshot").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages && viewModel.uiState.value.attachments.size == 3 }
        compose.runOnUiThread { viewModel.attachImage(gallery[1]) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        val retained = viewModel.uiState.value.attachments
        assertEquals(listOf(gallery[0], screenshots[1], gallery[1]), retained.map { it.original })
        assertEquals(listOf(false, true, false), retained.map { it.screenshot })
        assertEquals(crop, retained.first().crop)
        assertEquals(3, compose.onAllNodesWithContentDescription("Crop image").fetchSemanticsNodes().size)
        val screenshot = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "multiple-images.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        compose.onNode(hasSetTextAction()).performTextReplacement("Keep the draft")
        val chatId = viewModel.uiState.value.chatId
        compose.runOnUiThread { viewModel.saveSettings(viewModel.uiState.value.settings.copy(imageLimit = 2)) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.savingSettings }
        assertEquals(chatId, viewModel.uiState.value.chatId)
        assertEquals("Keep the draft", viewModel.uiState.value.prompt)
        assertEquals(retained.takeLast(2), viewModel.uiState.value.attachments)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        assertEquals(retained.takeLast(2).map { it.image }, request!!.messages.last().images)
        assertTrue(viewModel.uiState.value.attachments.isEmpty())
        events.trySend(OpenAIStreamEvent.Completed(JsonObject(emptyMap())))
        events.close()
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
    }

    @Test fun attachScreenshotCapturesFreshImagesAndStaysAvailableAfterSendingAndReopeningChat() {
        val first = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val second = Bitmap.createBitmap(50, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        var captures = 0
        setup(screenshotOverride = false, captureScreenshot = { if (++captures == 1) first else second })
        compose.onNodeWithContentDescription("Attach image").performClick()
        compose.onNodeWithText("Attach screenshot").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages && viewModel.uiState.value.attachments.lastOrNull()?.image != null }
        assertSame(first, viewModel.uiState.value.attachments.lastOrNull { it.screenshot }?.original)
        assertTrue(viewModel.uiState.value.attachments.lastOrNull()?.attached == true)
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        events.trySend(OpenAIStreamEvent.TextDelta("First answer"))
        events.trySend(OpenAIStreamEvent.Completed(JsonObject(emptyMap())))
        events.close()
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
        assertNull(viewModel.uiState.value.attachments.lastOrNull { it.screenshot }?.original)
        compose.onNodeWithContentDescription("Attach image").performClick()
        compose.onNodeWithText("Attach screenshot").assertIsDisplayed()
        compose.onNodeWithText("Choose image").assertIsDisplayed()
        compose.onNodeWithText("Attach screenshot").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages && viewModel.uiState.value.attachments.lastOrNull()?.image != null }
        assertSame(second, viewModel.uiState.value.attachments.lastOrNull { it.screenshot }?.original)
        val chatId = viewModel.uiState.value.chatId
        compose.runOnUiThread { viewModel.newChat() }
        compose.waitUntil(5_000) { viewModel.uiState.value.ready }
        compose.runOnUiThread { viewModel.openChat(chatId) }
        compose.waitUntil(5_000) { viewModel.uiState.value.chatId == chatId && !viewModel.uiState.value.historyLoading }
        compose.onNode(hasSetTextAction()).performTextReplacement("Follow-up draft")
        val before = viewModel.uiState.value
        compose.onNodeWithContentDescription("Attach image").performClick()
        compose.onNodeWithText("Attach screenshot").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages && viewModel.uiState.value.attachments.lastOrNull()?.image != null }
        val after = viewModel.uiState.value
        assertEquals(3, captures)
        assertSame(second, after.attachments.lastOrNull { it.screenshot }?.original)
        assertEquals(chatId, after.chatId)
        assertEquals(before.turns, after.turns)
        assertEquals(before.selectedPreset, after.selectedPreset)
        assertEquals("Follow-up draft", after.prompt)
        assertTrue(after.attachments.lastOrNull()?.attached == true)
    }

    @Test fun failedRecaptureKeepsTheExistingImageCropAndDraft() {
        setup(captureScreenshot = { throw ScreenCaptureException("Protected screen") })
        val original = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { viewModel.captureOnInvocation { original } }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages && viewModel.uiState.value.attachments.lastOrNull()?.image != null }
        val crop = ImageCrop(0.2f, 0.2f, 0.8f, 0.8f)
        compose.runOnUiThread { viewModel.applyCrop(viewModel.uiState.value.attachments.last().id, crop); viewModel.setPrompt("Keep this draft") }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        val before = viewModel.uiState.value
        compose.onNodeWithContentDescription("Attach image").performClick()
        compose.onNodeWithText("Attach screenshot").performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.error == "Protected screen" }
        val after = viewModel.uiState.value
        assertFalse(after.capturingScreenshot)
        assertSame(before.attachments.lastOrNull()?.original, after.attachments.lastOrNull()?.original)
        assertSame(before.attachments.lastOrNull()?.image, after.attachments.lastOrNull()?.image)
        assertSame(before.attachments.lastOrNull { it.screenshot }?.original, after.attachments.lastOrNull { it.screenshot }?.original)
        assertEquals(crop, after.attachments.lastOrNull()?.crop)
        assertEquals(before.prompt, after.prompt)
        assertEquals(before.chatId, after.chatId)
    }

    @Test fun generatedImageOnlyReplyIsVisibleAndSurvivesHistoryReopening() {
        setup()
        val image = runBlocking {
            ImageProcessor.prepare(Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) },
                ImageCrop.Full, ImageQuality.Original).input
        }
        compose.onNode(hasSetTextAction()).performTextReplacement("Draw an image")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        events.trySend(OpenAIStreamEvent.ImageGenerated(GeneratedImage("test-image", image)))
        events.trySend(OpenAIStreamEvent.Completed(JsonObject(emptyMap())))
        events.close()
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Generated image").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Generated image").performClick()
        compose.onNodeWithContentDescription("Generated image preview").assertExists()
        compose.onNodeWithContentDescription("Close image").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.waitUntil(5_000) { viewModel.uiState.value.ready }
        compose.onNodeWithContentDescription("Chat history").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.historyLoading }
        compose.onNodeWithText("Draw an image").performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.historyOpen }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Generated image").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(image, viewModel.uiState.value.turns.single().generatedImages.single().image)
    }

    @Test fun screenshotCanBeAddedRemovedAndAddedAgainWithoutRecapturing() {
        setup(screenshotOverride = false)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        var captures = 0
        compose.runOnUiThread { viewModel.captureOnInvocation { captures++; bitmap } }
        compose.waitUntil(5_000) { viewModel.uiState.value.attachments.lastOrNull()?.image != null && !viewModel.uiState.value.invoking }
        assertFalse(viewModel.uiState.value.attachments.lastOrNull()?.attached == true)
        compose.onNodeWithContentDescription("Crop image").assertExists()
        compose.onNodeWithContentDescription("Add image").assertIsNotSelected().performClick()
        compose.onNodeWithContentDescription("Remove image").assertIsSelected()
        assertSame(bitmap, viewModel.uiState.value.attachments.lastOrNull()?.original)
        compose.onAllNodesWithContentDescription("Remove image").onLast().performClick()
        assertFalse(viewModel.uiState.value.attachments.lastOrNull()?.attached == true)
        compose.onNodeWithContentDescription("Crop image").assertExists()
        assertSame(bitmap, viewModel.uiState.value.attachments.lastOrNull { it.screenshot }?.original)
        compose.onNodeWithContentDescription("Add image").assertIsNotSelected().performClick()
        assertTrue(viewModel.uiState.value.attachments.lastOrNull()?.attached == true)
        assertEquals(1, captures)
        compose.runOnUiThread { viewModel.newChat() }
        compose.waitUntil(5_000) { viewModel.uiState.value.ready }
        assertNull(viewModel.uiState.value.attachments.lastOrNull { it.screenshot }?.original)
    }

    @Test fun manualScreenshotModeDoesNotIncludeAnUnattachedCaptureInRequests() {
        setup(attachScreenshots = false)
        compose.runOnUiThread { viewModel.captureOnInvocation { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) } }
        compose.waitUntil(5_000) { viewModel.uiState.value.attachments.lastOrNull()?.image != null && !viewModel.uiState.value.invoking }
        compose.onNode(hasSetTextAction()).performTextReplacement("Only text")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        assertTrue(request!!.messages.last().images.isEmpty())
        assertNull(viewModel.uiState.value.attachments.lastOrNull { it.screenshot }?.original)
        compose.runOnUiThread { viewModel.cancel() }
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
    }

    @Test fun presetAlwaysAttachesScreenshotWhenGlobalSettingIsOff() {
        setup(attachScreenshots = false, screenshotOverride = true)
        compose.runOnUiThread { viewModel.captureOnInvocation { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) } }
        compose.waitUntil(5_000) { viewModel.uiState.value.attachments.lastOrNull()?.image != null && !viewModel.uiState.value.invoking }
        compose.onNodeWithContentDescription("Remove image").assertIsSelected()
        val image = viewModel.uiState.value.attachments.lastOrNull()?.image
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        assertEquals(listOf(image), request!!.messages.last().images)
        compose.runOnUiThread { viewModel.cancel() }
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
    }

    @Test fun presetNeverAttachesScreenshotWhenGlobalSettingIsOn() {
        setup(screenshotOverride = false)
        compose.runOnUiThread { viewModel.captureOnInvocation { Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) } }
        compose.waitUntil(5_000) { viewModel.uiState.value.attachments.lastOrNull()?.image != null && !viewModel.uiState.value.invoking }
        compose.onNodeWithContentDescription("Add image").assertIsNotSelected()
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitUntil(5_000) { request != null }
        assertTrue(request!!.messages.last().images.isEmpty())
        compose.runOnUiThread { viewModel.cancel() }
        compose.waitUntil(5_000) { !viewModel.uiState.value.isStreaming }
    }

    @Test fun changingScreenshotOverrideAndPresetsKeepsCropAndLeavesGalleryImagesAlone() {
        setup(screenshotOverride = false)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        var captures = 0
        compose.runOnUiThread { viewModel.captureOnInvocation { captures++; bitmap } }
        compose.waitUntil(5_000) { viewModel.uiState.value.attachments.lastOrNull()?.image != null && !viewModel.uiState.value.invoking }
        val crop = ImageCrop(0.2f, 0.2f, 0.8f, 0.8f)
        compose.runOnUiThread { viewModel.applyCrop(viewModel.uiState.value.attachments.last().id, crop) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        val croppedImage = viewModel.uiState.value.attachments.lastOrNull()?.image
        compose.onNodeWithText("No preset").performClick()
        compose.onNodeWithContentDescription("Remove image").assertIsSelected()
        compose.onNodeWithText("Custom", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Add image").assertIsNotSelected()
        compose.runOnUiThread {
            val settings = viewModel.uiState.value.settings
            viewModel.saveSettings(settings.copy(presets = settings.presets.map { it.copy(attachScreenshot = true) }))
        }
        compose.waitUntil(5_000) { !viewModel.uiState.value.savingSettings }
        compose.onNodeWithContentDescription("Remove image").assertIsSelected()
        assertEquals(crop, viewModel.uiState.value.attachments.lastOrNull()?.crop)
        assertSame(croppedImage, viewModel.uiState.value.attachments.lastOrNull()?.image)
        assertEquals(1, captures)
        val galleryImage = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { viewModel.attachImage(galleryImage) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        compose.onAllNodesWithContentDescription("Remove image").onLast().performClick()
        compose.onNodeWithText("No preset").performClick()
        compose.onNodeWithText("Custom", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Add image").assertIsNotSelected()
        assertSame(galleryImage, viewModel.uiState.value.attachments.lastOrNull()?.original)
    }

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
        compose.waitUntil(5_000) { viewModel.uiState.value.attachments.lastOrNull()?.image != null }
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
        compose.waitUntil(5_000) { viewModel.uiState.value.attachments.lastOrNull()?.image != null }
        assertEquals("Explain this image", viewModel.uiState.value.prompt)
        compose.runOnUiThread { viewModel.applyCrop(viewModel.uiState.value.attachments.last().id, ImageCrop(0.2f, 0.2f, 0.8f, 0.8f)) }
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        assertEquals(60, viewModel.uiState.value.attachments.lastOrNull()?.preview!!.width)
        compose.onNodeWithContentDescription("Crop image").performClick()
        compose.onNodeWithText("60 × 120 px").assertIsDisplayed()
        compose.onNodeWithText("Reset", useUnmergedTree = true).performClick()
        compose.onNodeWithText("100 × 200 px").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel").performClick()
        assertEquals(60, viewModel.uiState.value.attachments.lastOrNull()?.preview!!.width)
        compose.onNodeWithContentDescription("Crop image").performClick()
        compose.onNodeWithText("Reset", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Apply", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { !viewModel.uiState.value.preparingImages }
        assertEquals(100, viewModel.uiState.value.attachments.lastOrNull()?.preview!!.width)
        assertEquals(200, viewModel.uiState.value.attachments.lastOrNull()?.preview!!.height)
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
