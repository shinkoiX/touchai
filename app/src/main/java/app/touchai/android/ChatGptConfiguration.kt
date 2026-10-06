package app.touchai.android

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.touchai.core.openai.ChatGptModel
import app.touchai.core.openai.OpenAIModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ChatGptConfiguration(value: OpenAIModelConfig, onChange: (OpenAIModelConfig) -> Unit) {
    val context = LocalContext.current
    val manager = (context.applicationContext as TouchAiApplication).chatGptAccounts
    val accounts by manager.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == value.chatGptAccountId }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onChange)
    var accountMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var models by remember(value.chatGptAccountId) { mutableStateOf<List<ChatGptModel>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            accountMessage = null
            val id = result.data!!.getStringExtra(ChatGptSignInActivity.AccountId)!!
            latestChange(latestValue.copy(chatGptAccountId = id, model = if (id == latestValue.chatGptAccountId) latestValue.model else ""))
            reload++
        }
    }
    fun launchSignIn(id: String?) {
        signIn.launch(Intent(context, ChatGptSignInActivity::class.java).putExtra(ChatGptSignInActivity.AccountId, id))
    }
    LaunchedEffect(manager) {
        try { manager.load() }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; accountMessage = "Could not load ChatGPT accounts." }
    }
    LaunchedEffect(value.chatGptAccountId, account?.tokens != null, reload) {
        models = emptyList()
        error = null
        loading = false
        if (account?.tokens != null) {
            loading = true
            try {
                models = manager.models(account.id)
                if (models.isEmpty()) error = "No models are listed for this ChatGPT account."
                if (latestValue.model.isBlank() && models.isNotEmpty()) latestChange(latestValue.copy(model = models.first().slug))
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "Could not load ChatGPT models." }
            finally { loading = false }
        }
    }
    if (accounts.isNotEmpty()) Box {
        OutlinedButton(onClick = { accountMenu = true }, enabled = !signingOut, modifier = Modifier.fillMaxWidth()) {
            Text(account?.let { it.label + if (it.tokens == null) " · Signed out" else "" } ?: "Select ChatGPT account")
        }
        DropdownMenu(accountMenu, { accountMenu = false }) {
            accounts.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.label + if (candidate.tokens == null) " · Signed out" else "") }, onClick = {
                accountMenu = false
                if (candidate.id != value.chatGptAccountId) onChange(value.copy(chatGptAccountId = candidate.id, model = ""))
            }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { launchSignIn(account?.id) }, enabled = !signingOut) { Text("Continue with ChatGPT") }
        if (account != null) TextButton(onClick = { launchSignIn(null) }, enabled = !signingOut) { Text("Add account") }
    }
    if (account?.tokens != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedTextField(value.model, { onChange(value.copy(model = it)) },
                    label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    trailingIcon = {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else AppIconButton(R.drawable.ic_expand, "Choose ChatGPT model", { modelMenu = true }, enabled = models.isNotEmpty())
                    })
                DropdownMenu(modelMenu, { modelMenu = false }) {
                    models.forEach { model -> DropdownMenuItem(text = { Text(model.name) }, onClick = {
                        modelMenu = false; onChange(value.copy(model = model.slug))
                    }) }
                }
            }
            AppIconButton(R.drawable.ic_refresh, "Refresh ChatGPT models", { reload++ }, enabled = !loading && !signingOut)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { uriHandler.openUri("https://chatgpt.com/settings/usage") }) { Text("Usage") }
            TextButton(onClick = {
                signingOut = true
                scope.launch {
                    try { accountMessage = manager.signOut(account.id) }
                    catch (failure: Exception) { if (failure is CancellationException) throw failure; accountMessage = "Could not sign out of ChatGPT." }
                    finally { signingOut = false }
                }
            }, enabled = !signingOut) { Text(if (signingOut) "Signing out…" else "Sign out") }
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    accountMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
