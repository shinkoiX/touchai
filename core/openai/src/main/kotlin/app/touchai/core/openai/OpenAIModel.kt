package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class OpenAIModel(private val httpClient: HttpClient) : ChatClient {
    override fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent> = flow {
        var responseId = request.resumeResponseId
        if (responseId != null) {
            pollResponse(config, responseId)
        } else {
            emitAll(createStream(config, request) { responseId = it }.catch { error ->
                if (error is CancellationException) throw error
                if (error !is IOException && error !is HttpRequestTimeoutException && error !is ResponseInterruptedException) throw error
                val id = responseId
                if (id != null && config.backgroundResponses) {
                    emit(OpenAIStreamEvent.RecoveryStatus("Reconnecting…"))
                    pollResponse(config, id)
                } else throw ResponseInterruptedException(
                    if (config.backgroundResponses) "Connection interrupted before a response ID was received. The request cannot be recovered safely."
                    else "Connection interrupted. This request does not support server-side recovery.", error)
            })
        }
    }

    private fun createStream(config: OpenAIModelConfig, request: OpenAIRequest, onCheckpoint: (String) -> Unit): Flow<OpenAIStreamEvent> = flow {
        var responseId: String? = null
        httpClient.preparePost(buildRequestUrl(config)) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            accept(ContentType.Text.EventStream)
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = config.timeoutMillis }
            setBody(buildRequestBody(config, request).toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
                emit(OpenAIStreamEvent.ResponsePayload(JsonPrimitive(body)))
                val message = try {
                    (Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.string("message")
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
                throw OpenAIRequestException("HTTP ${response.status.value}: ${message ?: response.status.description}", response.status.value)
            }
            if (response.contentType()?.match(ContentType.Text.EventStream) != true) {
                emit(OpenAIStreamEvent.ResponsePayload(JsonPrimitive(response.bodyAsText())))
                throw OpenAIStreamException("The endpoint did not return an event stream. Check the API protocol.")
            }

            val parser = SseByteParser()
            val channel = response.bodyAsChannel()
            val bytes = ByteArray(8192)
            var completed = false
            val receivedText = StringBuilder()
            val imageIds = mutableSetOf<String>()
            streamLoop@ while (!completed) {
                currentCoroutineContext().ensureActive()
                val count = channel.readAvailable(bytes, 0, bytes.size)
                if (count == -1) break
                for (event in parser.accept(bytes, count)) {
                    if (event.data == "[DONE]") break@streamLoop
                    if (event.data.isBlank()) continue
                    val payload = try { parseStreamPayload(event.data) }
                    catch (error: OpenAIStreamException) {
                        emit(OpenAIStreamEvent.ResponsePayload(JsonPrimitive(event.data)))
                        throw error
                    }
                    emit(OpenAIStreamEvent.ResponsePayload(payload))
                    if (config.protocol == ApiProtocol.Responses && config.backgroundResponses && responseId == null) {
                        (payload["response"] as? JsonObject)?.string("id")?.let {
                            responseId = it
                            onCheckpoint(it)
                            emit(OpenAIStreamEvent.ResponseCheckpoint(it))
                        }
                    }
                    val decoded = decodeStreamPayload(config.protocol, payload, event.name)
                    decoded.deltas.forEach { receivedText.append(it); emit(OpenAIStreamEvent.TextDelta(it)) }
                    decoded.finalText?.let { finalText ->
                        if (finalText.startsWith(receivedText.toString()) && finalText.length > receivedText.length) {
                            val remaining = finalText.substring(receivedText.length)
                            receivedText.append(remaining)
                            emit(OpenAIStreamEvent.TextDelta(remaining))
                        }
                    }
                    decoded.images.forEach { if (imageIds.add(it.id)) emit(OpenAIStreamEvent.ImageGenerated(it)) }
                    if (decoded.imageStatusChanged) emit(OpenAIStreamEvent.ImageGenerationStatus(decoded.imageStatus))
                    decoded.searchStatus?.let { emit(OpenAIStreamEvent.SearchStatus(it)) }
                    decoded.citations.forEach { emit(OpenAIStreamEvent.Citation(it)) }
                    decoded.failure?.let { throw it }
                    decoded.completed?.let {
                        emit(OpenAIStreamEvent.Completed(it))
                        completed = true
                    }
                    if (completed) break@streamLoop
                }
            }
            if (!completed) {
                throw ResponseInterruptedException("The connection ended before completion was confirmed.")
            }
        }
    }

    suspend fun cancelResponse(config: OpenAIModelConfig, id: String): JsonObject {
        val response = httpClient.post("${buildRequestUrl(config)}/${id.encodeURLPathPart()}/cancel") {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json); accept(ContentType.Application.Json)
            timeout { requestTimeoutMillis = config.timeoutMillis.coerceAtMost(15_000L) }
            setBody("{}")
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) throw OpenAIRequestException("Could not cancel response: HTTP ${response.status.value}.", response.status.value)
        return Json.parseToJsonElement(body).jsonObject
    }

    private suspend fun FlowCollector<OpenAIStreamEvent>.pollResponse(config: OpenAIModelConfig, id: String) {
        emit(OpenAIStreamEvent.ResponseCheckpoint(id))
        var retryMillis = 1_000L
        while (true) {
            currentCoroutineContext().ensureActive()
            val response = try {
                val result = httpClient.get("${buildRequestUrl(config)}/${id.encodeURLPathPart()}") {
                    header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                    accept(ContentType.Application.Json)
                    timeout { requestTimeoutMillis = config.timeoutMillis }
                }
                val body = result.bodyAsText()
                if (!result.status.isSuccess()) throw OpenAIRequestException("Could not recover response: HTTP ${result.status.value}.", result.status.value)
                Json.parseToJsonElement(body).jsonObject
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!isTransientResponseError(error)) throw ResponseInterruptedException("Could not retrieve the saved response: ${error.message}", error)
                emit(OpenAIStreamEvent.RecoveryStatus("Waiting for connection…"))
                delay(retryMillis)
                retryMillis = (retryMillis * 2).coerceAtMost(30_000)
                continue
            }
            retryMillis = 1_000L
            emit(OpenAIStreamEvent.ResponsePayload(response))
            when (response.string("status")) {
                "queued", "in_progress" -> {
                    emit(OpenAIStreamEvent.RecoveryStatus("Waiting for response…"))
                    delay(2_000)
                }
                "completed", "cancelled", "failed", "incomplete" -> {
                    val output = readResponseOutput(response)
                    emit(OpenAIStreamEvent.TextSnapshot(output.text))
                    output.images.forEach { emit(OpenAIStreamEvent.ImageGenerated(it)) }
                    output.citations.forEach { emit(OpenAIStreamEvent.Citation(it)) }
                    emit(OpenAIStreamEvent.RecoveryStatus(null))
                    when (response.string("status")) {
                        "completed" -> { emit(OpenAIStreamEvent.Completed(response)); return }
                        "cancelled" -> throw ResponseCancelledException(response)
                        "incomplete" -> throw incomplete((response["incomplete_details"] as? JsonObject)?.string("reason") ?: "generation stopped early")
                        else -> throw OpenAIRequestException((response["error"] as? JsonObject)?.string("message") ?: "The API response failed.")
                    }
                }
                else -> throw OpenAIStreamException("The saved response has an unsupported status.")
            }
        }
    }

    suspend fun complete(config: OpenAIModelConfig, request: OpenAIRequest): OpenAICompletion {
        val text = StringBuilder()
        var response: JsonObject? = null
        val images = mutableListOf<GeneratedImage>()
        stream(config, request).collect { event ->
            when (event) {
                is OpenAIStreamEvent.TextDelta -> text.append(event.text)
                is OpenAIStreamEvent.TextSnapshot -> { text.clear(); text.append(event.text) }
                is OpenAIStreamEvent.Completed -> response = event.response
                is OpenAIStreamEvent.ImageGenerated -> images += event.value
                is OpenAIStreamEvent.SearchStatus, is OpenAIStreamEvent.Citation,
                is OpenAIStreamEvent.ImageGenerationStatus, is OpenAIStreamEvent.ResponsePayload -> Unit
                is OpenAIStreamEvent.ResponseCheckpoint, is OpenAIStreamEvent.RecoveryStatus -> Unit
            }
        }
        return OpenAICompletion(text.toString(), response!!, images.distinctBy { it.id })
    }
}

internal data class SseEvent(val name: String?, val data: String)
internal data class DecodedEvent(
    val deltas: List<String> = emptyList(),
    val completed: JsonObject? = null,
    val failure: Exception? = null,
    val searchStatus: String? = null,
    val citations: List<WebCitation> = emptyList(),
    val images: List<GeneratedImage> = emptyList(),
    val finalText: String? = null,
    val imageStatus: String? = null,
    val imageStatusChanged: Boolean = false,
)

/** Decode UTF-8 only after collecting a whole line, including across HTTP chunk boundaries. */
internal class SseByteParser {
    private val line = ByteArrayOutputStream()
    private val parser = SseParser()
    private var skipLf = false

    fun accept(bytes: ByteArray, count: Int): List<SseEvent> = buildList {
        for (index in 0 until count) {
            val value = bytes[index].toInt() and 0xff
            if (skipLf && value == 10) { skipLf = false; continue }
            skipLf = false
            if (value == 10 || value == 13) {
                parser.accept(line.toByteArray().toString(Charsets.UTF_8))?.let { add(it) }
                line.reset()
                skipLf = value == 13
            } else line.write(value)
        }
    }
}

/** An SSE event is dispatched only at a blank line. */
internal class SseParser {
    private var eventName: String? = null
    private val data = mutableListOf<String>()

    fun accept(line: String): SseEvent? {
        if (line.isEmpty()) {
            val event = if (data.isEmpty()) null else SseEvent(eventName, data.joinToString("\n"))
            eventName = null
            data.clear()
            return event
        }
        if (line.startsWith(':')) return null
        val field = line.substringBefore(':')
        val value = if (':' in line) line.substringAfter(':').removePrefix(" ") else ""
        when (field) {
            "event" -> eventName = value
            "data" -> data += value
        }
        return null
    }
}

internal fun decodeStreamEvent(protocol: ApiProtocol, event: SseEvent): DecodedEvent {
    if (event.data.isBlank()) return DecodedEvent()
    return decodeStreamPayload(protocol, parseStreamPayload(event.data), event.name)
}

private fun parseStreamPayload(data: String): JsonObject = try {
        Json.parseToJsonElement(data).jsonObject
    } catch (_: SerializationException) {
        throw OpenAIStreamException("The endpoint returned invalid stream JSON.")
    } catch (_: IllegalArgumentException) {
        throw OpenAIStreamException("The endpoint returned an invalid stream event.")
    }

private fun decodeStreamPayload(protocol: ApiProtocol, payload: JsonObject, name: String?): DecodedEvent {
    payload["error"]?.takeUnless { it == JsonNull }?.let { error ->
        val message = (error as? JsonObject)?.string("message") ?: "The API reported an error."
        return DecodedEvent(failure = OpenAIRequestException(message))
    }
    if (payload.string("type") == "error" || name == "error") {
        return DecodedEvent(failure = OpenAIRequestException(payload.string("message") ?: "The API reported a stream error."))
    }
    return when (protocol) {
        ApiProtocol.ChatCompletions -> decodeChatEvent(payload)
        ApiProtocol.Responses -> decodeResponseEvent(payload, name)
    }
}

private fun decodeChatEvent(payload: JsonObject): DecodedEvent {
    val choices = payload["choices"] as? JsonArray
        ?: throw OpenAIStreamException("Expected Chat Completions events. Check the API protocol.")
    if (choices.isEmpty()) return DecodedEvent() // Usage-only event.
    val choice = choices.first().jsonObject
    val delta = choice["delta"] as? JsonObject
    val text = listOfNotNull(delta?.string("content"), delta?.string("refusal"))
    val finish = choice.string("finish_reason")
    return DecodedEvent(
        deltas = text,
        completed = if (finish == "stop") payload else null,
        failure = if (finish != null && finish != "stop") incomplete(finish) else null,
        citations = parseCitations(delta?.get("annotations")),
        images = (delta?.get("images") as? JsonArray).orEmpty().mapIndexed { index, element ->
            val image = element.jsonObject
            val url = (image["image_url"] as? JsonObject)?.string("url")
                ?: throw OpenAIStreamException("The generated image did not include an image URL.")
            GeneratedImage("${payload.string("id")}:image:${choice["index"] ?: 0}:${image["index"] ?: index}", OpenAIImage(url))
        },
    )
}

private fun incomplete(reason: String) = IncompleteResponseException(
    when (reason) {
        "length", "max_output_tokens" -> "the model reached its output limit"
        "content_filter" -> "the provider filtered the response"
        "tool_calls", "function_call" -> "the model requested a tool this app does not execute"
        else -> reason
    },
)

private fun decodeResponseEvent(payload: JsonObject, name: String?): DecodedEvent =
    when (payload.string("type") ?: name) {
        "response.output_text.delta", "response.refusal.delta" ->
            DecodedEvent(deltas = listOfNotNull(payload.string("delta")))
        "response.completed" -> {
            val response = payload["response"] as? JsonObject
                ?: throw OpenAIStreamException("The completion event did not include response data.")
            val output = readResponseOutput(response)
            DecodedEvent(completed = response, citations = output.citations, images = output.images, finalText = output.text)
        }
        "response.output_item.done" -> DecodedEvent(images = listOfNotNull((payload["item"] as? JsonObject)?.let(::generatedImage)))
        "response.image_generation_call.in_progress", "response.image_generation_call.generating" ->
            DecodedEvent(imageStatus = "Generating image…", imageStatusChanged = true)
        "response.image_generation_call.completed" -> DecodedEvent(imageStatusChanged = true)
        "response.web_search_call.in_progress" -> DecodedEvent(searchStatus = "Preparing web search…")
        "response.web_search_call.searching" -> DecodedEvent(searchStatus = "Searching the web…")
        "response.web_search_call.completed" -> DecodedEvent(searchStatus = "Web search complete")
        "response.output_text.annotation.added" -> DecodedEvent(citations =
            payload["annotation"]?.let { parseCitations(JsonArray(listOf(it)), textOffset = null) }.orEmpty())
        "response.failed" -> {
            val response = payload["response"]?.jsonObject
            DecodedEvent(failure = OpenAIRequestException(
                (response?.get("error") as? JsonObject)?.string("message") ?: "The API response failed.",
            ))
        }
        "response.incomplete" -> {
            val response = payload["response"]?.jsonObject
            DecodedEvent(failure = incomplete(
                (response?.get("incomplete_details") as? JsonObject)?.string("reason") ?: "generation stopped early",
            ))
        }
        "response.cancelled" -> {
            val response = payload.getValue("response").jsonObject
            val output = readResponseOutput(response)
            DecodedEvent(failure = ResponseCancelledException(response), images = output.images, citations = output.citations, finalText = output.text)
        }
        null -> throw OpenAIStreamException("Expected Responses events. Check the API protocol.")
        else -> DecodedEvent() // Other lifecycle events do not change the answer.
    }

fun readResponseOutput(response: JsonObject): ResponseOutput {
    var textOffset = 0
    val text = StringBuilder()
    val citations = mutableListOf<WebCitation>()
    val items = (response["output"] as? JsonArray).orEmpty()
    items.filter { it.jsonObject.string("type") == "message" }.forEach { item ->
        (item.jsonObject["content"] as? JsonArray).orEmpty().forEach { content ->
            val part = content.jsonObject
            citations += parseCitations(part["annotations"], textOffset)
            val value = (part.string("text") ?: part.string("refusal")).orEmpty()
            text.append(value); textOffset += value.length
        }
    }
    return ResponseOutput(text.toString(), items.mapNotNull { generatedImage(it.jsonObject) }, citations)
}

fun isTransientResponseError(error: Exception): Boolean = error is IOException || error is HttpRequestTimeoutException ||
    (error is OpenAIRequestException && (error.httpStatus in listOf(408, 429) || (error.httpStatus ?: 0) >= 500))

private fun generatedImage(item: JsonObject): GeneratedImage? {
    if (item.string("type") != "image_generation_call") return null
    val result = item.string("result")?.takeIf { it.isNotEmpty() } ?: return null
    val id = item.string("id") ?: throw OpenAIStreamException("The generated image did not include an ID.")
    val mime = when (item.string("output_format") ?: "png") {
        "png" -> "image/png"
        "jpeg", "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> throw OpenAIStreamException("The generated image uses an unsupported format.")
    }
    return GeneratedImage(id, OpenAIImage("data:$mime;base64,$result"))
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun parseCitations(value: JsonElement?, textOffset: Int? = 0): List<WebCitation> = (value as? JsonArray).orEmpty().mapNotNull { element ->
    val annotation = element as? JsonObject ?: return@mapNotNull null
    if (annotation.string("type") != "url_citation") return@mapNotNull null
    val citation = annotation["url_citation"] as? JsonObject ?: annotation
    val url = citation.string("url") ?: return@mapNotNull null
    val uri = try { java.net.URI(url) } catch (_: java.net.URISyntaxException) { return@mapNotNull null }
    if (uri.scheme !in listOf("https", "http") || uri.host.isNullOrBlank()) return@mapNotNull null
    WebCitation(url, citation.string("title") ?: uri.host,
        textOffset?.let { offset -> (citation["start_index"] as? JsonPrimitive)?.intOrNull?.plus(offset) },
        textOffset?.let { offset -> (citation["end_index"] as? JsonPrimitive)?.intOrNull?.plus(offset) })
}
