package app.touchai.android

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.FrameLayout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine

class ScreenCaptureService : AccessibilityService() {
    private val runtime get() = (application as TouchAiApplication).quickAccess
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private var bubble: ImageView? = null
    private var restoreHandle: View? = null
    private var bubbleVisible = false
    private var capturing = false
    private var options = QuickAccessSettings()
    private val params by lazy {
        WindowManager.LayoutParams(dp(52), dp(52), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
    }
    private val restoreParams by lazy {
        WindowManager.LayoutParams(dp(96), dp(48), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
    }

    override fun onServiceConnected() { runtime.connect(this) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun updateButtonPosition(value: QuickAccessSettings) {
        options = value
        sizeButton()
        positionButton()
    }

    private fun sizeButton() {
        params.width = dp(options.buttonSizeDp)
        params.height = params.width
        val padding = (params.width * 14f / 52f).roundToInt()
        bubble?.setPadding(padding, padding, padding, padding)
    }

    fun setBubbleVisible(visible: Boolean) {
        bubbleVisible = visible
        if (visible && bubble == null) createBubble()
        bubble?.visibility = if (visible && !capturing) View.VISIBLE else View.GONE
    }

    fun setRestoreHandleVisible(visible: Boolean) {
        if (visible && restoreHandle == null) createRestoreHandle()
        restoreHandle?.visibility = if (visible && !capturing) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility") // Both tapping and swiping up invoke the accessible click action.
    private fun createRestoreHandle() {
        val handle = FrameLayout(this).apply {
            contentDescription = "Restore chat"
            background = GradientDrawable().apply {
                setColor(Color.rgb(246, 242, 252))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(200, 197, 208))
            }
            addView(View(this@ScreenCaptureService).apply {
                background = GradientDrawable().apply {
                    setColor(Color.rgb(79, 70, 229))
                    cornerRadius = dp(2).toFloat()
                }
            }, FrameLayout.LayoutParams(dp(32), dp(4), Gravity.CENTER))
            setOnClickListener { startActivity(CaptureActivity.restoreIntent(this@ScreenCaptureService)) }
        }
        var downY = 0f
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        handle.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> true
                MotionEvent.ACTION_UP -> {
                    val dy = event.rawY - downY
                    if (abs(dy) <= slop || dy < -dp(24)) target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        restoreHandle = handle
        positionRestoreHandle(updateWindow = false)
        windowManager.addView(handle, restoreParams)
    }

    private fun positionRestoreHandle(updateWindow: Boolean = true) {
        val insets = windowManager.maximumWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        restoreParams.y = insets.bottom + dp(8)
        if (updateWindow) restoreHandle?.let { windowManager.updateViewLayout(it, restoreParams) }
    }

    @SuppressLint("ClickableViewAccessibility") // Taps call performClick; drags only reposition the overlay.
    private fun createBubble() {
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_spark)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = getString(R.string.floating_button_description)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(123, 108, 255), Color.rgb(52, 36, 196))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), Color.argb(90, 255, 255, 255))
            }
            elevation = dp(6).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setOnClickListener { if (!capturing) startActivity(CaptureActivity.intent(this@ScreenCaptureService)) }
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var initialX = 0
        var initialY = 0
        var dragging = false
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    initialX = params.x; initialY = params.y; dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    dragging = dragging || abs(dx) > slop || abs(dy) > slop
                    if (dragging) {
                        val bounds = buttonBounds()
                        params.x = (initialX + dx.roundToInt()).coerceIn(bounds.left, bounds.right)
                        params.y = (initialY + dy.roundToInt()).coerceIn(bounds.top, bounds.bottom)
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val bounds = buttonBounds()
                        val right = params.x >= (bounds.left + bounds.right) / 2
                        val y = (params.y - bounds.top).toFloat() / (bounds.bottom - bounds.top).coerceAtLeast(1)
                        options = options.copy(buttonOnRight = right, buttonY = y)
                        positionButton()
                        runtime.saveButtonPosition(right, y)
                    } else target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { positionButton(); true }
                else -> false
            }
        }
        bubble = view
        sizeButton()
        positionButton(updateWindow = false)
        windowManager.addView(view, params)
    }

    private fun positionButton(updateWindow: Boolean = true) {
        if (bubble == null) return
        val bounds = buttonBounds()
        params.x = if (options.buttonOnRight) bounds.right else bounds.left
        params.y = bounds.top + ((bounds.bottom - bounds.top) * options.buttonY.coerceIn(0f, 1f)).roundToInt()
        if (updateWindow) windowManager.updateViewLayout(bubble, params)
    }

    private fun buttonBounds(): android.graphics.Rect {
        val metrics = windowManager.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return android.graphics.Rect(insets.left + dp(8), insets.top + dp(8),
            (metrics.bounds.width() - insets.right - dp(options.buttonSizeDp + 8)).coerceAtLeast(insets.left + dp(8)),
            (metrics.bounds.height() - insets.bottom - dp(options.buttonSizeDp + 8)).coerceAtLeast(insets.top + dp(8)))
    }

    suspend fun captureScreen(waitForNotificationShade: Boolean): Bitmap {
        if (capturing) throw ScreenCaptureException("A screen capture is already in progress.")
        capturing = true
        bubble?.visibility = View.GONE
        restoreHandle?.visibility = View.GONE
        try {
            if (waitForNotificationShade) {
                if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                val cleared = awaitNotificationShadeDismissal(options.notificationCaptureDelayMillis, ::hasCoveringSystemWindow)
                if (!cleared) throw ScreenCaptureException("The notification panel is still open. Close it and try again, or continue without an image.")
            }
            // Allow the transparent entry activity and hidden bubble to reach the display compositor.
            repeat(2) { nextFrame() }
            return suspendCancellableCoroutine { continuation ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = result.hardwareBuffer.use { buffer ->
                            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            if (hardware == null) null else try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
                        }
                        if (bitmap == null) {
                            if (continuation.isActive) continuation.resumeWithException(ScreenCaptureException("Android could not decode the screenshot."))
                        } else if (continuation.isActive) {
                            continuation.resume(bitmap) { _, discarded, _ -> discarded.recycle() }
                        } else bitmap.recycle()
                    }
                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) continuation.resumeWithException(ScreenCaptureException(captureError(errorCode)))
                    }
                })
            }
        } finally {
            capturing = false
            setBubbleVisible(bubbleVisible)
        }
    }

    private fun hasCoveringSystemWindow(): Boolean {
        val metrics = windowManager.maximumWindowMetrics
        val screen = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val bounds = android.graphics.Rect()
        return windows.any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM) false else {
                window.getBoundsInScreen(bounds)
                bounds.width() > screen.width() * 0.8f && bounds.height() > insets.top + insets.bottom
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); positionButton(); positionRestoreHandle() }
    override fun onDestroy() {
        bubbleVisible = false
        bubble?.let(windowManager::removeViewImmediate)
        bubble = null
        restoreHandle?.let(windowManager::removeViewImmediate)
        restoreHandle = null
        runtime.disconnect(this)
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

private suspend fun nextFrame() = suspendCancellableCoroutine<Unit> { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}

internal fun captureError(code: Int): String = when (code) {
    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Screen capture access was disabled. Enable TouchAI in Accessibility settings."
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Captures were requested too quickly. Try again in a moment, or continue without an image."
    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "This screen blocks screenshots. Continue without an image."
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "The display is not available. Continue without an image."
    else -> "Android could not capture this screen. You can continue without an image."
}
