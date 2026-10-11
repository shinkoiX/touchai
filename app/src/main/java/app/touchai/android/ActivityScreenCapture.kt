package app.touchai.android

import android.app.ActivityManager
import android.graphics.Bitmap
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal suspend fun ComponentActivity.captureThroughTransparentWindow(capture: suspend () -> Bitmap): Bitmap {
    val alpha = window.attributes.alpha
    val dimAmount = window.attributes.dimAmount
    window.attributes = window.attributes.apply { this.alpha = 0f; this.dimAmount = 0f }
    try {
        window.decorView.awaitFrameCommit()
        hideKeyboardForCapture()
        window.decorView.awaitFrameCommit()
        return capture()
    } finally {
        window.attributes = window.attributes.apply { this.alpha = alpha; this.dimAmount = dimAmount }
    }
}

private suspend fun ComponentActivity.hideKeyboardForCapture() {
    val decor = window.decorView
    val ime = WindowInsets.Type.ime()
    if (!decor.rootWindowInsets.isVisible(ime)) {
        window.insetsController!!.hide(ime)
        return
    }
    try {
        suspendCancellableCoroutine<Unit> { continuation ->
            decor.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>) = insets
                override fun onEnd(animation: WindowInsetsAnimation) {
                    if (animation.typeMask and ime != 0 && !decor.rootWindowInsets.isVisible(ime) && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            })
            window.insetsController!!.hide(ime)
        }
    } finally {
        decor.setWindowInsetsAnimationCallback(null)
    }
}

private suspend fun View.awaitFrameCommit() = suspendCancellableCoroutine<Unit> { continuation ->
    val observer = viewTreeObserver
    val callback = Runnable { if (continuation.isActive) continuation.resume(Unit) }
    observer.registerFrameCommitCallback(callback)
    continuation.invokeOnCancellation { observer.unregisterFrameCommitCallback(callback) }
    invalidate()
}

internal suspend fun ComponentActivity.captureUnderlyingScreen(capture: suspend () -> Bitmap): Bitmap {
    val task = getSystemService(ActivityManager::class.java).appTasks.first { it.taskInfo.taskId == taskId }
    WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
    if (!moveTaskToBack(true)) throw ScreenCaptureException("Android could not hide TouchAI for the screenshot.")
    try {
        // Wait for the whole window, including its popups and closing animation, to leave the screen.
        val hidden = withTimeoutOrNull(3_000) {
            lifecycle.currentStateFlow.first { !it.isAtLeast(Lifecycle.State.STARTED) }
        }
        if (hidden == null) throw ScreenCaptureException("TouchAI is still visible. Try capturing again.")
        return capture()
    } finally {
        task.moveToFront()
    }
}
