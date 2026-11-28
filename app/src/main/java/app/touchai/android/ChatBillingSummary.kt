package app.touchai.android

import app.touchai.core.openai.ApiCost
import app.touchai.core.openai.ApiUsage
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.OpenAIModelConfig
import app.touchai.core.openai.RequestLogRecord
import app.touchai.core.openai.buildRequestUrl
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ConversationBillingSummary(
    val turnCount: Int,
    val totalInputTokens: Long,
    val totalCachedInputTokens: Long,
    val totalCacheWrite30mTokens: Long,
    val totalCacheWrite12hTokens: Long,
    val totalCacheWriteOtherTokens: Long,
    val totalOutputTokens: Long,
    val totalReasoningTokens: Long,
    val totalToolUseInputTokens: Long,
    val totalWebSearchCalls: Int,
    val totalGoogleSearchCalls: Int,
    val totalImageGenerationCalls: Int,
    val pricedTurnCount: Int,
    val unpricedApiTurnCount: Int,
    val chatGptTurnCount: Int,
    val totalTokenCostUsd: Double,
    val totalToolCostUsd: Double,
    val totalCostUsd: Double,
    val lastTurnUsage: ApiUsage?,
    val lastTurnCost: ApiCost?,
    val lastTurnIsChatGpt: Boolean,
) {
    val totalCacheWriteTokens: Long get() = totalCacheWrite30mTokens + totalCacheWrite12hTokens + totalCacheWriteOtherTokens
    val totalUncachedInputTokens: Long get() = (totalInputTokens - totalCachedInputTokens - totalCacheWriteTokens).coerceAtLeast(0L)
    val totalSearchCalls: Int get() = totalWebSearchCalls + totalGoogleSearchCalls
    val cacheHitRate: Double? get() = if (totalInputTokens > 0L) totalCachedInputTokens.toDouble() / totalInputTokens.toDouble() else null
    val hasKnownCost: Boolean get() = pricedTurnCount > 0
    val isOnlyChatGpt: Boolean get() = chatGptTurnCount > 0 && pricedTurnCount == 0 && unpricedApiTurnCount == 0
}

fun summarizeConversationBilling(turns: List<ChatTurn>): ConversationBillingSummary? {
    var turnCount = 0
    var inputTokens = 0L
    var cachedInputTokens = 0L
    var cacheWrite30m = 0L
    var cacheWrite12h = 0L
    var cacheWriteOther = 0L
    var outputTokens = 0L
    var reasoningTokens = 0L
    var toolUseInput = 0L
    var webSearches = 0
    var googleSearches = 0
    var imageGens = 0
    var pricedTurns = 0
    var unpricedApiTurns = 0
    var chatGptTurns = 0
    var tokenCost = 0.0
    var toolCost = 0.0
    var totalCost = 0.0
    var lastUsage: ApiUsage? = null
    var lastCost: ApiCost? = null
    var lastIsChatGpt = false

    for (turn in turns) {
        val usage = turn.usage ?: continue
        if (!usage.hasData) continue
        turnCount++
        inputTokens += usage.inputTokens
        cachedInputTokens += usage.cachedInputTokens
        cacheWrite30m += usage.cacheWrite30mTokens
        cacheWrite12h += usage.cacheWrite12hTokens
        cacheWriteOther += usage.cacheWriteOtherTokens
        outputTokens += usage.outputTokens
        reasoningTokens += usage.reasoningTokens
        toolUseInput += usage.toolUseInputTokens
        webSearches += usage.webSearchCalls
        googleSearches += usage.googleSearchCalls
        imageGens += usage.imageGenerationCalls
        val isChatGpt = turn.ai.api.authentication == AuthenticationMethod.ChatGpt
        val cost = turn.cost
        when {
            isChatGpt -> chatGptTurns++
            cost != null -> {
                pricedTurns++
                tokenCost += cost.tokenCostUsd
                toolCost += cost.toolCostUsd
                totalCost += cost.totalCostUsd
            }
            else -> unpricedApiTurns++
        }
        lastUsage = usage
        lastCost = cost
        lastIsChatGpt = isChatGpt
    }
    if (turnCount == 0) return null
    return ConversationBillingSummary(
        turnCount = turnCount,
        totalInputTokens = inputTokens,
        totalCachedInputTokens = cachedInputTokens,
        totalCacheWrite30mTokens = cacheWrite30m,
        totalCacheWrite12hTokens = cacheWrite12h,
        totalCacheWriteOtherTokens = cacheWriteOther,
        totalOutputTokens = outputTokens,
        totalReasoningTokens = reasoningTokens,
        totalToolUseInputTokens = toolUseInput,
        totalWebSearchCalls = webSearches,
        totalGoogleSearchCalls = googleSearches,
        totalImageGenerationCalls = imageGens,
        pricedTurnCount = pricedTurns,
        unpricedApiTurnCount = unpricedApiTurns,
        chatGptTurnCount = chatGptTurns,
        totalTokenCostUsd = tokenCost,
        totalToolCostUsd = toolCost,
        totalCostUsd = totalCost,
        lastTurnUsage = lastUsage,
        lastTurnCost = lastCost,
        lastTurnIsChatGpt = lastIsChatGpt,
    )
}

fun formatUsd(amount: Double): String = when {
    amount <= 0.0 -> "$0"
    amount < 0.0001 -> "<$0.0001"
    amount < 0.1 -> String.format(Locale.US, "$%.4f", amount)
    amount < 1.0 -> String.format(Locale.US, "$%.3f", amount)
    else -> String.format(Locale.US, "$%.2f", amount)
}

fun formatTokenCount(count: Long): String = when {
    count < 1_000L -> count.toString()
    count < 100_000L -> String.format(Locale.US, "%.1fk", count / 1_000.0).removeSuffix(".0k").let {
        if (it.endsWith("k")) it else "${it}k"
    }
    count < 1_000_000L -> "${count / 1_000L}k"
    else -> String.format(Locale.US, "%.2fM", count / 1_000_000.0)
}

fun formatCacheHitPercent(rate: Double): String {
    val pct = (rate * 100.0).coerceIn(0.0, 100.0)
    return when {
        pct <= 0.0 -> "0%"
        pct >= 100.0 -> "100%"
        pct < 1.0 || pct > 99.0 -> String.format(Locale.US, "%.1f%%", pct)
        else -> String.format(Locale.US, "%.0f%%", pct)
    }
}

const val THIRTY_DAYS_MILLIS: Long = 30L * 24L * 60L * 60L * 1000L

data class ConnectionSpendSummary(
    val connectionId: String,
    val requestCount: Int = 0,
    val pricedRequestCount: Int = 0,
    val unpricedApiRequestCount: Int = 0,
    val totalTokenCostUsd: Double = 0.0,
    val totalToolCostUsd: Double = 0.0,
    val totalCostUsd: Double = 0.0,
    val totalInputTokens: Long = 0L,
    val totalCachedInputTokens: Long = 0L,
    val totalOutputTokens: Long = 0L,
    val totalSearchCalls: Int = 0,
) {
    val cacheHitRate: Double?
        get() = if (totalInputTokens > 0L) totalCachedInputTokens.toDouble() / totalInputTokens.toDouble() else null
}

/**
 * Aggregates API spend and token usage per [ApiConnection] from request logs in `[sinceMillis, ∞)`.
 * Matches by explicit `connectionId` first, then falls back to `(authentication, endpoint)` for older logs.
 */
fun summarizeConnectionSpend(
    connections: List<ApiConnection>,
    records: List<RequestLogRecord>,
    sinceMillis: Long,
): Map<String, ConnectionSpendSummary> {
    if (connections.isEmpty()) return emptyMap()
    val byId = connections.associateBy { it.id }
    val firstByEndpointKey = LinkedHashMap<String, String>()
    for (connection in connections) {
        val key = connectionEndpointKey(connection)
        if (key !in firstByEndpointKey) firstByEndpointKey[key] = connection.id
    }

    val totals = LinkedHashMap<String, ConnectionSpendSummary>(connections.size)
    for (connection in connections) {
        totals[connection.id] = ConnectionSpendSummary(connection.id)
    }

    for (record in records) {
        if (record.startedAt < sinceMillis) continue
        val usage = (record.data["usage"] as? JsonObject)?.let(ApiUsage::fromJson)
        val cost = (record.data["cost"] as? JsonObject)?.let(ApiCost::fromJson)
        if (usage == null && cost == null) continue

        val explicitId = record.data["connectionId"]?.jsonPrimitive?.contentOrNull?.takeIf { it in byId }
        val targetId = explicitId ?: run {
            val auth = record.data["authentication"]?.jsonPrimitive?.contentOrNull ?: AuthenticationMethod.ApiKey.label
            val endpoint = record.data["endpoint"]?.jsonPrimitive?.contentOrNull?.trimEnd('/').orEmpty()
            firstByEndpointKey["$auth|$endpoint"]
        } ?: continue

        val connection = byId[targetId] ?: continue
        val current = totals.getValue(targetId)
        val isChatGpt = connection.authentication == AuthenticationMethod.ChatGpt
        totals[targetId] = current.copy(
            requestCount = current.requestCount + 1,
            pricedRequestCount = current.pricedRequestCount + if (!isChatGpt && cost != null) 1 else 0,
            unpricedApiRequestCount = current.unpricedApiRequestCount + if (!isChatGpt && cost == null) 1 else 0,
            totalTokenCostUsd = current.totalTokenCostUsd + if (!isChatGpt && cost != null) cost.tokenCostUsd else 0.0,
            totalToolCostUsd = current.totalToolCostUsd + if (!isChatGpt && cost != null) cost.toolCostUsd else 0.0,
            totalCostUsd = current.totalCostUsd + if (!isChatGpt && cost != null) cost.totalCostUsd else 0.0,
            totalInputTokens = current.totalInputTokens + (usage?.inputTokens ?: 0L),
            totalCachedInputTokens = current.totalCachedInputTokens + (usage?.cachedInputTokens ?: 0L),
            totalOutputTokens = current.totalOutputTokens + (usage?.outputTokens ?: 0L),
            totalSearchCalls = current.totalSearchCalls + (usage?.searchCalls ?: 0),
        )
    }
    return totals
}

private fun connectionEndpointKey(connection: ApiConnection): String {
    val endpoint = buildRequestUrl(OpenAIModelConfig().using(connection)).trimEnd('/')
    return "${connection.authentication.label}|$endpoint"
}
