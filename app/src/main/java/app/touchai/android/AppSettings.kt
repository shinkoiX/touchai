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

data class QuickAccessSettings(
    val floatingButton: Boolean = true,
    val notification: Boolean = true,
    val buttonOnRight: Boolean = true,
    val buttonY: Float = 0.35f,
    val notificationCaptureDelayMillis: Int = 200,
    val buttonSizeDp: Int = 52,
    val attachScreenshotAutomatically: Boolean = true,
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
