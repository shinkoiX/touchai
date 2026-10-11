package app.touchai.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ActivityScreenCaptureInstrumentedTest {
    @Test fun mainWindowIsHiddenDuringCaptureAndRestoredAfterSuccessAndFailure() = verifyCapture(MainActivity::class.java)

    @Test fun popoverIsHiddenDuringCaptureAndRestoredAfterSuccessAndFailure() = verifyCapture(CaptureActivity::class.java)

    private fun verifyCapture(activityClass: Class<out ComponentActivity>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        automation.waitForIdle(500, 5_000)
        val background = automation.takeScreenshot()
        val intent = if (activityClass == CaptureActivity::class.java) CaptureActivity.restoreIntent(context)
            else Intent(context, activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ComponentActivity>(intent).use { scenario ->
            lateinit var activity: ComponentActivity
            lateinit var draft: EditText
            val drawn = CountDownLatch(1)
            scenario.onActivity {
                activity = it
                draft = EditText(it).apply { setText("Preserved draft") }
                val content = FrameLayout(it).apply {
                    setBackgroundColor(Color.MAGENTA)
                    addView(draft, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
                }
                it.setContentView(content)
                content.viewTreeObserver.registerFrameCommitCallback { drawn.countDown() }
                draft.requestFocus()
                WindowCompat.getInsetsController(it.window, it.window.decorView).show(WindowInsetsCompat.Type.ime())
            }
            assertTrue("The test content must finish drawing", drawn.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            automation.waitForIdle(100, 5_000)
            val before = automation.takeScreenshot()
            assertTrue("The test window must be visible before capture", magentaFraction(before) > 0.25f)
            before.recycle()
            val taskId = activity.taskId
            val alpha = activity.window.attributes.alpha
            val dimAmount = activity.window.attributes.dimAmount
            val transparent = activityClass == CaptureActivity::class.java
            val capture: suspend (suspend () -> Bitmap) -> Bitmap = { takeScreenshot ->
                if (transparent) activity.captureThroughTransparentWindow(takeScreenshot)
                else activity.captureUnderlyingScreen(takeScreenshot)
            }
            val screenshot = runBlocking {
                withContext(Dispatchers.Main) {
                    capture {
                        if (transparent) assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                        else assertFalse(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                        withContext(Dispatchers.IO) {
                            if (!transparent) assertEquals("com.android.settings", automation.rootInActiveWindow.packageName.toString())
                            if (transparent) assertFalse("The keyboard must be hidden", activity.window.decorView.rootWindowInsets.isVisible(WindowInsets.Type.ime()))
                        }
                        withContext(Dispatchers.IO) { automation.takeScreenshot() }
                    }
                }
            }
            assertTrue("The captured screen must exclude TouchAI", magentaFraction(screenshot) < 0.01f)
            assertTrue("The captured screen must show the underlying app instead of the keyboard", matchingBottomFraction(background, screenshot) > 0.95f)
            screenshot.recycle()
            awaitRestored(activity)
            scenario.onActivity {
                assertEquals(taskId, it.taskId)
                assertEquals(alpha, it.window.attributes.alpha, 0f)
                assertEquals(dimAmount, it.window.attributes.dimAmount, 0f)
                assertEquals("Preserved draft", draft.text.toString())
            }
            runBlocking {
                withContext(Dispatchers.Main) {
                    try {
                        capture { throw ScreenCaptureException("Protected screen") }
                        fail("Expected capture failure")
                    } catch (error: ScreenCaptureException) {
                        assertEquals("Protected screen", error.message)
                    }
                }
            }
            awaitRestored(activity)
            scenario.onActivity {
                assertEquals(alpha, it.window.attributes.alpha, 0f)
                assertEquals(dimAmount, it.window.attributes.dimAmount, 0f)
                assertEquals("Preserved draft", draft.text.toString())
            }
        }
        background.recycle()
    }

    private fun matchingBottomFraction(expected: Bitmap, actual: Bitmap): Float {
        var matching = 0
        var total = 0
        for (y in expected.height * 7 / 10 until expected.height * 9 / 10 step 8) for (x in 0 until expected.width step 8) {
            val before = expected.getPixel(x, y)
            val after = actual.getPixel(x, y)
            if (kotlin.math.abs(Color.red(before) - Color.red(after)) <= 8 &&
                kotlin.math.abs(Color.green(before) - Color.green(after)) <= 8 &&
                kotlin.math.abs(Color.blue(before) - Color.blue(after)) <= 8) matching++
            total++
        }
        return matching.toFloat() / total
    }

    private fun awaitRestored(activity: ComponentActivity) = runBlocking {
        withTimeout(5_000) { activity.lifecycle.currentStateFlow.first { it == Lifecycle.State.RESUMED } }
    }

    private fun magentaFraction(bitmap: Bitmap): Float {
        var magenta = 0
        var total = 0
        for (y in 0 until bitmap.height step 8) for (x in 0 until bitmap.width step 8) {
            if (bitmap.getPixel(x, y) == Color.MAGENTA) magenta++
            total++
        }
        return magenta.toFloat() / total
    }
}
