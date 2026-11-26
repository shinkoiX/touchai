package app.touchai.android

import java.io.Reader
import java.util.Locale

data class ChatHistorySearchResult(
    val entry: ChatHistoryEntry,
    val snippet: String? = null,
)

internal data class IndexedChatRecord(
    val entry: ChatHistoryEntry,
    val fileSize: Long,
    val fileModified: Long,
    val searchText: String,
)

private val WHITESPACE = Regex("\\s+")

internal fun normalizeSearchSegment(text: String): String = text.replace(WHITESPACE, " ").trim()

internal fun extractSearchText(chat: SavedChat): String = buildString {
    fun appendSegment(value: String?) {
        if (value.isNullOrBlank()) return
        val normalized = normalizeSearchSegment(value)
        if (normalized.isEmpty()) return
        if (isNotEmpty()) append('\n')
        append(normalized)
    }
    // Only index the latest version of a branched conversation tree.
    chat.latestTurns.forEach { turn ->
        appendSegment(turn.user.text)
        appendSegment(turn.answer)
        appendSegment(turn.reasoning)
        appendSegment(turn.presetName)
        turn.citations.forEach { appendSegment(it.title) }
        appendSegment(turn.ai.api.model)
    }
}

internal fun tokenizeSearchQuery(query: String): List<String> =
    query.lowercase(Locale.ROOT).trim().split(WHITESPACE).filter { it.isNotEmpty() }.distinct()

internal fun matchChat(chat: SavedChat, query: String): ChatHistorySearchResult? {
    val terms = tokenizeSearchQuery(query)
    if (terms.isEmpty()) return ChatHistorySearchResult(chat.entry)
    val entry = chat.entry
    val titleLower = entry.title.lowercase(Locale.ROOT)
    val text = extractSearchText(chat)
    val textLower = text.lowercase(Locale.ROOT)
    if (!terms.all { term -> titleLower.contains(term) || textLower.contains(term) }) return null
    return ChatHistorySearchResult(entry, extractSnippet(entry.title, titleLower, text, textLower, terms))
}

internal fun extractSnippet(
    title: String,
    titleLower: String,
    searchText: String,
    searchTextLower: String,
    terms: List<String>,
): String? {
    if (terms.isEmpty() || searchText.isEmpty()) return null
    val alignedCase = searchTextLower.length == searchText.length
    var offset = 0
    var fallbackStart = -1
    var fallbackEnd = -1
    var fallbackMatchIndex = -1
    var fallbackTermLength = 0
    val allInTitle = terms.all { titleLower.contains(it) }

    while (offset <= searchText.length) {
        val lineEnd = searchText.indexOf('\n', offset).let { if (it < 0) searchText.length else it }
        if (lineEnd > offset) {
            var earliest = Int.MAX_VALUE
            var matchedLen = 0
            if (alignedCase) {
                for (term in terms) {
                    val idx = searchTextLower.indexOf(term, offset)
                    if (idx in offset until lineEnd && idx - offset < earliest) {
                        earliest = idx - offset
                        matchedLen = term.length
                    }
                }
            } else {
                val segLower = searchText.substring(offset, lineEnd).lowercase(Locale.ROOT)
                for (term in terms) {
                    val idx = segLower.indexOf(term)
                    if (idx in 0 until earliest) {
                        earliest = idx
                        matchedLen = term.length
                    }
                }
            }
            if (earliest != Int.MAX_VALUE) {
                val segLen = lineEnd - offset
                val sameAsTitle = offset == 0 && segLen == title.length && searchText.regionMatches(0, title, 0, title.length)
                val matchOutsideVisibleTitle = offset == 0 && (earliest + matchedLen > title.length ||
                    displayWidth(searchText, offset, (offset + earliest + matchedLen).coerceAtMost(lineEnd)) > 64)
                if ((!sameAsTitle && (offset > 0 || matchOutsideVisibleTitle || !allInTitle)) || matchOutsideVisibleTitle) {
                    val segment = searchText.substring(offset, lineEnd)
                    return buildSnippetWindow(segment, earliest.coerceAtMost(segment.length), matchedLen)
                }
                if (fallbackStart < 0 && !sameAsTitle) {
                    fallbackStart = offset
                    fallbackEnd = lineEnd
                    fallbackMatchIndex = earliest.coerceAtMost(segLen)
                    fallbackTermLength = matchedLen
                }
            }
        }
        if (lineEnd == searchText.length) break
        offset = lineEnd + 1
    }
    if (fallbackStart >= 0 && !allInTitle) {
        val segment = searchText.substring(fallbackStart, fallbackEnd)
        return buildSnippetWindow(segment, fallbackMatchIndex, fallbackTermLength)
    }
    return null
}

private fun charDisplayWidth(ch: Char): Int = if (ch.code <= 0x7F) 1 else 2

private fun displayWidth(text: String, start: Int, end: Int): Int {
    var width = 0
    for (i in start until end) width += charDisplayWidth(text[i])
    return width
}

/**
 * Builds a compact snippet window keeping the matched keyword near the start (always visible
 * within the first line on a phone screen, even when the match sits at the end of a long CJK paragraph).
 */
private fun buildSnippetWindow(
    segment: String,
    matchStart: Int,
    matchLength: Int,
    maxLeadWidth: Int = 18,
    maxTotalWidth: Int = 120,
): String {
    val safeMatchStart = matchStart.coerceIn(0, segment.length)
    val safeMatchEnd = (safeMatchStart + matchLength).coerceIn(safeMatchStart, segment.length)
    // Walk backwards from matchStart up to maxLeadWidth display units (~9 CJK or ~18 ASCII chars).
    var start = safeMatchStart
    var leadWidth = 0
    while (start > 0) {
        val w = charDisplayWidth(segment[start - 1])
        if (leadWidth + w > maxLeadWidth) break
        start--
        leadWidth += w
    }
    if (start > 0) {
        // Snap forward to a nearby ASCII word boundary when possible without jumping past matchStart.
        val nextSpace = segment.indexOf(' ', start)
        if (nextSpace in (start + 1)..(start + 8) && nextSpace < safeMatchStart) start = nextSpace + 1
    }
    if (start > 0 && Character.isLowSurrogate(segment[start]) && Character.isHighSurrogate(segment[start - 1])) start++

    // Walk forward from start up to maxTotalWidth display units (always covering the full matched term).
    var end = safeMatchEnd
    var totalWidth = displayWidth(segment, start, end)
    while (end < segment.length) {
        val w = charDisplayWidth(segment[end])
        if (totalWidth + w > maxTotalWidth) break
        end++
        totalWidth += w
    }
    if (end < segment.length) {
        val prevSpace = segment.lastIndexOf(' ', end)
        if (prevSpace > safeMatchEnd && prevSpace >= end - 8) end = prevSpace
    }
    if (end < segment.length && Character.isLowSurrogate(segment[end]) && Character.isHighSurrogate(segment[end - 1])) end--

    if (start == 0 && end == segment.length) return segment
    return buildString(end - start + 2) {
        if (start > 0) append('…')
        append(segment, start, end)
        if (end < segment.length) append('…')
    }
}

/**
 * In-memory full-text search index backed by a character 2-gram (bigram) inverted index.
 * Supports both CJK and Latin substring queries in sub-millisecond time while maintaining
 * incremental O(|doc|) updates on upsert/delete.
 */
internal class ChatHistorySearchIndex {
    private class EntryNode(
        val docId: Int,
        val record: IndexedChatRecord,
        val titleLower: String,
        val searchTextLower: String,
        val bigrams: IntArray,
    )

    private var nextDocId = 1
    private val byChatId = HashMap<String, EntryNode>()
    private val byDocId = HashMap<Int, EntryNode>()
    private val allNodes = ArrayList<EntryNode>()
    private val postings = HashMap<Int, MutableSet<Int>>()

    val size: Int get() = byChatId.size

    fun get(id: String): IndexedChatRecord? = byChatId[id]?.record

    fun ids(): Set<String> = byChatId.keys.toSet()

    fun allRecords(): List<IndexedChatRecord> = allNodes.map { it.record }

    fun listEntries(): List<ChatHistoryEntry> =
        allNodes.map { it.record.entry }.sortedByDescending { it.updatedAt }

    fun clear() {
        byChatId.clear()
        byDocId.clear()
        allNodes.clear()
        postings.clear()
        nextDocId = 1
    }

    fun upsert(record: IndexedChatRecord) {
        remove(record.entry.id)
        val docId = nextDocId++
        val titleLower = record.entry.title.lowercase(Locale.ROOT)
        val textLower = record.searchText.lowercase(Locale.ROOT)
        val bigrams = collectBigrams(titleLower, textLower)
        val node = EntryNode(docId, record, titleLower, textLower, bigrams)
        byChatId[record.entry.id] = node
        byDocId[docId] = node
        allNodes.add(node)
        for (gram in bigrams) {
            postings.getOrPut(gram) { HashSet() }.add(docId)
        }
    }

    fun remove(id: String): Boolean {
        val existing = byChatId.remove(id) ?: return false
        byDocId.remove(existing.docId)
        val idx = allNodes.indexOf(existing)
        if (idx >= 0) {
            val last = allNodes.removeAt(allNodes.lastIndex)
            if (idx < allNodes.size) allNodes[idx] = last
        }
        for (gram in existing.bigrams) {
            val bucket = postings[gram] ?: continue
            bucket.remove(existing.docId)
            if (bucket.isEmpty()) postings.remove(gram)
        }
        return true
    }

    fun search(query: String): List<ChatHistorySearchResult> {
        val terms = tokenizeSearchQuery(query)
        if (terms.isEmpty()) return listEntries().map { ChatHistorySearchResult(it) }

        val queryBigrams = collectQueryBigrams(terms)
        data class Ranked(val result: ChatHistorySearchResult, val titleMatched: Boolean)
        val matched = ArrayList<Ranked>()

        fun checkNode(node: EntryNode) {
            for (term in terms) {
                if (!node.titleLower.contains(term) && !node.searchTextLower.contains(term)) return
            }
            var titleMatched = true
            for (term in terms) {
                if (!node.titleLower.contains(term)) { titleMatched = false; break }
            }
            val snippet = extractSnippet(node.record.entry.title, node.titleLower, node.record.searchText, node.searchTextLower, terms)
            matched.add(Ranked(ChatHistorySearchResult(node.record.entry, snippet), titleMatched))
        }

        if (queryBigrams.isEmpty()) {
            for (i in 0 until allNodes.size) checkNode(allNodes[i])
        } else if (queryBigrams.size == 1) {
            val bucket = postings[queryBigrams[0]] ?: return emptyList()
            for (docId in bucket) {
                byDocId[docId]?.let(::checkNode)
            }
        } else {
            val buckets = ArrayList<Set<Int>>(queryBigrams.size)
            for (gram in queryBigrams) {
                val bucket = postings[gram] ?: return emptyList()
                buckets.add(bucket)
            }
            buckets.sortBy { it.size }
            val smallest = buckets[0]
            outer@ for (docId in smallest) {
                for (i in 1 until buckets.size) {
                    if (!buckets[i].contains(docId)) continue@outer
                }
                byDocId[docId]?.let(::checkNode)
            }
        }

        matched.sortWith(compareByDescending<Ranked> { it.titleMatched }.thenByDescending { it.result.entry.updatedAt })
        return matched.map { it.result }
    }

    private fun collectBigrams(titleLower: String, textLower: String): IntArray {
        val seen = HashSet<Int>()
        addBigramsFrom(titleLower, seen)
        addBigramsFrom(textLower, seen)
        val array = IntArray(seen.size)
        var i = 0
        for (gram in seen) array[i++] = gram
        return array
    }

    private fun collectQueryBigrams(terms: List<String>): IntArray {
        val seen = HashSet<Int>()
        for (term in terms) addBigramsFrom(term, seen)
        val array = IntArray(seen.size)
        var i = 0
        for (gram in seen) array[i++] = gram
        return array
    }

    private fun addBigramsFrom(text: String, target: MutableSet<Int>) {
        if (text.length < 2) return
        var prev = text[0]
        for (i in 1 until text.length) {
            val curr = text[i]
            if (!prev.isWhitespace() && !curr.isWhitespace()) {
                target.add((prev.code shl 16) or curr.code)
            }
            prev = curr
        }
    }
}

/**
 * Streaming JSON text extractor for ChatHistoryStore body (line 2 of <id>.jsonl).
 * Extracts only searchable text fields (user text, answer, reasoning, presetName, citation titles,
 * and model) while skipping base64 images and encrypted API keys with O(1) memory overhead.
 */
internal fun extractSearchTextFromBody(reader: Reader): String {
    val segments = StringBuilder()
    fun appendSegment(value: String?) {
        if (value.isNullOrBlank()) return
        val normalized = normalizeSearchSegment(value)
        if (normalized.isEmpty()) return
        if (segments.isNotEmpty()) segments.append('\n')
        segments.append(normalized)
    }
    StreamingBodyScanner(reader).extractTurns(::appendSegment)
    return segments.toString()
}

private class StreamingBodyScanner(private val reader: Reader) {
    private var pushed = -2

    fun extractTurns(onText: (String?) -> Unit) {
        if (peekNonWhitespace() != '{'.code) return
        nextChar() // '{'
        while (true) {
            when (peekNonWhitespace()) {
                '}'.code, -1 -> { nextChar(); return }
                ','.code -> { nextChar(); continue }
            }
            val key = readString() ?: return
            expectColon()
            if (key == "turns") readTurnsArray(onText) else skipValue()
        }
    }

    private fun readTurnsArray(onText: (String?) -> Unit) {
        if (peekNonWhitespace() != '['.code) { skipValue(); return }
        nextChar() // '['
        while (true) {
            when (peekNonWhitespace()) {
                ']'.code, -1 -> { nextChar(); return }
                ','.code -> { nextChar(); continue }
            }
            readTurnObject(onText)
        }
    }

    private fun readTurnObject(onText: (String?) -> Unit) {
        if (peekNonWhitespace() != '{'.code) { skipValue(); return }
        nextChar() // '{'
        var userText: String? = null
        var answer: String? = null
        var reasoning: String? = null
        var presetName: String? = null
        var model: String? = null
        val citationTitles = mutableListOf<String>()
        while (true) {
            when (peekNonWhitespace()) {
                '}'.code, -1 -> { nextChar(); break }
                ','.code -> { nextChar(); continue }
            }
            val key = readString() ?: break
            expectColon()
            when (key) {
                "text" -> userText = readNullableString()
                "answer" -> answer = readNullableString()
                "reasoning" -> reasoning = readNullableString()
                "presetName" -> presetName = readNullableString()
                "citations" -> readCitations(citationTitles)
                "ai" -> model = readAiModel()
                else -> skipValue()
            }
        }
        onText(userText)
        onText(answer)
        onText(reasoning)
        onText(presetName)
        citationTitles.forEach(onText)
        onText(model)
    }

    private fun readCitations(target: MutableList<String>) {
        if (peekNonWhitespace() != '['.code) { skipValue(); return }
        nextChar() // '['
        while (true) {
            when (peekNonWhitespace()) {
                ']'.code, -1 -> { nextChar(); return }
                ','.code -> { nextChar(); continue }
            }
            if (peekNonWhitespace() != '{'.code) { skipValue(); continue }
            nextChar() // '{'
            while (true) {
                when (peekNonWhitespace()) {
                    '}'.code, -1 -> { nextChar(); break }
                    ','.code -> { nextChar(); continue }
                }
                val key = readString() ?: break
                expectColon()
                if (key == "title") readNullableString()?.let(target::add) else skipValue()
            }
        }
    }

    private fun readAiModel(): String? {
        if (peekNonWhitespace() != '{'.code) { skipValue(); return null }
        nextChar() // '{'
        var model: String? = null
        while (true) {
            when (peekNonWhitespace()) {
                '}'.code, -1 -> { nextChar(); return model }
                ','.code -> { nextChar(); continue }
            }
            val key = readString() ?: return model
            expectColon()
            if (key == "model") model = readNullableString() else skipValue()
        }
    }

    private fun readNullableString(): String? {
        val ch = peekNonWhitespace()
        return if (ch == '"'.code) readString() else { skipValue(); null }
    }

    private fun readString(): String? {
        if (peekNonWhitespace() != '"'.code) return null
        nextChar() // '"'
        val out = StringBuilder()
        while (true) {
            val ch = nextChar()
            if (ch == -1 || ch == '"'.code) return out.toString()
            if (ch == '\\'.code) {
                when (val esc = nextChar()) {
                    -1 -> return out.toString()
                    '"'.code -> out.append('"')
                    '\\'.code -> out.append('\\')
                    '/'.code -> out.append('/')
                    'b'.code -> out.append('\b')
                    'f'.code -> out.append('\u000C')
                    'n'.code -> out.append('\n')
                    'r'.code -> out.append('\r')
                    't'.code -> out.append('\t')
                    'u'.code -> {
                        var code = 0
                        for (i in 0 until 4) {
                            val digit = hexValue(nextChar())
                            if (digit < 0) break
                            code = (code shl 4) or digit
                        }
                        out.append(code.toChar())
                    }
                    else -> out.append(esc.toChar())
                }
            } else {
                out.append(ch.toChar())
            }
        }
    }

    private fun skipValue() {
        when (peekNonWhitespace()) {
            -1 -> return
            '"'.code -> skipString()
            '{'.code -> {
                nextChar()
                var depth = 1
                while (depth > 0) {
                    when (val ch = nextChar()) {
                        -1 -> return
                        '"'.code -> skipStringBody()
                        '{'.code -> depth++
                        '}'.code -> depth--
                    }
                }
            }
            '['.code -> {
                nextChar()
                var depth = 1
                while (depth > 0) {
                    when (val ch = nextChar()) {
                        -1 -> return
                        '"'.code -> skipStringBody()
                        '['.code -> depth++
                        ']'.code -> depth--
                    }
                }
            }
            else -> {
                while (true) {
                    val ch = peekChar()
                    if (ch == -1 || ch == ','.code || ch == '}'.code || ch == ']'.code || ch <= ' '.code) return
                    nextChar()
                }
            }
        }
    }

    private fun skipString() {
        if (nextChar() != '"'.code) return
        skipStringBody()
    }

    private fun skipStringBody() {
        while (true) {
            when (nextChar()) {
                -1, '"'.code -> return
                '\\'.code -> if (nextChar() == -1) return
            }
        }
    }

    private fun expectColon() {
        if (peekNonWhitespace() == ':'.code) nextChar()
    }

    private fun peekNonWhitespace(): Int {
        while (true) {
            val ch = peekChar()
            if (ch == -1 || ch > ' '.code) return ch
            nextChar()
        }
    }

    private fun peekChar(): Int {
        if (pushed == -2) pushed = reader.read()
        return pushed
    }

    private fun nextChar(): Int {
        if (pushed != -2) {
            val ch = pushed
            pushed = -2
            return ch
        }
        return reader.read()
    }

    private fun hexValue(ch: Int): Int = when (ch) {
        in '0'.code..'9'.code -> ch - '0'.code
        in 'a'.code..'f'.code -> ch - 'a'.code + 10
        in 'A'.code..'F'.code -> ch - 'A'.code + 10
        else -> -1
    }
}
