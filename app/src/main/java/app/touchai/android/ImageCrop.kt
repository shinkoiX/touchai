package app.touchai.android

import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Fractional image coordinates, independent of zoom, viewport, and display rotation. */
data class ImageCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object { val Full = ImageCrop(0f, 0f, 1f, 1f) }
    fun pixels(width: Int, height: Int): PixelCrop {
        val x = floor(left.coerceIn(0f, 1f) * width).toInt().coerceAtMost(width - 1)
        val y = floor(top.coerceIn(0f, 1f) * height).toInt().coerceAtMost(height - 1)
        val endX = ceil(right.coerceIn(0f, 1f) * width).toInt().coerceIn(x + 1, width)
        val endY = ceil(bottom.coerceIn(0f, 1f) * height).toInt().coerceIn(y + 1, height)
        return PixelCrop(x, y, endX - x, endY - y)
    }
}

data class PixelCrop(val x: Int, val y: Int, val width: Int, val height: Int)
enum class CropHandle { TopLeft, TopRight, BottomLeft, BottomRight, Left, Top, Right, Bottom, Move }

internal fun ImageCrop.hitHandle(x: Float, y: Float, width: Float, height: Float, radius: Float): CropHandle? {
    val left = left * width
    val right = right * width
    val top = top * height
    val bottom = bottom * height
    val corners = listOf(
        Triple(CropHandle.TopLeft, left, top), Triple(CropHandle.TopRight, right, top),
        Triple(CropHandle.BottomLeft, left, bottom), Triple(CropHandle.BottomRight, right, bottom),
    )
    fun distanceSquared(corner: Triple<CropHandle, Float, Float>): Float =
        (x - corner.second) * (x - corner.second) + (y - corner.third) * (y - corner.third)
    val nearest = corners.minBy(::distanceSquared)
    if (distanceSquared(nearest) <= radius * radius) return nearest.first
    val edges = buildList {
        if (y in top..bottom) {
            add(CropHandle.Left to abs(x - left))
            add(CropHandle.Right to abs(x - right))
        }
        if (x in left..right) {
            add(CropHandle.Top to abs(y - top))
            add(CropHandle.Bottom to abs(y - bottom))
        }
    }.filter { it.second <= radius }
    return edges.minByOrNull { it.second }?.first
        ?: if (x in left..right && y in top..bottom) CropHandle.Move else null
}

fun ImageCrop.drag(handle: CropHandle, dx: Float, dy: Float, minimum: Float = 0.03f): ImageCrop = when (handle) {
    CropHandle.TopLeft -> copy(left = (left + dx).coerceIn(0f, right - minimum), top = (top + dy).coerceIn(0f, bottom - minimum))
    CropHandle.TopRight -> copy(right = (right + dx).coerceIn(left + minimum, 1f), top = (top + dy).coerceIn(0f, bottom - minimum))
    CropHandle.BottomLeft -> copy(left = (left + dx).coerceIn(0f, right - minimum), bottom = (bottom + dy).coerceIn(top + minimum, 1f))
    CropHandle.BottomRight -> copy(right = (right + dx).coerceIn(left + minimum, 1f), bottom = (bottom + dy).coerceIn(top + minimum, 1f))
    CropHandle.Left -> copy(left = (left + dx).coerceIn(0f, right - minimum))
    CropHandle.Top -> copy(top = (top + dy).coerceIn(0f, bottom - minimum))
    CropHandle.Right -> copy(right = (right + dx).coerceIn(left + minimum, 1f))
    CropHandle.Bottom -> copy(bottom = (bottom + dy).coerceIn(top + minimum, 1f))
    CropHandle.Move -> {
        val shiftX = dx.coerceIn(-left, 1f - right)
        val shiftY = dy.coerceIn(-top, 1f - bottom)
        ImageCrop(left + shiftX, top + shiftY, right + shiftX, bottom + shiftY)
    }
}

fun cropBetween(x1: Float, y1: Float, x2: Float, y2: Float): ImageCrop {
    val left = min(x1, x2).coerceIn(0f, 0.97f)
    val top = min(y1, y2).coerceIn(0f, 0.97f)
    return ImageCrop(left, top, max(max(x1, x2), left + 0.03f).coerceAtMost(1f), max(max(y1, y2), top + 0.03f).coerceAtMost(1f))
}
