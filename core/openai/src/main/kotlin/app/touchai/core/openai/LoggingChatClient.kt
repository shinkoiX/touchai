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

/** Records only an explicit list of diagnostic fields; never serializes config, images, or exceptions. */
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
            put("model", config.model)
            config.reasoningEffort?.let { put("reasoningEffort", it) }
            put("webSearch", config.webSearch)
            put("timeoutMillis", config.timeoutMillis)
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
        // Finish is non-cancellable so Stop records the partial result as well.
        try {
            logs.started(record)
            client.stream(config, request).collect { event ->
                if (event is OpenAIStreamEvent.TextDelta) {
                    if (firstTextMillis == null && event.text.isNotEmpty()) firstTextMillis = elapsed()
                    outputCharacters += event.text.length
                    textChunks++
                }
                emit(event)
            }
        } catch (error: CancellationException) {
            status = "Cancelled"
            throw error
        } catch (error: Exception) {
            status = if (error is IncompleteResponseException) "Incomplete" else "Failed"
            failure = error
            throw error
        } finally {
            val result = buildJsonObject {
                put("status", status)
                put("durationMillis", elapsed())
                firstTextMillis?.let { put("firstTextMillis", it) }
                put("outputCharacters", outputCharacters)
                put("textChunks", textChunks)
                // Provider error text can echo request bodies or credentials.
                failure?.let { put("errorType", it.javaClass.simpleName) }
                (failure as? OpenAIRequestException)?.httpStatus?.let { put("httpStatus", it) }
            }
            withContext(NonCancellable) { logs.finished(record.copy(data = JsonObject(record.data + result))) }
        }
    }
}
