package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ServiceTierTest {
    private val sseHeaders = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val request = OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Ping")))

    @Test fun requestBodiesMapServiceTierPerProtocolAndOmitForChatGpt() {
        val openAiBody = buildRequestBody(
            OpenAIModelConfig(apiKey = "k", model = "gpt-5.4", protocol = ApiProtocol.Responses, serviceTier = "fast"),
            request,
        )
        assertEquals("fast", openAiBody["service_tier"]?.jsonPrimitive?.contentOrNull)

        val geminiBody = buildRequestBody(
            OpenAIModelConfig(apiKey = "k", model = "gemini-4-neon", baseUrl = GeminiBaseUrl, protocol = ApiProtocol.Gemini, serviceTier = "fast"),
            request,
        )
        assertEquals("priority", geminiBody["service_tier"]?.jsonPrimitive?.contentOrNull)

        val chatGptBody = buildRequestBody(
            OpenAIModelConfig(authentication = AuthenticationMethod.ChatGpt, model = "gpt-5.4", protocol = ApiProtocol.Responses, serviceTier = "fast"),
            request,
        )
        assertNull(chatGptBody["service_tier"])
    }

    @Test fun openaiUltrafastFallsBackToFastThenPriorityAndEmitsSingleEvent() = runBlocking {
        val sentTiers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    val body = Json.parseToJsonElement(req.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                    val tier = body["service_tier"]?.jsonPrimitive?.contentOrNull
                    sentTiers += tier
                    if (tier in listOf("ultrafast", "fast")) {
                        respond(
                            """{"error":{"message":"Invalid service_tier '$tier'."}}""",
                            HttpStatusCode.BadRequest,
                            jsonHeaders,
                        )
                    } else {
                        respond(
                            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_tier\",\"status\":\"completed\",\"output\":[]}}\n\n",
                            HttpStatusCode.OK,
                            sseHeaders,
                        )
                    }
                }
            }
        }
        val config = OpenAIModelConfig(apiKey = "k", model = "gpt-5.4", protocol = ApiProtocol.Responses, serviceTier = "ultrafast")
        val events = OpenAIModel(client).stream(config, request).toList()
        assertEquals(listOf("ultrafast", "fast", "priority"), sentTiers)
        assertEquals(
            OpenAIStreamEvent.ServiceTierAdjusted("ultrafast", "priority"),
            events.filterIsInstance<OpenAIStreamEvent.ServiceTierAdjusted>().single(),
        )
    }

    @Test fun geminiPriorityFallsBackToDefaultWhenRejected() = runBlocking {
        val sentTiers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    val body = Json.parseToJsonElement(req.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                    val tier = body["service_tier"]?.jsonPrimitive?.contentOrNull
                    sentTiers += tier
                    if (tier != null) {
                        respond(
                            """{"error":{"message":"service_tier 'priority' is not supported for this model."}}""",
                            HttpStatusCode.BadRequest,
                            jsonHeaders,
                        )
                    } else {
                        respond(
                            "event: interaction.completed\ndata: {\"event_type\":\"interaction.completed\",\"interaction\":{\"id\":\"int_tier\",\"status\":\"completed\",\"outputs\":[]}}\n\n",
                            HttpStatusCode.OK,
                            sseHeaders,
                        )
                    }
                }
            }
        }
        val config = OpenAIModelConfig(apiKey = "k", model = "gemini-3-flash", baseUrl = GeminiBaseUrl, protocol = ApiProtocol.Gemini, serviceTier = "priority")
        val events = OpenAIModel(client).stream(config, request).toList()
        assertEquals(listOf("priority", null), sentTiers)
        assertEquals(
            OpenAIStreamEvent.ServiceTierAdjusted("priority", null),
            events.filterIsInstance<OpenAIStreamEvent.ServiceTierAdjusted>().single(),
        )
    }

    @Test fun openaiResponsesInStreamErrorForInvalidServiceTierTriggersFallback() = runBlocking {
        val sentTiers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    val body = Json.parseToJsonElement(req.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                    val tier = body["service_tier"]?.jsonPrimitive?.contentOrNull
                    sentTiers += tier
                    if (tier == "ultrafast") {
                        // Matches real OpenAI Responses API behavior: HTTP 200 SSE followed by in-stream invalid_request_error.
                        respond(
                            "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_err\",\"status\":\"in_progress\"}}\n\n" +
                                "event: response.in_progress\ndata: {\"type\":\"response.in_progress\",\"response\":{\"id\":\"resp_err\",\"status\":\"in_progress\"}}\n\n" +
                                "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"code\":null,\"message\":\"Invalid service_tier argument\",\"param\":\"service_tier\"}}\n\n",
                            HttpStatusCode.OK,
                            sseHeaders,
                        )
                    } else {
                        respond(
                            "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n" +
                                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_ok\",\"status\":\"completed\",\"output\":[]}}\n\n",
                            HttpStatusCode.OK,
                            sseHeaders,
                        )
                    }
                }
            }
        }
        val config = OpenAIModelConfig(apiKey = "k", model = "gpt-6.1-terra", protocol = ApiProtocol.Responses, serviceTier = "ultrafast")
        val events = OpenAIModel(client).stream(config, request).toList()
        assertEquals(listOf("ultrafast", "fast"), sentTiers)
        assertEquals(
            OpenAIStreamEvent.ServiceTierAdjusted("ultrafast", "fast"),
            events.filterIsInstance<OpenAIStreamEvent.ServiceTierAdjusted>().single(),
        )
        assertEquals("OK", events.filterIsInstance<OpenAIStreamEvent.TextDelta>().joinToString("") { it.text })
    }
}
