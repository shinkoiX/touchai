package app.touchai.core.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import java.util.Locale

private val MatchHighlightStyle = SpanStyle(
    background = Color(0x66FFD54F),
    fontWeight = FontWeight.Medium,
)

private val ActiveMatchHighlightStyle = SpanStyle(
    background = Color(0xFFFFB300),
    color = Color(0xFF1A1200),
    fontWeight = FontWeight.Bold,
)

@Composable
fun StreamingMarkdown(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 28.sp,
    highlightTerms: List<String> = emptyList(),
    activeMatchOffset: Int? = null,
    onActiveMatchPositioned: ((coords: LayoutCoordinates, yOffsetPx: Float) -> Unit)? = null,
) {
    val chunks = remember(text) { splitStableMarkdownChunks(text) }
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = textColor,
        fontSize = fontSize,
        lineHeight = lineHeight,
    )
    val colors = markdownColor(
        text = textColor,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
        dividerColor = MaterialTheme.colorScheme.outline,
        tableBackground = MaterialTheme.colorScheme.surfaceVariant,
    )
    val codeSize = (fontSize.value - 2f).coerceAtLeast(11f).sp
    val typography = markdownTypography(
        h1 = bodyStyle.copy(fontSize = (fontSize.value + 4f).sp, fontWeight = FontWeight.SemiBold),
        h2 = bodyStyle.copy(fontSize = (fontSize.value + 2f).sp, fontWeight = FontWeight.SemiBold),
        h3 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        h4 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        h5 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        h6 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        text = bodyStyle,
        code = bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = codeSize),
        inlineCode = bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = codeSize),
        quote = bodyStyle.copy(
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        ),
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        textLink = TextLinkStyles(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
            ),
        ),
        table = bodyStyle,
    )
    val animations = markdownAnimations(animateTextSize = { this })
    val hasHighlights = highlightTerms.isNotEmpty()

    Column(modifier = modifier) {
        if (!isStreaming && !hasHighlights) {
            Markdown(
                content = text,
                colors = colors,
                typography = typography,
                modifier = Modifier.fillMaxWidth(),
                animations = animations,
                immediate = true,
            )
            return@Column
        }
        chunks.forEach { chunk ->
            key(chunk.startOffset) {
                val activeOffsetInChunk = activeMatchOffset
                    ?.takeIf { it in chunk.startOffset until (chunk.startOffset + chunk.text.length) }
                    ?.minus(chunk.startOffset)
                val chunkHasMatch = hasHighlights && findHighlightRanges(chunk.text, highlightTerms).isNotEmpty()
                val reportedNodePosition = remember(chunk.startOffset, activeOffsetInChunk) { BooleanArray(1) }
                var chunkCoords by remember(chunk.startOffset) { mutableStateOf<LayoutCoordinates?>(null) }
                LaunchedEffect(activeOffsetInChunk, chunkCoords) {
                    val offset = activeOffsetInChunk ?: return@LaunchedEffect
                    val coords = chunkCoords ?: return@LaunchedEffect
                    if (!coords.isAttached || reportedNodePosition[0] || onActiveMatchPositioned == null) return@LaunchedEffect
                    val fraction = (offset.toFloat() / chunk.text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
                    onActiveMatchPositioned(coords, coords.size.height * fraction)
                }
                val chunkModifier = if (activeOffsetInChunk != null && onActiveMatchPositioned != null) {
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            chunkCoords = coords
                            if (!reportedNodePosition[0]) {
                                val fraction = (activeOffsetInChunk.toFloat() / chunk.text.length.coerceAtLeast(1)).coerceIn(0f, 1f)
                                onActiveMatchPositioned(coords, coords.size.height * fraction)
                            }
                        }
                } else {
                    Modifier.fillMaxWidth()
                }
                if (!isStreaming || chunk.isComplete) {
                    val components = if (chunkHasMatch) {
                        highlightedMarkdownComponents(
                            highlightTerms = highlightTerms,
                            activeOffsetInChunk = activeOffsetInChunk,
                            onNodePositioned = if (activeOffsetInChunk != null && onActiveMatchPositioned != null) {
                                { coords, yOffsetPx ->
                                    reportedNodePosition[0] = true
                                    onActiveMatchPositioned(coords, yOffsetPx)
                                }
                            } else null,
                        )
                    } else {
                        markdownComponents()
                    }
                    Box(chunkModifier) {
                        Markdown(
                            content = chunk.text,
                            colors = colors,
                            typography = typography,
                            components = components,
                            modifier = Modifier.fillMaxWidth(),
                            animations = animations,
                            immediate = true,
                        )
                    }
                } else {
                    val styled = remember(chunk.text, highlightTerms, activeOffsetInChunk) {
                        if (!chunkHasMatch) AnnotatedString(chunk.text)
                        else applyHighlightSpans(
                            base = AnnotatedString(chunk.text),
                            terms = highlightTerms,
                            activeOrdinal = activeOffsetInChunk?.let { offset ->
                                findHighlightRanges(chunk.text.substring(0, offset.coerceIn(0, chunk.text.length)), highlightTerms).size
                            },
                        )
                    }
                    Text(
                        text = styled,
                        modifier = chunkModifier,
                        style = bodyStyle,
                    )
                }
            }
        }
    }
}

private fun highlightedMarkdownComponents(
    highlightTerms: List<String>,
    activeOffsetInChunk: Int?,
    onNodePositioned: ((coords: LayoutCoordinates, yOffsetPx: Float) -> Unit)?,
): MarkdownComponents {
    @Composable
    fun HighlightedNodeText(
        content: String,
        node: ASTNode,
        style: TextStyle,
        contentChildType: IElementType? = null,
    ) {
        val targetNode = contentChildType?.let { node.findChildOfType(it) } ?: node
        val settings = annotatorSettings()
        val containsActive = activeOffsetInChunk != null && activeOffsetInChunk in node.startOffset until node.endOffset
        val activeOrdinal = remember(content, node.startOffset, node.endOffset, highlightTerms, activeOffsetInChunk) {
            if (!containsActive || activeOffsetInChunk == null) null
            else {
                val prefixStart = targetNode.startOffset.coerceIn(0, content.length)
                val prefixEnd = activeOffsetInChunk.coerceIn(prefixStart, content.length)
                findHighlightRanges(content.substring(prefixStart, prefixEnd), highlightTerms).size
            }
        }
        val base = remember(content, targetNode.startOffset, targetNode.endOffset, style, settings) {
            content.buildMarkdownAnnotatedString(targetNode, style, settings)
        }
        val highlighted = remember(base, highlightTerms, activeOrdinal) {
            applyHighlightSpans(base, highlightTerms, activeOrdinal)
        }
        var nodeCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        LaunchedEffect(containsActive, activeOffsetInChunk, nodeCoords) {
            val coords = nodeCoords ?: return@LaunchedEffect
            if (!containsActive || onNodePositioned == null || !coords.isAttached) return@LaunchedEffect
            val span = (node.endOffset - node.startOffset).coerceAtLeast(1)
            val fraction = (((activeOffsetInChunk ?: node.startOffset) - node.startOffset).toFloat() / span).coerceIn(0f, 1f)
            onNodePositioned(coords, coords.size.height * fraction)
        }
        val modifier = if (containsActive && onNodePositioned != null) {
            Modifier.onGloballyPositioned { coords ->
                nodeCoords = coords
                val span = (node.endOffset - node.startOffset).coerceAtLeast(1)
                val fraction = (((activeOffsetInChunk ?: node.startOffset) - node.startOffset).toFloat() / span).coerceIn(0f, 1f)
                onNodePositioned(coords, coords.size.height * fraction)
            }
        } else {
            Modifier
        }
        MarkdownText(
            content = highlighted,
            node = targetNode,
            modifier = modifier,
            style = style,
        )
    }

    @Composable
    fun HighlightedCodeBlock(
        code: String,
        node: ASTNode,
        content: String,
        style: TextStyle,
    ) {
        val containsActive = activeOffsetInChunk != null && activeOffsetInChunk in node.startOffset until node.endOffset
        val activeOrdinal = remember(content, node.startOffset, node.endOffset, highlightTerms, activeOffsetInChunk) {
            if (!containsActive || activeOffsetInChunk == null) null
            else {
                val rawPrefix = content.substring(node.startOffset.coerceIn(0, content.length), activeOffsetInChunk.coerceIn(node.startOffset, content.length))
                findHighlightRanges(rawPrefix, highlightTerms).size
            }
        }
        val highlighted = remember(code, highlightTerms, activeOrdinal) {
            applyHighlightSpans(AnnotatedString(code), highlightTerms, activeOrdinal)
        }
        var codeCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        LaunchedEffect(containsActive, activeOffsetInChunk, codeCoords) {
            val coords = codeCoords ?: return@LaunchedEffect
            if (!containsActive || onNodePositioned == null || !coords.isAttached) return@LaunchedEffect
            val span = (node.endOffset - node.startOffset).coerceAtLeast(1)
            val fraction = (((activeOffsetInChunk ?: node.startOffset) - node.startOffset).toFloat() / span).coerceIn(0f, 1f)
            onNodePositioned(coords, coords.size.height * fraction)
        }
        val modifier = if (containsActive && onNodePositioned != null) {
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    codeCoords = coords
                    val span = (node.endOffset - node.startOffset).coerceAtLeast(1)
                    val fraction = (((activeOffsetInChunk ?: node.startOffset) - node.startOffset).toFloat() / span).coerceIn(0f, 1f)
                    onNodePositioned(coords, coords.size.height * fraction)
                }
        } else {
            Modifier.fillMaxWidth()
        }
        MarkdownCodeBackground(
            color = LocalMarkdownColors.current.codeBackground,
            modifier = modifier.padding(vertical = 8.dp),
        ) {
            Text(
                text = highlighted,
                style = style,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            )
        }
    }

    return markdownComponents(
        text = { model -> HighlightedNodeText(model.content, model.node, model.typography.text) },
        paragraph = { model -> HighlightedNodeText(model.content, model.node, model.typography.paragraph) },
        heading1 = { model -> HighlightedNodeText(model.content, model.node, model.typography.h1, MarkdownTokenTypes.ATX_CONTENT) },
        heading2 = { model -> HighlightedNodeText(model.content, model.node, model.typography.h2, MarkdownTokenTypes.ATX_CONTENT) },
        heading3 = { model -> HighlightedNodeText(model.content, model.node, model.typography.h3, MarkdownTokenTypes.ATX_CONTENT) },
        heading4 = { model -> HighlightedNodeText(model.content, model.node, model.typography.h4, MarkdownTokenTypes.ATX_CONTENT) },
        heading5 = { model -> HighlightedNodeText(model.content, model.node, model.typography.h5, MarkdownTokenTypes.ATX_CONTENT) },
        heading6 = { model -> HighlightedNodeText(model.content, model.node, model.typography.h6, MarkdownTokenTypes.ATX_CONTENT) },
        setextHeading1 = { model -> HighlightedNodeText(model.content, model.node, model.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT) },
        setextHeading2 = { model -> HighlightedNodeText(model.content, model.node, model.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT) },
        codeFence = { model ->
            MarkdownCodeFence(model.content, model.node, model.typography.code) { code, _, style ->
                HighlightedCodeBlock(code, model.node, model.content, style)
            }
        },
        codeBlock = { model ->
            MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, _, style ->
                HighlightedCodeBlock(code, model.node, model.content, style)
            }
        },
    )
}

fun findHighlightRanges(text: String, terms: List<String>): List<IntRange> {
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

fun applyHighlightSpans(
    base: AnnotatedString,
    terms: List<String>,
    activeOrdinal: Int? = null,
    matchStyle: SpanStyle = MatchHighlightStyle,
    activeMatchStyle: SpanStyle = ActiveMatchHighlightStyle,
): AnnotatedString {
    val ranges = findHighlightRanges(base.text, terms)
    if (ranges.isEmpty()) return base
    val builder = AnnotatedString.Builder(base)
    ranges.forEachIndexed { index, range ->
        val style = if (activeOrdinal != null && index == activeOrdinal) activeMatchStyle else matchStyle
        builder.addStyle(style, range.first, range.last + 1)
    }
    return builder.toAnnotatedString()
}
