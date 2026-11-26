package app.touchai.core.openai

import java.util.Locale

val OpenAiServiceTiers: List<String?> = listOf(null, "flex", "fast", "ultrafast")
val GeminiServiceTiers: List<String?> = listOf(null, "flex", "priority")

/** Maps the user's configured [serviceTier] to the exact wire value expected by [protocol]. */
fun wireServiceTier(protocol: ApiProtocol, serviceTier: String?): String? {
    val normalized = serviceTier?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return null
    return when (protocol) {
        ApiProtocol.Gemini -> when (normalized) {
            "standard", "default", "auto" -> null
            "fast", "ultrafast", "priority" -> "priority"
            else -> normalized
        }
        ApiProtocol.Responses, ApiProtocol.ChatCompletions -> when (normalized) {
            "standard", "default", "auto" -> null
            else -> normalized
        }
    }
}

internal fun isServiceTierRejected(config: OpenAIModelConfig, body: String): Boolean {
    if (config.authentication == AuthenticationMethod.ChatGpt) return false
    val requested = wireServiceTier(config.requestProtocol, config.serviceTier) ?: return false
    val combined = listOfNotNull(errorMessage(body), body).joinToString(" ").lowercase(Locale.ROOT)
    val mentionsParam = "service_tier" in combined || "service tier" in combined
    val mentionsRejection = "unsupported" in combined || "not supported" in combined ||
        "does not support" in combined || "invalid" in combined || "unrecognized" in combined ||
        "unknown" in combined || "allowed" in combined || "must be" in combined ||
        "one of" in combined || "expected" in combined || "cannot" in combined ||
        "enum" in combined || "not available" in combined
    if (mentionsParam && mentionsRejection) return true

    val quotesValue = "'$requested'" in combined || "\"$requested\"" in combined || "`$requested`" in combined
    return quotesValue && mentionsRejection && ("tier" in combined || "priority" in combined || "fast" in combined || "flex" in combined)
}

internal data class ServiceTierFallbackDecision(val shouldRetry: Boolean, val nextTier: String?)

/**
 * Selects the next fallback service tier after HTTP 400 rejects the current wire tier.
 * - OpenAI: `ultrafast` -> `fast` -> `priority` (legacy alias on older gateways) -> `null` (Default).
 * - Gemini: `priority` / `flex` -> `null` (Default / Standard).
 */
internal fun nextFallbackServiceTier(
    protocol: ApiProtocol,
    currentTier: String?,
    tried: Set<String?>,
): ServiceTierFallbackDecision {
    val current = wireServiceTier(protocol, currentTier)
    val candidates: List<String?> = when (protocol) {
        ApiProtocol.Gemini -> listOf(null)
        ApiProtocol.Responses, ApiProtocol.ChatCompletions -> when (current) {
            "ultrafast" -> listOf("fast", "priority", null)
            "fast" -> listOf("priority", null)
            else -> listOf(null)
        }
    }
    val next = candidates.firstOrNull { it !in tried }
    return if (next != null || (null in candidates && null !in tried)) {
        ServiceTierFallbackDecision(true, next)
    } else {
        ServiceTierFallbackDecision(false, null)
    }
}
