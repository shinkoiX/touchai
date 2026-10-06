package app.touchai.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.*

/** Keeps PKCE state and the loopback listener alive across activity recreation. */
class ChatGptSignInActivity : ComponentActivity() {
    private val model by viewModels<ChatGptSignInViewModel> {
        viewModelFactory { initializer {
            ChatGptSignInViewModel(application, (application as TouchAiApplication).chatGptAccounts, intent.getStringExtra(AccountId))
        } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == CancelSignIn) { finish(); return }
        setContent {
            TouchAiTheme {
                val browserUrl = model.browserUrl
                LaunchedEffect(browserUrl) {
                    if (browserUrl != null) {
                        try {
                            CustomTabsIntent.Builder()
                                .setShowTitle(true)
                                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                                .build()
                                .launchUrl(this@ChatGptSignInActivity, browserUrl.toUri())
                            model.browserOpened()
                        } catch (_: android.content.ActivityNotFoundException) { model.browserUnavailable() }
                    }
                }
                LaunchedEffect(model.accountId) {
                    model.accountId?.let {
                        setResult(Activity.RESULT_OK, Intent().putExtra(AccountId, it))
                        finish()
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Continue with ChatGPT", style = MaterialTheme.typography.headlineSmall)
                        if (model.error == null) {
                            CircularProgressIndicator()
                        } else Text(model.error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { finish() }) { Text(if (model.error == null) "Cancel" else "Close") }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == CancelSignIn) finish()
    }

    companion object {
        const val AccountId = "chatgpt_account_id"
        const val CancelSignIn = "app.touchai.android.CANCEL_CHATGPT_SIGN_IN"
    }
}

class ChatGptSignInViewModel(application: Application, manager: ChatGptAccountManager, existingAccountId: String?) : ViewModel() {
    var browserUrl by mutableStateOf<String?>(null)
        private set
    var accountId by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val signIn = viewModelScope.launch {
        try {
            ChatGptSignInService.start(application)
            accountId = manager.signIn(existingAccountId) { url -> withContext(Dispatchers.Main) { browserUrl = url } }
        }
        catch (_: TimeoutCancellationException) { error = "Sign-in timed out. Try again." }
        catch (error: CancellationException) { throw error }
        catch (failure: Exception) { error = failure.message ?: "ChatGPT sign-in failed." }
        finally { ChatGptSignInService.stop(application) }
    }

    fun browserOpened() { browserUrl = null }
    fun browserUnavailable() { signIn.cancel(); browserUrl = null; error = "Install a browser to sign in to ChatGPT." }
}
