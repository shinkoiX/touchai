package app.touchai.core.openai

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

interface RequestLogSink {
    suspend fun started(record: RequestLogRecord)
    suspend fun finished(record: RequestLogRecord)
}

data class RequestLogRecord(val id: String, val startedAt: Long, val data: JsonObject) {
    val status: String get() = data.getValue("status").jsonPrimitive.content
    fun json(): JsonObject = JsonObject(data + mapOf("id" to JsonPrimitive(id), "startedAt" to JsonPrimitive(startedAt)))
}

/** Records request metadata and full response output, with credentials redacted. */
class LoggingChatClient(private val client: ChatClient, private val logs: RequestLogSink) : ChatClient {
    override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        val record = RequestLogRecord(UUID.randomUUID().toString(), System.currentTimeMillis(), buildJsonObject {
            put("status", "Running")
            put("purpose", request.purpose.name)
            request.presetName?.let { put("preset", it) }
            put("endpoint", buildRequestUrl(config))
            put("protocol", config.protocol.label)
            put("authentication", config.authentication.label)
            put("model", config.model)
            config.reasoningEffort?.let { put("reasoningEffort", it) }
            put("webSearch", config.webSearch)
            put("timeoutMillis", config.timeoutMillis)
            put("backgroundResponses", config.protocol == ApiProtocol.Responses && config.backgroundResponses)
            request.resumeResponseId?.let { put("responseId", it) }
            put("instructions", request.instructions)
            put("messageCount", request.messages.size)
            put("imageCount", request.messages.sumOf { it.images.size })
            put("inputCharacters", request.instructions.length + request.messages.sumOf { it.text.length })
            putJsonArray("messages") {
                request.messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role.value)
                    put("text", message.text)
                    put("imageCount", message.images.size)
                    putJsonArray("imageDetail") { message.images.forEach { add(it.detail) } }
                }) }
            }
        })
        var status = "Completed"
        var failure: Exception? = null
        var firstTextMillis: Long? = null
        var outputCharacters = 0
        var textChunks = 0
        val outputText = StringBuilder()
        val images = linkedMapOf<String, GeneratedImage>()
        var responsePayload: JsonElement? = null
        val responseEvents = mutableListOf<JsonElement>()
        var responseId = request.resumeResponseId
        // Finish is non-cancellable so Stop records the partial result as well.
        try {
            logs.started(record.copy(data = redactLogCredentials(record.data, config.apiKey).jsonObject))
            client.stream(config, request).collect { event ->
                if (event is OpenAIStreamEvent.TextDelta) {
                    if (firstTextMillis == null && event.text.isNotEmpty()) firstTextMillis = elapsed()
                    outputCharacters += event.text.length
                    textChunks++
                    outputText.append(event.text)
                }
                when (event) {
                    is OpenAIStreamEvent.TextSnapshot -> {
                        outputText.clear(); outputText.append(event.text); outputCharacters = event.text.length
                        if (firstTextMillis == null && event.text.isNotEmpty()) firstTextMillis = elapsed()
                    }
                    is OpenAIStreamEvent.ResponseCheckpoint -> responseId = event.id
                    is OpenAIStreamEvent.ImageGenerated -> images[event.value.id] = event.value
                    is OpenAIStreamEvent.Completed -> responsePayload = event.response
                    is OpenAIStreamEvent.ResponsePayload -> responseEvents += event.value
                    else -> Unit
                }
                emit(event)
            }
        } catch (error: CancellationException) {
            status = if (error is UserRequestedCancellation) "Cancelled" else "Interrupted"
            if (error is UserRequestedCancellation) error.response?.let { response ->
                responsePayload = response
                val output = readResponseOutput(response)
                if (output.text.isNotEmpty() || response["status"]?.jsonPrimitive?.content == "completed") {
                    outputText.clear(); outputText.append(output.text); outputCharacters = output.text.length
                }
                output.images.forEach { images[it.id] = it }
                if (response["status"]?.jsonPrimitive?.content == "completed") status = "Completed"
            }
            throw error
        } catch (error: Exception) {
            status = when (error) {
                is IncompleteResponseException -> "Incomplete"
                is ResponseInterruptedException -> "Interrupted"
                is ResponseCancelledException -> "Cancelled"
                else -> "Failed"
            }
            failure = error
            throw error
        } finally {
            val result = buildJsonObject {
                put("status", status)
                responseId?.let { put("responseId", it) }
                put("durationMillis", elapsed())
                firstTextMillis?.let { put("firstTextMillis", it) }
                put("outputCharacters", outputCharacters)
                put("textChunks", textChunks)
                put("outputImageCount", images.size)
                putJsonObject("output") {
                    put("text", outputText.toString())
                    putJsonArray("images") { images.values.forEach { generated -> add(buildJsonObject {
                        put("id", generated.id); put("url", generated.image.url)
                    }) } }
                    responsePayload?.let { put("response", it) }
                    put("events", JsonArray(responseEvents))
                    failure?.message?.let { put("error", it) }
                }
                failure?.let { put("errorType", it.javaClass.simpleName) }
                (failure as? OpenAIRequestException)?.httpStatus?.let { put("httpStatus", it) }
            }
            withContext(NonCancellable) {
                logs.finished(record.copy(data = redactLogCredentials(JsonObject(record.data + result), config.apiKey).jsonObject))
            }
        }
    }
}

internal fun redactLogCredentials(value: JsonElement, apiKey: String): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { (key, item) ->
        if (key.lowercase().replace("_", "").replace("-", "") in setOf(
                "authorization", "apikey", "accesstoken", "refreshtoken", "idtoken", "password", "secret", "cookie", "setcookie")) JsonPrimitive("[REDACTED]")
        else redactLogCredentials(item, apiKey)
    })
    is JsonArray -> JsonArray(value.map { redactLogCredentials(it, apiKey) })
    is JsonPrimitive -> if (value.isString && apiKey.isNotEmpty()) JsonPrimitive(value.content.replace(apiKey, "[REDACTED]")) else value
}
