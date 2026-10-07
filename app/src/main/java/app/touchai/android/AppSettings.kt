package app.touchai.android

import app.touchai.core.openai.OpenAIModelConfig

data class AiConfiguration(val api: OpenAIModelConfig = OpenAIModelConfig(), val instructions: String = "")

data class PromptPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val customAi: AiConfiguration? = null,
)

enum class ImageQuality(val label: String, val maxEdge: Int?, val jpegQuality: Int) {
    Original("Original", null, 100), Balanced("Balanced", 2048, 92), Small("Small", 1280, 85),
}

enum class GestureCorner(val label: String, val onRight: Boolean, val onBottom: Boolean) {
    TopLeft("Top-left", false, false), TopRight("Top-right", true, false),
    BottomLeft("Bottom-left", false, true), BottomRight("Bottom-right", true, true),
}

// Swipe directions use bottom-left coordinates before reflecting for the selected corner.
enum class CornerGesture {
    SwipeUpRight, SwipeUp, SwipeRight, DoubleTap, LongPress;

    fun label(corner: GestureCorner): String = when (this) {
        SwipeUpRight -> "Swipe ${if (corner.onBottom) "up" else "down"}-${if (corner.onRight) "left" else "right"}"
        SwipeUp -> if (corner.onBottom) "Swipe up" else "Swipe down"
        SwipeRight -> if (corner.onRight) "Swipe left" else "Swipe right"
        DoubleTap -> "Double tap"
        LongPress -> "Long press"
    }

    fun arrowRotation(corner: GestureCorner): Float? = when (this) {
        SwipeUpRight -> if (corner.onBottom) {
            if (corner.onRight) 315f else 45f
        } else {
            if (corner.onRight) 225f else 135f
        }
        SwipeUp -> if (corner.onBottom) 0f else 180f
        SwipeRight -> if (corner.onRight) 270f else 90f
        DoubleTap, LongPress -> null
    }
}

data class QuickAccessSettings(
    val floatingButton: Boolean = true,
    val notification: Boolean = true,
    val buttonOnRight: Boolean = true,
    val buttonY: Float = 0.35f,
    val notificationCaptureDelayMillis: Int = 200,
    val buttonSizeDp: Int = 52,
    val attachScreenshotAutomatically: Boolean = true,
    val cornerSwipe: Boolean = false,
    val gestureCorner: GestureCorner = GestureCorner.BottomLeft,
    val cornerGestures: Set<CornerGesture> = setOf(CornerGesture.SwipeUpRight),
    val cornerOpacityPercent: Int = 35,
    val cornerAreaSizeDp: Int = 48,
)

data class AppSettings(
    val api: OpenAIModelConfig = OpenAIModelConfig(),
    val instructions: String = "",
    val presets: List<PromptPreset> = listOf(
        PromptPreset("explain", "Explain", "Explain the attached screen clearly and concisely."),
        PromptPreset("translate", "Translate", "Translate the text in the attached image into English."),
        PromptPreset("summarize", "Summarize", "Summarize the key points in the attached image."),
    ),
    val lastPresetId: String? = "explain",
    val imageQuality: ImageQuality = ImageQuality.Balanced,
    val quickAccess: QuickAccessSettings = QuickAccessSettings(),
)

fun AppSettings.aiFor(presetId: String?): AiConfiguration =
    presets.find { it.id == presetId }?.customAi ?: AiConfiguration(api, instructions)

interface SettingsRepository {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
    suspend fun rememberPreset(id: String?)
}
