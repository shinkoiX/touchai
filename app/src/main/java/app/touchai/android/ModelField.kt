package app.touchai.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.AvailableModel
import app.touchai.core.openai.OpenAIModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Model ID field that lists the models the selected connection reports: ChatGPT models for a signed-in
 * account, or `GET /models` for an API key. The ID can always be typed, for providers that do not list models.
 */
@Composable
fun ModelField(connection: ApiConnection?, model: String, onChange: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as TouchAiApplication
    val manager = app.chatGptAccounts
    val accounts by manager.accounts.collectAsStateWithLifecycle()
    val chatGpt = connection?.authentication == AuthenticationMethod.ChatGpt
    val account = accounts.firstOrNull { chatGpt && it.id == connection?.chatGptAccountId }
    val endpoint = connection?.endpoint()
    val latestModel by rememberUpdatedState(model)
    val latestChange by rememberUpdatedState(onChange)
    var menu by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<AvailableModel>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    // An API key connection lists models only once its URL and key are usable.
    val apiProblem = if (endpoint == null || chatGpt) null else when {
        endpoint.apiKey.isBlank() -> "Enter the connection's API key to list its models."
        else -> apiError(OpenAIModelConfig(model = "-").using(endpoint), requireCredentials = true)
    }
    LaunchedEffect(manager) {
        try { manager.load() } catch (failure: Exception) { if (failure is CancellationException) throw failure }
    }
    LaunchedEffect(endpoint?.authentication, endpoint?.baseUrl, endpoint?.apiKey, account?.id, account?.tokens != null, reload) {
        models = emptyList(); failed = false; loading = false
        message = when {
            endpoint == null -> null
            chatGpt && account == null -> "Select a ChatGPT account in this connection to list its models."
            chatGpt && account?.tokens == null -> "Sign in to this ChatGPT connection to list its models."
            else -> apiProblem
        }
        if (endpoint == null || message != null) return@LaunchedEffect
        if (!chatGpt) delay(500) // Wait for typing in the connection to settle.
        loading = true
        try {
            models = if (chatGpt) manager.models(account!!.id).map { AvailableModel(it.slug, it.name) }
                else app.modelCatalog.models(endpoint.baseUrl, endpoint.apiKey)
            if (models.isEmpty()) { message = "No models are listed for this connection."; failed = true }
            else if (chatGpt && latestModel.isBlank()) latestChange(models.first().id)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            message = failure.message ?: "Could not load models."; failed = true
        } finally { loading = false }
    }
    // While typing, narrow the list to matching models; an exact match shows the full list again.
    val shown = models.takeIf { list -> model.isBlank() || list.any { it.id == model } }
        ?: models.filter { it.id.contains(model, ignoreCase = true) || it.name.contains(model, ignoreCase = true) }.ifEmpty { models }
    // Refresh sits inside the field so the box spans the full width like the other fields.
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(model, onChange, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), isError = failed,
            supportingText = (message ?: models.takeIf { it.isNotEmpty() }?.let { "${it.size} models available" })?.let { text -> { Text(text) } },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (connection != null) AppIconButton(R.drawable.ic_refresh, "Refresh models", { reload++ })
                    AppIconButton(R.drawable.ic_expand, "Choose model", { menu = true }, enabled = models.isNotEmpty())
                }
            })
        DropdownMenu(menu, { menu = false }, Modifier.heightIn(max = 360.dp)) {
            shown.forEach { candidate -> DropdownMenuItem(
                text = { Column {
                    Text(candidate.name)
                    if (candidate.name != candidate.id) Text(candidate.id, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                onClick = { menu = false; onChange(candidate.id) })
            }
        }
    }
}
