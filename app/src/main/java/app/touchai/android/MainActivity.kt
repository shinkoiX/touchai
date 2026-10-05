package app.touchai.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val app get() = application as TouchAiApplication
    private val viewModel by viewModels<OpenAIChatViewModel> { app.chatViewModelFactory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.action == OpenSettings) viewModel.showSettings(true)
        setContent { TouchAiTheme { Surface(Modifier.fillMaxSize()) { OpenAIChatScreen(viewModel, app.quickAccess) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == OpenSettings) { viewModel.loadSettings(); viewModel.showSettings(true) }
    }

    companion object { const val OpenSettings = "app.touchai.android.OPEN_SETTINGS" }
}
