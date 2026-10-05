package app.touchai.android

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.touchai.core.openai.OpenAIImage
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ImageFileData(val mimeType: String, val extension: String, val bytes: ByteArray)

internal fun imageFileData(image: OpenAIImage): ImageFileData {
    val header = image.url.substringBefore(',')
    val (mime, extension) = when (header) {
        "data:image/png;base64" -> "image/png" to "png"
        "data:image/jpeg;base64" -> "image/jpeg" to "jpg"
        "data:image/webp;base64" -> "image/webp" to "webp"
        else -> throw IOException("Unsupported image format.")
    }
    return ImageFileData(mime, extension, Base64.getDecoder().decode(image.url.substringAfter(',')))
}

internal object ImageGallery {
    suspend fun save(context: Context, image: OpenAIImage): Uri = withContext(Dispatchers.IO) {
        val data = imageFileData(image)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "TouchAI-${System.currentTimeMillis()}.${data.extension}")
            put(MediaStore.Images.Media.MIME_TYPE, data.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TouchAI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create the picture.")
        try {
            val output = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open the picture for writing.")
            output.use { it.write(data.bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
