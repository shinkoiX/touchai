package app.touchai.core.openui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crisp monochrome thin-stroke vector Lucide icons drawn natively on Compose Canvas.
 * Replaces platform emoji so badges, buttons, lists, and cards match ChatGPT / OpenUI aesthetics.
 */
@Composable
internal fun OpenUiGlyph(
    name: String,
    sizeDp: Dp = 18.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    val key = name.lowercase().trim()
    Canvas(modifier = modifier.size(sizeDp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = (1.85f * u).coerceAtLeast(1.3.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLucideIcon(key, u, tint, stroke)
    }
}

private fun DrawScope.drawLucideIcon(name: String, u: Float, color: Color, stroke: Stroke) {
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
    fun poly(closed: Boolean = false, vararg pts: Pair<Float, Float>) {
        if (pts.size < 2) return
        val path = Path().apply {
            moveTo(pts[0].first * u, pts[0].second * u)
            for (i in 1 until pts.size) lineTo(pts[i].first * u, pts[i].second * u)
            if (closed) close()
        }
        drawPath(path, color, style = stroke)
    }

    when (name) {
        "plus" -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }
        "plus-circle", "circle-plus" -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            line(12f, 8f, 12f, 16f)
            line(8f, 12f, 16f, 12f)
        }
        "minus" -> line(5f, 12f, 19f, 12f)
        "minus-circle", "circle-minus" -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            line(8f, 12f, 16f, 12f)
        }
        "x", "close" -> {
            line(18f, 6f, 6f, 18f)
            line(6f, 6f, 18f, 18f)
        }
        "x-circle", "circle-x" -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            line(15f, 9f, 9f, 15f)
            line(9f, 9f, 15f, 15f)
        }
        "check" -> poly(false, 20f to 6f, 9f to 17f, 4f to 12f)
        "check-circle", "check-circle-2", "circle-check" -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            poly(false, 8.5f to 12.2f, 11f to 14.7f, 16f to 9.5f)
        }
        "circle" -> drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
        "arrow-left" -> {
            line(19f, 12f, 5f, 12f)
            poly(false, 12f to 19f, 5f to 12f, 12f to 5f)
        }
        "arrow-right" -> {
            line(5f, 12f, 19f, 12f)
            poly(false, 12f to 5f, 19f to 12f, 12f to 19f)
        }
        "arrow-up" -> {
            line(12f, 19f, 12f, 5f)
            poly(false, 5f to 12f, 12f to 5f, 19f to 12f)
        }
        "arrow-down" -> {
            line(12f, 5f, 12f, 19f)
            poly(false, 19f to 12f, 12f to 19f, 5f to 12f)
        }
        "chevron-left" -> poly(false, 15f to 18f, 9f to 12f, 15f to 6f)
        "chevron-right" -> poly(false, 9f to 18f, 15f to 12f, 9f to 6f)
        "chevron-down" -> poly(false, 6f to 9f, 12f to 15f, 18f to 9f)
        "chevron-up" -> poly(false, 18f to 15f, 12f to 9f, 6f to 15f)
        "rotate-ccw", "undo" -> {
            drawArc(color, startAngle = -145f, sweepAngle = 290f, useCenter = false, topLeft = p(3.5f, 3.5f), size = Size(17f * u, 17f * u), style = stroke)
            poly(false, 3.5f to 5f, 3.5f to 10f, 8.5f to 10f)
        }
        "rotate-cw", "refresh-cw", "redo" -> {
            drawArc(color, startAngle = -35f, sweepAngle = 290f, useCenter = false, topLeft = p(3.5f, 3.5f), size = Size(17f * u, 17f * u), style = stroke)
            poly(false, 20.5f to 5f, 20.5f to 10f, 15.5f to 10f)
        }
        "info" -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            line(12f, 16f, 12f, 12f)
            drawCircle(color, 1.1f * u, p(12f, 8.2f))
        }
        "alert-triangle", "triangle-alert" -> {
            poly(true, 12f to 3.5f, 21f to 19.5f, 3f to 19.5f)
            line(12f, 9.5f, 12f, 14f)
            drawCircle(color, 1.0f * u, p(12f, 16.8f))
        }
        "alert-circle", "circle-alert" -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            line(12f, 8f, 12f, 12.5f)
            drawCircle(color, 1.1f * u, p(12f, 16f))
        }
        "help-circle", "circle-help" -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            drawArc(color, -160f, 200f, false, p(9.2f, 7.5f), Size(5.6f * u, 5.2f * u), style = stroke)
            drawCircle(color, 1.1f * u, p(12f, 16.5f))
        }
        "clock", "timer", "watch" -> {
            drawCircle(color, 9.5f * u, p(12f, 12f), style = stroke)
            poly(false, 12f to 7f, 12f to 12f, 15.5f to 14f)
        }
        "calendar", "calendar-days" -> {
            drawRoundRect(color, p(3.5f, 5f), Size(17f * u, 15.5f * u), CornerRadius(2.5f * u), style = stroke)
            line(8f, 3f, 8f, 7f)
            line(16f, 3f, 16f, 7f)
            line(3.5f, 10f, 20.5f, 10f)
        }
        "map-pin", "pin", "navigation" -> {
            drawCircle(color, 7.2f * u, p(12f, 10f), style = stroke)
            drawCircle(color, 2.5f * u, p(12f, 10f), style = stroke)
            poly(false, 6.2f to 14.5f, 12f to 21.5f, 17.8f to 14.5f)
        }
        "search" -> {
            drawCircle(color, 7f * u, p(10.5f, 10.5f), style = stroke)
            line(15.8f, 15.8f, 20.5f, 20.5f)
        }
        "star" -> {
            val path = Path()
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 9.5f else 4.2f
                val a = (-90.0 + i * 36.0) * PI / 180.0
                val x = (12f + r * cos(a).toFloat()) * u
                val y = (12.5f + r * sin(a).toFloat()) * u
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color, style = stroke)
        }
        "heart" -> {
            val path = Path().apply {
                moveTo(12f * u, 20f * u)
                cubicTo(4f * u, 14.5f * u, 3f * u, 9.5f * u, 6.5f * u, 6.2f * u)
                cubicTo(8.5f * u, 4.3f * u, 11f * u, 5.2f * u, 12f * u, 7.2f * u)
                cubicTo(13f * u, 5.2f * u, 15.5f * u, 4.3f * u, 17.5f * u, 6.2f * u)
                cubicTo(21f * u, 9.5f * u, 20f * u, 14.5f * u, 12f * u, 20f * u)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        "external-link", "link" -> {
            poly(false, 18f to 13f, 18f to 19f, 5f to 19f, 5f to 6f, 11f to 6f)
            poly(false, 15f to 4f, 20f to 4f, 20f to 9f)
            line(10.5f, 13.5f, 20f, 4f)
        }
        "copy" -> {
            drawRoundRect(color, p(9f, 9f), Size(11f * u, 11f * u), CornerRadius(2f * u), style = stroke)
            poly(false, 5f to 15f, 4f to 15f, 4f to 4f, 15f to 4f, 15f to 5f)
        }
        "play" -> poly(true, 7f to 5f, 19f to 12f, 7f to 19f)
        "pause" -> {
            line(8.5f, 5f, 8.5f, 19f)
            line(15.5f, 5f, 15.5f, 19f)
        }
        "car", "bus", "train" -> {
            drawRoundRect(color, p(3.5f, 10.5f), Size(17f * u, 6.5f * u), CornerRadius(2f * u), style = stroke)
            poly(false, 6f to 10.5f, 8f to 6f, 16f to 6f, 18f to 10.5f)
            drawCircle(color, 1.5f * u, p(7.5f, 18f), style = stroke)
            drawCircle(color, 1.5f * u, p(16.5f, 18f), style = stroke)
        }
        "home", "house" -> {
            poly(false, 3.5f to 11f, 12f to 4f, 20.5f to 11f)
            poly(false, 5.5f to 10f, 5.5f to 20f, 18.5f to 20f, 18.5f to 10f)
        }
        "sun" -> {
            drawCircle(color, 4.5f * u, p(12f, 12f), style = stroke)
            for (i in 0 until 8) {
                val a = i * 45.0 * PI / 180.0
                line(12f + 7f * cos(a).toFloat(), 12f + 7f * sin(a).toFloat(), 12f + 9.2f * cos(a).toFloat(), 12f + 9.2f * sin(a).toFloat())
            }
        }
        "moon" -> {
            drawArc(color, 35f, 235f, false, p(4.5f, 4.5f), Size(15f * u, 15f * u), style = stroke)
            drawArc(color, 95f, 125f, false, p(9.5f, 4.5f), Size(10f * u, 10f * u), style = stroke)
        }
        "sparkles", "zap", "bolt" -> {
            poly(true, 13f to 3f, 5f to 13.5f, 11.5f to 13.5f, 11f to 21f, 19f to 10.5f, 12.5f to 10.5f)
        }
        "flame", "fire" -> {
            val path = Path().apply {
                moveTo(12f * u, 3.5f * u)
                cubicTo(16f * u, 8f * u, 18.5f * u, 11f * u, 18.5f * u, 14.5f * u)
                cubicTo(18.5f * u, 18f * u, 15.8f * u, 20.5f * u, 12f * u, 20.5f * u)
                cubicTo(8.2f * u, 20.5f * u, 5.5f * u, 18f * u, 5.5f * u, 14.5f * u)
                cubicTo(5.5f * u, 11.5f * u, 8.5f * u, 8.5f * u, 12f * u, 3.5f * u)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        "trending-up" -> {
            poly(false, 21f to 7f, 13.5f to 14.5f, 9f to 10f, 3f to 16f)
            poly(false, 16f to 7f, 21f to 7f, 21f to 12f)
        }
        "trending-down" -> {
            poly(false, 21f to 17f, 13.5f to 9.5f, 9f to 14f, 3f to 8f)
            poly(false, 16f to 17f, 21f to 17f, 21f to 12f)
        }
        "user", "users" -> {
            drawCircle(color, 4f * u, p(12f, 8f), style = stroke)
            drawArc(color, 180f, 180f, false, p(5f, 14f), Size(14f * u, 10f * u), style = stroke)
        }
        "dollar-sign", "coins", "wallet" -> {
            line(12f, 3f, 12f, 21f)
            poly(false, 16f to 7f, 9.5f to 7f, 8f to 9.5f, 9.5f to 12f, 14.5f to 12f, 16f to 14.5f, 14.5f to 17f, 8f to 17f)
        }
        "tag" -> {
            poly(true, 4f to 4f, 11.5f to 4f, 20f to 12.5f, 12.5f to 20f, 4f to 11.5f)
            drawCircle(color, 1.3f * u, p(8.5f, 8.5f))
        }
        "book", "book-open" -> {
            poly(false, 3f to 5f, 10f to 5f, 12f to 7f, 14f to 5f, 21f to 5f, 21f to 18f, 14f to 18f, 12f to 20f, 10f to 18f, 3f to 18f, 3f to 5f)
            line(12f, 7f, 12f, 20f)
        }
        "image" -> {
            drawRoundRect(color, p(3.5f, 4.5f), Size(17f * u, 15f * u), CornerRadius(2.5f * u), style = stroke)
            drawCircle(color, 1.5f * u, p(9f, 9.5f))
            poly(false, 20f to 15.5f, 15.5f to 11f, 7f to 19.5f)
        }
        "trash", "trash-2" -> {
            line(4f, 6f, 20f, 6f)
            poly(false, 6f to 6f, 7f to 20f, 17f to 20f, 18f to 6f)
            line(9f, 3.5f, 15f, 3.5f)
        }
        "settings", "sliders" -> {
            line(4f, 7f, 20f, 7f)
            line(4f, 17f, 20f, 17f)
            drawCircle(color, 2.2f * u, p(9f, 7f))
            drawCircle(color, 2.2f * u, p(15f, 17f))
        }
        else -> {
            // Subtle crisp ring-dot fallback instead of jarring Unicode bullet/emoji
            drawCircle(color, 3.2f * u, p(12f, 12f), style = stroke)
        }
    }
}
