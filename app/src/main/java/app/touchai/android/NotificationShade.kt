package app.touchai.android

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal suspend fun awaitNotificationShadeDismissal(settleDelayMillis: Int, hasCoveringWindow: () -> Boolean): Boolean =
    withTimeoutOrNull(3_000) {
        var clearForMillis = 0
        var wasClear = !hasCoveringWindow()
        // Accessibility windows can disappear before SystemUI's closing animation and blur finish.
        // Require a continuous clear interval, including when the first window snapshot is clear.
        while (!wasClear || clearForMillis < settleDelayMillis) {
            val interval = if (wasClear) minOf(32, settleDelayMillis - clearForMillis) else 32
            delay(interval.toLong())
            val clear = !hasCoveringWindow()
            clearForMillis = if (clear && wasClear) clearForMillis + interval else 0
            wasClear = clear
        }
        true
    } ?: false
