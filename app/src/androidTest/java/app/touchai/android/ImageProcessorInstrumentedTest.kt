package app.touchai.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageProcessorInstrumentedTest {
    @Test fun onlyTheCroppedPixelsAreEncoded() = runBlocking {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        for (y in 0 until 100) for (x in 50 until 100) bitmap.setPixel(x, y, Color.BLUE)
        val prepared = ImageProcessor.prepare(bitmap, ImageCrop(0.5f, 0f, 1f, 1f), ImageQuality.Original)
        val bytes = Base64.getDecoder().decode(prepared.input.url.substringAfter(','))
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(50, decoded.width)
        assertEquals(100, decoded.height)
        assertEquals(Color.BLUE, decoded.getPixel(0, 0))
        assertEquals(Color.BLUE, decoded.getPixel(49, 99))
        assertFalse(prepared.input.url.isBlank())
    }

    @Test fun fullImageKeepsTheOriginalDimensionsAndBalancedReducesTheLongestEdge() = runBlocking {
        val bitmap = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GREEN)
        val original = ImageProcessor.prepare(bitmap, ImageCrop.Full, ImageQuality.Original)
        assertEquals(2400, original.preview.width)
        assertEquals(1200, original.preview.height)
        val balanced = ImageProcessor.prepare(bitmap, ImageCrop.Full, ImageQuality.Balanced)
        assertEquals(2048, balanced.preview.width)
        assertEquals(1024, balanced.preview.height)
    }

    @Test fun androidKeystoreEncryptsAndDecryptsAcrossCipherInstances() {
        val encrypted = ApiKeyCipher().encrypt("instrumentation-test-only")
        assertNotEquals("instrumentation-test-only", encrypted)
        assertEquals("instrumentation-test-only", ApiKeyCipher().decrypt(encrypted))
    }
}
