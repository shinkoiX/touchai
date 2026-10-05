package app.touchai.android

import org.junit.Assert.*
import org.junit.Test

class ImageCropTest {
    @Test fun cropMapsBackToOriginalPixels() {
        val crop = ImageCrop(0.25f, 0.1f, 0.75f, 0.9f)
        assertEquals(PixelCrop(100, 20, 200, 160), crop.pixels(400, 200))
        assertEquals(PixelCrop(50, 40, 100, 320), crop.pixels(200, 400))
    }
    @Test fun movingSelectionCannotLosePixelsOutsideTheImage() {
        val crop = ImageCrop(0.2f, 0.2f, 0.6f, 0.7f)
        val moved = crop.drag(CropHandle.Move, 1f, -1f)
        assertEquals(1f, moved.right, 0.0001f)
        assertEquals(0f, moved.top, 0.0001f)
        assertEquals(crop.right - crop.left, moved.right - moved.left, 0.0001f)
        assertEquals(crop.bottom - crop.top, moved.bottom - moved.top, 0.0001f)
    }
    @Test fun resizingCannotInvertOrCollapseTheSelection() {
        val crop = ImageCrop(0.2f, 0.2f, 0.8f, 0.8f).drag(CropHandle.TopLeft, 1f, 1f)
        assertEquals(0.03f, crop.right - crop.left, 0.0001f)
        assertEquals(0.03f, crop.bottom - crop.top, 0.0001f)
    }
    @Test fun reverseDraggingAndFractionalEdgesProduceValidPixelBounds() {
        val crop = cropBetween(0.8f, 0.9f, 0.2f, 0.1f)
        assertEquals(ImageCrop(0.2f, 0.1f, 0.8f, 0.9f), crop)
        assertEquals(PixelCrop(3, 0, 4, 10), ImageCrop(0.333f, 0f, 0.666f, 1f).pixels(10, 10))
    }
}
