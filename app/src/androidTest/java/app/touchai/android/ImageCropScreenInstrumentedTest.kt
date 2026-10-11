package app.touchai.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageCropScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
    private var applied: ImageCrop? = null

    private fun setup(initial: ImageCrop = ImageCrop.Full): Rect {
        compose.setContent { TouchAiTheme { ImageCropScreen(bitmap, initial, { applied = it }, {}) } }
        val canvas = compose.onNodeWithContentDescription("Crop selection").fetchSemanticsNode().boundsInRoot
        val gutter = 28f * compose.activity.resources.displayMetrics.density
        val scale = min((canvas.width - 2f * gutter) / bitmap.width, (canvas.height - 2f * gutter) / bitmap.height)
        val size = Size(bitmap.width * scale, bitmap.height * scale)
        return Rect(Offset((canvas.width - size.width) / 2f, (canvas.height - size.height) / 2f), size)
    }

    @Test fun fastCornerDragStartsFromTheFingerDownPositionInTheMargin() {
        val image = setup()
        val margin = 12f * compose.activity.resources.displayMetrics.density
        compose.onNodeWithContentDescription("Crop selection").performTouchInput {
            down(image.topLeft - Offset(margin, margin))
            moveTo(image.topLeft + Offset(image.width * 0.2f, image.height * 0.2f), delayMillis = 32)
            up()
        }
        compose.onNodeWithText("Apply", useUnmergedTree = true).performClick()
        assertTrue(applied!!.left > 0.1f)
        assertTrue(applied!!.top > 0.1f)
        assertEquals(1f, applied!!.right)
        assertEquals(1f, applied!!.bottom)
    }

    @Test fun eachEdgeCanBeResizedFromTheOuterMargin() {
        val image = setup()
        val margin = 12f * compose.activity.resources.displayMetrics.density
        val gestures = listOf(
            CropHandle.Left to (Offset(image.left - margin, image.center.y) to Offset(image.left + image.width * 0.2f, image.center.y)),
            CropHandle.Top to (Offset(image.center.x, image.top - margin) to Offset(image.center.x, image.top + image.height * 0.2f)),
            CropHandle.Right to (Offset(image.right + margin, image.center.y) to Offset(image.right - image.width * 0.2f, image.center.y)),
            CropHandle.Bottom to (Offset(image.center.x, image.bottom + margin) to Offset(image.center.x, image.bottom - image.height * 0.2f)),
        )
        for ((handle, positions) in gestures) {
            compose.onNodeWithText("Reset", useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription("Crop selection").performTouchInput { swipe(positions.first, positions.second, 250) }
            compose.onNodeWithText("Apply", useUnmergedTree = true).performClick()
            val crop = applied!!
            when (handle) {
                CropHandle.Left -> { assertTrue(crop.left > 0.1f); assertEquals(0f, crop.top); assertEquals(1f, crop.right); assertEquals(1f, crop.bottom) }
                CropHandle.Top -> { assertTrue(crop.top > 0.1f); assertEquals(0f, crop.left); assertEquals(1f, crop.right); assertEquals(1f, crop.bottom) }
                CropHandle.Right -> { assertTrue(crop.right < 0.9f); assertEquals(0f, crop.top); assertEquals(0f, crop.left); assertEquals(1f, crop.bottom) }
                CropHandle.Bottom -> { assertTrue(crop.bottom < 0.9f); assertEquals(0f, crop.top); assertEquals(0f, crop.left); assertEquals(1f, crop.right) }
                else -> error("The test uses edge handles")
            }
        }
        compose.onNodeWithText("Reset", useUnmergedTree = true).performClick()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "crop-margin-handles.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Test fun movingTheSelectionPreservesItsSize() {
        val initial = ImageCrop(0.2f, 0.2f, 0.7f, 0.7f)
        val image = setup(initial)
        val start = image.topLeft + Offset(image.width * 0.45f, image.height * 0.45f)
        compose.onNodeWithContentDescription("Crop selection").performTouchInput {
            swipe(start, start + Offset(image.width * 0.1f, image.height * 0.1f), 250)
        }
        compose.onNodeWithText("Apply", useUnmergedTree = true).performClick()
        assertTrue(applied!!.left > initial.left + 0.05f)
        assertTrue(applied!!.top > initial.top + 0.05f)
        assertEquals(initial.right - initial.left, applied!!.right - applied!!.left, 0.0001f)
        assertEquals(initial.bottom - initial.top, applied!!.bottom - applied!!.top, 0.0001f)
    }
}
