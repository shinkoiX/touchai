package app.touchai.core.openui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.floor

@Composable
internal fun OpenUiCol(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
    isRoot: Boolean = false,
) {
    val children = element.props.elementListProp("children")
    val defaultGap = if (isRoot) 20.dp else 8.dp
    val gap = openUiStepDp(element.props.intProp("gap"), defaultGap)
    val align = when (element.props.stringProp("align")) {
        "center" -> Alignment.CenterHorizontally
        "end" -> Alignment.End
        else -> Alignment.Start
    }
    // When the model stacks multiple itemized Cards vertically inside a Col, render them as
    // clean borderless sections separated by subtle hairlines instead of stacking heavy boxes.
    val cardCount = children.count { it.typeName == "Card" }
    val flattenStackedCards = cardCount >= 2

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (flattenStackedCards) 14.dp else gap),
        horizontalAlignment = align,
    ) {
        children.forEachIndexed { index, child ->
            val stableKey = child.componentId ?: "${child.typeName}:$index"
            key(stableKey) {
                if (child.typeName == "Spacer") {
                    Spacer(Modifier.height(gap.coerceAtLeast(8.dp)))
                } else if (flattenStackedCards && child.typeName == "Card") {
                    if (index > 0 && children[index - 1].typeName == "Card") {
                        OpenUiDivider()
                    }
                    OpenUiFlatSection(child, ctx)
                } else {
                    RenderOpenUiElement(child, ctx)
                }
            }
        }
    }
}

@Composable
private fun OpenUiFlatSection(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val children = element.props.elementListProp("children")
    val gap = openUiStepDp(element.props.intProp("gap"), 10.dp)
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        children.forEach { child ->
            if (child.typeName == "Spacer") Spacer(Modifier.height(gap))
            else RenderOpenUiElement(child, ctx)
        }
    }
}

private fun OpenUiElement.isLeadingMediaBox(): Boolean {
    if (typeName == "Image") return true
    if (typeName == "Box") {
        val inner = props.elementListProp("children")
        if (inner.size == 1 && inner[0].typeName == "Image") return true
    }
    return false
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpenUiRow(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val children = element.props.elementListProp("children")
    val gap = openUiStepDp(element.props.intProp("gap"), 8.dp)
    val wrap = element.props.boolProp("wrap") == true
    val vAlign = when (element.props.stringProp("align")) {
        "start" -> Alignment.Top
        "end" -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }

    if (wrap) {
        FlowRow(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            children.forEach { child ->
                if (child.typeName != "Spacer") {
                    RenderOpenUiElement(child, ctx)
                }
            }
        }
        return
    }

    // Detect official intelligent-ui "Media Row" pattern:
    // Row([Box([Image(...)]) | Image(...), Col(...)], gap, "start")
    val isMediaRow = children.size == 2 &&
        children[0].isLeadingMediaBox() &&
        children[1].typeName in setOf("Col", "Box", "Text", "Markdown")

    if (isMediaRow) {
        BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
            val mediaWidth = (maxWidth * 0.34f).coerceIn(112.dp, 190.dp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap.coerceAtLeast(16.dp)),
                verticalAlignment = Alignment.Top,
            ) {
                Box(modifier = Modifier.width(mediaWidth)) {
                    RenderOpenUiElement(children[0], ctx)
                }
                Box(modifier = Modifier.weight(1f)) {
                    RenderOpenUiElement(children[1], ctx)
                }
            }
        }
        return
    }

    val hArrange = when (element.props.stringProp("justify")) {
        "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
        "end" -> Arrangement.spacedBy(gap, Alignment.End)
        "between" -> Arrangement.SpaceBetween
        else -> Arrangement.spacedBy(gap, Alignment.Start)
    }

    val hasFlexChild = children.any {
        it.typeName == "Spacer" || (it.props.doubleProp("flex") ?: 0.0) > 0.0
    }
    val expandLastTextBlock = !hasFlexChild &&
        children.size == 2 &&
        children[0].typeName in setOf("Icon", "Badge", "IconButton") &&
        children[1].typeName in setOf("Col", "Text", "Title", "Markdown")

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = hArrange,
        verticalAlignment = vAlign,
    ) {
        children.forEachIndexed { idx, child ->
            val flex = child.props.doubleProp("flex")?.toFloat()
            when {
                child.typeName == "Spacer" -> Spacer(Modifier.weight(1f))
                flex != null && flex > 0f -> Box(Modifier.weight(flex)) { RenderOpenUiElement(child, ctx) }
                expandLastTextBlock && idx == 1 -> Box(Modifier.weight(1f)) { RenderOpenUiElement(child, ctx) }
                else -> RenderOpenUiElement(child, ctx)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OpenUiBox(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val props = element.props
    val scheme = MaterialTheme.colorScheme
    val children = props.elementListProp("children")
    val direction = props.stringProp("direction") ?: "col"
    val gap = openUiStepDp(props.intProp("gap"), 0.dp)
    val pad = openUiStepDp(props.intProp("padding"), 0.dp)
    val bg = openUiColor(props.stringProp("background"), scheme, asBackground = true)
    val radius = openUiRadiusDp(props["radius"], 0.dp)
    val hasBorder = props.boolProp("border") == true
    val wrap = props.boolProp("wrap") == true

    var boxMod = modifier
    when (val w = openUiLength(props["width"])) {
        is OpenUiLength.Fixed -> boxMod = boxMod.width(w.dp)
        is OpenUiLength.Fraction -> boxMod = boxMod.fillMaxWidth(w.fraction)
        null -> if (direction == "col") boxMod = boxMod.fillMaxWidth()
    }
    when (val h = openUiLength(props["height"])) {
        is OpenUiLength.Fixed -> boxMod = boxMod.height(h.dp)
        is OpenUiLength.Fraction -> Unit
        null -> Unit
    }

    val shape = RoundedCornerShape(radius)
    if (radius > 0.dp) boxMod = boxMod.clip(shape)
    if (bg != null) boxMod = boxMod.background(bg, shape)
    if (hasBorder && bg == null) boxMod = boxMod.background(scheme.surfaceContainerLow, shape)
    if (pad > 0.dp) boxMod = boxMod.padding(pad)

    if (direction == "row") {
        if (wrap) {
            FlowRow(
                modifier = boxMod,
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                children.forEach { if (it.typeName != "Spacer") RenderOpenUiElement(it, ctx) }
            }
        } else {
            val vAlign = when (props.stringProp("align")) {
                "start" -> Alignment.Top
                "end" -> Alignment.Bottom
                else -> Alignment.CenterVertically
            }
            val hArrange = when (props.stringProp("justify")) {
                "center" -> Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
                "end" -> Arrangement.spacedBy(gap, Alignment.End)
                "between" -> Arrangement.SpaceBetween
                else -> Arrangement.spacedBy(gap, Alignment.Start)
            }
            Row(modifier = boxMod, horizontalArrangement = hArrange, verticalAlignment = vAlign) {
                children.forEach { child ->
                    val flex = child.props.doubleProp("flex")?.toFloat()
                    when {
                        child.typeName == "Spacer" -> Spacer(Modifier.weight(1f))
                        flex != null && flex > 0f -> Box(Modifier.weight(flex)) { RenderOpenUiElement(child, ctx) }
                        else -> RenderOpenUiElement(child, ctx)
                    }
                }
            }
        }
    } else {
        val hAlign = when (props.stringProp("align")) {
            "center" -> Alignment.CenterHorizontally
            "end" -> Alignment.End
            else -> Alignment.Start
        }
        Column(modifier = boxMod, verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = hAlign) {
            children.forEach { child ->
                if (child.typeName == "Spacer") Spacer(Modifier.height(gap.coerceAtLeast(8.dp)))
                else RenderOpenUiElement(child, ctx)
            }
        }
    }
}

@Composable
internal fun OpenUiGrid(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val children = element.props.elementListProp("children")
    if (children.isEmpty()) return
    val requestedCols = (element.props.intProp("columns") ?: 2).coerceIn(1, 12)
    val minChildWidth = element.props.doubleProp("minChildWidth")?.dp
    val gap = openUiStepDp(element.props.intProp("gap"), 12.dp)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cols = if (minChildWidth != null && minChildWidth > 0.dp) {
            val fit = floor(((maxWidth + gap) / (minChildWidth + gap)).toDouble()).toInt().coerceAtLeast(1)
            minOf(requestedCols, fit)
        } else {
            // Keep grids readable on mobile screens even if model requests many columns without minChildWidth.
            val autoMax = if (maxWidth < 420.dp) minOf(requestedCols, 4) else requestedCols
            autoMax.coerceAtLeast(1)
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            children.chunked(cols).forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalAlignment = Alignment.Top,
                ) {
                    rowItems.forEach { item ->
                        Box(modifier = Modifier.weight(1f)) {
                            RenderOpenUiElement(item, ctx)
                        }
                    }
                    repeat(cols - rowItems.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
internal fun OpenUiCard(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val children = element.props.elementListProp("children")
    val gap = openUiStepDp(element.props.intProp("gap"), 12.dp)
    val padding = openUiStepDp(element.props.intProp("padding"), 16.dp)
    val scheme = MaterialTheme.colorScheme

    // Pure borderless tonal surface — no boxy Material outline strokes.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = scheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            children.forEach { child ->
                if (child.typeName == "Spacer") Spacer(Modifier.height(gap))
                else RenderOpenUiElement(child, ctx)
            }
        }
    }
}

@Composable
internal fun OpenUiCarousel(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val children = element.props.elementListProp("children")
    val itemWidth = (element.props.doubleProp("itemWidth") ?: 240.0).coerceIn(120.0, 360.0).dp
    val gap = openUiStepDp(element.props.intProp("gap"), 12.dp)

    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Top,
    ) {
        children.forEach { child ->
            Box(modifier = Modifier.width(itemWidth)) {
                RenderOpenUiElement(child, ctx)
            }
        }
    }
}

@Composable
internal fun OpenUiPopover(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val triggerEl = element.props["trigger"]
    val contentEl = element.props["content"]

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            when (triggerEl) {
                is JsonPrimitive -> Text(
                    text = triggerEl.contentOrNull.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                is JsonArray -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    triggerEl.mapNotNull { it.asOpenUiElementOrNull() }.forEach { RenderOpenUiElement(it, ctx) }
                }
                else -> triggerEl.asOpenUiElementOrNull()?.let { RenderOpenUiElement(it, ctx) }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 180.dp, max = 300.dp),
        ) {
            Box(modifier = Modifier.padding(12.dp)) {
                when (contentEl) {
                    is JsonPrimitive -> Text(
                        text = contentEl.contentOrNull.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is JsonArray -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        contentEl.mapNotNull { it.asOpenUiElementOrNull() }.forEach { RenderOpenUiElement(it, ctx) }
                    }
                    else -> contentEl.asOpenUiElementOrNull()?.let { RenderOpenUiElement(it, ctx) }
                }
            }
        }
    }
}

@Composable
internal fun OpenUiDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        thickness = 0.75.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun OpenUiSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier = modifier.size(8.dp))
}
