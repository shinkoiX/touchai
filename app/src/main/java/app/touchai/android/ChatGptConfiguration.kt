package app.touchai.android

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Account part of a ChatGPT connection: choose, sign in, or sign out. Models are chosen per AI configuration. */
@Composable
fun ChatGptAccountConfiguration(accountId: String?, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val manager = (context.applicationContext as TouchAiApplication).chatGptAccounts
    val accounts by manager.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val latestChange by rememberUpdatedState(onChange)
    var accountMenu by remember { mutableStateOf(false) }
    var accountMessage by remember { mutableStateOf<String?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            accountMessage = null
            latestChange(result.data!!.getStringExtra(ChatGptSignInActivity.AccountId)!!)
        }
    }
    fun launchSignIn(id: String?) {
        signIn.launch(Intent(context, ChatGptSignInActivity::class.java).putExtra(ChatGptSignInActivity.AccountId, id))
    }
    LaunchedEffect(manager) {
        try { manager.load() }
        catch (failure: Exception) { if (failure is CancellationException) throw failure; accountMessage = "Could not load ChatGPT accounts." }
    }
    if (accounts.isNotEmpty()) Box {
        OutlinedButton(onClick = { accountMenu = true }, enabled = !signingOut, modifier = Modifier.fillMaxWidth()) {
            Text(account?.let { it.label + if (it.tokens == null) " · Signed out" else "" } ?: "Select ChatGPT account")
        }
        DropdownMenu(accountMenu, { accountMenu = false }) {
            accounts.forEach { candidate -> DropdownMenuItem(text = { Text(candidate.label + if (candidate.tokens == null) " · Signed out" else "") }, onClick = {
                accountMenu = false
                if (candidate.id != accountId) onChange(candidate.id)
            }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { launchSignIn(account?.id) }, enabled = !signingOut) { Text("Continue with ChatGPT") }
        if (account != null) TextButton(onClick = { launchSignIn(null) }, enabled = !signingOut) { Text("Add account") }
    }
    if (account?.tokens != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    accountMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
