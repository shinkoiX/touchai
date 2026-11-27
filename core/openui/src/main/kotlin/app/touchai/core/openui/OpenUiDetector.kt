package app.touchai.core.openui

/**
 * Detects whether an assistant message is openui-lang and extracts the clean program text
 * (handling both bare openui-lang responses and responses wrapped in a markdown code fence).
 */
object OpenUiDetector {
    private val rootLineRegex = Regex("""(?m)^\s*root\s*=\s*[A-Za-z_$]""")
    private val statementStartRegex = Regex("""^\s*(?:root|\$[A-Za-z_][A-Za-z0-9_]*|[A-Za-z_][A-Za-z0-9_]*)\s*=""")
    private val openFenceRegex = Regex("""^```(?:openui(?:-lang)?|lang)?\s*\r?\n""", RegexOption.IGNORE_CASE)
    private val closeFenceRegex = Regex("""\r?\n```\s*$""")

    fun extractProgram(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val unwrapped = if (trimmed.startsWith("```")) {
            val match = openFenceRegex.find(trimmed) ?: return null
            val body = trimmed.substring(match.range.last + 1)
            body.replace(closeFenceRegex, "").trim()
        } else {
            trimmed
        }

        if (unwrapped.isEmpty()) return null
        if (unwrapped.startsWith("root =") || unwrapped.startsWith("root=")) {
            return unwrapped
        }
        // During early streaming or when $vars precede root=, require the first line to be a valid assignment
        // AND a root = statement to be present.
        if (statementStartRegex.containsMatchIn(unwrapped) && rootLineRegex.containsMatchIn(unwrapped)) {
            return unwrapped
        }
        return null
    }

    /**
     * Returns true if the streaming prefix already looks like it is starting an openui-lang response
     * (used to avoid flashing raw `root = Col(...)` text while the very first line is still arriving).
     */
    fun looksLikeStreamingOpenUi(raw: String): Boolean {
        val trimmed = raw.trimStart()
        if (trimmed.isEmpty()) return false
        if (extractProgram(trimmed) != null) return true
        val check = if (trimmed.startsWith("```")) {
            val newline = trimmed.indexOf('\n')
            if (newline < 0) return trimmed.startsWith("```openui", ignoreCase = true)
            trimmed.substring(newline + 1).trimStart()
        } else {
            trimmed
        }
        return "root =".startsWith(check) || check.startsWith("root =") || check.startsWith("root=")
    }
}
