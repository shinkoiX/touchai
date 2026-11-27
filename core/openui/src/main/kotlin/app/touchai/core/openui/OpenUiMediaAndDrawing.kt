package app.touchai.core.openui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

@Composable
internal fun OpenUiImage(element: OpenUiElement, modifier: Modifier = Modifier) {
    val src = element.props.stringProp("src").orEmpty()
    val alt = element.props.stringProp("alt").orEmpty()
    val ratio = (element.props.doubleProp("aspectRatio") ?: 1.5).coerceIn(0.2, 5.0).toFloat()
    val radius = openUiRadiusDp(element.props["radius"], 12.dp)
    val fit = if (element.props.stringProp("fit") == "contain") ContentScale.Fit else ContentScale.Crop
    val scheme = MaterialTheme.colorScheme
    var zoomed by remember { mutableStateOf(false) }

    val shape = RoundedCornerShape(radius)
    val inRow = LocalOpenUiInRow.current
    Box(
        modifier = (if (inRow) modifier.width(128.dp) else modifier.fillMaxWidth())
            .aspectRatio(ratio)
            .clip(shape)
            .background(scheme.surfaceContainerLow)
            .clickable(enabled = src.isNotBlank()) { zoomed = true },
        contentAlignment = Alignment.Center,
    ) {
        if (src.isBlank()) {
            ImagePlaceholder(alt)
        } else {
            SubcomposeAsyncImage(
                model = src,
                contentDescription = alt.ifBlank { "Image" },
                contentScale = fit,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    ImageShimmer(modifier = Modifier.fillMaxSize())
                },
                error = {
                    ImagePlaceholder(alt)
                },
            )
        }
    }

    if (zoomed && src.isNotBlank()) {
        Dialog(
            onDismissRequest = { zoomed = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize().clickable { zoomed = false },
                color = Color.Black.copy(alpha = 0.94f),
            ) {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    SubcomposeAsyncImage(
                        model = src,
                        contentDescription = alt.ifBlank { "Image preview" },
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageShimmer(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "photoShimmer")
    val shift by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerOffset",
    )
    val base = MaterialTheme.colorScheme.surfaceContainerLow
    val highlight = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f)
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                start = Offset(shift - 240f, 0f),
                end = Offset(shift, 180f),
            ),
        ),
    )
}

@Composable
private fun ImagePlaceholder(alt: String) {
    Column(
        modifier = Modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OpenUiGlyph("image", sizeDp = 22.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
        if (alt.isNotBlank()) {
            Text(
                text = alt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun OpenUiIcon(element: OpenUiElement, modifier: Modifier = Modifier) {
    val name = element.props.stringProp("name").orEmpty()
    val sizeDp = when (element.props.stringProp("size")) {
        "sm" -> 15.dp
        "lg" -> 22.dp
        "xl" -> 28.dp
        else -> 18.dp
    }
    val tint = openUiColor(element.props.stringProp("color"), MaterialTheme.colorScheme)
        ?: MaterialTheme.colorScheme.onSurface
    OpenUiGlyph(name = name, sizeDp = sizeDp, tint = tint, modifier = modifier)
}

@Composable
internal fun OpenUiTable(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val columns = element.props.arrayProp("columns")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    val rows = element.props.arrayProp("rows")?.mapNotNull { it as? JsonArray }.orEmpty()
    if (columns.isEmpty() && rows.isEmpty()) return

    val scheme = MaterialTheme.colorScheme
    val colCount = max(columns.size, rows.maxOfOrNull { it.size } ?: 0)
    val numericCols = remember(rows, colCount) {
        (0 until colCount).map { colIdx ->
            rows.isNotEmpty() && rows.all { row ->
                val cell = row.getOrNull(colIdx) as? JsonPrimitive
                cell?.doubleOrNull != null || cell?.contentOrNull?.matches(Regex("""^[$€£¥+-]?[\d,.]+%?$""")) == true
            }
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerLow.copy(alpha = 0.65f),
    ) {
        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column {
                if (columns.isNotEmpty()) {
                    Row(
                        modifier = Modifier.background(scheme.surfaceContainerLow).padding(vertical = 10.dp, horizontal = 8.dp),
                    ) {
                        columns.forEachIndexed { colIdx, title ->
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurfaceVariant,
                                textAlign = if (numericCols.getOrElse(colIdx) { false }) TextAlign.End else TextAlign.Start,
                                modifier = Modifier.widthIn(min = 96.dp, max = 200.dp).padding(horizontal = 8.dp),
                            )
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant)
                }
                rows.forEachIndexed { rowIdx, row ->
                    if (rowIdx > 0) {
                        HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (colIdx in 0 until colCount) {
                            val cell = row.getOrNull(colIdx)
                            val alignEnd = numericCols.getOrElse(colIdx) { false }
                            Box(
                                modifier = Modifier.widthIn(min = 96.dp, max = 200.dp).padding(horizontal = 8.dp),
                                contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
                            ) {
                                val childEl = cell.asOpenUiElementOrNull()
                                if (childEl != null) {
                                    RenderOpenUiElement(childEl, ctx)
                                } else {
                                    Text(
                                        text = (cell as? JsonPrimitive)?.contentOrNull.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = if (alignEnd) TextAlign.End else TextAlign.Start,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun OpenUiChart(element: OpenUiElement, modifier: Modifier = Modifier) {
    val type = element.props.stringProp("type") ?: "bar"
    val data = element.props.arrayProp("data")?.mapNotNull { it as? JsonObject }.orEmpty()
    if (data.isEmpty()) return

    val scheme = MaterialTheme.colorScheme
    val palette = listOf(
        openUiColor("chart1", scheme)!!,
        openUiColor("chart2", scheme)!!,
        openUiColor("chart3", scheme)!!,
        openUiColor("chart4", scheme)!!,
        openUiColor("chart5", scheme)!!,
        openUiColor("chart6", scheme)!!,
    )
    val allKeys = data.first().keys.toList()
    val xKey = element.props.stringProp("x") ?: allKeys.firstOrNull { k ->
        (data.first()[k] as? JsonPrimitive)?.doubleOrNull == null
    } ?: allKeys.first()
    val explicitSeries = element.props.arrayProp("series")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val seriesKeys = explicitSeries?.takeIf { it.isNotEmpty() } ?: allKeys.filter { k ->
        k != xKey && data.any { (it[k] as? JsonPrimitive)?.doubleOrNull != null }
    }
    if (seriesKeys.isEmpty()) return

    val stacked = element.props.boolProp("stacked") == true
    val format = element.props.stringProp("format") ?: "number"
    val plotHeight = (element.props.doubleProp("height") ?: 200.0).coerceIn(140.0, 360.0).dp

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (type == "pie" || type == "donut") {
            val valueKey = seriesKeys.first()
            val slices = data.mapIndexed { i, row ->
                val label = (row[xKey] as? JsonPrimitive)?.contentOrNull ?: "${i + 1}"
                val value = ((row[valueKey] as? JsonPrimitive)?.doubleOrNull ?: 0.0).coerceAtLeast(0.0)
                Triple(label, value, palette[i % palette.size])
            }
            val total = slices.sumOf { it.second }.coerceAtLeast(1e-9)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Canvas(modifier = Modifier.size(148.dp)) {
                    var startAngle = -90f
                    slices.forEach { (_, value, color) ->
                        val sweep = ((value / total) * 360.0).toFloat()
                        if (type == "donut") {
                            drawArc(
                                color = color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = Stroke(width = size.minDimension * 0.24f),
                                topLeft = Offset(size.minDimension * 0.14f, size.minDimension * 0.14f),
                                size = Size(size.minDimension * 0.72f, size.minDimension * 0.72f),
                            )
                        } else {
                            drawArc(color = color, startAngle = startAngle, sweepAngle = sweep, useCenter = true)
                        }
                        startAngle += sweep
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.forEach { (label, value, color) ->
                        val displayVal = if (format == "percent") "${((value / total) * 100).toInt()}%"
                        else if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                            Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(displayVal, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            return@Column
        }

        // Cartesian charts: bar / line / area / scatter
        val maxVal = if (stacked) {
            data.maxOfOrNull { row -> seriesKeys.sumOf { ((row[it] as? JsonPrimitive)?.doubleOrNull ?: 0.0).coerceAtLeast(0.0) } } ?: 1.0
        } else {
            data.maxOfOrNull { row -> seriesKeys.maxOfOrNull { (row[it] as? JsonPrimitive)?.doubleOrNull ?: 0.0 } ?: 0.0 } ?: 1.0
        }.coerceAtLeast(1e-6)
        val gridColor = scheme.outlineVariant.copy(alpha = 0.45f)
        val labelColor = scheme.onSurfaceVariant.toArgb()

        Canvas(modifier = Modifier.fillMaxWidth().height(plotHeight)) {
            val leftPad = 36.dp.toPx()
            val bottomPad = 24.dp.toPx()
            val topPad = 10.dp.toPx()
            val rightPad = 8.dp.toPx()
            val plotW = (size.width - leftPad - rightPad).coerceAtLeast(1f)
            val plotH = (size.height - topPad - bottomPad).coerceAtLeast(1f)

            val paint = Paint().apply {
                color = labelColor
                textSize = 10.sp.toPx()
                isAntiAlias = true
            }

            // Horizontal grid lines + Y labels
            for (step in 0..3) {
                val frac = step / 3f
                val y = topPad + plotH * (1f - frac)
                drawLine(gridColor, Offset(leftPad, y), Offset(leftPad + plotW, y), strokeWidth = 1f)
                val v = maxVal * frac
                val text = if (v >= 1000) "${(v / 1000).toInt()}k" else if (v % 1.0 == 0.0) v.toLong().toString() else "%.1f".format(v)
                drawIntoCanvas { it.nativeCanvas.drawText(text, 2f, y + 4f, paint) }
            }

            val count = data.size
            val groupW = plotW / count.coerceAtLeast(1)

            when (type) {
                "bar" -> {
                    data.forEachIndexed { i, row ->
                        val gx = leftPad + i * groupW
                        if (stacked) {
                            val barW = (groupW * 0.62f).coerceAtMost(36.dp.toPx())
                            val bx = gx + (groupW - barW) / 2f
                            var accH = 0f
                            seriesKeys.forEachIndexed { sIdx, key ->
                                val v = ((row[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0).coerceAtLeast(0.0)
                                val h = ((v / maxVal) * plotH).toFloat()
                                val y = topPad + plotH - accH - h
                                drawRoundRect(
                                    color = palette[sIdx % palette.size],
                                    topLeft = Offset(bx, y),
                                    size = Size(barW, h),
                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                )
                                accH += h
                            }
                        } else {
                            val slotW = (groupW * 0.72f) / seriesKeys.size
                            val startX = gx + (groupW * 0.14f)
                            seriesKeys.forEachIndexed { sIdx, key ->
                                val v = ((row[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0).coerceAtLeast(0.0)
                                val h = ((v / maxVal) * plotH).toFloat()
                                drawRoundRect(
                                    color = palette[sIdx % palette.size],
                                    topLeft = Offset(startX + sIdx * slotW + 1f, topPad + plotH - h),
                                    size = Size((slotW - 2f).coerceAtLeast(2f), h),
                                    cornerRadius = CornerRadius(3.dp.toPx()),
                                )
                            }
                        }
                    }
                }
                "line", "area" -> {
                    seriesKeys.forEachIndexed { sIdx, key ->
                        val color = palette[sIdx % palette.size]
                        val pts = data.mapIndexed { i, row ->
                            val x = leftPad + (if (count == 1) plotW / 2f else i * (plotW / (count - 1)))
                            val v = (row[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                            val y = topPad + plotH - ((v / maxVal) * plotH).toFloat()
                            Offset(x, y)
                        }
                        if (type == "area" && pts.size >= 2) {
                            val fillPath = Path().apply {
                                moveTo(pts.first().x, topPad + plotH)
                                pts.forEach { lineTo(it.x, it.y) }
                                lineTo(pts.last().x, topPad + plotH)
                                close()
                            }
                            drawPath(fillPath, color = color.copy(alpha = 0.18f))
                        }
                        if (pts.size >= 2) {
                            val linePath = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                pts.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(linePath, color = color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                        pts.forEach { drawCircle(color, radius = 3.dp.toPx(), center = it) }
                    }
                }
                "scatter" -> {
                    val xNums = data.map { (it[xKey] as? JsonPrimitive)?.doubleOrNull ?: 0.0 }
                    val minX = xNums.minOrNull() ?: 0.0
                    val spanX = ((xNums.maxOrNull() ?: 1.0) - minX).coerceAtLeast(1e-6)
                    seriesKeys.forEachIndexed { sIdx, key ->
                        val color = palette[sIdx % palette.size]
                        data.forEachIndexed { i, row ->
                            val xv = xNums[i]
                            val yv = (row[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                            val px = leftPad + (((xv - minX) / spanX) * plotW).toFloat()
                            val py = topPad + plotH - ((yv / maxVal) * plotH).toFloat()
                            drawCircle(color, radius = 4.dp.toPx(), center = Offset(px, py))
                        }
                    }
                }
            }

            // X labels (sparsified so they don't overlap)
            val labelStep = max(1, count / 5)
            data.forEachIndexed { i, row ->
                if (i % labelStep == 0 || i == count - 1) {
                    val label = ((row[xKey] as? JsonPrimitive)?.contentOrNull.orEmpty()).take(8)
                    val cx = leftPad + i * groupW + groupW / 2f
                    paint.textAlign = Paint.Align.CENTER
                    drawIntoCanvas { it.nativeCanvas.drawText(label, cx, size.height - 4f, paint) }
                }
            }
        }

        if (seriesKeys.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                seriesKeys.forEachIndexed { idx, key ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(palette[idx % palette.size]))
                        Text(key, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun OpenUiYouTube(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    val raw = element.props.stringProp("video").orEmpty()
    val title = element.props.stringProp("title") ?: "Watch on YouTube"
    val videoId = extractYouTubeId(raw)
    val watchUrl = if (videoId != null) "https://www.youtube.com/watch?v=$videoId" else raw
    val thumbUrl = videoId?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" }

    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = watchUrl.isNotBlank()) {
            ctx.onOpenUrl(watchUrl)
        },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                if (thumbUrl != null) {
                    SubcomposeAsyncImage(
                        model = thumbUrl,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("▶", color = Color.White, fontSize = 20.sp)
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

private fun extractYouTubeId(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.matches(Regex("""^[A-Za-z0-9_-]{11}$"""))) return trimmed
    return Regex("""(?:v=|youtu\.be/|embed/)([A-Za-z0-9_-]{11})""").find(trimmed)?.groupValues?.get(1)
}

@Composable
internal fun OpenUiFlashcard(
    element: OpenUiElement,
    ctx: OpenUiRenderContext,
    modifier: Modifier = Modifier,
) {
    var flipped by remember { mutableStateOf(false) }
    val hint = element.props.stringProp("hint")
    val scheme = MaterialTheme.colorScheme
    val content = if (flipped) element.props["back"] else element.props["front"]

    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { flipped = !flipped },
        shape = RoundedCornerShape(18.dp),
        color = if (flipped) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (flipped) "Back · Tap to flip" else "Front · Tap to flip",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            when (content) {
                is JsonPrimitive -> Text(
                    text = content.contentOrNull.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                is JsonArray -> Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    content.mapNotNull { it.asOpenUiElementOrNull() }.forEach { RenderOpenUiElement(it, ctx) }
                }
                else -> content.asOpenUiElementOrNull()?.let { RenderOpenUiElement(it, ctx) }
            }
            if (!flipped && !hint.isNullOrBlank()) {
                Text(
                    text = "Hint: $hint",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun OpenUiCelebration(element: OpenUiElement, modifier: Modifier = Modifier) {
    val active = element.props.boolProp("when") == true
    val progress = remember { Animatable(0f) }
    val particles = remember {
        val rng = Random(42)
        List(28) {
            val angle = rng.nextFloat() * 2f * PI.toFloat()
            val speed = 0.4f + rng.nextFloat() * 0.9f
            Triple(angle, speed, Color(0xFF000000L or listOf(0x3B82F6, 0x10B981, 0xF59E0B, 0xEC4899, 0x8B5CF6)[it % 5].toLong()))
        }
    }
    LaunchedEffect(active) {
        if (active) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(1100, easing = LinearEasing))
        }
    }
    if (!active || progress.value >= 1f) return
    Canvas(modifier = modifier.fillMaxWidth().height(90.dp)) {
        val cx = size.width / 2f
        val cy = size.height * 0.6f
        val p = progress.value
        particles.forEach { (angle, speed, color) ->
            val r = p * speed * size.minDimension * 1.1f
            val x = cx + cos(angle) * r
            val y = cy + sin(angle) * r + p * p * 40f
            drawCircle(color.copy(alpha = (1f - p).coerceIn(0f, 1f)), radius = 4.dp.toPx(), center = Offset(x, y))
        }
    }
}

// ── Native Compose Canvas SVG Renderer (Svg + all 10 primitives) ──

@Composable
internal fun OpenUiSvg(element: OpenUiElement, modifier: Modifier = Modifier) {
    val viewBox = parseViewBox(element.props.stringProp("viewBox"))
    val children = element.props.elementListProp("children")
    val scheme = MaterialTheme.colorScheme
    val ratio = (viewBox.width / viewBox.height).coerceIn(0.25f, 4f)
    val inRow = LocalOpenUiInRow.current

    val explicitWidth = openUiLength(element.props["width"])
    val explicitHeight = openUiLength(element.props["height"])
    var mod = modifier
    when (explicitWidth) {
        is OpenUiLength.Fixed -> mod = mod.width(
            if (inRow) explicitWidth.dp.coerceAtMost(160.dp) else explicitWidth.dp,
        )
        is OpenUiLength.Fraction -> mod = if (inRow) {
            mod.width((viewBox.width.coerceIn(72f, 160f)).dp)
        } else {
            mod.fillMaxWidth(explicitWidth.fraction)
        }
        null -> mod = if (inRow) {
            mod.width((viewBox.width.coerceIn(72f, 160f)).dp)
        } else {
            mod.fillMaxWidth()
        }
    }
    mod = when {
        explicitWidth is OpenUiLength.Fixed && explicitHeight is OpenUiLength.Fixed -> {
            val fixedRatio = (explicitWidth.dp.value / explicitHeight.dp.value).coerceIn(0.25f, 4f)
            mod.aspectRatio(fixedRatio)
        }
        explicitHeight is OpenUiLength.Fixed && !inRow -> mod.height(explicitHeight.dp)
        else -> mod.aspectRatio(ratio)
    }

    Canvas(modifier = mod) {
        val sx = size.width / viewBox.width
        val sy = size.height / viewBox.height
        val scale = min(sx, sy)
        val offsetX = (size.width - viewBox.width * scale) / 2f - viewBox.minX * scale
        val offsetY = (size.height - viewBox.height * scale) / 2f - viewBox.minY * scale

        withTransform({
            translate(left = offsetX, top = offsetY)
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
        }) {
            children.forEach { child -> drawSvgNode(child, scheme, parentAlpha = 1f) }
        }
    }
}

private data class SvgViewBox(val minX: Float, val minY: Float, val width: Float, val height: Float)

private fun parseViewBox(raw: String?): SvgViewBox {
    val nums = raw?.trim()?.split(Regex("""[\s,]+"""))?.mapNotNull { it.toFloatOrNull() }.orEmpty()
    if (nums.size == 4 && nums[2] > 0f && nums[3] > 0f) {
        return SvgViewBox(nums[0], nums[1], nums[2], nums[3])
    }
    return SvgViewBox(0f, 0f, 240f, 160f)
}

private fun DrawScope.drawSvgNode(node: OpenUiElement, scheme: ColorScheme, parentAlpha: Float) {
    val props = node.props
    val opacity = ((props.doubleProp("opacity")?.toFloat() ?: 1f) * parentAlpha).coerceIn(0f, 1f)
    val defaultInk = scheme.onSurface

    when (node.typeName) {
        "G" -> {
            val transform = props.stringProp("transform")
            val children = props.elementListProp("children")
            applySvgTransform(transform) {
                children.forEach { drawSvgNode(it, scheme, opacity) }
            }
        }
        "Rect" -> {
            val x = props.doubleProp("x")?.toFloat() ?: 0f
            val y = props.doubleProp("y")?.toFloat() ?: 0f
            val w = props.doubleProp("width")?.toFloat() ?: 0f
            val h = props.doubleProp("height")?.toFloat() ?: 0f
            val rx = props.doubleProp("rx")?.toFloat() ?: 0f
            val fillStr = props.stringProp("fill")
            val strokeStr = props.stringProp("stroke")
            val strokeWidth = props.doubleProp("strokeWidth")?.toFloat() ?: 1f
            val fill = resolveSvgFill(fillStr, strokeStr, scheme, defaultInk)
            val stroke = openUiColor(strokeStr, scheme)
            if (fill != null) {
                drawRoundRect(fill.copy(alpha = fill.alpha * opacity), Offset(x, y), Size(w, h), CornerRadius(rx, rx), style = Fill)
            }
            if (stroke != null) {
                drawRoundRect(stroke.copy(alpha = stroke.alpha * opacity), Offset(x, y), Size(w, h), CornerRadius(rx, rx), style = Stroke(width = strokeWidth))
            }
        }
        "Circle" -> {
            val cx = props.doubleProp("cx")?.toFloat() ?: 0f
            val cy = props.doubleProp("cy")?.toFloat() ?: 0f
            val r = props.doubleProp("r")?.toFloat() ?: 0f
            val fill = resolveSvgFill(props.stringProp("fill"), props.stringProp("stroke"), scheme, defaultInk)
            val stroke = openUiColor(props.stringProp("stroke"), scheme)
            val sw = props.doubleProp("strokeWidth")?.toFloat() ?: 1f
            if (fill != null) drawCircle(fill.copy(alpha = fill.alpha * opacity), r, Offset(cx, cy), style = Fill)
            if (stroke != null) drawCircle(stroke.copy(alpha = stroke.alpha * opacity), r, Offset(cx, cy), style = Stroke(width = sw))
        }
        "Ellipse" -> {
            val cx = props.doubleProp("cx")?.toFloat() ?: 0f
            val cy = props.doubleProp("cy")?.toFloat() ?: 0f
            val rx = props.doubleProp("rx")?.toFloat() ?: 0f
            val ry = props.doubleProp("ry")?.toFloat() ?: 0f
            val fill = resolveSvgFill(props.stringProp("fill"), props.stringProp("stroke"), scheme, defaultInk)
            val stroke = openUiColor(props.stringProp("stroke"), scheme)
            val sw = props.doubleProp("strokeWidth")?.toFloat() ?: 1f
            val tl = Offset(cx - rx, cy - ry)
            val sz = Size(rx * 2f, ry * 2f)
            if (fill != null) drawOval(fill.copy(alpha = fill.alpha * opacity), tl, sz, style = Fill)
            if (stroke != null) drawOval(stroke.copy(alpha = stroke.alpha * opacity), tl, sz, style = Stroke(width = sw))
        }
        "Line" -> {
            val x1 = props.doubleProp("x1")?.toFloat() ?: 0f
            val y1 = props.doubleProp("y1")?.toFloat() ?: 0f
            val x2 = props.doubleProp("x2")?.toFloat() ?: 0f
            val y2 = props.doubleProp("y2")?.toFloat() ?: 0f
            val stroke = openUiColor(props.stringProp("stroke"), scheme) ?: defaultInk
            val sw = props.doubleProp("strokeWidth")?.toFloat() ?: 2f
            drawLine(stroke.copy(alpha = stroke.alpha * opacity), Offset(x1, y1), Offset(x2, y2), strokeWidth = sw, cap = StrokeCap.Round)
        }
        "Polyline", "Polygon" -> {
            val closed = node.typeName == "Polygon"
            val pts = parseSvgPoints(props.stringProp("points"))
            if (pts.size >= 2) {
                val path = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    pts.drop(1).forEach { lineTo(it.x, it.y) }
                    if (closed) close()
                }
                val fill = if (closed) {
                    resolveSvgFill(props.stringProp("fill"), props.stringProp("stroke"), scheme, defaultInk)
                } else {
                    openUiColor(props.stringProp("fill"), scheme)?.takeUnless { it == Color.Transparent }
                }
                val stroke = openUiColor(props.stringProp("stroke"), scheme)
                    ?: if (!closed && fill == null) defaultInk else null
                val sw = props.doubleProp("strokeWidth")?.toFloat() ?: 2f
                if (fill != null) drawPath(path, fill.copy(alpha = fill.alpha * opacity), style = Fill)
                if (stroke != null) drawPath(path, stroke.copy(alpha = stroke.alpha * opacity), style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        "Path" -> {
            val d = props.stringProp("d") ?: return
            val composePath = runCatching { PathParser().parsePathString(d).toPath() }.getOrNull() ?: return
            val fillStr = props.stringProp("fill")
            val fill = if (!fillStr.isNullOrBlank() && fillStr != "none") openUiColor(fillStr, scheme) else null
            val strokeStr = props.stringProp("stroke")
            val stroke = openUiColor(strokeStr, scheme) ?: if (fill == null) defaultInk else null
            val sw = props.doubleProp("strokeWidth")?.toFloat() ?: 2f
            if (fill != null) drawPath(composePath, fill.copy(alpha = fill.alpha * opacity), style = Fill)
            if (stroke != null) drawPath(composePath, stroke.copy(alpha = stroke.alpha * opacity), style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        "SvgText" -> {
            val text = props.stringProp("text").orEmpty()
            val x = props.doubleProp("x")?.toFloat() ?: 0f
            val y = props.doubleProp("y")?.toFloat() ?: 0f
            val textSize = props.doubleProp("size")?.toFloat() ?: 12f
            val fill = openUiColor(props.stringProp("fill"), scheme) ?: defaultInk
            val anchor = props.stringProp("anchor") ?: "start"
            val weight = props.stringProp("weight")
            val paint = Paint().apply {
                color = fill.copy(alpha = fill.alpha * opacity).toArgb()
                this.textSize = textSize
                isAntiAlias = true
                textAlign = when (anchor) {
                    "middle" -> Paint.Align.CENTER
                    "end" -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
                typeface = if (weight == "semibold" || weight == "bold") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(text, x, y, paint)
            }
        }
    }
}

private fun resolveSvgFill(fillStr: String?, strokeStr: String?, scheme: ColorScheme, defaultInk: Color): Color? {
    if (fillStr == "none" || fillStr == "transparent") return null
    if (!fillStr.isNullOrBlank()) return openUiColor(fillStr, scheme, asBackground = fillStr == "surface")
    return if (!strokeStr.isNullOrBlank()) null else defaultInk
}

private fun parseSvgPoints(raw: String?): List<Offset> {
    if (raw.isNullOrBlank()) return emptyList()
    val nums = raw.trim().split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
    val out = ArrayList<Offset>(nums.size / 2)
    var i = 0
    while (i + 1 < nums.size) {
        out.add(Offset(nums[i], nums[i + 1]))
        i += 2
    }
    return out
}

private fun DrawScope.applySvgTransform(transform: String?, block: DrawScope.() -> Unit) {
    if (transform.isNullOrBlank()) {
        block()
        return
    }
    val ops = Regex("""(translate|rotate|scale)\(([^)]+)\)""").findAll(transform).toList()
    if (ops.isEmpty()) {
        block()
        return
    }
    withTransform({
        for (op in ops) {
            val args = op.groupValues[2].trim().split(Regex("""[\s,]+""")).mapNotNull { it.toFloatOrNull() }
            when (op.groupValues[1]) {
                "translate" -> if (args.size in 1..2) translate(left = args[0], top = args.getOrElse(1) { 0f })
                "rotate" -> when (args.size) {
                    1 -> rotate(degrees = args[0], pivot = Offset.Zero)
                    3 -> rotate(degrees = args[0], pivot = Offset(args[1], args[2]))
                }
                "scale" -> if (args.size in 1..2) scale(scaleX = args[0], scaleY = args.getOrElse(1) { args[0] }, pivot = Offset.Zero)
            }
        }
    }, block)
}
