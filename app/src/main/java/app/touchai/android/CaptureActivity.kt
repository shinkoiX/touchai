package app.touchai.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModelProvider

class CaptureActivity : ComponentActivity() {
    private val app get() = application as TouchAiApplication
    private val session by viewModels<ChatSessionOwner>()
    private val viewModel by lazy { ViewModelProvider(session, app.chatViewModelFactory(invoked = intent.action != RestoreChat))[OpenAIChatViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.action == RestoreChat) app.collapsedChatSession.take()?.let(session::adopt)
        else app.collapsedChatSession.clear()
        app.quickAccess.clearChatRestoreHandle()
        setContent {
            TouchAiTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                // Until capture finishes this activity draws nothing over the previous app.
                if (!state.invoking) {
                    key(state.chatId) {
                        QuickChatPanel(onClose = ::finish, onOpenApp = ::openInApp, onCollapse = ::collapseChat) {
                            OpenAIChatScreen(viewModel, app.quickAccess, onClose = ::finish)
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && intent.action != RestoreChat) requestCapture()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        app.quickAccess.clearChatRestoreHandle()
        if (intent.action == RestoreChat) return
        app.collapsedChatSession.clear()
        viewModel.prepareInvocation()
        if (window.decorView.hasWindowFocus()) requestCapture()
    }

    private fun requestCapture() {
        val fromNotification = intent.getBooleanExtra(FromNotification, false)
        viewModel.captureOnInvocation { app.quickAccess.capture(fromNotification) }
    }

    private fun openInApp() {
        app.chatSessionTransfer.offer(session.detach())
        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.OpenChat)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun collapseChat(): Boolean {
        try { app.quickAccess.showChatRestoreHandle() }
        catch (error: ScreenCaptureException) { viewModel.reportError(error.message!!); return false }
        app.collapsedChatSession.offer(session.detach())
        finish()
        return true
    }

    companion object {
        private const val FromNotification = "from_notification"
        private const val RestoreChat = "app.touchai.android.RESTORE_CHAT"
        fun restoreIntent(context: Context) = intent(context).setAction(RestoreChat)
        fun intent(context: Context, fromNotification: Boolean = false) = Intent(context, CaptureActivity::class.java)
            .putExtra(FromNotification, fromNotification)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
