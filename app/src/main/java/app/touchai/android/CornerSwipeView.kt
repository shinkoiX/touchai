package app.touchai.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor") // Created by the capture service, never inflated from XML.
internal class CornerSwipeView(context: Context, private val onTap: (Float, Float) -> Unit, onCapture: () -> Unit) : FrameLayout(context) {
    private var tracking = false
    private var triggered = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var gestures = setOf(CornerGesture.SwipeUpRight)
    private var corner = GestureCorner.BottomLeft
    private var pendingTap: PointF? = null
    private val marker = ImageView(context)
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (!tracking || triggered) return false
            if (CornerGesture.DoubleTap in gestures) pendingTap = PointF(event.rawX, event.rawY)
            else onTap(event.rawX, event.rawY)
            return true
        }
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            pendingTap?.let {
                pendingTap = null
                onTap(it.x, it.y)
            }
            return true
        }
        override fun onDoubleTap(event: MotionEvent): Boolean {
            pendingTap = null
            return true
        }
        override fun onDoubleTapEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) triggerCapture()
            return true
        }
        override fun onLongPress(event: MotionEvent) {
            if (CornerGesture.LongPress in gestures) triggerCapture()
        }
    }
    private val detector = GestureDetector(context, gestureListener)

    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.rgb(246, 242, 252))
            setStroke(dp(1), Color.rgb(79, 70, 229))
        }
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            onCapture()
        }
        addView(marker.apply {
            setImageResource(R.drawable.ic_arrow_up)
            rotation = 45f
            imageTintList = ColorStateList.valueOf(Color.rgb(79, 70, 229))
            setPadding(dp(5), dp(5), dp(5), dp(5))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(28), dp(28), Gravity.CENTER))
    }

    fun configure(options: QuickAccessSettings) {
        tracking = false
        pendingTap = null
        gestures = options.cornerGestures
        corner = options.gestureCorner
        detector.setIsLongpressEnabled(CornerGesture.LongPress in gestures)
        detector.setOnDoubleTapListener(if (CornerGesture.DoubleTap in gestures) gestureListener else null)
        contentDescription = context.getString(R.string.corner_gesture_description,
            CornerGesture.entries.filter { it in gestures }.joinToString { it.label(corner) })
        alpha = options.cornerOpacityPercent / 100f
        val rotation = gestures.singleOrNull()?.arrowRotation(corner)
        marker.setImageResource(if (rotation == null) R.drawable.ic_spark else R.drawable.ic_arrow_up)
        marker.rotation = rotation ?: 0f
        marker.layoutParams = LayoutParams(dp(28), dp(28), Gravity.CENTER)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            tracking = false
            pendingTap = null
        }
    }

    override fun onDetachedFromWindow() {
        tracking = false
        pendingTap = null
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility") // Recognized gestures call performClick through triggerCapture.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = true
                triggered = false
                downX = event.rawX
                downY = event.rawY
                downTime = event.eventTime
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                pendingTap = null
            }
        }
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val density = resources.displayMetrics.density
            val inwardX = (event.rawX - downX) / density * if (corner.onRight) -1f else 1f
            val inwardY = (event.rawY - downY) / density * if (corner.onBottom) -1f else 1f
            if (gestures.any { isCornerCaptureSwipe(it,
                    inwardX, inwardY, event.eventTime - downTime) }) {
                triggerCapture()
            }
            tracking = false
        }
        return true
    }

    private fun triggerCapture() {
        if (!tracking || triggered) return
        triggered = true
        pendingTap = null
        performClick()
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

internal fun isCornerCaptureSwipe(gesture: CornerGesture, rightDp: Float, upDp: Float, durationMillis: Long): Boolean =
    durationMillis <= 1_500 && when (gesture) {
        CornerGesture.SwipeUpRight -> rightDp >= 32f && upDp >= 32f &&
            rightDp * rightDp + upDp * upDp >= 80f * 80f && rightDp <= upDp * 3f && upDp <= rightDp * 3f
        CornerGesture.SwipeUp -> upDp >= 80f && abs(rightDp) <= upDp / 3f
        CornerGesture.SwipeRight -> rightDp >= 80f && abs(upDp) <= rightDp / 3f
        CornerGesture.DoubleTap, CornerGesture.LongPress -> false
    }
