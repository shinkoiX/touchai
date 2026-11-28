package app.touchai.core.openai

import java.util.Locale
import kotlinx.serialization.json.*

/**
 * Normalized token and billable tool usage reported by OpenAI Responses,
 * OpenAI Chat Completions, or Google Gemini Interactions.
 *
 * Conventions:
 * - [inputTokens] is the total input token count (including [cachedInputTokens] and [cacheWriteTokens]).
 * - [outputTokens] is the total billable output token count (including [reasoningTokens]).
 *   For Gemini Interactions, where `total_thought_tokens` is reported separately from
 *   `total_output_tokens`, [extractApiUsage] sums both into [outputTokens].
 */
data class ApiUsage(
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cacheWrite30mTokens: Long = 0,
    val cacheWrite12hTokens: Long = 0,
    val cacheWriteOtherTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val audioInputTokens: Long = 0,
    val toolUseInputTokens: Long = 0,
    val webSearchCalls: Int = 0,
    val googleSearchCalls: Int = 0,
    val imageGenerationCalls: Int = 0,
    val resolvedModel: String? = null,
    val effectiveServiceTier: String? = null,
) {
    val cacheWriteTokens: Long get() = cacheWrite30mTokens + cacheWrite12hTokens + cacheWriteOtherTokens
    val uncachedInputTokens: Long get() = (inputTokens - cachedInputTokens - cacheWriteTokens).coerceAtLeast(0L)
    val totalTokens: Long get() = inputTokens + toolUseInputTokens + outputTokens
    val searchCalls: Int get() = webSearchCalls + googleSearchCalls
    val cacheHitRate: Double? get() = if (inputTokens > 0L) cachedInputTokens.toDouble() / inputTokens.toDouble() else null
    val hasData: Boolean
        get() = inputTokens > 0L || outputTokens > 0L || toolUseInputTokens > 0L || searchCalls > 0 || imageGenerationCalls > 0

    fun toJson(): JsonObject = buildJsonObject {
        put("inputTokens", inputTokens)
        if (cachedInputTokens > 0L) put("cachedInputTokens", cachedInputTokens)
        if (cacheWrite30mTokens > 0L) put("cacheWrite30mTokens", cacheWrite30mTokens)
        if (cacheWrite12hTokens > 0L) put("cacheWrite12hTokens", cacheWrite12hTokens)
        if (cacheWriteOtherTokens > 0L) put("cacheWriteOtherTokens", cacheWriteOtherTokens)
        put("outputTokens", outputTokens)
        if (reasoningTokens > 0L) put("reasoningTokens", reasoningTokens)
        if (audioInputTokens > 0L) put("audioInputTokens", audioInputTokens)
        if (toolUseInputTokens > 0L) put("toolUseInputTokens", toolUseInputTokens)
        if (webSearchCalls > 0) put("webSearchCalls", webSearchCalls)
        if (googleSearchCalls > 0) put("googleSearchCalls", googleSearchCalls)
        if (imageGenerationCalls > 0) put("imageGenerationCalls", imageGenerationCalls)
        resolvedModel?.let { put("resolvedModel", it) }
        effectiveServiceTier?.let { put("effectiveServiceTier", it) }
    }

    companion object {
        fun fromJson(obj: JsonObject): ApiUsage = ApiUsage(
            inputTokens = obj.longValue("inputTokens") ?: 0L,
            cachedInputTokens = obj.longValue("cachedInputTokens") ?: 0L,
            cacheWrite30mTokens = obj.longValue("cacheWrite30mTokens") ?: 0L,
            cacheWrite12hTokens = obj.longValue("cacheWrite12hTokens") ?: 0L,
            cacheWriteOtherTokens = obj.longValue("cacheWriteOtherTokens") ?: 0L,
            outputTokens = obj.longValue("outputTokens") ?: 0L,
            reasoningTokens = obj.longValue("reasoningTokens") ?: 0L,
            audioInputTokens = obj.longValue("audioInputTokens") ?: 0L,
            toolUseInputTokens = obj.longValue("toolUseInputTokens") ?: 0L,
            webSearchCalls = obj.intValue("webSearchCalls") ?: 0,
            googleSearchCalls = obj.intValue("googleSearchCalls") ?: 0,
            imageGenerationCalls = obj.intValue("imageGenerationCalls") ?: 0,
            resolvedModel = obj.string("resolvedModel"),
            effectiveServiceTier = obj.string("effectiveServiceTier"),
        )
    }
}

/** Official estimated USD cost for a single API request. */
data class ApiCost(
    val tokenCostUsd: Double,
    val toolCostUsd: Double,
    val totalCostUsd: Double = tokenCostUsd + toolCostUsd,
    val pricingModel: String,
    val serviceTier: String = "standard",
    val longContext: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("tokenCostUsd", tokenCostUsd)
        put("toolCostUsd", toolCostUsd)
        put("totalCostUsd", totalCostUsd)
        put("pricingModel", pricingModel)
        put("serviceTier", serviceTier)
        if (longContext) put("longContext", true)
    }

    companion object {
        fun fromJson(obj: JsonObject): ApiCost? {
            val total = obj.doubleValue("totalCostUsd") ?: return null
            val token = obj.doubleValue("tokenCostUsd") ?: total
            val tool = obj.doubleValue("toolCostUsd") ?: (total - token).coerceAtLeast(0.0)
            val model = obj.string("pricingModel") ?: ""
            val tier = obj.string("serviceTier") ?: "standard"
            val longCtx = obj["longContext"]?.jsonPrimitive?.booleanOrNull ?: false
            return ApiCost(token, tool, total, model, tier, longCtx)
        }
    }
}

/** Extracts normalized [ApiUsage] from a completed provider response object. */
fun extractApiUsage(
    protocol: ApiProtocol,
    response: JsonObject,
    fallbackModel: String? = null,
    fallbackServiceTier: String? = null,
): ApiUsage? {
    val usage = response["usage"] as? JsonObject
    return when (protocol) {
        ApiProtocol.Responses -> {
            val outputItems = (response["output"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val webSearches = outputItems.count { item ->
                item.string("type") == "web_search_call" &&
                    item.string("status").let { it == null || it == "completed" }
            }
            val imageGens = outputItems.count { item ->
                item.string("type") == "image_generation_call" &&
                    item.string("status").let { it == null || it == "completed" }
            }
            if (usage == null && webSearches == 0 && imageGens == 0) return null
            val inputTokens = usage?.longValue("input_tokens") ?: 0L
            val inputDetails = usage?.get("input_tokens_details") as? JsonObject
            val cached = inputDetails?.longValue("cached_tokens") ?: 0L
            val cacheWriteTotal = inputDetails?.longValue("cache_write_tokens") ?: 0L
            val writeDetails = inputDetails?.get("cache_write_tokens_details") as? JsonObject
            val ttl30m = writeDetails?.longValue("ttl_30m_tokens") ?: 0L
            val ttl12h = writeDetails?.longValue("ttl_12h_tokens") ?: 0L
            val otherWrite = (cacheWriteTotal - ttl30m - ttl12h).coerceAtLeast(0L)
            val outputTokens = usage?.longValue("output_tokens") ?: 0L
            val reasoningTokens = (usage?.get("output_tokens_details") as? JsonObject)?.longValue("reasoning_tokens") ?: 0L
            val audioInput = inputDetails?.longValue("audio_tokens") ?: 0L
            ApiUsage(
                inputTokens = inputTokens,
                cachedInputTokens = cached,
                cacheWrite30mTokens = ttl30m,
                cacheWrite12hTokens = ttl12h,
                cacheWriteOtherTokens = otherWrite,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                audioInputTokens = audioInput,
                webSearchCalls = webSearches,
                imageGenerationCalls = imageGens,
                resolvedModel = response.string("model") ?: fallbackModel?.takeIf(String::isNotBlank),
                effectiveServiceTier = response.string("service_tier") ?: fallbackServiceTier,
            ).takeIf { it.hasData }
        }

        ApiProtocol.ChatCompletions -> {
            if (usage == null) return null
            val inputTokens = usage.longValue("prompt_tokens") ?: usage.longValue("input_tokens") ?: 0L
            val promptDetails = (usage["prompt_tokens_details"] as? JsonObject)
                ?: (usage["input_tokens_details"] as? JsonObject)
            val cached = promptDetails?.longValue("cached_tokens") ?: 0L
            val audioInput = promptDetails?.longValue("audio_tokens") ?: 0L
            val cacheWriteTotal = promptDetails?.longValue("cache_write_tokens") ?: 0L
            val writeDetails = promptDetails?.get("cache_write_tokens_details") as? JsonObject
            val ttl30m = writeDetails?.longValue("ttl_30m_tokens") ?: 0L
            val ttl12h = writeDetails?.longValue("ttl_12h_tokens") ?: 0L
            val otherWrite = (cacheWriteTotal - ttl30m - ttl12h).coerceAtLeast(0L)
            val outputTokens = usage.longValue("completion_tokens") ?: usage.longValue("output_tokens") ?: 0L
            val completionDetails = (usage["completion_tokens_details"] as? JsonObject)
                ?: (usage["output_tokens_details"] as? JsonObject)
            val reasoningTokens = completionDetails?.longValue("reasoning_tokens") ?: 0L
            ApiUsage(
                inputTokens = inputTokens,
                cachedInputTokens = cached,
                cacheWrite30mTokens = ttl30m,
                cacheWrite12hTokens = ttl12h,
                cacheWriteOtherTokens = otherWrite,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                audioInputTokens = audioInput,
                resolvedModel = response.string("model") ?: fallbackModel?.takeIf(String::isNotBlank),
                effectiveServiceTier = response.string("service_tier") ?: fallbackServiceTier,
            ).takeIf { it.hasData }
        }

        ApiProtocol.Gemini -> {
            if (usage == null) return null
            val inputTokens = usage.longValue("total_input_tokens") ?: 0L
            val cached = usage.longValue("total_cached_tokens") ?: 0L
            val rawOutput = usage.longValue("total_output_tokens") ?: 0L
            val thoughtTokens = usage.longValue("total_thought_tokens") ?: 0L
            val toolUseTokens = usage.longValue("total_tool_use_tokens") ?: 0L
            val modalities = (usage["input_tokens_by_modality"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val audioInput = modalities
                .filter { it.string("modality")?.equals("audio", ignoreCase = true) == true }
                .sumOf { it.longValue("tokens") ?: 0L }
            val groundingCounts = (usage["grounding_tool_count"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val googleSearches = groundingCounts
                .filter { it.string("type") == "google_search" }
                .sumOf { it.intValue("count") ?: 0 }
            val modelId = (response.string("model") ?: fallbackModel)?.takeIf(String::isNotBlank)?.let(::geminiModelId)
            ApiUsage(
                inputTokens = inputTokens,
                cachedInputTokens = cached,
                outputTokens = rawOutput + thoughtTokens,
                reasoningTokens = thoughtTokens,
                audioInputTokens = audioInput,
                toolUseInputTokens = toolUseTokens,
                googleSearchCalls = googleSearches,
                resolvedModel = modelId,
                effectiveServiceTier = response.string("service_tier") ?: fallbackServiceTier,
            ).takeIf { it.hasData }
        }
    }
}

/**
 * Estimates official API cost (USD) using built-in OpenAI and Google Gemini rate cards.
 * Returns null for ChatGPT OAuth sessions (subscription plan usage) or unrecognized models.
 */
fun estimateApiCost(
    config: OpenAIModelConfig,
    usage: ApiUsage,
    timestampMillis: Long = System.currentTimeMillis(),
): ApiCost? {
    if (config.authentication == AuthenticationMethod.ChatGpt) return null
    val modelName = usage.resolvedModel?.takeIf(String::isNotBlank) ?: config.model
    return OfficialPricingCatalog.estimate(
        model = modelName,
        protocol = config.requestProtocol,
        usage = usage,
        fallbackServiceTier = wireServiceTier(config.requestProtocol, config.serviceTier),
        timestampMillis = timestampMillis,
    )
}

internal object OfficialPricingCatalog {
    // UTC epoch milliseconds for officially scheduled price transitions.
    private const val DEC_1_2026_UTC = 1796083200_000L // 2026-12-01T00:00:00Z
    private const val JAN_1_2027_UTC = 1798761600_000L // 2027-01-01T00:00:00Z
    private const val MAR_1_2027_PT_UTC = 1803888000_000L // 2027-03-01T00:00:00-08:00

    private const val OPENAI_WEB_SEARCH_PER_CALL = 10.0 / 1000.0
    private const val GEMINI_3_PLUS_SEARCH_PER_CALL = 14.0 / 1000.0
    private const val GEMINI_2_5_SEARCH_PER_CALL = 35.0 / 1000.0

    private data class TierRates(
        val input: Double,
        val output: Double,
        val cachedInput: Double? = null,
        val cacheWrite30m: Double? = null,
        val cacheWrite12h: Double? = null,
        val audioInput: Double? = null,
        val cachedAudioInput: Double? = null,
        val imageOutputPerImage: Double? = null,
    )

    private data class LongContextRule(
        val thresholdTokens: Long,
        val inputMultiplier: Double = 2.0,
        val outputMultiplier: Double = 1.5,
    )

    private data class ModelCard(
        val id: String,
        val provider: Provider,
        val standard: TierRates,
        val flex: TierRates? = null,
        val fast: TierRates? = null, // OpenAI "fast" / "priority", Gemini "priority"
        val ultrafast: TierRates? = null,
        val longContext: LongContextRule? = null,
        val searchPerCallUsd: Double? = null,
    )

    private enum class Provider { OpenAI, Gemini }

    private val openAiLongContext = LongContextRule(thresholdTokens = 272_000L, inputMultiplier = 2.0, outputMultiplier = 1.5)
    private val geminiLongContext = LongContextRule(thresholdTokens = 200_000L, inputMultiplier = 2.0, outputMultiplier = 1.5)

    private val dateSnapshotRegex = Regex("-20\\d{2}-\\d{2}-\\d{2}$")

    fun estimate(
        model: String,
        protocol: ApiProtocol,
        usage: ApiUsage,
        fallbackServiceTier: String?,
        timestampMillis: Long,
    ): ApiCost? {
        val card = findCard(model, timestampMillis) ?: return null
        val requestedTier = normalizeTier(usage.effectiveServiceTier ?: fallbackServiceTier)
        val (tierLabel, baseRates) = selectTier(card, requestedTier)

        val totalInputForThreshold = usage.inputTokens + usage.toolUseInputTokens
        val longRule = card.longContext
        val longCtx = longRule != null && totalInputForThreshold > longRule.thresholdTokens
        val inMult = if (longCtx) longRule.inputMultiplier else 1.0
        val outMult = if (longCtx) longRule.outputMultiplier else 1.0

        val inputRate = baseRates.input * inMult
        val cachedRate = (baseRates.cachedInput ?: baseRates.input) * inMult
        val write30mRate = (baseRates.cacheWrite30m ?: baseRates.input) * inMult
        val write12hRate = (baseRates.cacheWrite12h ?: baseRates.cacheWrite30m ?: baseRates.input) * inMult
        val outputRate = baseRates.output * outMult

        // Handle distinct audio input pricing when present (Gemini Flash models).
        val uncachedInput = usage.uncachedInputTokens
        val audioRate = baseRates.audioInput?.times(inMult)
        val audioTokens = if (audioRate != null) usage.audioInputTokens.coerceAtMost(uncachedInput) else 0L
        val nonAudioUncached = (uncachedInput - audioTokens).coerceAtLeast(0L)

        var tokenCost = 0.0
        tokenCost += nonAudioUncached * inputRate / 1_000_000.0
        if (audioTokens > 0L && audioRate != null) {
            tokenCost += audioTokens * audioRate / 1_000_000.0
        }
        tokenCost += usage.toolUseInputTokens * inputRate / 1_000_000.0
        tokenCost += usage.cachedInputTokens * cachedRate / 1_000_000.0
        tokenCost += usage.cacheWrite30mTokens * write30mRate / 1_000_000.0
        tokenCost += usage.cacheWrite12hTokens * write12hRate / 1_000_000.0
        tokenCost += usage.cacheWriteOtherTokens * write30mRate / 1_000_000.0
        tokenCost += usage.outputTokens * outputRate / 1_000_000.0

        var toolCost = 0.0
        if (usage.webSearchCalls > 0) {
            toolCost += usage.webSearchCalls * OPENAI_WEB_SEARCH_PER_CALL
        }
        if (usage.googleSearchCalls > 0) {
            val perCall = card.searchPerCallUsd ?: when {
                card.id.startsWith("gemini-2.5") -> GEMINI_2_5_SEARCH_PER_CALL
                else -> GEMINI_3_PLUS_SEARCH_PER_CALL
            }
            toolCost += usage.googleSearchCalls * perCall
        }
        if (usage.imageGenerationCalls > 0 && baseRates.imageOutputPerImage != null) {
            toolCost += usage.imageGenerationCalls * baseRates.imageOutputPerImage
        }

        return ApiCost(
            tokenCostUsd = tokenCost,
            toolCostUsd = toolCost,
            totalCostUsd = tokenCost + toolCost,
            pricingModel = card.id,
            serviceTier = tierLabel,
            longContext = longCtx,
        )
    }

    private fun normalizeTier(raw: String?): String {
        val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when (value) {
            "", "default", "standard", "auto", "scale" -> "standard"
            "flex" -> "flex"
            "fast", "priority" -> "fast"
            "ultrafast" -> "ultrafast"
            else -> "standard"
        }
    }

    private fun selectTier(card: ModelCard, tier: String): Pair<String, TierRates> = when (tier) {
        "flex" -> card.flex?.let { "flex" to it } ?: ("standard" to card.standard)
        "fast" -> card.fast?.let { (if (card.provider == Provider.Gemini) "priority" else "fast") to it }
            ?: ("standard" to card.standard)
        "ultrafast" -> card.ultrafast?.let { "ultrafast" to it }
            ?: card.fast?.let { "fast" to it }
            ?: ("standard" to card.standard)
        else -> "standard" to card.standard
    }

    private fun normalizeModelId(raw: String): String {
        val cleaned = raw.trim().lowercase(Locale.ROOT)
            .removePrefix("models/")
            .substringAfterLast('/')
        return cleaned
    }

    private fun findCard(rawModel: String, timestampMillis: Long): ModelCard? {
        val normalized = normalizeModelId(rawModel)
        if (normalized.isEmpty()) return null
        val aliased = aliases[normalized] ?: normalized
        cardFor(aliased, timestampMillis)?.let { return it }
        val withoutDate = dateSnapshotRegex.replace(aliased, "")
        if (withoutDate != aliased) {
            val aliasedNoDate = aliases[withoutDate] ?: withoutDate
            cardFor(aliasedNoDate, timestampMillis)?.let { return it }
        }
        return null
    }

    private val aliases: Map<String, String> = mapOf(
        "gemini-3.1-pro-preview-customtools" to "gemini-3.1-pro-preview",
        "gemini-3.1-flash-lite-preview" to "gemini-3.1-flash-lite",
        "gemini-3.1-flash-image-preview" to "gemini-3.1-flash-image",
        "gemini-3-pro-image-preview" to "gemini-3-pro-image",
        "nano-banana-pro-preview" to "gemini-3-pro-image",
        "gpt-6.2-astra-healthcare" to "gpt-6.2-astra",
        "gpt-6.2-sol-healthcare" to "gpt-6.2-sol",
        "gemma-4-26b-a4b-it" to "gemma-4",
        "gemma-4-31b-it" to "gemma-4",
    )

    private fun openAiWriteTier(input: Double, output: Double, cached: Double): TierRates = TierRates(
        input = input,
        output = output,
        cachedInput = cached,
        cacheWrite30m = input * 1.25,
        cacheWrite12h = input * 2.5,
    )

    private fun cardFor(id: String, timestampMillis: Long): ModelCard? = when (id) {
        // --- OpenAI GPT-6.2 ---
        "gpt-6.2-astra" -> {
            val ultrafast = if (timestampMillis < MAR_1_2027_PT_UTC) {
                openAiWriteTier(24.0, 120.0, 1.2)
            } else {
                openAiWriteTier(48.0, 240.0, 2.4)
            }
            ModelCard(
                id = id,
                provider = Provider.OpenAI,
                standard = openAiWriteTier(8.0, 40.0, 0.4),
                flex = null,
                fast = openAiWriteTier(16.0, 80.0, 0.8),
                ultrafast = ultrafast,
                longContext = openAiLongContext,
            )
        }
        "gpt-6.2-sol" -> {
            val ultrafast = if (timestampMillis < MAR_1_2027_PT_UTC) {
                openAiWriteTier(6.0, 30.0, 0.3)
            } else {
                openAiWriteTier(12.0, 60.0, 0.6)
            }
            ModelCard(
                id = id,
                provider = Provider.OpenAI,
                standard = openAiWriteTier(2.0, 10.0, 0.1),
                flex = null,
                fast = openAiWriteTier(4.0, 20.0, 0.2),
                ultrafast = ultrafast,
                longContext = openAiLongContext,
            )
        }

        // --- OpenAI GPT-6.1 & GPT-6 ---
        "gpt-6.1-sol" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = openAiWriteTier(2.0, 10.0, 0.1),
            flex = openAiWriteTier(1.0, 5.0, 0.05),
            fast = openAiWriteTier(4.0, 20.0, 0.2),
            ultrafast = openAiWriteTier(12.0, 60.0, 0.6),
            longContext = openAiLongContext,
        )
        "gpt-6.1-sol-healthcare" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(2.0, 10.0, 0.1),
            fast = TierRates(4.0, 20.0, 0.2),
            longContext = openAiLongContext,
        )
        "gpt-6.1-terra" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = openAiWriteTier(1.0, 5.0, 0.05),
            flex = openAiWriteTier(0.5, 2.5, 0.025),
            fast = openAiWriteTier(2.0, 10.0, 0.1),
            longContext = openAiLongContext,
        )
        "gpt-6-astra" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = openAiWriteTier(10.0, 50.0, 1.0),
            flex = openAiWriteTier(5.0, 25.0, 0.5),
            fast = openAiWriteTier(20.0, 100.0, 2.0),
            ultrafast = openAiWriteTier(60.0, 300.0, 6.0),
            longContext = openAiLongContext,
        )
        "gpt-6-astra-healthcare" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(10.0, 50.0, 1.0),
            fast = TierRates(20.0, 100.0, 2.0),
            longContext = openAiLongContext,
        )
        "gpt-6-sol" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = openAiWriteTier(2.0, 10.0, 0.2),
            flex = openAiWriteTier(1.0, 5.0, 0.1),
            fast = openAiWriteTier(4.0, 20.0, 0.4),
            longContext = openAiLongContext,
        )
        "gpt-6-luna" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = openAiWriteTier(0.1, 0.5, 0.01),
            flex = openAiWriteTier(0.05, 0.25, 0.005),
            fast = openAiWriteTier(0.2, 1.0, 0.02),
            longContext = openAiLongContext,
        )

        // --- OpenAI GPT-5.6 ---
        "gpt-5.6-sol" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = openAiWriteTier(4.0, 20.0, 0.4),
            flex = openAiWriteTier(2.0, 10.0, 0.2),
            fast = openAiWriteTier(8.0, 40.0, 0.8),
            longContext = openAiLongContext,
        )
        "gpt-5.6-terra" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = openAiWriteTier(2.0, 12.0, 0.2),
            flex = openAiWriteTier(1.0, 6.0, 0.1),
            fast = openAiWriteTier(4.0, 24.0, 0.4),
            longContext = openAiLongContext,
        )
        "gpt-5.6-luna" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = openAiWriteTier(0.2, 1.2, 0.02),
            flex = openAiWriteTier(0.1, 0.6, 0.01),
            fast = openAiWriteTier(0.4, 2.4, 0.04),
            longContext = openAiLongContext,
        )

        // --- OpenAI GPT-5.5 / 5.4 / 5.3 / 5.2 / 5.1 / 5 ---
        "gpt-5.5" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(5.0, 30.0, 0.5),
            flex = TierRates(2.5, 15.0, 0.25),
            fast = TierRates(12.5, 75.0, 1.25),
            longContext = openAiLongContext,
        )
        "gpt-5.5-pro" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(30.0, 180.0),
            flex = TierRates(15.0, 90.0),
            longContext = openAiLongContext,
        )
        "gpt-5.4" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(2.5, 15.0, 0.25),
            flex = TierRates(1.25, 7.5, 0.13),
            fast = TierRates(5.0, 30.0, 0.5),
            longContext = openAiLongContext,
        )
        "gpt-5.4-mini" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(0.75, 4.5, 0.075),
            flex = TierRates(0.375, 2.25, 0.0375),
            fast = TierRates(1.5, 9.0, 0.15),
        )
        "gpt-5.4-nano" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(0.2, 1.25, 0.02),
            flex = TierRates(0.1, 0.625, 0.01),
        )
        "gpt-5.4-pro" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(30.0, 180.0),
            flex = TierRates(15.0, 90.0),
            longContext = openAiLongContext,
        )
        "gpt-5.3-codex" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(1.75, 14.0, 0.175),
            fast = TierRates(3.5, 28.0, 0.35),
        )
        "gpt-5.2" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(1.75, 14.0, 0.175),
            flex = TierRates(0.875, 7.0, 0.0875),
            fast = TierRates(3.5, 28.0, 0.35),
        )
        "gpt-5.2-pro" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(21.0, 168.0),
        )
        "gpt-5.1", "gpt-5" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(1.25, 10.0, 0.125),
            flex = TierRates(0.625, 5.0, 0.0625),
            fast = TierRates(2.5, 20.0, 0.25),
        )
        "gpt-5-mini" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(0.25, 2.0, 0.025),
            flex = TierRates(0.125, 1.0, 0.0125),
            fast = TierRates(0.45, 3.6, 0.045),
        )
        "gpt-5-nano" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(0.05, 0.4, 0.005),
            flex = TierRates(0.025, 0.2, 0.0025),
        )
        "gpt-5-pro" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(15.0, 120.0),
        )
        "gpt-5-search-api" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(1.25, 10.0, 0.125),
        )
        "chat-latest" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(5.0, 30.0, 0.5),
        )

        // --- OpenAI Legacy (o3 / GPT-4.1 / GPT-4o) ---
        "o3" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(2.0, 8.0, 0.5),
            flex = TierRates(1.0, 4.0, 0.25),
            fast = TierRates(3.5, 14.0, 0.875),
        )
        "o3-pro" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(20.0, 80.0),
        )
        "gpt-4.1" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(2.0, 8.0, 0.5),
            fast = TierRates(3.5, 14.0, 0.875),
        )
        "gpt-4.1-mini" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(0.4, 1.6, 0.1),
            fast = TierRates(0.7, 2.8, 0.175),
        )
        "gpt-4o" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(2.5, 10.0, 1.25),
            fast = TierRates(4.25, 17.0, 2.125),
        )
        "gpt-4o-mini" -> ModelCard(
            id = id,
            provider = Provider.OpenAI,
            standard = TierRates(0.15, 0.6, 0.075),
            fast = TierRates(0.25, 1.0, 0.125),
        )

        // --- Google Gemini 4 ---
        "gemini-4-carbon", "gemini-4-argon" -> {
            val promo = timestampMillis < DEC_1_2026_UTC
            ModelCard(
                id = id,
                provider = Provider.Gemini,
                standard = if (promo) TierRates(2.0, 10.0, 0.1) else TierRates(4.0, 20.0, 0.2),
                flex = if (promo) TierRates(1.0, 5.0, 0.05) else TierRates(2.0, 10.0, 0.1),
                fast = if (promo) TierRates(3.6, 18.0, 0.18) else TierRates(7.2, 36.0, 0.36),
                searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
            )
        }
        "gemini-4-neon" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(3.0, 15.0, 0.15),
            flex = TierRates(1.5, 7.5, 0.075),
            fast = TierRates(5.4, 27.0, 0.27),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )
        "gemini-4-hydrogen" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(1.5, 7.5, 0.075),
            flex = TierRates(0.75, 3.75, 0.0375),
            fast = TierRates(2.7, 13.5, 0.135),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )

        // --- Google Gemini 3.x ---
        "gemini-3.8-flash", "gemini-3.6-flash" -> {
            val promo = timestampMillis < JAN_1_2027_UTC
            ModelCard(
                id = id,
                provider = Provider.Gemini,
                standard = if (promo) TierRates(0.75, 3.75, 0.075) else TierRates(1.5, 7.5, 0.15),
                flex = if (promo) TierRates(0.375, 1.875, 0.0375) else TierRates(0.75, 3.75, 0.075),
                fast = if (promo) TierRates(1.35, 6.75, 0.135) else TierRates(2.7, 13.5, 0.27),
                searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
            )
        }
        "gemini-3.5-flash-lite" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.3, 2.5, 0.03),
            flex = TierRates(0.15, 1.25, 0.02),
            fast = TierRates(0.54, 4.5, 0.05),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )
        "gemini-3.1-pro-preview" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(2.0, 12.0, 0.2),
            flex = TierRates(1.0, 6.0, 0.2),
            fast = TierRates(3.6, 21.6, 0.36),
            longContext = geminiLongContext,
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )
        "gemini-3.1-flash-lite" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.25, 1.5, 0.025, audioInput = 0.5, cachedAudioInput = 0.05),
            flex = TierRates(0.125, 0.75, 0.0125, audioInput = 0.25, cachedAudioInput = 0.025),
            fast = TierRates(0.45, 2.7, 0.045, audioInput = 0.9, cachedAudioInput = 0.09),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )
        "gemini-3-flash-preview" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.5, 3.0, 0.05, audioInput = 1.0, cachedAudioInput = 0.1),
            flex = TierRates(0.25, 1.5, 0.05, audioInput = 0.5, cachedAudioInput = 0.1),
            fast = TierRates(0.9, 5.4, 0.09, audioInput = 1.8, cachedAudioInput = 0.18),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )

        // --- Google Gemini 2.5 ---
        "gemini-2.5-pro" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(1.25, 10.0, 0.125),
            flex = TierRates(0.625, 5.0, 0.125),
            fast = TierRates(2.25, 18.0, 0.225),
            longContext = geminiLongContext,
            searchPerCallUsd = GEMINI_2_5_SEARCH_PER_CALL,
        )
        "gemini-2.5-flash" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.3, 2.5, 0.03, audioInput = 1.0, cachedAudioInput = 0.1),
            flex = TierRates(0.15, 1.25, 0.03, audioInput = 0.5, cachedAudioInput = 0.1),
            fast = TierRates(0.54, 4.5, 0.054, audioInput = 1.8, cachedAudioInput = 0.18),
            searchPerCallUsd = GEMINI_2_5_SEARCH_PER_CALL,
        )
        "gemini-2.5-flash-lite" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.1, 0.4, 0.01, audioInput = 0.3, cachedAudioInput = 0.03),
            flex = TierRates(0.05, 0.2, 0.01, audioInput = 0.15, cachedAudioInput = 0.03),
            fast = TierRates(0.18, 0.72, 0.018, audioInput = 0.54, cachedAudioInput = 0.054),
            searchPerCallUsd = GEMINI_2_5_SEARCH_PER_CALL,
        )

        // --- Google Gemini Omni / Image / Gemma ---
        "gemini-omni-1.2-flash", "gemini-omni-1.1-flash" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(1.5, 9.0),
        )
        "gemini-nano-banana-2.1" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(1.5, 7.5),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )
        "gemini-3.1-flash-image" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.5, 3.0),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )
        "gemini-3.1-flash-lite-image" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.25, 1.5),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )
        "gemini-3-pro-image" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(2.0, 12.0),
            searchPerCallUsd = GEMINI_3_PLUS_SEARCH_PER_CALL,
        )
        "gemini-2.5-flash-image" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.3, 0.0, imageOutputPerImage = 0.039),
            flex = TierRates(0.15, 0.0, imageOutputPerImage = 0.0195),
            fast = TierRates(0.54, 0.0, imageOutputPerImage = 0.0702),
        )
        "gemma-4" -> ModelCard(
            id = id,
            provider = Provider.Gemini,
            standard = TierRates(0.0, 0.0, 0.0),
        )
        else -> null
    }
}

private fun JsonObject.longValue(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
private fun JsonObject.intValue(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull
private fun JsonObject.doubleValue(key: String): Double? = (get(key) as? JsonPrimitive)?.doubleOrNull
