package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenAIModelStreamTest {
    private lateinit var server: MockWebServer
    private lateinit var http: HttpClient
    private lateinit var model: OpenAIModel
    private val request = OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Hello")))

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        http = HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout)
            engine { config { retryOnConnectionFailure(false) } }
        }
        model = OpenAIModel(http)
    }
    @After fun tearDown() { http.close(); server.shutdown() }

    private fun config(protocol: ApiProtocol = ApiProtocol.ChatCompletions) = OpenAIModelConfig(
        apiKey = "test-only-key", model = "test", baseUrl = server.url("/v1").toString(),
        protocol = protocol, timeoutMillis = 5_000,
    )
    private fun enqueue(body: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body))
    }
    private fun sse(json: String) = "data: $json\n\n"
    private fun chat(text: String, finish: String? = null): String = sse(buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject {
            put("index", 0)
            putJsonObject("delta") { put("content", text) }
            put("finish_reason", finish?.let(::JsonPrimitive) ?: JsonNull)
        }) }
    }.toString())

    @Test fun streamsFragmentedUnicodeAndSendsAuthenticatedPost() = runBlocking {
        val stream = ": heartbeat\r\n\r\n" + (chat("Hello 世界 👋") + chat("", "stop")).replace("\n", "\r\n")
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(stream, 1))
        val result = model.complete(config(), request)
        assertEquals("Hello 世界 👋", result.text)
        val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", sent.method)
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer test-only-key", sent.getHeader("Authorization"))
        assertEquals("text/event-stream", sent.getHeader("Accept"))
        assertEquals("test", Json.parseToJsonElement(sent.body.readUtf8()).jsonObject.getValue("model").jsonPrimitive.content)
    }

    @Test fun responsesStreamsRefusalTextAndCompletes() = runBlocking {
        enqueue(sse("""{"type":"response.created","response":{"id":"test"}}""") +
            sse("""{"type":"response.refusal.delta","delta":"Cannot help with that."}""") +
            sse("""{"type":"response.completed","response":{"id":"test","status":"completed"}}"""))
        assertEquals("Cannot help with that.", model.complete(config(ApiProtocol.Responses), request).text)
        assertEquals("/v1/responses", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test fun chatRefusalIsVisible() = runBlocking {
        enqueue(sse("""{"choices":[{"delta":{"refusal":"Cannot comply."},"finish_reason":"stop"}]}"""))
        assertEquals("Cannot comply.", model.complete(config(), request).text)
    }

    @Test fun incompleteResponseRetainsDeltasAndReportsTheReason() = runBlocking {
        enqueue(sse("""{"type":"response.output_text.delta","delta":"Partial"}""") +
            sse("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""))
        val events = mutableListOf<OpenAIStreamEvent>()
        val error = runCatching { model.stream(config(ApiProtocol.Responses), request).toList(events) }.exceptionOrNull()
        assertTrue(error is IncompleteResponseException)
        assertTrue(error!!.message!!.contains("output limit"))
        assertEquals(listOf(OpenAIStreamEvent.TextDelta("Partial")), events)
    }

    @Test fun lengthFinishKeepsTextFromTheSameFinalChunk() = runBlocking {
        enqueue(chat("Last words", "length"))
        val events = mutableListOf<OpenAIStreamEvent>()
        val error = runCatching { model.stream(config(), request).toList(events) }.exceptionOrNull()
        assertTrue(error is IncompleteResponseException)
        assertEquals(listOf(OpenAIStreamEvent.TextDelta("Last words")), events)
    }

    @Test fun explicitErrorAndNestedFailureAreReported() = runBlocking {
        for (payload in listOf(
            """{"type":"error","message":"Provider unavailable"}""",
            """{"type":"response.failed","response":{"error":{"message":"Provider unavailable"}}}""",
            """{"error":{"message":"Provider unavailable"}}""",
        )) {
            enqueue(sse(payload))
            val error = runCatching { model.complete(config(ApiProtocol.Responses), request) }.exceptionOrNull()
            assertTrue(error is OpenAIRequestException)
            assertEquals("Provider unavailable", error!!.message)
        }
        assertEquals(3, server.requestCount)
    }

    @Test fun endOfFileAndDoneWithoutCompletionAreNotSuccess() = runBlocking {
        for (ending in listOf("", "data: [DONE]\n\n")) {
            enqueue(chat("Partial") + ending)
            assertTrue(runCatching { model.complete(config(), request) }.exceptionOrNull() is IncompleteResponseException)
        }
    }

    @Test fun unframedTerminalEventDoesNotCountAsCompleted() = runBlocking {
        enqueue(chat("Partial") + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
        assertTrue(runCatching { model.complete(config(), request) }.exceptionOrNull() is IncompleteResponseException)
    }

    @Test fun httpErrorsContainStatusAndProviderMessageWithoutRetrying() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid key"}}"""))
        val error = runCatching { model.complete(config(), request) }.exceptionOrNull()
        assertEquals("HTTP 401: Invalid key", error!!.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun malformedAndMismatchedStreamsFailClearly() = runBlocking {
        enqueue(sse("not json"))
        assertTrue(runCatching { model.complete(config(), request) }.exceptionOrNull() is OpenAIStreamException)
        enqueue(sse("""{"type":"response.created"}"""))
        assertTrue(runCatching { model.complete(config(), request) }.exceptionOrNull()!!.message!!.contains("protocol"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{}"))
        assertTrue(runCatching { model.complete(config(), request) }.exceptionOrNull() is OpenAIStreamException)
        assertEquals(3, server.requestCount)
    }

    @Test fun namedErrorEventsAreReportedForBothProtocols() = runBlocking {
        ApiProtocol.entries.forEach { protocol ->
            enqueue("event: error\ndata: {\"message\":\"Stream failed\"}\n\n")
            assertEquals("Stream failed", runCatching { model.complete(config(protocol), request) }.exceptionOrNull()!!.message)
        }
    }

    @Test fun searchStatusAndCitationsAreEmittedWithTheAnswer() = runBlocking {
        enqueue(sse("""{"type":"response.web_search_call.searching"}""") +
            sse("""{"type":"response.output_text.delta","delta":"A fact [1]"}""") +
            sse("""{"type":"response.output_text.annotation.added","annotation":{"type":"url_citation","url":"https://example.com/source","title":"Source","start_index":7,"end_index":10}}""") +
            sse("""{"type":"response.completed","response":{"output":[{"type":"message","content":[{"type":"output_text","text":"A fact [1]","annotations":[{"type":"url_citation","url":"https://example.com/source","title":"Source","start_index":7,"end_index":10}]}]}]}}"""))
        val events = model.stream(config(ApiProtocol.Responses), request).toList()
        assertTrue(events.any { it is OpenAIStreamEvent.SearchStatus })
        val source = events.filterIsInstance<OpenAIStreamEvent.Citation>().last().source
        assertEquals("https://example.com/source", source.url)
        assertEquals(7, source.startIndex)
        assertTrue(events.last() is OpenAIStreamEvent.Completed)
    }

    @Test fun chatCitationsSupportTheNestedSchemaAndRejectNonWebLinks() = runBlocking {
        enqueue(sse("""{"choices":[{"delta":{"content":"Fact","annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com/source","title":"Source","start_index":0,"end_index":4}},{"type":"url_citation","url_citation":{"url":"javascript:alert(1)","title":"Invalid"}}]},"finish_reason":"stop"}]}"""))
        val citations = model.stream(config(), request).toList().filterIsInstance<OpenAIStreamEvent.Citation>()
        assertEquals(1, citations.size)
        assertEquals("https://example.com/source", citations.single().source.url)
    }

    @Test fun cancellingAWaitingRequestClosesItsCoroutine() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job = launch { model.stream(config(), request).collect {} }
        val sent = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        assertNotNull(sent)
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(1, server.requestCount)
    }

    @Test fun timeoutEndsAWaitingRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val error = runCatching { model.complete(config().copy(timeoutMillis = 200), request) }.exceptionOrNull()
        assertTrue(error is io.ktor.client.plugins.HttpRequestTimeoutException)
    }

    @Test fun sseParserJoinsDataLinesAndIgnoresComments() {
        val parser = SseParser()
        assertNull(parser.accept(": comment"))
        assertNull(parser.accept("event: response.output_text.delta"))
        assertNull(parser.accept("data: {\"type\":\"response.output_text.delta\","))
        assertNull(parser.accept("data: \"delta\":\"hello\"}"))
        val event = parser.accept("")!!
        assertEquals("response.output_text.delta", event.name)
        assertEquals(listOf("hello"), decodeStreamEvent(ApiProtocol.Responses, event).deltas)
    }

    @Test fun byteParserPreservesEveryUnicodeBoundaryAndAllSseLineEndings() {
        for (ending in listOf("\n", "\r\n", "\r")) {
            val parser = SseByteParser()
            val input = "data: {\"choices\":[{\"delta\":{\"content\":\"世界 👋 café\"},\"finish_reason\":\"stop\"}]}$ending$ending".toByteArray(Charsets.UTF_8)
            val events = input.flatMap { parser.accept(byteArrayOf(it), 1) }
            assertEquals(listOf("世界 👋 café"), decodeStreamEvent(ApiProtocol.ChatCompletions, events.single()).deltas)
        }
    }
}
