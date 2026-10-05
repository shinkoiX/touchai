package app.touchai.core.openai

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StreamingTextBuffer(
    private val scope: CoroutineScope,
    private val flushIntervalMillis: Long = 100L,
    private val onFlush: (String) -> Unit,
) {
    private val pendingText = StringBuilder()
    private var flushJob: Job? = null

    fun append(text: String) {
        pendingText.append(text)
        if (flushJob?.isActive == true) return

        flushJob = scope.launch {
            delay(flushIntervalMillis)
            flushJob = null
            publishPendingText()
        }
    }

    fun flush() {
        flushJob?.cancel()
        flushJob = null
        publishPendingText()
    }

    fun clear() {
        flushJob?.cancel()
        flushJob = null
        pendingText.clear()
    }

    private fun publishPendingText() {
        if (pendingText.isEmpty()) return

        val text = pendingText.toString()
        pendingText.clear()
        onFlush(text)
    }
}
