package app.touchai.android

import app.touchai.core.openai.OpenAIImage
import java.io.IOException
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ImageGalleryTest {
    @Test fun savingPreservesOriginalEncodedBytesAndFormat() {
        val bytes = byteArrayOf(0, 1, 2, 3, -1, -128)
        for ((mime, extension) in listOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp")) {
            val image = OpenAIImage("data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}")
            val data = imageFileData(image)
            assertEquals(mime, data.mimeType)
            assertEquals(extension, data.extension)
            assertArrayEquals(bytes, data.bytes)
        }
    }

    @Test(expected = IOException::class) fun unsupportedFormatsCannotBeSavedAsPictures() {
        imageFileData(OpenAIImage("data:text/plain;base64,dGVzdA=="))
    }
}
