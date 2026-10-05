package app.touchai.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class CaptureActivity : ComponentActivity() {
    private val app get() = application as TouchAiApplication
    private val viewModel by viewModels<OpenAIChatViewModel> { app.chatViewModelFactory(invoked = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TouchAiTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                // Until capture finishes this activity draws nothing over the previous app.
                if (!state.invoking) {
                    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))) {
                        val landscape = maxWidth > maxHeight
                        Column(Modifier.fillMaxSize()) {
                            if (!landscape) Spacer(Modifier.weight(0.12f).fillMaxWidth().clickable { finish() })
                            Surface(Modifier.weight(if (landscape) 1f else 0.88f).fillMaxWidth(),
                                shape = if (landscape) RectangleShape else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
                                Column {
                                    if (!landscape) Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(32.dp, 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
                                    }
                                    OpenAIChatScreen(viewModel, app.quickAccess, onClose = ::finish)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) requestCapture()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.prepareInvocation()
        if (window.decorView.hasWindowFocus()) requestCapture()
    }

    private fun requestCapture() {
        val fromNotification = intent.getBooleanExtra(FromNotification, false)
        viewModel.captureOnInvocation { app.quickAccess.capture(fromNotification) }
    }

    companion object {
        private const val FromNotification = "from_notification"
        fun intent(context: Context, fromNotification: Boolean = false) = Intent(context, CaptureActivity::class.java)
            .putExtra(FromNotification, fromNotification)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
