package app.touchai.android

import java.util.Locale

enum class SearchMatchSection { User, Reasoning, Answer, Citation }

data class ChatSearchMatch(
    val turnIndex: Int,
    val section: SearchMatchSection,
    val start: Int,
    val end: Int,
    val term: String,
    val citationIndex: Int = 0,
) {
    fun locationLabel(): String = buildString {
        append("T")
        append(turnIndex + 1)
        append(" · ")
        append(
            when (section) {
                SearchMatchSection.User -> "You"
                SearchMatchSection.Reasoning -> "Thoughts"
                SearchMatchSection.Answer -> "Reply"
                SearchMatchSection.Citation -> "Source"
            },
        )
    }
}

/**
 * Finds all non-overlapping case-insensitive occurrences of [terms] in [text], sorted by start offset.
 */
internal fun findTextMatchRanges(text: String, terms: List<String>): List<IntRange> {
    if (text.isEmpty() || terms.isEmpty()) return emptyList()
    val cleaned = terms.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
    if (cleaned.isEmpty()) return emptyList()
    val lower = text.lowercase(Locale.ROOT)
    val raw = mutableListOf<IntRange>()
    for (term in cleaned) {
        var pos = 0
        while (pos < lower.length) {
            val found = lower.indexOf(term, pos)
            if (found < 0) break
            val endExclusive = (found + term.length).coerceAtMost(text.length)
            if (found < endExclusive) raw.add(found until endExclusive)
            pos = found + term.length
        }
    }
    if (raw.isEmpty()) return emptyList()
    raw.sortWith(compareBy<IntRange> { it.first }.thenByDescending { it.last - it.first })
    val result = ArrayList<IntRange>(raw.size)
    var lastEnd = -1
    for (range in raw) {
        if (range.first >= lastEnd) {
            result.add(range)
            lastEnd = range.last + 1
        }
    }
    return result
}

/**
 * Finds every search hit across all turns in reading order, using the exact normalized strings
 * rendered by UserMessage, ReasoningSection, StreamingMarkdown, and citation rows.
 */
internal fun findChatSearchMatches(turns: List<ChatTurn>, query: String): List<ChatSearchMatch> {
    val terms = tokenizeSearchQuery(query)
    if (terms.isEmpty() || turns.isEmpty()) return emptyList()
    val matches = mutableListOf<ChatSearchMatch>()
    turns.forEachIndexed { turnIndex, turn ->
        if (turn.user.text.isNotBlank()) {
            findTextMatchRanges(turn.user.text, terms).forEach { range ->
                matches += ChatSearchMatch(
                    turnIndex = turnIndex,
                    section = SearchMatchSection.User,
                    start = range.first,
                    end = range.last + 1,
                    term = turn.user.text.substring(range.first, range.last + 1),
                )
            }
        }
        val reasoningText = turn.reasoning.trim().replace("\r\n", "\n")
        if (reasoningText.isNotEmpty()) {
            findTextMatchRanges(reasoningText, terms).forEach { range ->
                matches += ChatSearchMatch(
                    turnIndex = turnIndex,
                    section = SearchMatchSection.Reasoning,
                    start = range.first,
                    end = range.last + 1,
                    term = reasoningText.substring(range.first, range.last + 1),
                )
            }
        }
        if (turn.answer.isNotEmpty()) {
            val answerMarkdown = citationMarkdown(turn.answer, turn.citations).replace("\r\n", "\n")
            findTextMatchRanges(answerMarkdown, terms).forEach { range ->
                matches += ChatSearchMatch(
                    turnIndex = turnIndex,
                    section = SearchMatchSection.Answer,
                    start = range.first,
                    end = range.last + 1,
                    term = answerMarkdown.substring(range.first, range.last + 1),
                )
            }
        }
        val sources = turn.citations.distinctBy { it.url }
        sources.forEachIndexed { citationIndex, citation ->
            findTextMatchRanges(citation.title, terms).forEach { range ->
                matches += ChatSearchMatch(
                    turnIndex = turnIndex,
                    section = SearchMatchSection.Citation,
                    start = range.first,
                    end = range.last + 1,
                    term = citation.title.substring(range.first, range.last + 1),
                    citationIndex = citationIndex,
                )
            }
        }
    }
    return matches
}
