package app.touchai.android

import app.touchai.core.openai.ApiProtocol
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.ChatGptOAuth
import app.touchai.core.openai.OpenAIModelConfig
import java.net.URI
import java.util.UUID

/**
 * Model options for one AI choice. In settings, [connectionId] selects a shared [ApiConnection] that supplies
 * the endpoint and credentials; a null id keeps the legacy inline fields of [api].
 */
data class AiConfiguration(
    val api: OpenAIModelConfig = OpenAIModelConfig(),
    val instructions: String = "",
    val connectionId: String? = null,
)

/** A reusable endpoint and credential: an API key for an HTTPS base URL, or a signed-in ChatGPT account. */
data class ApiConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val authentication: AuthenticationMethod = AuthenticationMethod.ApiKey,
    val baseUrl: String = OpenAIModelConfig().baseUrl,
    val protocol: ApiProtocol = OpenAIModelConfig().protocol,
    val apiKey: String = "",
    val chatGptAccountId: String? = null,
) {
    val supportsBackgroundResponses: Boolean get() = authentication == AuthenticationMethod.ApiKey && protocol == ApiProtocol.Responses

    fun endpoint(): ApiConnection = if (authentication == AuthenticationMethod.ChatGpt)
        copy(baseUrl = ChatGptOAuth.Resource, protocol = ApiProtocol.Responses, apiKey = "") else copy(chatGptAccountId = null)
}

/** Applies the connection's endpoint and credential to model options. */
fun OpenAIModelConfig.using(connection: ApiConnection): OpenAIModelConfig {
    val endpoint = connection.endpoint()
    return copy(authentication = endpoint.authentication, baseUrl = endpoint.baseUrl, protocol = endpoint.protocol,
        apiKey = endpoint.apiKey, chatGptAccountId = endpoint.chatGptAccountId,
        backgroundResponses = backgroundResponses && endpoint.supportsBackgroundResponses)
}

/** Clears fields that belong to a connection, so settings never keep a second copy of a credential. */
fun OpenAIModelConfig.withoutConnection(): OpenAIModelConfig = OpenAIModelConfig().let { blank ->
    copy(authentication = blank.authentication, baseUrl = blank.baseUrl, protocol = blank.protocol, apiKey = "", chatGptAccountId = null)
}

private fun OpenAIModelConfig.asConnection(id: String, name: String) =
    ApiConnection(id, name, authentication, baseUrl, protocol, apiKey, chatGptAccountId).endpoint()

data class PromptPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val customAi: AiConfiguration? = null,
    val attachScreenshot: Boolean? = null,
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
    val connectionId: String? = null,
    val connections: List<ApiConnection> = emptyList(),
    val presets: List<PromptPreset> = listOf(
        PromptPreset("explain", "Explain", "Explain the attached screen clearly and concisely."),
        PromptPreset("translate", "Translate", "Translate the text in the attached image into English."),
        PromptPreset("summarize", "Summarize", "Summarize the key points in the attached image."),
    ),
    val lastPresetId: String? = "explain",
    val imageQuality: ImageQuality = ImageQuality.Balanced,
    val imageLimit: Int = 3,
    val quickAccess: QuickAccessSettings = QuickAccessSettings(),
)

val AppSettings.defaultAi: AiConfiguration get() = AiConfiguration(api, instructions, connectionId)

fun AppSettings.connection(id: String?): ApiConnection? = id?.let { connections.find { connection -> connection.id == it } }

/** Returns the request-ready configuration, with the selected connection's endpoint and credential applied. */
fun AppSettings.resolve(ai: AiConfiguration): AiConfiguration {
    val id = ai.connectionId ?: return ai
    val connection = connection(id) ?: return ai.copy(api = ai.api.withoutConnection())
    return ai.copy(api = ai.api.using(connection))
}

fun AppSettings.aiFor(presetId: String?): AiConfiguration =
    resolve(presets.find { it.id == presetId }?.customAi ?: defaultAi)

/** Every resolved configuration, used to find a credential again for chats whose key is not stored. */
fun AppSettings.credentialSources(): List<OpenAIModelConfig> =
    (listOf(defaultAi) + presets.mapNotNull { it.customAi }).map { resolve(it).api } + connections.map { OpenAIModelConfig().using(it) }

/**
 * Moves inline endpoints and credentials from older settings into shared connections. Identical endpoints and
 * credentials share one connection, so a key typed into several presets is stored once.
 */
fun AppSettings.withConnections(): AppSettings {
    val connections = connections.toMutableList()
    // Stable ids keep repeated loads of unsaved, older settings equal to each other.
    fun newId() = generateSequence(1) { it + 1 }.map { "connection-$it" }.first { id -> connections.none { it.id == id } }
    fun adopt(api: OpenAIModelConfig): String {
        val endpoint = api.asConnection("", "")
        connections.firstOrNull { it.endpoint().copy(id = "", name = "") == endpoint }?.let { return it.id }
        val base = if (api.authentication == AuthenticationMethod.ChatGpt) "ChatGPT"
            else runCatching { URI(api.baseUrl.trim()).host }.getOrNull()?.takeIf(String::isNotBlank) ?: "API"
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { candidate -> connections.none { it.name == candidate } }
        return api.asConnection(newId(), name).also { connections += it }.id
    }
    val defaultId = connectionId ?: adopt(api)
    val migratedPresets = presets.map { preset ->
        val ai = preset.customAi ?: return@map preset
        val id = ai.connectionId ?: adopt(ai.api)
        preset.copy(customAi = ai.copy(api = (if (ai.connectionId == null) ai.api.using(connection(connections, id)) else ai.api).withoutConnection(), connectionId = id))
    }
    val defaultApi = if (connectionId == null) api.using(connection(connections, defaultId)) else api
    return copy(api = defaultApi.withoutConnection(), connectionId = defaultId, connections = connections, presets = migratedPresets)
}

private fun connection(connections: List<ApiConnection>, id: String) = connections.first { it.id == id }

fun AppSettings.attachScreenshotFor(presetId: String?): Boolean =
    presets.find { it.id == presetId }?.attachScreenshot ?: quickAccess.attachScreenshotAutomatically

interface SettingsRepository {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
    suspend fun rememberPreset(id: String?)
}
