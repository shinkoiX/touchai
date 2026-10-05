package app.touchai.core.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

enum class ApiProtocol(val label: String, val path: String) {
    ChatCompletions("Chat Completions", "chat/completions"),
    Responses("Responses", "responses"),
}

data class OpenAIModelConfig(
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val protocol: ApiProtocol = ApiProtocol.ChatCompletions,
    val reasoningEffort: String? = null,
    val webSearch: Boolean = true,
    val timeoutMillis: Long = 120_000L,
    val backgroundResponses: Boolean = false,
)

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
    "${config.baseUrl.trimEnd('/')}/${config.protocol.path}"

fun buildRequestBody(config: OpenAIModelConfig, request: OpenAIRequest): JsonObject = buildJsonObject {
    put("model", config.model)
    put("stream", true)
    when (config.protocol) {
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
            put("store", config.backgroundResponses)
            if (config.backgroundResponses) put("background", true)
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
    }
}
