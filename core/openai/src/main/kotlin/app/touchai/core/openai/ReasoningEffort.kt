package app.touchai.core.openai

import java.util.Locale
import kotlin.math.abs

/** The 6 primary selectable reasoning effort tiers, ordered from lowest to highest thinking depth. */
val ReasoningEffortTiers: List<String> = listOf("none", "low", "medium", "high", "xhigh", "max")

/**
 * Full canonical effort scale across OpenAI (GPT-5.x/6, o-series), Google Gemini (3/3.1/4),
 * and Anthropic/OpenAI-compatible gateways, ordered from lowest to highest thinking depth.
 */
internal val CanonicalEffortScale: List<String> = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

private val GeminiEffortScale: List<String> = listOf("minimal", "low", "medium", "high", "xhigh")

private val EffortTokenRegex = Regex("""\b(none|minimal|low|medium|high|xhigh|max)\b""")
private val SupportedClauseRegex = Regex(
    """(?:supported|allowed|valid|accepted|available|must be|one of|expected)[^.\n]*""",
    RegexOption.IGNORE_CASE,
)

internal fun isReasoningEffortRejected(config: OpenAIModelConfig, body: String): Boolean {
    val requested = config.reasoningEffort?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val requestedLower = requested.lowercase(Locale.ROOT)
    val combined = listOfNotNull(errorMessage(body), body).joinToString(" ").lowercase(Locale.ROOT)
    val mentionsSummaryOnly = ("reasoning.summary" in combined || "reasoning summary" in combined ||
        "reasoning summaries" in combined || "thinking_summaries" in combined) &&
        "effort" !in combined && "thinking_level" !in combined
    if (mentionsSummaryOnly) return false

    val mentionsEffortParam = "reasoning.effort" in combined || "reasoning_effort" in combined ||
        "thinking_level" in combined || "thinking_config" in combined ||
        Regex("""\beffort\b""").containsMatchIn(combined)
    val mentionsRejection = "unsupported" in combined || "not supported" in combined ||
        "does not support" in combined || "invalid" in combined || "unrecognized" in combined ||
        "unknown" in combined || "allowed" in combined || "must be" in combined ||
        "one of" in combined || "expected" in combined || "cannot" in combined ||
        "enum" in combined || "out of range" in combined
    if (mentionsEffortParam && mentionsRejection) return true

    // OpenAI often reports `"Unsupported value: 'max' is not supported with this model. Supported values are: ..."`
    // without repeating the word "effort" in error.message.
    val quotesRequestedEffort = requestedLower in CanonicalEffortScale && (
        "'$requestedLower'" in combined || "\"$requestedLower\"" in combined || "`$requestedLower`" in combined
    )
    if (quotesRequestedEffort && mentionsRejection && parseSupportedEffortsFromError(body, requested).isNotEmpty()) {
        return true
    }

    // Some non-reasoning models reject the whole `reasoning` / `thinking` parameter.
    return ("reasoning" in combined || "thinking" in combined) &&
        ("not supported" in combined || "unsupported" in combined || "does not support" in combined || "unrecognized" in combined)
}

internal fun parseSupportedEffortsFromError(body: String, rejectedEffort: String): List<String> {
    val text = (errorMessage(body) ?: body).lowercase(Locale.ROOT)
    val rejected = rejectedEffort.trim().lowercase(Locale.ROOT)
    val found = LinkedHashSet<String>()
    for (clause in SupportedClauseRegex.findAll(text)) {
        for (match in EffortTokenRegex.findAll(clause.value)) {
            val token = match.groupValues[1]
            if (token != rejected) found.add(token)
        }
    }
    return CanonicalEffortScale.filter { it in found }
}

internal fun nearestEffort(target: String, candidates: Collection<String>): String? {
    if (candidates.isEmpty()) return null
    val normalizedTarget = target.trim().lowercase(Locale.ROOT)
    val targetIndex = CanonicalEffortScale.indexOf(normalizedTarget)
        .let { if (it >= 0) it else CanonicalEffortScale.indexOf("medium") }
    return candidates
        .map { it.trim().lowercase(Locale.ROOT) }
        .distinct()
        .minWithOrNull(
            compareBy<String> { candidate ->
                val idx = CanonicalEffortScale.indexOf(candidate)
                if (idx >= 0) abs(idx - targetIndex) else Int.MAX_VALUE
            }.thenBy { candidate ->
                // Tie-break: step inward toward the safer/faster lower tier first.
                CanonicalEffortScale.indexOf(candidate).let { if (it >= 0) it else Int.MAX_VALUE }
            },
        )
}

internal data class EffortFallbackDecision(val shouldRetry: Boolean, val nextEffort: String?)

/**
 * Selects the nearest untried reasoning effort after HTTP 400 rejects [requested].
 * Uses server-reported supported values when present in [errorBody]; otherwise walks the
 * protocol-appropriate canonical ladder and ultimately falls back to `null` (model default).
 */
internal fun nextFallbackEffort(
    protocol: ApiProtocol,
    requested: String,
    tried: Set<String?>,
    errorBody: String,
): EffortFallbackDecision {
    val serverSupported = parseSupportedEffortsFromError(errorBody, requested)
        .filter { it !in tried }
    if (serverSupported.isNotEmpty()) {
        return EffortFallbackDecision(true, nearestEffort(requested, serverSupported))
    }

    val lower = (errorMessage(errorBody) ?: errorBody).lowercase(Locale.ROOT)
    val wholeFeatureUnsupported = ("reasoning" in lower || "thinking" in lower) &&
        ("not supported" in lower || "does not support" in lower || "unsupported parameter" in lower) &&
        "effort" !in lower && "thinking_level" !in lower
    if (wholeFeatureUnsupported) {
        return if (null !in tried) EffortFallbackDecision(true, null) else EffortFallbackDecision(false, null)
    }

    val scale = if (protocol == ApiProtocol.Gemini) GeminiEffortScale else CanonicalEffortScale
    val untried = scale.filter { it !in tried }
    val concreteRetriesDone = tried.count { it != null } - 1
    if (untried.isNotEmpty() && concreteRetriesDone < 3) {
        return EffortFallbackDecision(true, nearestEffort(requested, untried))
    }
    return if (null !in tried) EffortFallbackDecision(true, null) else EffortFallbackDecision(false, null)
}
