package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

class OpenAIModel(private val httpClient: HttpClient) : ChatClient {
    override fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent> = flow {
        httpClient.preparePost(buildRequestUrl(config)) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            accept(ContentType.Text.EventStream)
            contentType(ContentType.Application.Json)
            timeout { requestTimeoutMillis = config.timeoutMillis }
            setBody(buildRequestBody(config, request).toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val body = response.bodyAsText()
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
                throw OpenAIStreamException("The endpoint did not return an event stream. Check the API protocol.")
            }

            val parser = SseByteParser()
            val channel = response.bodyAsChannel()
            val bytes = ByteArray(8192)
            var completed = false
            streamLoop@ while (!completed) {
                currentCoroutineContext().ensureActive()
                val count = channel.readAvailable(bytes, 0, bytes.size)
                if (count == -1) break
                for (event in parser.accept(bytes, count)) {
                    if (event.data == "[DONE]") break@streamLoop
                    val decoded = decodeStreamEvent(config.protocol, event)
                    decoded.deltas.forEach { emit(OpenAIStreamEvent.TextDelta(it)) }
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
                throw IncompleteResponseException("the connection ended before completion was confirmed")
            }
        }
    }

    suspend fun complete(config: OpenAIModelConfig, request: OpenAIRequest): OpenAICompletion {
        val text = StringBuilder()
        var response: JsonObject? = null
        stream(config, request).collect { event ->
            when (event) {
                is OpenAIStreamEvent.TextDelta -> text.append(event.text)
                is OpenAIStreamEvent.Completed -> response = event.response
                is OpenAIStreamEvent.SearchStatus, is OpenAIStreamEvent.Citation -> Unit
            }
        }
        return OpenAICompletion(text.toString(), response!!)
    }
}

internal data class SseEvent(val name: String?, val data: String)
internal data class DecodedEvent(
    val deltas: List<String> = emptyList(),
    val completed: JsonObject? = null,
    val failure: Exception? = null,
    val searchStatus: String? = null,
    val citations: List<WebCitation> = emptyList(),
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
    val payload = try {
        Json.parseToJsonElement(event.data).jsonObject
    } catch (_: SerializationException) {
        throw OpenAIStreamException("The endpoint returned invalid stream JSON.")
    } catch (_: IllegalArgumentException) {
        throw OpenAIStreamException("The endpoint returned an invalid stream event.")
    }
    payload["error"]?.takeUnless { it == JsonNull }?.let { error ->
        val message = (error as? JsonObject)?.string("message") ?: "The API reported an error."
        return DecodedEvent(failure = OpenAIRequestException(message))
    }
    if (payload.string("type") == "error" || event.name == "error") {
        return DecodedEvent(failure = OpenAIRequestException(payload.string("message") ?: "The API reported a stream error."))
    }
    return when (protocol) {
        ApiProtocol.ChatCompletions -> decodeChatEvent(payload)
        ApiProtocol.Responses -> decodeResponseEvent(payload, event.name)
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
            var textOffset = 0
            val citations = (response["output"] as? JsonArray).orEmpty().flatMap { item ->
                (item.jsonObject["content"] as? JsonArray).orEmpty().flatMap { content ->
                    val part = content.jsonObject
                    val sources = parseCitations(part["annotations"], textOffset)
                    textOffset += (part.string("text") ?: part.string("refusal")).orEmpty().length
                    sources
                }
            }
            DecodedEvent(completed = response, citations = citations)
        }
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
        null -> throw OpenAIStreamException("Expected Responses events. Check the API protocol.")
        else -> DecodedEvent() // Lifecycle and other non-text events do not change the answer.
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
