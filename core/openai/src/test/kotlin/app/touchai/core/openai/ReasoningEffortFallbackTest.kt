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

class ReasoningEffortFallbackTest {
    private val sseHeaders = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    private val request = OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Hello")))

    @Test fun openaiResponsesParsesSupportedValuesFrom400AndJumpsToNearestTier() = runBlocking {
        val sentEfforts = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    val body = Json.parseToJsonElement(req.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                    val effort = (body["reasoning"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("effort")?.jsonPrimitive?.contentOrNull
                    sentEfforts += effort
                    if (effort == "max") {
                        respond(
                            """{"error":{"message":"Unsupported value: 'max' is not supported with this model. Supported values are: 'none', 'low', 'medium', 'high', and 'xhigh'."}}""",
                            HttpStatusCode.BadRequest,
                            jsonHeaders,
                        )
                    } else {
                        respond(
                            "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}\n\n" +
                                "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":[]}}\n\n",
                            HttpStatusCode.OK,
                            sseHeaders,
                        )
                    }
                }
            }
        }
        val config = OpenAIModelConfig(apiKey = "test-key", model = "gpt-5.4", protocol = ApiProtocol.Responses, reasoningEffort = "max")
        val events = OpenAIModel(client).stream(config, request).toList()
        assertEquals(listOf("max", "xhigh"), sentEfforts)
        assertEquals(OpenAIStreamEvent.EffortAdjusted("max", "xhigh"), events.filterIsInstance<OpenAIStreamEvent.EffortAdjusted>().single())
        assertEquals("OK", events.filterIsInstance<OpenAIStreamEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test fun geminiFallsBackFromNoneToMinimalAlongProtocolLadder() = runBlocking {
        val sentLevels = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    val body = Json.parseToJsonElement(req.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                    val level = (body["generation_config"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("thinking_level")?.jsonPrimitive?.contentOrNull
                    sentLevels += level
                    if (level == "none") {
                        respond(
                            """{"error":{"message":"Invalid thinking_level value."}}""",
                            HttpStatusCode.BadRequest,
                            jsonHeaders,
                        )
                    } else {
                        respond(
                            "event: step.delta\ndata: {\"event_type\":\"step.delta\",\"delta\":{\"type\":\"text\",\"text\":\"Hi\"}}\n\n" +
                                "event: interaction.completed\ndata: {\"event_type\":\"interaction.completed\",\"interaction\":{\"id\":\"int_1\",\"status\":\"completed\",\"outputs\":[]}}\n\n",
                            HttpStatusCode.OK,
                            sseHeaders,
                        )
                    }
                }
            }
        }
        val config = OpenAIModelConfig(apiKey = "test-key", model = "gemini-4-neon", baseUrl = GeminiBaseUrl, protocol = ApiProtocol.Gemini, reasoningEffort = "none")
        val events = OpenAIModel(client).stream(config, request).toList()
        assertEquals(listOf("none", "minimal"), sentLevels)
        assertEquals(OpenAIStreamEvent.EffortAdjusted("none", "minimal"), events.filterIsInstance<OpenAIStreamEvent.EffortAdjusted>().single())
    }

    @Test fun multiStepFallbackStepsDownAndEmitsSingleAdjustmentEvent() = runBlocking {
        val sentEfforts = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    val body = Json.parseToJsonElement(req.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                    val effort = body["reasoning_effort"]?.jsonPrimitive?.contentOrNull
                    sentEfforts += effort
                    if (effort in listOf("max", "xhigh")) {
                        respond(
                            """{"error":{"message":"Invalid reasoning_effort parameter."}}""",
                            HttpStatusCode.BadRequest,
                            jsonHeaders,
                        )
                    } else {
                        respond(
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n",
                            HttpStatusCode.OK,
                            sseHeaders,
                        )
                    }
                }
            }
        }
        val config = OpenAIModelConfig(apiKey = "test-key", model = "o3", protocol = ApiProtocol.ChatCompletions, reasoningEffort = "max")
        val events = OpenAIModel(client).stream(config, request).toList()
        assertEquals(listOf("max", "xhigh", "high"), sentEfforts)
        assertEquals(OpenAIStreamEvent.EffortAdjusted("max", "high"), events.filterIsInstance<OpenAIStreamEvent.EffortAdjusted>().single())
    }

    @Test fun nonReasoningModelFallsBackDirectlyToDefaultAndUnrelatedErrorsAreNotRetried() = runBlocking {
        val sentEfforts = mutableListOf<String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { req ->
                    val body = Json.parseToJsonElement(req.body.toByteArray().toString(Charsets.UTF_8)).jsonObject
                    val effort = (body["reasoning"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("effort")?.jsonPrimitive?.contentOrNull
                    sentEfforts += effort
                    if (effort != null) {
                        respond(
                            """{"error":{"message":"This model does not support reasoning parameters."}}""",
                            HttpStatusCode.BadRequest,
                            jsonHeaders,
                        )
                    } else {
                        respond(
                            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_2\",\"status\":\"completed\",\"output\":[]}}\n\n",
                            HttpStatusCode.OK,
                            sseHeaders,
                        )
                    }
                }
            }
        }
        val config = OpenAIModelConfig(apiKey = "test-key", model = "gpt-4o", protocol = ApiProtocol.Responses, reasoningEffort = "high")
        val events = OpenAIModel(client).stream(config, request).toList()
        assertEquals(listOf("high", null), sentEfforts)
        assertEquals(OpenAIStreamEvent.EffortAdjusted("high", null), events.filterIsInstance<OpenAIStreamEvent.EffortAdjusted>().single())

        var unrelatedCalls = 0
        val failingClient = HttpClient(MockEngine) {
            engine {
                addHandler {
                    unrelatedCalls++
                    respond("""{"error":{"message":"Invalid image payload"}}""", HttpStatusCode.BadRequest, jsonHeaders)
                }
            }
        }
        try {
            OpenAIModel(failingClient).stream(config, request).toList()
            fail("Expected OpenAIRequestException for unrelated HTTP 400")
        } catch (error: OpenAIRequestException) {
            assertEquals(400, error.httpStatus)
            assertEquals(1, unrelatedCalls)
        }
    }
}
