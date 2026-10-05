package app.touchai.android

import app.touchai.core.openai.WebCitation

fun citationMarkdown(text: String, citations: List<WebCitation>): String {
    var result = text
    var nextBoundary = text.length
    val ordered = citations.distinct().sortedByDescending { it.startIndex ?: -1 }
    ordered.forEach { source ->
        val start = source.startIndex
        val end = source.endIndex
        if (start != null && end != null && start >= 0 && end > start && end <= nextBoundary) {
            val original = text.substring(start, end)
            val label = if ('\uE200' in original) "Source" else original
            val escaped = label.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("\n", " ")
            val url = source.url.replace("<", "%3C").replace(">", "%3E")
            result = result.substring(0, start) + "[$escaped](<$url>)" + result.substring(end)
            nextBoundary = start
        }
    }
    return result
}
