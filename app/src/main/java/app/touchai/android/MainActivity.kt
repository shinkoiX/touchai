package app.touchai.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    private val app get() = application as TouchAiApplication
    private val session by viewModels<ChatSessionOwner>()
    private var viewModel by mutableStateOf<OpenAIChatViewModel?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showChat(intent)
        setContent {
            TouchAiTheme {
                Surface(Modifier.fillMaxSize()) {
                    viewModel?.let { model -> key(model) { OpenAIChatScreen(model, app.quickAccess) } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showChat(intent)
    }

    private fun showChat(intent: Intent) {
        val incoming = (if (intent.action == OpenChat) app.chatSessionTransfer.take() else null)
            ?: app.collapsedChatSession.take()
        incoming?.let {
            session.adopt(it)
            app.quickAccess.clearChatRestoreHandle()
        }
        val model = ViewModelProvider(session, app.chatViewModelFactory())[OpenAIChatViewModel::class.java]
        viewModel = model
        if (intent.action == OpenSettings) { model.loadSettings(); model.showSettings(true) }
    }

    companion object {
        const val OpenSettings = "app.touchai.android.OPEN_SETTINGS"
        const val OpenChat = "app.touchai.android.OPEN_CHAT"
    }
}
