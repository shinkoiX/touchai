package app.touchai.android

import org.junit.Assert.*
import org.junit.Test

class CornerSwipeTest {
    @Test fun diagonalSwipesWorkForShortAndFullScreenMotions() {
        assertTrue(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 70f, 90f, 300))
        assertTrue(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 320f, 720f, 900))
        assertTrue(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 650f, 300f, 1_200))
    }

    @Test fun tapsAndSmallMovementsDoNotCapture() {
        CornerGesture.entries.forEach { gesture ->
            assertFalse(isCornerCaptureSwipe(gesture, 0f, 0f, 50))
            assertFalse(isCornerCaptureSwipe(gesture, 35f, 40f, 180))
        }
    }

    @Test fun scrollingOrSwipingInOtherDirectionsDoesNotCapture() {
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 0f, 200f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 200f, 0f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, -100f, 100f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 100f, -100f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 35f, 240f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 240f, 35f, 300))
    }

    @Test fun holdingBeforeDraggingDoesNotCapture() {
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUpRight, 100f, 100f, 2_000))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUp, 0f, 150f, 2_000))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeRight, 150f, 0f, 2_000))
    }

    @Test fun upwardSwipesAllowDriftButRejectOtherDirections() {
        assertTrue(isCornerCaptureSwipe(CornerGesture.SwipeUp, 0f, 150f, 300))
        assertTrue(isCornerCaptureSwipe(CornerGesture.SwipeUp, -25f, 150f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUp, 150f, 0f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUp, 100f, 100f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeUp, 0f, -150f, 300))
    }

    @Test fun rightwardSwipesAllowDriftButRejectOtherDirections() {
        assertTrue(isCornerCaptureSwipe(CornerGesture.SwipeRight, 150f, 0f, 300))
        assertTrue(isCornerCaptureSwipe(CornerGesture.SwipeRight, 150f, -25f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeRight, 0f, 150f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeRight, 100f, 100f, 300))
        assertFalse(isCornerCaptureSwipe(CornerGesture.SwipeRight, -150f, 0f, 300))
    }

    @Test fun tapAndHoldChoicesDoNotEnableSwipes() {
        for (gesture in listOf(CornerGesture.DoubleTap, CornerGesture.LongPress)) {
            assertFalse(isCornerCaptureSwipe(gesture, 150f, 0f, 300))
            assertFalse(isCornerCaptureSwipe(gesture, 0f, 150f, 300))
            assertFalse(isCornerCaptureSwipe(gesture, 100f, 100f, 300))
        }
    }
}
