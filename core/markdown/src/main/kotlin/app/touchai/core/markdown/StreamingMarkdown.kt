package app.touchai.core.markdown

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
) {
    val chunks = remember(text) { splitStableMarkdownChunks(text) }
    val bodyStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        lineHeight = 28.sp,
    )
    val colors = markdownColor(
        text = MaterialTheme.colorScheme.onSurface,
        codeBackground = MaterialTheme.colorScheme.surfaceVariant,
        inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
        dividerColor = MaterialTheme.colorScheme.outline,
        tableBackground = MaterialTheme.colorScheme.surfaceVariant,
    )
    val typography = markdownTypography(
        h1 = bodyStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        h2 = bodyStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        h3 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        h4 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        h5 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        h6 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
        text = bodyStyle,
        code = bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        inlineCode = bodyStyle.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
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
