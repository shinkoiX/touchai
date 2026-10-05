package app.touchai.core.markdown

data class MarkdownStreamChunk(
    val startOffset: Int,
    val text: String,
    val isComplete: Boolean,
)

fun splitStableMarkdownChunks(text: String): List<MarkdownStreamChunk> {
    val normalizedText = text.replace("\r\n", "\n")
    if (normalizedText.isBlank()) return emptyList()

    val chunks = mutableListOf<MarkdownStreamChunk>()
    var chunkStart = 0
    var lineStart = 0
    var fenceMarker: Char? = null
    var fenceLength = 0

    normalizedText.forEachIndexed { index, character ->
        if (character != '\n') return@forEachIndexed

        val line = normalizedText.substring(lineStart, index)
        val fence = fencePattern.matchEntire(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val suffix = fence.groupValues[2]
            if (fenceMarker == null && (marker.first() != '`' || '`' !in suffix)) {
                fenceMarker = marker.first()
                fenceLength = marker.length
            } else if (marker.first() == fenceMarker && marker.length >= fenceLength && suffix.isBlank()) {
                fenceMarker = null
            }
        }
        if (fenceMarker == null && line.isBlank()) {
            addChunk(
                chunks = chunks,
                source = normalizedText,
                start = chunkStart,
                end = lineStart,
                isComplete = true,
            )
            chunkStart = index + 1
        }
        lineStart = index + 1
    }

    addChunk(
        chunks = chunks,
        source = normalizedText,
        start = chunkStart,
        end = normalizedText.length,
        isComplete = false,
    )
    return chunks
}

private val fencePattern = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

private fun addChunk(
    chunks: MutableList<MarkdownStreamChunk>,
    source: String,
    start: Int,
    end: Int,
    isComplete: Boolean,
) {
    val contentStart = source.indexOfFirstNonNewline(start, end)
    val contentEnd = source.indexAfterLastNonNewline(contentStart, end)
    if (contentStart >= contentEnd) return

    chunks += MarkdownStreamChunk(
        startOffset = contentStart,
        text = source.substring(contentStart, contentEnd),
        isComplete = isComplete,
    )
}

private fun String.indexOfFirstNonNewline(start: Int, end: Int): Int {
    var index = start
    while (index < end && this[index] == '\n') index += 1
    return index
}

private fun String.indexAfterLastNonNewline(start: Int, end: Int): Int {
    var index = end
    while (index > start && this[index - 1] == '\n') index -= 1
    return index
}
