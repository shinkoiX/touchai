package app.touchai.core.openui

import android.content.Context

object OpenUiAssets {
    private const val BUNDLE_ASSET_PATH = "openui/openui-headless.min.js"
    private const val PROMPT_ASSET_PATH = "openui/intelligent-ui-system-prompt.txt"

    @Volatile
    private var cachedBundle: String? = null

    @Volatile
    private var cachedPrompt: String? = null

    fun loadHeadlessBundle(context: Context): String {
        cachedBundle?.let { return it }
        return synchronized(this) {
            cachedBundle ?: context.assets.open(BUNDLE_ASSET_PATH).bufferedReader().use { it.readText() }
                .also { cachedBundle = it }
        }
    }

    fun loadSystemPrompt(context: Context): String {
        cachedPrompt?.let { return it }
        return synchronized(this) {
            cachedPrompt ?: context.assets.open(PROMPT_ASSET_PATH).bufferedReader().use { it.readText() }
                .also { cachedPrompt = it }
        }
    }

    fun mergeInstructions(
        userInstructions: String,
        openUiSystemPrompt: String,
        enabled: Boolean,
        webSearchEnabled: Boolean = false,
    ): String {
        if (!enabled) return userInstructions
        val trimmedUser = userInstructions.trim()
        return buildString {
            if (webSearchEnabled) {
                append(WEB_SEARCH_PRIORITY_PREAMBLE)
                append("\n\n")
            }
            append(openUiSystemPrompt.trimEnd())
            if (webSearchEnabled) {
                append("\n\n")
                append(WEB_SEARCH_PRIORITY_REMINDER)
            }
            if (trimmedUser.isNotEmpty()) {
                append("\n\n## User Additional Instructions\n")
                append(trimmedUser)
            }
        }
    }

    private const val WEB_SEARCH_PRIORITY_PREAMBLE =
        "## Priority Tool Rule: Web Search First\n" +
        "- Web search is ENABLED for this conversation and takes priority over jumping straight to UI generation.\n" +
        "- Whenever the user asks about real-world facts, medical/scientific topics, current events, products, prices, places, people, software/docs, or anything where up-to-date facts or real photos improve accuracy, ALWAYS call your web search tool FIRST before writing openui-lang.\n" +
        "- Do not skip web search just because the final output uses openui-lang. After web search finishes, compose the final answer in valid openui-lang grounded in the search results, reusing any verified real image URLs in Image(...) and real source URLs in Link(...)."

    private const val WEB_SEARCH_PRIORITY_REMINDER =
        "## Reminder Before Answering\n" +
        "- Remember: call web search FIRST whenever external facts, up-to-date data, or real image URLs help answer the request; then emit pure valid openui-lang."
}
