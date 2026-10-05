package app.touchai.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.scale
import app.touchai.core.openai.OpenAIImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PreparedImage(val input: OpenAIImage, val preview: Bitmap)

object ImageProcessor {
    suspend fun decode(image: OpenAIImage, maxEdge: Int): Bitmap = withContext(Dispatchers.Default) {
        val bytes = Base64.getDecoder().decode(image.url.substringAfter(','))
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxEdge) options.inSampleSize *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("The image cannot be decoded.")
    }

    suspend fun prepare(bitmap: Bitmap, crop: ImageCrop, quality: ImageQuality): PreparedImage = withContext(Dispatchers.Default) {
        val area = crop.pixels(bitmap.width, bitmap.height)
        val cropped = Bitmap.createBitmap(bitmap, area.x, area.y, area.width, area.height)
        val maximum = quality.maxEdge
        val resized = if (maximum != null && maxOf(cropped.width, cropped.height) > maximum) {
            val scale = maximum.toFloat() / maxOf(cropped.width, cropped.height)
            cropped.scale((cropped.width * scale).roundToInt().coerceAtLeast(1),
                (cropped.height * scale).roundToInt().coerceAtLeast(1), true)
        } else cropped
        val lossless = quality == ImageQuality.Original
        val bytes = ByteArrayOutputStream().use { output ->
            resized.compress(if (lossless) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality.jpegQuality, output)
            output.toByteArray()
        }
        PreparedImage(OpenAIImage("data:image/${if (lossless) "png" else "jpeg"};base64,${Base64.getEncoder().encodeToString(bytes)}"), resized)
    }

    suspend fun load(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit }
            ?: throw IOException("The image cannot be opened.")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image format.")
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 4096) options.inSampleSize *= 2
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("The image cannot be decoded.")
    }
}
