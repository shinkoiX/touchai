package app.touchai.core.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations

@Composable
fun StreamingMarkdown(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 28.sp,
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

    Column(modifier = modifier) {
        if (!isStreaming) {
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
                if (chunk.isComplete) {
                    Markdown(
                        content = chunk.text,
                        colors = colors,
                        typography = typography,
                        modifier = Modifier.fillMaxWidth(),
                        animations = animations,
                        immediate = true,
                    )
                } else {
                    Text(
                        text = chunk.text,
                        modifier = Modifier.fillMaxWidth(),
                        style = bodyStyle,
                    )
                }
            }
        }
    }
}
