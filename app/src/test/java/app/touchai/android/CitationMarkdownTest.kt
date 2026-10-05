package app.touchai.android

import app.touchai.core.openai.WebCitation
import org.junit.Assert.*
import org.junit.Test

class CitationMarkdownTest {
    @Test fun citationsBecomeClickableWithoutRemovingTheClaim() {
        val source = WebCitation("https://example.com/source", "Source", 7, 10)
        assertEquals("A fact [\\[1\\]](<https://example.com/source>)", citationMarkdown("A fact [1]", listOf(source)))
    }
    @Test fun invalidAndOverlappingProviderRangesDoNotCorruptText() {
        val source = WebCitation("https://example.com/source", "Source", -1, 100)
        assertEquals("Text", citationMarkdown("Text", listOf(source)))
        val valid = source.copy(startIndex = 0, endIndex = 4)
        assertEquals("[Text](<https://example.com/source>)", citationMarkdown("Text", listOf(valid, valid)))
    }
}
