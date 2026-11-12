package app.touchai.core.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

enum class ApiProtocol(val label: String, val path: String) {
    ChatCompletions("Chat Completions", "chat/completions"),
    Responses("Responses", "responses"),
    /** Google's Interactions API, which supports Google Search grounding. */
    Gemini("Gemini", "interactions"),
}

/** Base URL for Google's native Gemini API, used with [ApiProtocol.Gemini]. */
const val GeminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta"

enum class AuthenticationMethod(val label: String) { ApiKey("API key"), ChatGpt("ChatGPT") }

data class OpenAIModelConfig(
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val protocol: ApiProtocol = ApiProtocol.ChatCompletions,
    val reasoningEffort: String? = null,
    val webSearch: Boolean = true,
    val timeoutMillis: Long = 120_000L,
    val backgroundResponses: Boolean = false,
    val authentication: AuthenticationMethod = AuthenticationMethod.ApiKey,
    val chatGptAccountId: String? = null,
) {
    val requestProtocol: ApiProtocol get() = if (authentication == AuthenticationMethod.ChatGpt) ApiProtocol.Responses else protocol
    val recoverResponses: Boolean get() = authentication == AuthenticationMethod.ApiKey && protocol == ApiProtocol.Responses && backgroundResponses
}

data class OpenAIImage(val url: String, val detail: String = "auto")
data class GeneratedImage(val id: String, val image: OpenAIImage)
enum class MessageRole(val value: String) { User("user"), Assistant("assistant") }

data class ChatMessage(
    val role: MessageRole,
    val text: String,
    val images: List<OpenAIImage> = emptyList(),
)

enum class RequestPurpose { Chat, Retry, Resume, ConnectionTest }

data class OpenAIRequest(
    val messages: List<ChatMessage>,
    val instructions: String = "",
    val purpose: RequestPurpose = RequestPurpose.Chat,
    val presetName: String? = null,
    val resumeResponseId: String? = null,
)

sealed interface OpenAIStreamEvent {
    data class TextDelta(val text: String) : OpenAIStreamEvent
    data class Completed(val response: JsonObject) : OpenAIStreamEvent
    data class SearchStatus(val status: String) : OpenAIStreamEvent
    data class Citation(val source: WebCitation) : OpenAIStreamEvent
    data class ImageGenerated(val value: GeneratedImage) : OpenAIStreamEvent
    data class ImageGenerationStatus(val status: String?) : OpenAIStreamEvent
    data class ResponsePayload(val value: JsonElement) : OpenAIStreamEvent
    data class ResponseCheckpoint(val id: String) : OpenAIStreamEvent
    data class RecoveryStatus(val status: String?) : OpenAIStreamEvent
    data class TextSnapshot(val text: String) : OpenAIStreamEvent
}

data class WebCitation(
    val url: String,
    val title: String,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)

interface ChatClient {
    fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent>
}

data class OpenAICompletion(val text: String, val response: JsonObject, val images: List<GeneratedImage> = emptyList())
class OpenAIRequestException(message: String, val httpStatus: Int? = null) : Exception(message)
class OpenAIStreamException(message: String) : Exception(message)
class IncompleteResponseException(val reason: String) : Exception("Answer incomplete: $reason")
class ResponseInterruptedException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ResponseCancelledException(val response: JsonObject) : Exception("The provider cancelled this response.")
class UserRequestedCancellation(val response: JsonObject? = null) : kotlinx.coroutines.CancellationException("Stopped by user")

data class ResponseOutput(val text: String, val images: List<GeneratedImage>, val citations: List<WebCitation>)

fun buildRequestUrl(config: OpenAIModelConfig): String =
    if (config.authentication == AuthenticationMethod.ChatGpt) "${ChatGptOAuth.Resource}/responses"
    else "${config.baseUrl.trimEnd('/')}/${config.protocol.path}"

/** Google uses its own key header; OpenAI-compatible APIs use a bearer token. */
internal fun io.ktor.client.request.HttpRequestBuilder.authorize(config: OpenAIModelConfig) {
    if (config.requestProtocol == ApiProtocol.Gemini) headers.append("x-goog-api-key", config.apiKey)
    else headers.append(io.ktor.http.HttpHeaders.Authorization, "Bearer ${config.apiKey}")
}

/** Google lists models as `models/{id}`, but the Interactions API takes the bare id. */
fun geminiModelId(model: String): String = model.removePrefix("models/")

fun buildRequestBody(config: OpenAIModelConfig, request: OpenAIRequest): JsonObject = buildJsonObject {
    put("model", if (config.requestProtocol == ApiProtocol.Gemini) geminiModelId(config.model) else config.model)
    put("stream", true)
    when (config.requestProtocol) {
        ApiProtocol.ChatCompletions -> {
            putJsonArray("messages") {
                if (request.instructions.isNotBlank()) add(buildJsonObject {
                    put("role", "system")
                    put("content", request.instructions)
                })
                request.messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role.value)
                    if (message.images.isEmpty()) {
                        put("content", message.text)
                    } else {
                        putJsonArray("content") {
                            if (message.text.isNotBlank()) add(buildJsonObject {
                                put("type", "text")
                                put("text", message.text)
                            })
                            message.images.forEach { image -> add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", image.url)
                                    put("detail", image.detail)
                                }
                            }) }
                        }
                    }
                }) }
            }
            config.reasoningEffort?.let { put("reasoning_effort", it) }
            if (config.webSearch) putJsonObject("web_search_options") { }
        }
        ApiProtocol.Responses -> {
            put("store", config.recoverResponses)
            if (config.recoverResponses) put("background", true)
            if (request.instructions.isNotBlank()) put("instructions", request.instructions)
            putJsonArray("input") {
                request.messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role.value)
                    if (message.images.isEmpty()) {
                        put("content", message.text)
                    } else {
                        putJsonArray("content") {
                            if (message.text.isNotBlank()) add(buildJsonObject {
                                put("type", "input_text")
                                put("text", message.text)
                            })
                            message.images.forEach { image -> add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", image.url)
                                put("detail", image.detail)
                            }) }
                        }
                    }
                }) }
            }
            config.reasoningEffort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
            if (config.webSearch) putJsonArray("tools") { add(buildJsonObject { put("type", "web_search") }) }
        }
        ApiProtocol.Gemini -> {
            // The full history is sent each turn, so nothing needs to be stored by Google.
            put("store", false)
            if (request.instructions.isNotBlank()) put("system_instruction", request.instructions)
            putJsonArray("input") {
                request.messages.forEach { message -> add(buildJsonObject {
                    put("type", if (message.role == MessageRole.User) "user_input" else "model_output")
                    putJsonArray("content") {
                        if (message.text.isNotBlank() || message.images.isEmpty()) add(buildJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        })
                        message.images.forEach { image -> add(geminiImage(image)) }
                    }
                }) }
            }
            config.reasoningEffort?.let { effort -> putJsonObject("generation_config") { put("thinking_level", effort) } }
            if (config.webSearch) putJsonArray("tools") { add(buildJsonObject { put("type", "google_search") }) }
        }
    }
}

private fun geminiImage(image: OpenAIImage): JsonObject = buildJsonObject {
    put("type", "image")
    val inline = Regex("^data:([^;,]+);base64,(.*)$", RegexOption.DOT_MATCHES_ALL).find(image.url)
    if (inline != null) {
        put("mime_type", inline.groupValues[1])
        put("data", inline.groupValues[2])
    } else put("uri", image.url)
    if (image.detail == "low" || image.detail == "high") put("resolution", image.detail)
}
