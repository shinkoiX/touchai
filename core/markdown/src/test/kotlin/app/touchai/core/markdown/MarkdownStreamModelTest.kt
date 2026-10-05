package app.touchai.core.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownStreamModelTest {
    @Test
    fun shorterOrDifferentFenceDoesNotEndTheCodeBlock() {
        val text = "````markdown\n```kotlin\n\n~~~\n\n```\n````\n\nTail"
        val chunks = splitStableMarkdownChunks(text)
        assertEquals(2, chunks.size)
        assertEquals("````markdown\n```kotlin\n\n~~~\n\n```\n````", chunks.first().text)
        assertEquals("Tail", chunks.last().text)
    }

    @Test
    fun keepsCompletedBlocksStableWhileTheTailGrows() {
        val first = splitStableMarkdownChunks("## Heading\n\nStreaming")
        val second = splitStableMarkdownChunks("## Heading\n\nStreaming text")

        assertEquals(MarkdownStreamChunk(0, "## Heading", true), first[0])
        assertEquals(first[0], second[0])
        assertEquals(MarkdownStreamChunk(12, "Streaming text", false), second[1])
    }

    @Test
    fun doesNotSplitBlankLinesInsideCodeFences() {
        assertEquals(
            listOf(
                MarkdownStreamChunk(
                    startOffset = 0,
                    text = "```kotlin\nval first = 1\n\nval second = 2\n```",
                    isComplete = true,
                ),
                MarkdownStreamChunk(
                    startOffset = 45,
                    text = "Paragraph",
                    isComplete = false,
                ),
            ),
            splitStableMarkdownChunks(
                "```kotlin\nval first = 1\n\nval second = 2\n```\n\nParagraph",
            ),
        )
    }

    @Test
    fun handlesTextEndingWithNewlines() {
        assertEquals(
            listOf(MarkdownStreamChunk(0, "Paragraph", true)),
            splitStableMarkdownChunks("Paragraph\n\n"),
        )
    }
}
