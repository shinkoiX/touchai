package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ApiBillingTest {
    private val nov28_2026 = 1795824000_000L // 2026-11-28T00:00:00Z
    private val dec05_2026 = 1796428800_000L // 2026-12-05T00:00:00Z
    private val jan05_2027 = 1799107200_000L // 2027-01-05T00:00:00Z
    private val mar05_2027 = 1804204800_000L // 2027-03-05T00:00:00Z

    @Test fun extractsOpenAiResponsesCacheWriteCacheReadAndWebSearchUsage() {
        val response = Json.parseToJsonElement(
            """
            {
              "model": "gpt-5.6-luna",
              "service_tier": "default",
              "usage": {
                "input_tokens": 1977,
                "input_tokens_details": {
                  "cached_tokens": 1964,
                  "cache_write_tokens": 10,
                  "cache_write_tokens_details": { "ttl_30m_tokens": 10, "ttl_12h_tokens": 0 }
                },
                "output_tokens": 220,
                "output_tokens_details": { "reasoning_tokens": 128 },
                "total_tokens": 2197
              },
              "output": [
                { "type": "reasoning" },
                { "type": "web_search_call", "status": "completed" },
                { "type": "message", "content": [{ "type": "output_text", "text": "ok" }] }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val usage = extractApiUsage(ApiProtocol.Responses, response)!!
        assertEquals(1977L, usage.inputTokens)
        assertEquals(1964L, usage.cachedInputTokens)
        assertEquals(10L, usage.cacheWrite30mTokens)
        assertEquals(0L, usage.cacheWrite12hTokens)
        assertEquals(3L, usage.uncachedInputTokens)
        assertEquals(220L, usage.outputTokens)
        assertEquals(128L, usage.reasoningTokens)
        assertEquals(1, usage.webSearchCalls)
        assertEquals(1964.0 / 1977.0, usage.cacheHitRate!!, 1e-9)

        val config = OpenAIModelConfig(model = "gpt-5.6-luna", protocol = ApiProtocol.Responses)
        val cost = estimateApiCost(config, usage, nov28_2026)!!
        // gpt-5.6-luna Standard: in=0.20, cached=0.02, 30m_write=0.25, out=1.20; web_search=$0.01
        val expectedTokenCost = (3 * 0.20 + 1964 * 0.02 + 10 * 0.25 + 220 * 1.20) / 1_000_000.0
        assertEquals(expectedTokenCost, cost.tokenCostUsd, 1e-12)
        assertEquals(0.01, cost.toolCostUsd, 1e-12)
        assertEquals(expectedTokenCost + 0.01, cost.totalCostUsd, 1e-12)
        assertEquals("gpt-5.6-luna", cost.pricingModel)
        assertEquals("standard", cost.serviceTier)
    }

    @Test fun extractsGeminiInteractionsThoughtsAndGroundingSearchCalls() {
        val interaction = Json.parseToJsonElement(
            """
            {
              "model": "gemini-3.5-flash-lite",
              "service_tier": "flex",
              "usage": {
                "total_tokens": 350,
                "total_input_tokens": 140,
                "input_tokens_by_modality": [{ "modality": "text", "tokens": 140 }],
                "total_cached_tokens": 40,
                "total_output_tokens": 32,
                "total_tool_use_tokens": 0,
                "total_thought_tokens": 178,
                "grounding_tool_count": [{ "type": "google_search", "count": 2 }]
              }
            }
            """.trimIndent(),
        ).jsonObject

        val usage = extractApiUsage(ApiProtocol.Gemini, interaction)!!
        assertEquals(140L, usage.inputTokens)
        assertEquals(40L, usage.cachedInputTokens)
        assertEquals(100L, usage.uncachedInputTokens)
        // Output MUST include thought tokens for Gemini Interactions!
        assertEquals(210L, usage.outputTokens)
        assertEquals(178L, usage.reasoningTokens)
        assertEquals(2, usage.googleSearchCalls)
        assertEquals("flex", usage.effectiveServiceTier)

        val config = OpenAIModelConfig(model = "gemini-3.5-flash-lite", protocol = ApiProtocol.Gemini)
        val cost = estimateApiCost(config, usage, nov28_2026)!!
        // gemini-3.5-flash-lite Flex: in=0.15, cached=0.02, out=1.25; google_search=$0.014 * 2
        val expectedTokenCost = (100 * 0.15 + 40 * 0.02 + 210 * 1.25) / 1_000_000.0
        assertEquals(expectedTokenCost, cost.tokenCostUsd, 1e-12)
        assertEquals(0.028, cost.toolCostUsd, 1e-12)
        assertEquals("flex", cost.serviceTier)
    }

    @Test fun appliesScheduledPriceChangesLongContextAndSnapshotMatching() {
        val carbonUsage = ApiUsage(inputTokens = 1_000_000L, cachedInputTokens = 500_000L, outputTokens = 100_000L)
        val carbonConfig = OpenAIModelConfig(model = "models/gemini-4-carbon", protocol = ApiProtocol.Gemini)
        val promoCost = estimateApiCost(carbonConfig, carbonUsage, nov28_2026)!!
        val postDecCost = estimateApiCost(carbonConfig, carbonUsage, dec05_2026)!!
        // Promo: 0.5M*2 + 0.5M*0.1 + 0.1M*10 = 2.05; Post Dec 1: 0.5M*4 + 0.5M*0.2 + 0.1M*20 = 4.10
        assertEquals(2.05, promoCost.totalCostUsd, 1e-9)
        assertEquals(4.10, postDecCost.totalCostUsd, 1e-9)

        val flash38Config = OpenAIModelConfig(model = "gemini-3.8-flash", protocol = ApiProtocol.Gemini)
        val flash38Usage = ApiUsage(inputTokens = 1_000_000L, outputTokens = 1_000_000L)
        assertEquals(4.50, estimateApiCost(flash38Config, flash38Usage, nov28_2026)!!.totalCostUsd, 1e-9)
        assertEquals(9.00, estimateApiCost(flash38Config, flash38Usage, jan05_2027)!!.totalCostUsd, 1e-9)

        // OpenAI GPT-6.2 Astra Ultrafast promo (3x) vs list (6x) + >272K long context (in 2x, out 1.5x)
        val astraConfig = OpenAIModelConfig(model = "gpt-6.2-astra", protocol = ApiProtocol.Responses, serviceTier = "ultrafast")
        val longUsage = ApiUsage(inputTokens = 300_000L, outputTokens = 100_000L, effectiveServiceTier = "ultrafast")
        val ultraPromo = estimateApiCost(astraConfig, longUsage, nov28_2026)!!
        val ultraList = estimateApiCost(astraConfig, longUsage, mar05_2027)!!
        assertTrue(ultraPromo.longContext)
        // Promo ultrafast long: in=48, out=180 => 0.3*48 + 0.1*180 = 14.4 + 18 = 32.4
        assertEquals(32.4, ultraPromo.totalCostUsd, 1e-9)
        // List ultrafast long: in=96, out=360 => 0.3*96 + 0.1*360 = 28.8 + 36 = 64.8
        assertEquals(64.8, ultraList.totalCostUsd, 1e-9)

        // Snapshot suffix stripping + Flex discount does not discount web search tool fee
        val nanoConfig = OpenAIModelConfig(model = "gpt-5-nano-2025-08-07", protocol = ApiProtocol.Responses, serviceTier = "flex")
        val nanoUsage = ApiUsage(inputTokens = 1_000_000L, outputTokens = 1_000_000L, webSearchCalls = 1, effectiveServiceTier = "flex")
        val nanoCost = estimateApiCost(nanoConfig, nanoUsage, nov28_2026)!!
        // gpt-5-nano Flex: in=0.025, out=0.20 => token=0.225, tool=0.01
        assertEquals(0.225, nanoCost.tokenCostUsd, 1e-9)
        assertEquals(0.01, nanoCost.toolCostUsd, 1e-9)
        assertEquals("gpt-5-nano", nanoCost.pricingModel)
    }

    @Test fun excludesChatGptOAuthFromDollarBillingAndHandlesAudioAndLongContextGemini() {
        val oauthConfig = OpenAIModelConfig(
            model = "gpt-5.4",
            protocol = ApiProtocol.Responses,
            authentication = AuthenticationMethod.ChatGpt,
        )
        assertNull(estimateApiCost(oauthConfig, ApiUsage(inputTokens = 1000, outputTokens = 500), nov28_2026))

        // Gemini 3.1 Pro Preview: >200k long context + Flex cached input same as Standard
        val proConfig = OpenAIModelConfig(model = "gemini-3.1-pro-preview", protocol = ApiProtocol.Gemini, serviceTier = "flex")
        val proUsage = ApiUsage(inputTokens = 300_000L, cachedInputTokens = 100_000L, outputTokens = 100_000L, effectiveServiceTier = "flex")
        val proCost = estimateApiCost(proConfig, proUsage, nov28_2026)!!
        assertTrue(proCost.longContext)
        // Flex >200k: in=2.0, cached=0.4, out=9.0 => 0.2*2.0 + 0.1*0.4 + 0.1*9.0 = 0.4 + 0.04 + 0.9 = 1.34
        assertEquals(1.34, proCost.totalCostUsd, 1e-9)

        // Gemini 2.5 Flash audio modality + 2.5 search rate ($35/1k)
        val flash25 = OpenAIModelConfig(model = "gemini-2.5-flash", protocol = ApiProtocol.Gemini)
        val audioUsage = ApiUsage(inputTokens = 1_000_000L, audioInputTokens = 400_000L, outputTokens = 0L, googleSearchCalls = 1)
        val audioCost = estimateApiCost(flash25, audioUsage, nov28_2026)!!
        // 0.6M * 0.30 + 0.4M * 1.00 = 0.18 + 0.40 = 0.58; tool = 0.035
        assertEquals(0.58, audioCost.tokenCostUsd, 1e-9)
        assertEquals(0.035, audioCost.toolCostUsd, 1e-9)
    }

    @Test fun chatCompletionsCapturesTrailingUsageChunkAndFallsBackWhenStreamOptionsRejected() = runBlocking {
        val server = MockWebServer().apply { start() }
        val http = HttpClient(OkHttp) { expectSuccess = false; followRedirects = false; install(HttpTimeout) }
        try {
            val model = OpenAIModel(http)
            val cfg = OpenAIModelConfig(
                apiKey = "test-key",
                model = "gpt-4o-mini",
                baseUrl = server.url("/v1").toString(),
                protocol = ApiProtocol.ChatCompletions,
                webSearch = false,
                timeoutMillis = 5_000,
            )
            val req = OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Hi")))

            // 1. Trailing usage-only chunk after finish_reason == "stop"
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"id\":\"c1\",\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"c1\",\"model\":\"gpt-4o-mini-2024-07-18\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":null}\n\n" +
                    "data: {\"id\":\"c1\",\"model\":\"gpt-4o-mini-2024-07-18\",\"service_tier\":\"default\",\"choices\":[],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":20,\"prompt_tokens_details\":{\"cached_tokens\":50}}}\n\n" +
                    "data: [DONE]\n\n",
                ),
            )
            val completed = model.complete(cfg, req)
            assertEquals("OK", completed.text)
            val parsed = extractApiUsage(ApiProtocol.ChatCompletions, completed.response)!!
            assertEquals(100L, parsed.inputTokens)
            assertEquals(50L, parsed.cachedInputTokens)
            assertEquals(20L, parsed.outputTokens)
            val sent1 = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(true, sent1["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.boolean)

            // 2. Fallback when a compatible provider rejects stream_options with HTTP 400
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"Unknown parameter: stream_options"}}"""))
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"id\":\"c2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Fallback\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
                ),
            )
            val fallbackEvents = model.stream(cfg, req).toList()
            assertEquals("Fallback", fallbackEvents.filterIsInstance<OpenAIStreamEvent.TextDelta>().joinToString("") { it.text })
            val firstTry = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val retryBody = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertTrue(firstTry.containsKey("stream_options"))
            assertFalse(retryBody.containsKey("stream_options"))
        } finally {
            http.close()
            server.shutdown()
        }
    }

    @Test fun apiUsageAndCostRoundTripJson() {
        val usage = ApiUsage(
            inputTokens = 1000,
            cachedInputTokens = 600,
            cacheWrite30mTokens = 200,
            cacheWrite12hTokens = 50,
            outputTokens = 300,
            reasoningTokens = 120,
            webSearchCalls = 1,
            resolvedModel = "gpt-6.2-sol",
            effectiveServiceTier = "fast",
        )
        val cost = ApiCost(tokenCostUsd = 0.012, toolCostUsd = 0.01, totalCostUsd = 0.022, pricingModel = "gpt-6.2-sol", serviceTier = "fast", longContext = true)
        assertEquals(usage, ApiUsage.fromJson(usage.toJson()))
        assertEquals(cost, ApiCost.fromJson(cost.toJson()))
    }
}
