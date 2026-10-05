package app.touchai.android

import kotlin.math.ceil
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
enum class CropHandle { TopLeft, TopRight, BottomLeft, BottomRight, Move }

fun ImageCrop.drag(handle: CropHandle, dx: Float, dy: Float, minimum: Float = 0.03f): ImageCrop = when (handle) {
    CropHandle.TopLeft -> copy(left = (left + dx).coerceIn(0f, right - minimum), top = (top + dy).coerceIn(0f, bottom - minimum))
    CropHandle.TopRight -> copy(right = (right + dx).coerceIn(left + minimum, 1f), top = (top + dy).coerceIn(0f, bottom - minimum))
    CropHandle.BottomLeft -> copy(left = (left + dx).coerceIn(0f, right - minimum), bottom = (bottom + dy).coerceIn(top + minimum, 1f))
    CropHandle.BottomRight -> copy(right = (right + dx).coerceIn(left + minimum, 1f), bottom = (bottom + dy).coerceIn(top + minimum, 1f))
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
