package app.touchai.android

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationShadeTest {
    @Test fun initiallyClearWindowStillWaitsForClosingAnimation() = runTest {
        val ready = async { awaitNotificationShadeDismissal(QuickAccessSettings().notificationCaptureDelayMillis) { false } }
        advanceTimeBy(199)
        runCurrent()
        assertFalse(ready.isCompleted)
        assertTrue(ready.await())
        assertEquals(200L, testScheduler.currentTime)
    }

    @Test fun usesTheConfiguredSettlingDelay() = runTest {
        assertTrue(awaitNotificationShadeDismissal(650) { false })
        assertEquals(650L, testScheduler.currentTime)
    }

    @Test fun zeroDelayStillWaitsForTheShadeToDisappear() = runTest {
        var covering = true
        val ready = async { awaitNotificationShadeDismissal(0) { covering } }
        advanceTimeBy(100)
        assertFalse(ready.isCompleted)
        covering = false
        assertTrue(ready.await())
        assertEquals(128L, testScheduler.currentTime)
    }

    @Test fun shadeReappearingRestartsTheSettlingPeriod() = runTest {
        var covering = false
        val ready = async { awaitNotificationShadeDismissal(200) { covering } }
        advanceTimeBy(100)
        covering = true
        advanceTimeBy(200)
        covering = false
        advanceTimeBy(199)
        runCurrent()
        assertFalse(ready.isCompleted)
        assertTrue(ready.await())
    }

    @Test fun shadeThatStaysOpenTimesOut() = runTest {
        assertFalse(awaitNotificationShadeDismissal(200) { true })
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test fun cancelledCaptureStopsCheckingWindows() = runTest {
        var checks = 0
        val ready = async { awaitNotificationShadeDismissal(200) { checks++; true } }
        advanceTimeBy(100)
        ready.cancel()
        runCurrent()
        val checksAtCancellation = checks
        advanceTimeBy(3_000)
        assertTrue(ready.isCancelled)
        assertEquals(checksAtCancellation, checks)
    }
}
