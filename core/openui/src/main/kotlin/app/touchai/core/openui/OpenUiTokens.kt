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

    return when (key) {
        "ink" -> scheme.onSurface
        "muted" -> scheme.onSurfaceVariant
        "faint" -> scheme.onSurfaceVariant.copy(alpha = 0.85f)
        "surface" -> if (asBackground) scheme.surfaceContainerLow else scheme.surfaceContainerHigh
        "neutral" -> if (asBackground) scheme.surfaceContainerLow else scheme.onSurfaceVariant
        "primary" -> if (asBackground) scheme.primaryContainer else scheme.primary
        "info" -> if (asBackground) {
            if (isDark) Color(0xFF2A3351) else Color(0xFFEEF5FF)
        } else {
            if (isDark) Color(0xFFA8CDFA) else Color(0xFF1F57C3)
        }
        "success" -> if (asBackground) {
            if (isDark) Color(0xFF283E33) else Color(0xFFEAFAF3)
        } else {
            if (isDark) Color(0xFF72CA9E) else Color(0xFF1F7A5C)
        }
        "warning" -> if (asBackground) {
            if (isDark) Color(0xFF423724) else Color(0xFFFFF6DC)
        } else {
            if (isDark) Color(0xFFEEBB63) else Color(0xFF8A5200)
        }
        "danger" -> if (asBackground) {
            if (isDark) Color(0xFF49272A) else Color(0xFFFFF1F5)
        } else {
            if (isDark) Color(0xFFEA7B85) else Color(0xFFC43A68)
        }
        "purple" -> if (asBackground) {
            if (isDark) Color(0xFF342B4A) else Color(0xFFF3EFFF)
        } else {
            if (isDark) Color(0xFFB49FEF) else Color(0xFF6B53C8)
        }
        "pink" -> if (asBackground) {
            if (isDark) Color(0xFF4E2C35) else Color(0xFFFFF1F5)
        } else {
            if (isDark) Color(0xFFF797B4) else Color(0xFFC43A68)
        }
        // Baby-blue, mint, lavender, butter, pink harmonious chart palette
        "chart1" -> if (isDark) Color(0xFF7BB2F4) else Color(0xFF3F7FE8)
        "chart2" -> if (isDark) Color(0xFF72CA9E) else Color(0xFF36B38B)
        "chart3" -> if (isDark) Color(0xFFB49FEF) else Color(0xFF937CE6)
        "chart4" -> if (isDark) Color(0xFFEEBB63) else Color(0xFFE5A93B)
        "chart5" -> if (isDark) Color(0xFFF797B4) else Color(0xFFEA6E95)
        "chart6" -> if (isDark) Color(0xFFA8CDFA) else Color(0xFF79B4F7)
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
