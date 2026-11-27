package app.touchai.core.openui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

sealed interface OpenUiLength {
    data class Fixed(val dp: Dp) : OpenUiLength
    data class Fraction(val fraction: Float) : OpenUiLength
}

fun openUiStepDp(steps: Int?, defaultDp: Dp = 0.dp): Dp =
    steps?.coerceAtLeast(0)?.let { (it * 4).dp } ?: defaultDp

fun openUiRadiusDp(value: JsonElement?, defaultDp: Dp = 0.dp): Dp {
    val prim = value as? JsonPrimitive ?: return defaultDp
    prim.doubleOrNull?.let { return it.coerceAtLeast(0.0).dp }
    return when (prim.contentOrNull?.lowercase()) {
        "none" -> 0.dp
        "sm" -> 6.dp
        "md" -> 10.dp
        "lg" -> 12.dp
        "xl" -> 14.dp
        "full" -> 999.dp
        else -> prim.contentOrNull?.removeSuffix("px")?.toDoubleOrNull()?.coerceAtLeast(0.0)?.dp ?: defaultDp
    }
}

fun openUiLength(value: JsonElement?): OpenUiLength? {
    val prim = value as? JsonPrimitive ?: return null
    prim.doubleOrNull?.let { return OpenUiLength.Fixed(it.coerceAtLeast(0.0).dp) }
    val str = prim.contentOrNull?.trim() ?: return null
    if (str.endsWith("%")) {
        val pct = str.removeSuffix("%").toFloatOrNull() ?: return null
        return OpenUiLength.Fraction((pct / 100f).coerceIn(0f, 1f))
    }
    val px = str.removeSuffix("px").toDoubleOrNull() ?: return null
    return OpenUiLength.Fixed(px.coerceAtLeast(0.0).dp)
}

fun openUiColor(
    name: String?,
    scheme: ColorScheme,
    asBackground: Boolean = false,
): Color? {
    val raw = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val isDark = scheme.surface.luminance() < 0.5f
    val key = raw.lowercase()

    val statusAlpha = if (isDark) 0.18f else 0.10f
    return when (key) {
        "ink" -> scheme.onSurface
        "muted" -> scheme.onSurfaceVariant
        "faint" -> scheme.onSurfaceVariant.copy(alpha = 0.62f)
        "surface" -> if (asBackground) scheme.surfaceContainerLow else scheme.surfaceContainerHigh
        "neutral" -> if (asBackground) scheme.surfaceContainerHigh.copy(alpha = if (isDark) 0.85f else 0.72f) else scheme.onSurfaceVariant
        "primary" -> if (asBackground) scheme.primary.copy(alpha = statusAlpha) else scheme.primary
        "info" -> {
            val base = if (isDark) Color(0xFF60A5FA) else Color(0xFF0285FF)
            if (asBackground) base.copy(alpha = statusAlpha) else base
        }
        "success" -> {
            val base = if (isDark) Color(0xFF34D399) else Color(0xFF00875A)
            if (asBackground) base.copy(alpha = statusAlpha) else base
        }
        "warning" -> {
            val base = if (isDark) Color(0xFFFBBF24) else Color(0xFFB45309)
            if (asBackground) base.copy(alpha = statusAlpha) else base
        }
        "danger" -> {
            val base = if (isDark) Color(0xFFF87171) else Color(0xFFDC2626)
            if (asBackground) base.copy(alpha = statusAlpha) else base
        }
        "purple" -> {
            val base = if (isDark) Color(0xFFC084FC) else Color(0xFF7C3AED)
            if (asBackground) base.copy(alpha = statusAlpha) else base
        }
        "pink" -> {
            val base = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777)
            if (asBackground) base.copy(alpha = statusAlpha) else base
        }
        // Official OpenUI intelligent-ui chart palette
        "chart1" -> if (isDark) Color(0xFF3B9EFF) else Color(0xFF0285FF)
        "chart2" -> if (isDark) Color(0xFF22C59B) else Color(0xFF00A67D)
        "chart3" -> if (isDark) Color(0xFFA78BFA) else Color(0xFF8B5CF6)
        "chart4" -> if (isDark) Color(0xFFFBBF58) else Color(0xFFF4A340)
        "chart5" -> if (isDark) Color(0xFFF084A6) else Color(0xFFE46D91)
        "chart6" -> if (isDark) Color(0xFF63B4BE) else Color(0xFF4B9CA6)
        "transparent" -> Color.Transparent
        "white" -> Color.White
        "black" -> Color.Black
        else -> parseCssColor(raw)
    }
}

fun parseCssColor(raw: String): Color? {
    val s = raw.trim()
    if (s.startsWith("#")) {
        val hex = s.substring(1)
        return runCatching {
            when (hex.length) {
                3 -> {
                    val r = "${hex[0]}${hex[0]}".toInt(16)
                    val g = "${hex[1]}${hex[1]}".toInt(16)
                    val b = "${hex[2]}${hex[2]}".toInt(16)
                    Color(r, g, b)
                }
                6 -> Color(0xFF000000L or hex.toLong(16))
                8 -> {
                    val r = hex.substring(0, 2).toInt(16)
                    val g = hex.substring(2, 4).toInt(16)
                    val b = hex.substring(4, 6).toInt(16)
                    val a = hex.substring(6, 8).toInt(16)
                    Color(r, g, b, a)
                }
                else -> null
            }
        }.getOrNull()
    }
    val rgbMatch = Regex("""^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([0-9.]+))?\s*\)$""", RegexOption.IGNORE_CASE).matchEntire(s)
    if (rgbMatch != null) {
        val r = rgbMatch.groupValues[1].toInt().coerceIn(0, 255)
        val g = rgbMatch.groupValues[2].toInt().coerceIn(0, 255)
        val b = rgbMatch.groupValues[3].toInt().coerceIn(0, 255)
        val a = rgbMatch.groupValues[4].takeIf { it.isNotEmpty() }?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        return Color(r / 255f, g / 255f, b / 255f, a)
    }
    return null
}
