package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var http: HttpClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        http = HttpClient(OkHttp) { expectSuccess = false; followRedirects = false; install(HttpTimeout) }
    }
    @After fun tearDown() { http.close(); server.shutdown() }

    private fun config(webSearch: Boolean = true) = OpenAIModelConfig(apiKey = "test-only-key", model = "models/gemini-test",
        baseUrl = server.url("/v1beta").toString(), protocol = ApiProtocol.Gemini, reasoningEffort = "low", webSearch = webSearch, timeoutMillis = 5_000)

    private fun event(type: String, json: String) = "event: $type\ndata: $json\n\n"

    @Test fun requestUsesInteractionsStepsGoogleSearchAndTheGoogleKeyHeader() {
        val body = buildRequestBody(config(), OpenAIRequest(listOf(
            ChatMessage(MessageRole.User, "What is this?", listOf(OpenAIImage("data:image/jpeg;base64,aW1hZ2U=", "high"))),
            ChatMessage(MessageRole.Assistant, "A cat."),
            ChatMessage(MessageRole.User, "Why?"),
        ), "Be brief."))
        assertEquals("gemini-test", body.getValue("model").jsonPrimitive.content)
        assertEquals(false, body.getValue("store").jsonPrimitive.boolean)
        assertEquals("Be brief.", body.getValue("system_instruction").jsonPrimitive.content)
        assertEquals("low", body.getValue("generation_config").jsonObject.getValue("thinking_level").jsonPrimitive.content)
        assertEquals(buildJsonArray { add(buildJsonObject { put("type", "google_search") }) }, body["tools"])
        val input = body.getValue("input").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user_input", "model_output", "user_input"), input.map { it.getValue("type").jsonPrimitive.content })
        assertEquals(buildJsonObject { put("type", "image"); put("mime_type", "image/jpeg"); put("data", "aW1hZ2U="); put("resolution", "high") },
            input[0].getValue("content").jsonArray[1])
        assertEquals("A cat.", input[1].getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
        assertFalse(buildRequestBody(config(webSearch = false), OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Hi")))).containsKey("tools"))
        assertTrue(buildRequestUrl(config()).endsWith("/v1beta/interactions"))
        assertFalse(OpenAIModelConfig(protocol = ApiProtocol.Gemini, backgroundResponses = true).recoverResponses)
    }

    @Test fun streamsTextSearchStatusAndCitationsUntilCompletion() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            event("interaction.created", """{"interaction":{"id":"i1","status":"in_progress"},"event_type":"interaction.created"}""") +
            event("step.start", """{"index":0,"step":{"type":"google_search_call"},"event_type":"step.start"}""") +
            event("step.stop", """{"index":0,"event_type":"step.stop"}""") +
            event("step.start", """{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""") +
            event("step.delta", """{"index":1,"delta":{"type":"text","text":"Hello, "},"event_type":"step.delta"}""") +
            event("step.delta", """{"index":1,"delta":{"type":"text","text":"world"},"event_type":"step.delta"}""") +
            event("step.delta", """{"index":1,"delta":{"type":"text_annotation_delta","annotations":[{"type":"url_citation","url":"https://example.com/a","title":"A","start_index":0,"end_index":5}]},"event_type":"step.delta"}""") +
            event("interaction.completed", """{"interaction":{"id":"i1","status":"completed","usage":{"total_tokens":3}},"event_type":"interaction.completed"}""") +
            "event: done\ndata: [DONE]\n\n"))
        val events = OpenAIModel(http).stream(config(), OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Hi")))).toList()
        assertEquals("Hello, world", events.filterIsInstance<OpenAIStreamEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.contains(OpenAIStreamEvent.SearchStatus("Searching the web…")))
        assertEquals(listOf(WebCitation("https://example.com/a", "A")), events.filterIsInstance<OpenAIStreamEvent.Citation>().map { it.source })
        assertEquals("i1", events.filterIsInstance<OpenAIStreamEvent.Completed>().single().response.getValue("id").jsonPrimitive.content)
        val sent = server.takeRequest()
        assertEquals("/v1beta/interactions", sent.path)
        assertEquals("test-only-key", sent.getHeader("x-goog-api-key"))
        assertNull(sent.getHeader("Authorization"))
    }

    @Test fun failuresShowGoogleMessagesIncludingArrayWrappedErrors() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""[{"error":{"code":400,"message":"Unknown name \"x\"."}}]"""))
        val http400 = runCatching { OpenAIModel(http).complete(config(), OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Hi")))) }.exceptionOrNull()
        assertEquals("HTTP 400: Unknown name \"x\".", http400?.message)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            event("error", """{"error":{"code":"gateway_timeout","message":"Timed out"},"event_type":"error"}""")))
        val midStream = runCatching { OpenAIModel(http).complete(config(), OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Hi")))) }.exceptionOrNull()
        assertEquals("Timed out", midStream?.message)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            event("interaction.completed", """{"interaction":{"id":"i2","status":"failed","errors":[{"code":"safety","message":"Blocked"}]},"event_type":"interaction.completed"}""")))
        val failed = runCatching { OpenAIModel(http).complete(config(), OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Hi")))) }.exceptionOrNull()
        assertEquals("Blocked", failed?.message)
    }

    @Test fun modelListUsesTheGoogleKeyAndKeepsContentModelsWithoutThePrefix() = runBlocking {
        var key: String? = null
        var auth: String? = null
        var url: String? = null
        val mock = HttpClient(MockEngine { request ->
            key = request.headers["x-goog-api-key"]; auth = request.headers[HttpHeaders.Authorization]; url = request.url.toString()
            respond("""{"models":[{"name":"models/gemini-b","displayName":"Gemini B","supportedGenerationMethods":["generateContent"]},""" +
                """{"name":"models/embedding-1","supportedGenerationMethods":["embedContent"]}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false }
        val models = ApiModelCatalog(mock).models(GeminiBaseUrl, "test-only-key", ApiProtocol.Gemini)
        assertEquals(listOf(AvailableModel("gemini-b", "Gemini B")), models)
        assertEquals("test-only-key", key)
        assertNull(auth)
        assertEquals("$GeminiBaseUrl/models?pageSize=1000", url)
    }
}
