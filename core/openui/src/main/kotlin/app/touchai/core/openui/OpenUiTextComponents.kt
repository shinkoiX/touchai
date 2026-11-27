package app.touchai.core.openui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.touchai.core.markdown.StreamingMarkdown
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Composable
internal fun OpenUiTitle(element: OpenUiElement, modifier: Modifier = Modifier) {
    val text = element.props.stringProp("text").orEmpty()
    val size = element.props.stringProp("size") ?: "lg"
    val color = openUiColor(element.props.stringProp("color"), MaterialTheme.colorScheme)
        ?: MaterialTheme.colorScheme.onSurface
    val style = when (size) {
        "sm" -> MaterialTheme.typography.titleSmall.copy(
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        "md" -> MaterialTheme.typography.titleMedium.copy(
            fontSize = 17.sp,
            lineHeight = 24.sp,
            letterSpacing = (-0.2).sp,
            fontWeight = FontWeight.SemiBold,
        )
        "xl" -> MaterialTheme.typography.headlineSmall.copy(
            fontSize = 23.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.4).sp,
            fontWeight = FontWeight.Bold,
        )
        "2xl" -> MaterialTheme.typography.headlineMedium.copy(
            fontSize = 28.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.6).sp,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
        )
        "3xl" -> MaterialTheme.typography.headlineLarge.copy(
            fontSize = 36.sp,
            lineHeight = 42.sp,
            letterSpacing = (-0.8).sp,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
        )
        else -> MaterialTheme.typography.titleLarge.copy(
            fontSize = 20.sp,
            lineHeight = 27.sp,
            letterSpacing = (-0.3).sp,
            fontWeight = FontWeight.Bold,
        )
    }
    Text(text = text, style = style, color = color, modifier = modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpenUiText(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val size = element.props.stringProp("size") ?: "md"
    val color = openUiColor(element.props.stringProp("color"), scheme) ?: scheme.onSurface
    val weight = when (element.props.stringProp("weight")) {
        "medium" -> FontWeight.Medium
        "semibold", "bold" -> FontWeight.SemiBold
        else -> FontWeight.Normal
    }
    val italic = element.props.boolProp("italic") == true
    val lineThrough = element.props.boolProp("lineThrough") == true
    val tabularNums = element.props.boolProp("tabularNums") == true
    val maxLines = element.props.intProp("maxLines") ?: Int.MAX_VALUE

    val baseStyle = when (size) {
        "xs" -> MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 19.sp)
        "sm" -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp)
        "lg" -> MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 27.sp)
        else -> MaterialTheme.typography.bodyMedium.copy(fontSize = 15.5.sp, lineHeight = 25.sp)
    }.copy(
        color = color,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textDecoration = if (lineThrough) TextDecoration.LineThrough else TextDecoration.None,
        fontFeatureSettings = if (tabularNums) "tnum" else null,
    )

    val raw = element.props["text"]
    if (raw is JsonArray) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            raw.forEach { item ->
                when (item) {
                    is JsonPrimitive -> {
                        val str = item.contentOrNull.orEmpty()
                        if (str.isNotEmpty()) {
                            val annotated = remember(str, scheme) { parseInlineMarkdown(str, scheme) }
                            Text(text = annotated, style = baseStyle)
                        }
                    }
                    else -> item.asOpenUiElementOrNull()?.let { RenderOpenUiElement(it, ctx) }
                }
            }
        }
        return
    }

    val str = (raw as? JsonPrimitive)?.contentOrNull.orEmpty()
    val annotated = remember(str, scheme) { parseInlineMarkdown(str, scheme) }
    Text(
        text = annotated,
        style = baseStyle,
        maxLines = maxLines,
        overflow = if (maxLines < Int.MAX_VALUE) TextOverflow.Ellipsis else TextOverflow.Clip,
        modifier = modifier,
    )
}

internal fun parseInlineMarkdown(text: String, scheme: androidx.compose.material3.ColorScheme): AnnotatedString {
    val tokenRegex = Regex("""(\*\*([^*]+)\*\*|\*([^*]+)\*|`([^`]+)`|\[([^\]]+)\]\((https?://[^)\s]+)\))""")
    return buildAnnotatedString {
        var cursor = 0
        for (match in tokenRegex.findAll(text)) {
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            when {
                match.groups[2] != null -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(match.groupValues[2])
                    pop()
                }
                match.groups[3] != null -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(match.groupValues[3])
                    pop()
                }
                match.groups[4] != null -> {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = scheme.surfaceContainerHigh,
                            fontSize = 13.sp,
                        ),
                    )
                    append(" ${match.groupValues[4]} ")
                    pop()
                }
                match.groups[5] != null && match.groups[6] != null -> {
                    pushLink(
                        LinkAnnotation.Url(
                            url = match.groupValues[6],
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = scheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium,
                                ),
                            ),
                        ),
                    )
                    append(match.groupValues[5])
                    pop()
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

@Composable
internal fun OpenUiCaption(element: OpenUiElement, modifier: Modifier = Modifier) {
    val text = element.props.stringProp("text").orEmpty()
    val scheme = MaterialTheme.colorScheme
    val color = openUiColor(element.props.stringProp("color"), scheme)
        ?: scheme.onSurfaceVariant
    val annotated = remember(text, scheme) { parseInlineMarkdown(text, scheme) }
    Text(
        text = annotated,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier,
    )
}

@Composable
internal fun OpenUiLabel(element: OpenUiElement, modifier: Modifier = Modifier) {
    val text = element.props.stringProp("text").orEmpty()
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
internal fun OpenUiBadge(element: OpenUiElement, modifier: Modifier = Modifier) {
    val text = element.props.stringProp("text").orEmpty()
    val colorKey = element.props.stringProp("color") ?: "neutral"
    val iconName = element.props.stringProp("icon")
    val scheme = MaterialTheme.colorScheme
    val bg = openUiColor(colorKey, scheme, asBackground = true) ?: scheme.surfaceContainerHigh
    val fg = openUiColor(colorKey, scheme, asBackground = false) ?: scheme.onSurfaceVariant

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = bg,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (!iconName.isNullOrBlank()) {
                OpenUiGlyph(iconName, sizeDp = 13.dp, tint = fg)
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                fontWeight = FontWeight.Medium,
                color = fg,
            )
        }
    }
}

@Composable
internal fun OpenUiLink(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val text = element.props.stringProp("text").orEmpty()
    val url = element.props.stringProp("url").orEmpty()
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = modifier.clickable(enabled = url.isNotBlank()) { ctx.onOpenUrl(url) },
    )
}

@Composable
internal fun OpenUiList(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val items = element.props.arrayProp("items") ?: return
    val markerStyle = element.props.stringProp("marker") ?: "bullet"
    val timeline = element.props.boolProp("timeline") == true
    val scheme = MaterialTheme.colorScheme

    val isRichList = !timeline && items.any { raw ->
        val el = raw.asOpenUiElementOrNull()
        el != null && (!el.props.stringProp("description").isNullOrBlank() || el.props.elementListProp("children").isNotEmpty())
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (timeline) 0.dp else if (isRichList) 12.dp else 8.dp),
    ) {
        items.forEachIndexed { index, raw ->
            if (isRichList && index > 0) {
                OpenUiDivider()
            }
            val isLast = index == items.lastIndex
            val itemEl = raw.asOpenUiElementOrNull()
            val title = itemEl?.props?.stringProp("title")
                ?: (raw as? JsonPrimitive)?.contentOrNull.orEmpty()
            val description = itemEl?.props?.stringProp("description")
            val customMarker = itemEl?.props?.stringProp("marker")
            val children = itemEl?.props?.elementListProp("children").orEmpty()

            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                if (markerStyle != "none" || !customMarker.isNullOrBlank() || timeline) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(top = 3.dp).width(if (!customMarker.isNullOrBlank() && customMarker.length > 2) 48.dp else 22.dp),
                    ) {
                        when {
                            !customMarker.isNullOrBlank() -> Text(
                                text = customMarker,
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            markerStyle == "number" -> Box(
                                modifier = Modifier.size(20.dp).clip(CircleShape).background(scheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                            else -> Box(
                                modifier = Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(scheme.primary),
                            )
                        }
                        if (timeline && !isLast) {
                            Box(
                                modifier = Modifier
                                    .padding(vertical = 4.dp)
                                    .width(1.5.dp)
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(scheme.outlineVariant),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).padding(bottom = if (timeline && !isLast) 14.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (title.isNotEmpty()) {
                        val annotated = remember(title, scheme) { parseInlineMarkdown(title, scheme) }
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (!description.isNullOrBlank() || children.isNotEmpty()) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                    if (!description.isNullOrBlank()) {
                        val descAnnotated = remember(description, scheme) { parseInlineMarkdown(description, scheme) }
                        Text(
                            text = descAnnotated,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            lineHeight = 20.sp,
                        )
                    }
                    children.forEach { child -> RenderOpenUiElement(child, ctx) }
                }
            }
        }
    }
}

@Composable
internal fun OpenUiListItemStandalone(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val title = element.props.stringProp("title").orEmpty()
    val desc = element.props.stringProp("description")
    val children = element.props.elementListProp("children")
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (!desc.isNullOrBlank()) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        children.forEach { RenderOpenUiElement(it, ctx) }
    }
}

@Composable
internal fun OpenUiMarkdown(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val text = element.props.stringProp("text").orEmpty()
    StreamingMarkdown(
        text = text,
        isStreaming = ctx.isStreaming,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun OpenUiQuote(element: OpenUiElement, modifier: Modifier = Modifier) {
    val text = element.props.stringProp("text").orEmpty()
    val cite = element.props.stringProp("cite")
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerLow.copy(alpha = 0.65f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(scheme.primary.copy(alpha = 0.75f)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            val annotated = remember(text, scheme) { parseInlineMarkdown(text, scheme) }
            Text(
                text = annotated,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 23.sp),
                color = scheme.onSurface.copy(alpha = 0.88f),
            )
            if (!cite.isNullOrBlank()) {
                Text(
                    text = "— $cite",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun OpenUiCode(element: OpenUiElement, modifier: Modifier = Modifier) {
    val code = element.props.stringProp("code").orEmpty()
    val language = element.props.stringProp("language")
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = language?.takeIf { it.isNotBlank() } ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("code", code))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Copy", style = MaterialTheme.typography.labelSmall)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 19.sp,
                    ),
                    color = scheme.onSurface,
                )
            }
        }
    }
}
