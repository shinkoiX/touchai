package app.touchai.core.openai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoggingChatClientTest {
    private class Sink : RequestLogSink {
        val starts = mutableListOf<RequestLogRecord>()
        val finishes = mutableListOf<RequestLogRecord>()
        override suspend fun started(record: RequestLogRecord) { starts += record }
        override suspend fun finished(record: RequestLogRecord) { yield(); finishes += record }
    }
    private fun client(block: () -> Flow<OpenAIStreamEvent>) = object : ChatClient {
        override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = block()
    }
    private val config = OpenAIModelConfig(apiKey = "secret-api-key", model = "test-model", webSearch = false)
    private val request = OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Explain the heading",
        listOf(OpenAIImage("data:image/png;base64,secret-image-bytes")))), "Be concise")

    @Test fun completionRecordsMessagesAndMetricsWithoutCredentialsImagesOrResponseBody() = runTest {
        val sink = Sink()
        val events = listOf(OpenAIStreamEvent.TextDelta("Private answer"), OpenAIStreamEvent.Completed(buildJsonObject { put("providerSecret", "hidden-response") }))
        val logged = LoggingChatClient(client { flow { events.forEach { emit(it) } } }, sink)
        assertEquals(events, logged.stream(config, request).toList())
        assertEquals("Running", sink.starts.single().status)
        val result = sink.finishes.single()
        assertEquals(sink.starts.single().id, result.id)
        assertEquals("Completed", result.status)
        assertEquals(14, result.data.getValue("outputCharacters").jsonPrimitive.int)
        assertEquals(1, result.data.getValue("imageCount").jsonPrimitive.int)
        assertTrue(result.data.getValue("durationMillis").jsonPrimitive.long >= 0)
        assertNotNull(result.data["firstTextMillis"])
        val json = result.json().toString()
        assertTrue(json.contains("Explain the heading"))
        assertTrue(json.contains("Be concise"))
        listOf("secret-api-key", "secret-image-bytes", "data:image", "Private answer", "hidden-response", "Authorization").forEach { assertFalse(json.contains(it)) }
    }

    @Test fun failureLogsStatusButNeverRawProviderError() = runTest {
        val sink = Sink()
        val error = OpenAIRequestException("Echoed secret-api-key and secret-image-bytes", 401)
        val logged = LoggingChatClient(client { flow { throw error } }, sink)
        try { logged.stream(config, request).toList(); fail("Expected failure") }
        catch (actual: OpenAIRequestException) { assertSame(error, actual) }
        val result = sink.finishes.single()
        assertEquals("Failed", result.status)
        assertEquals(401, result.data.getValue("httpStatus").jsonPrimitive.int)
        assertFalse(result.json().toString().contains("Echoed"))
        assertFalse(result.json().toString().contains("secret-api-key"))
    }

    @Test fun incompleteAndRetriedRequestsHaveSeparateRecords() = runTest {
        val sink = Sink()
        var attempt = 0
        val logged = LoggingChatClient(client { flow {
            emit(OpenAIStreamEvent.TextDelta("Partial"))
            if (attempt++ == 0) throw IncompleteResponseException("output limit")
            emit(OpenAIStreamEvent.Completed(JsonObject(emptyMap())))
        } }, sink)
        try { logged.stream(config, request).toList(); fail("Expected incomplete") }
        catch (_: IncompleteResponseException) { }
        logged.stream(config, request.copy(purpose = RequestPurpose.Retry)).toList()
        assertEquals(listOf("Incomplete", "Completed"), sink.finishes.map { it.status })
        assertNotEquals(sink.finishes[0].id, sink.finishes[1].id)
        assertEquals("Retry", sink.finishes.last().data.getValue("purpose").jsonPrimitive.content)
    }

    @Test fun stopPersistsCancelledEvenThoughCollectorIsCancelled() = runTest {
        val sink = Sink()
        val logged = LoggingChatClient(client { flow { emit(OpenAIStreamEvent.TextDelta("Partial")); awaitCancellation() } }, sink)
        val job = launch { logged.stream(config, request).toList() }
        runCurrent()
        job.cancelAndJoin()
        assertEquals("Cancelled", sink.finishes.single().status)
        assertEquals(7, sink.finishes.single().data.getValue("outputCharacters").jsonPrimitive.int)
    }

    @Test fun loggingContextIsNotSentToProvider() {
        val original = buildRequestBody(config, request)
        assertEquals(original, buildRequestBody(config, request.copy(purpose = RequestPurpose.ConnectionTest, presetName = "Private label")))
    }
}
