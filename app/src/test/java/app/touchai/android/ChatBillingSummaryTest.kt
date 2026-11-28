package app.touchai.android

import app.touchai.core.openai.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ChatBillingSummaryTest {
    @Test fun summarizesMultiTurnBillingAndCacheHitRate() {
        val ai = AiConfiguration(OpenAIModelConfig(model = "gpt-5.6-luna", protocol = ApiProtocol.Responses))
        val turn1 = ChatTurn(
            user = ChatMessage(MessageRole.User, "Q1"),
            ai = ai,
            answer = "A1",
            status = TurnStatus.Completed,
            usage = ApiUsage(inputTokens = 1000, cachedInputTokens = 0, cacheWrite30mTokens = 800, outputTokens = 200),
            cost = ApiCost(tokenCostUsd = 0.0005, toolCostUsd = 0.0, totalCostUsd = 0.0005, pricingModel = "gpt-5.6-luna"),
        )
        val turn2 = ChatTurn(
            user = ChatMessage(MessageRole.User, "Q2"),
            ai = ai,
            answer = "A2",
            status = TurnStatus.Completed,
            usage = ApiUsage(inputTokens = 2000, cachedInputTokens = 1500, outputTokens = 300, webSearchCalls = 1),
            cost = ApiCost(tokenCostUsd = 0.0006, toolCostUsd = 0.01, totalCostUsd = 0.0106, pricingModel = "gpt-5.6-luna"),
        )
        val summary = summarizeConversationBilling(listOf(turn1, turn2))!!
        assertEquals(2, summary.turnCount)
        assertEquals(3000L, summary.totalInputTokens)
        assertEquals(1500L, summary.totalCachedInputTokens)
        assertEquals(800L, summary.totalCacheWriteTokens)
        assertEquals(700L, summary.totalUncachedInputTokens)
        assertEquals(500L, summary.totalOutputTokens)
        assertEquals(1, summary.totalSearchCalls)
        assertEquals(0.5, summary.cacheHitRate!!, 1e-9)
        assertEquals(0.0111, summary.totalCostUsd, 1e-9)
        assertEquals(0.01, summary.totalToolCostUsd, 1e-9)
        assertEquals(turn2.usage, summary.lastTurnUsage)
    }

    @Test fun formatsCurrencyTokensAndCachePercentCleanly() {
        assertEquals("$0", formatUsd(0.0))
        assertEquals("<$0.0001", formatUsd(0.00004))
        assertEquals("$0.0042", formatUsd(0.0042))
        assertEquals("$0.250", formatUsd(0.25))
        assertEquals("$1.50", formatUsd(1.5))

        assertEquals("542", formatTokenCount(542))
        assertEquals("1.9k", formatTokenCount(1940))
        assertEquals("120k", formatTokenCount(120_400))
        assertEquals("1.25M", formatTokenCount(1_250_000))

        assertEquals("0%", formatCacheHitPercent(0.0))
        assertEquals("0.4%", formatCacheHitPercent(0.004))
        assertEquals("62%", formatCacheHitPercent(0.62))
        assertEquals("99.3%", formatCacheHitPercent(0.993))
        assertEquals("100%", formatCacheHitPercent(1.0))
    }

    @Test fun chatHistoryCodecPreservesUsageAndCost() {
        val turn = ChatTurn(
            user = ChatMessage(MessageRole.User, "Hello"),
            ai = AiConfiguration(OpenAIModelConfig(model = "gemini-4-carbon", protocol = ApiProtocol.Gemini)),
            answer = "World",
            status = TurnStatus.Completed,
            usage = ApiUsage(inputTokens = 500, cachedInputTokens = 250, outputTokens = 120, reasoningTokens = 80, googleSearchCalls = 1),
            cost = ApiCost(tokenCostUsd = 0.0017, toolCostUsd = 0.014, totalCostUsd = 0.0157, pricingModel = "gemini-4-carbon", serviceTier = "standard"),
        )
        val encoded = encodeChatTurn(turn)
        val decoded = decodeChatTurn(encoded, apiKey = "")
        assertEquals(turn.usage, decoded.usage)
        assertEquals(turn.cost, decoded.cost)
    }

    @Test fun summarizesLast30DaysSpendPerConnectionByIdAndLegacyEndpoint() {
        val now = 1_800_000_000_000L
        val since = now - THIRTY_DAYS_MILLIS
        val openAi1 = ApiConnection(id = "conn-oai-1", name = "OpenAI 1", baseUrl = "https://api.openai.com/v1", protocol = ApiProtocol.Responses, apiKey = "k1")
        val openAi2 = ApiConnection(id = "conn-oai-2", name = "OpenAI 2", baseUrl = "https://api.openai.com/v1", protocol = ApiProtocol.Responses, apiKey = "k2")
        val gemini = ApiConnection(id = "conn-gem", name = "Gemini", baseUrl = GeminiBaseUrl, protocol = ApiProtocol.Gemini, apiKey = "k3")
        val chatGpt = ApiConnection(id = "conn-cgpt", name = "ChatGPT", authentication = AuthenticationMethod.ChatGpt)

        fun logRecord(
            id: String,
            startedAt: Long,
            connectionId: String?,
            endpoint: String,
            auth: AuthenticationMethod,
            usage: ApiUsage,
            cost: ApiCost?,
        ) = RequestLogRecord(
            id = id,
            startedAt = startedAt,
            data = buildJsonObject {
                put("status", "Completed")
                put("authentication", auth.label)
                put("endpoint", endpoint)
                connectionId?.let { put("connectionId", it) }
                put("usage", usage.toJson())
                cost?.let { put("cost", it.toJson()) }
            },
        )

        val records = listOf(
            // Older than 30 days -> excluded
            logRecord("old", since - 1L, "conn-oai-1", "https://api.openai.com/v1/responses", AuthenticationMethod.ApiKey,
                ApiUsage(inputTokens = 1000, outputTokens = 100), ApiCost(0.5, 0.0, 0.5, "gpt-6.2-sol")),
            // Legacy record without connectionId -> attributed once to first matching OpenAI connection (conn-oai-1)
            logRecord("legacy", now - 10_000L, null, "https://api.openai.com/v1/responses", AuthenticationMethod.ApiKey,
                ApiUsage(inputTokens = 1000, cachedInputTokens = 800, outputTokens = 200), ApiCost(0.01, 0.0, 0.01, "gpt-5.6-luna")),
            // Explicit connectionId -> goes to conn-oai-2 even though endpoint matches conn-oai-1
            logRecord("explicit", now - 5_000L, "conn-oai-2", "https://api.openai.com/v1/responses", AuthenticationMethod.ApiKey,
                ApiUsage(inputTokens = 2000, cachedInputTokens = 1000, outputTokens = 300, webSearchCalls = 1), ApiCost(0.02, 0.01, 0.03, "gpt-6.2-sol")),
            // Gemini record
            logRecord("gem", now - 3_000L, "conn-gem", "$GeminiBaseUrl/interactions", AuthenticationMethod.ApiKey,
                ApiUsage(inputTokens = 500, outputTokens = 100, googleSearchCalls = 1), ApiCost(0.002, 0.014, 0.016, "gemini-4-carbon")),
            // ChatGPT OAuth record -> counts tokens/requests, $0 cost
            logRecord("cgpt", now - 1_000L, "conn-cgpt", "${ChatGptOAuth.Resource}/responses", AuthenticationMethod.ChatGpt,
                ApiUsage(inputTokens = 400, cachedInputTokens = 200, outputTokens = 100), null),
        )

        val summary = summarizeConnectionSpend(listOf(openAi1, openAi2, gemini, chatGpt), records, since)
        assertEquals(0.01, summary.getValue("conn-oai-1").totalCostUsd, 1e-9)
        assertEquals(1, summary.getValue("conn-oai-1").requestCount)
        assertEquals(0.8, summary.getValue("conn-oai-1").cacheHitRate!!, 1e-9)

        assertEquals(0.03, summary.getValue("conn-oai-2").totalCostUsd, 1e-9)
        assertEquals(0.01, summary.getValue("conn-oai-2").totalToolCostUsd, 1e-9)
        assertEquals(1, summary.getValue("conn-oai-2").totalSearchCalls)

        assertEquals(0.016, summary.getValue("conn-gem").totalCostUsd, 1e-9)
        assertEquals(0.014, summary.getValue("conn-gem").totalToolCostUsd, 1e-9)

        assertEquals(0.0, summary.getValue("conn-cgpt").totalCostUsd, 1e-9)
        assertEquals(1, summary.getValue("conn-cgpt").requestCount)
        assertEquals(400L, summary.getValue("conn-cgpt").totalInputTokens)
    }
}
