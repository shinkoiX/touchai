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

    @Test fun interruptedBackgroundStreamRetrievesTheSameResponseWithoutAnotherPost() = runBlocking {
        enqueue(sse("""{"type":"response.created","response":{"id":"resp_saved","background":true}}""") +
            sse("""{"type":"response.output_text.delta","delta":"Partial"}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"resp_saved","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Full answer"}]}]}"""))
        val result = model.complete(config(ApiProtocol.Responses).copy(backgroundResponses = true), request)
        assertEquals("Full answer", result.text)
        val creation = server.takeRequest()!!
        assertEquals("POST", creation.method)
        assertTrue(Json.parseToJsonElement(creation.body.readUtf8()).jsonObject.getValue("background").jsonPrimitive.boolean)
        val recovery = server.takeRequest()!!
        assertEquals("GET", recovery.method)
        assertEquals("/v1/responses/resp_saved", recovery.path)
        assertEquals(2, server.requestCount)
    }

    @Test fun restartingWithASavedIdOnlyRetrievesAndRetriesTransientGetFailures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"resp_saved","status":"completed","output":[]}"""))
        model.complete(config(ApiProtocol.Responses).copy(backgroundResponses = true), request.copy(resumeResponseId = "resp_saved"))
        repeat(2) {
            val sent = server.takeRequest()!!
            assertEquals("GET", sent.method)
            assertEquals("/v1/responses/resp_saved", sent.path)
        }
    }

    @Test fun lostCreationWithoutAnIdIsNotResubmitted() = runBlocking {
        enqueue(": keepalive\n\n")
        val error = runCatching { model.complete(config(ApiProtocol.Responses).copy(backgroundResponses = true), request) }.exceptionOrNull()
        assertTrue(error is ResponseInterruptedException)
        assertTrue(error!!.message!!.contains("response ID"))
        assertEquals(1, server.requestCount)
    }

    @Test fun manualCancellationCallsTheResponseCancelEndpoint() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"id":"resp_saved","status":"cancelled"}"""))
        assertEquals("cancelled", model.cancelResponse(config(ApiProtocol.Responses), "resp_saved").getValue("status").jsonPrimitive.content)
        val sent = server.takeRequest()!!
        assertEquals("POST", sent.method)
        assertEquals("/v1/responses/resp_saved/cancel", sent.path)
    }

    @Test fun downstreamFailuresDoNotTriggerRecoveryRequests() = runBlocking {
        enqueue(sse("""{"type":"response.created","response":{"id":"resp_saved"}}""") +
            sse("""{"type":"response.output_text.delta","delta":"Text"}"""))
        val failure = java.io.IOException("Consumer failed")
        val error = runCatching {
            model.stream(config(ApiProtocol.Responses).copy(backgroundResponses = true), request).collect {
                if (it is OpenAIStreamEvent.TextDelta) throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, error)
        assertEquals(1, server.requestCount)
    }

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
        assertEquals(listOf(OpenAIStreamEvent.TextDelta("Partial")), events.filterIsInstance<OpenAIStreamEvent.TextDelta>())
    }

    @Test fun lengthFinishKeepsTextFromTheSameFinalChunk() = runBlocking {
        enqueue(chat("Last words", "length"))
        val events = mutableListOf<OpenAIStreamEvent>()
        val error = runCatching { model.stream(config(), request).toList(events) }.exceptionOrNull()
        assertTrue(error is IncompleteResponseException)
        assertEquals(listOf(OpenAIStreamEvent.TextDelta("Last words")), events.filterIsInstance<OpenAIStreamEvent.TextDelta>())
    }

    @Test fun chatImageOnlyRepliesAreEmittedOnceAndPreserveFullPayloads() = runBlocking {
        val imageChunk = """{"id":"chat-image-test","choices":[{"index":0,"delta":{"role":"assistant","images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,aW1hZ2U="},"index":0}]},"finish_reason":null}]}"""
        enqueue(sse(imageChunk) + sse(imageChunk) + chat("", "stop"))
        val events = model.stream(config(), request).toList()
        val generated = events.filterIsInstance<OpenAIStreamEvent.ImageGenerated>().single().value
        assertEquals("data:image/png;base64,aW1hZ2U=", generated.image.url)
        assertTrue(events.filterIsInstance<OpenAIStreamEvent.TextDelta>().all { it.text.isEmpty() })
        assertEquals(1, events.filterIsInstance<OpenAIStreamEvent.Completed>().size)
        assertEquals(Json.parseToJsonElement(imageChunk), events.filterIsInstance<OpenAIStreamEvent.ResponsePayload>().first().value)
    }

    @Test fun chatImagesWithACaptionSurviveCompletion() = runBlocking {
        enqueue(sse("""{"id":"chat-image-test","choices":[{"index":0,"delta":{"content":"Two pictures","images":[{"image_url":{"url":"data:image/png;base64,b25l"}},{"image_url":{"url":"data:image/webp;base64,dHdv"}}]},"finish_reason":"stop"}]}"""))
        val result = model.complete(config(), request)
        assertEquals("Two pictures", result.text)
        assertEquals(listOf("data:image/png;base64,b25l", "data:image/webp;base64,dHdv"), result.images.map { it.image.url })
        assertEquals(2, result.images.map { it.id }.toSet().size)
    }

    @Test fun imageOnlyResponsesAreEmittedOnceAndPreserveFullPayloads() = runBlocking {
        val image = """{"id":"image-test","type":"image_generation_call","status":"completed","output_format":"png","result":"aW1hZ2U="}"""
        enqueue(sse("""{"type":"response.image_generation_call.generating"}""") +
            sse("""{"type":"response.output_item.done","item":$image}""") +
            sse("""{"type":"response.image_generation_call.completed"}""") +
            sse("""{"type":"response.completed","response":{"status":"completed","output":[$image,{"type":"message","content":[{"type":"output_text","text":""}]}]}}"""))
        val events = model.stream(config(ApiProtocol.Responses), request).toList()
        val generated = events.filterIsInstance<OpenAIStreamEvent.ImageGenerated>().single().value
        assertEquals("image-test", generated.id)
        assertEquals("data:image/png;base64,aW1hZ2U=", generated.image.url)
        assertEquals(4, events.filterIsInstance<OpenAIStreamEvent.ResponsePayload>().size)
        assertTrue(events.filterIsInstance<OpenAIStreamEvent.TextDelta>().isEmpty())
        assertTrue(events.any { it is OpenAIStreamEvent.ImageGenerationStatus && it.status != null })
        assertTrue(events.last() is OpenAIStreamEvent.Completed)
    }

    @Test fun completionOnlyImagesAndTextAreReadWithoutDuplicatingStreamedText() = runBlocking {
        for (prefix in listOf("", sse("""{"type":"response.output_text.delta","delta":"Caption"}"""))) {
            enqueue(prefix + sse("""{"type":"response.completed","response":{"output":[{"id":"one","type":"image_generation_call","output_format":"webp","result":"aW1hZ2U="},{"id":"two","type":"image_generation_call","output_format":"jpeg","result":"aW1hZ2U="},{"type":"message","content":[{"type":"output_text","text":"Caption"}]}]}}"""))
            val result = model.complete(config(ApiProtocol.Responses), request)
            assertEquals("Caption", result.text)
            assertEquals(listOf("one", "two"), result.images.map { it.id })
            assertTrue(result.images[0].image.url.startsWith("data:image/webp"))
            assertTrue(result.images[1].image.url.startsWith("data:image/jpeg"))
        }
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
            assertTrue(runCatching { model.complete(config(), request) }.exceptionOrNull() is ResponseInterruptedException)
        }
    }

    @Test fun unframedTerminalEventDoesNotCountAsCompleted() = runBlocking {
        enqueue(chat("Partial") + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}")
        assertTrue(runCatching { model.complete(config(), request) }.exceptionOrNull() is ResponseInterruptedException)
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
        assertTrue(error is ResponseInterruptedException)
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
