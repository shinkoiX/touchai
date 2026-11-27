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

internal data class ModelOption(val model: AvailableModel, val recent: Boolean)

/**
 * Reorders [catalog] so previously used models appear at the top in most-recently-used order
 * (same-endpoint history first, then matches from other endpoints), keeping remaining models
 * in their original provider order. When [catalog] is empty and [allowRecentFallback] is true,
 * falls back to models previously used with [endpointKey].
 */
internal fun prioritizeModels(
    catalog: List<AvailableModel>,
    recentModels: List<RecentModelEntry>,
    endpointKey: String?,
    allowRecentFallback: Boolean = true,
): List<ModelOption> {
    if (catalog.isEmpty()) {
        if (!allowRecentFallback || endpointKey.isNullOrBlank()) return emptyList()
        return recentModels
            .asSequence()
            .filter { it.endpointKey == endpointKey }
            .map { it.model }
            .distinct()
            .map { ModelOption(AvailableModel(it, it), recent = true) }
            .toList()
    }
    if (recentModels.isEmpty()) return catalog.map { ModelOption(it, recent = false) }

    val rank = LinkedHashMap<String, Int>()
    if (!endpointKey.isNullOrBlank()) {
        for (entry in recentModels) {
            if (entry.endpointKey == endpointKey) rank.putIfAbsent(entry.model, rank.size)
        }
    }
    for (entry in recentModels) {
        rank.putIfAbsent(entry.model, rank.size)
    }

    val recentOptions = ArrayList<Pair<Int, AvailableModel>>()
    val otherOptions = ArrayList<AvailableModel>(catalog.size)
    for (candidate in catalog) {
        val r = rank[candidate.id]
        if (r != null) recentOptions.add(r to candidate) else otherOptions.add(candidate)
    }
    recentOptions.sortBy { it.first }
    return buildList(catalog.size) {
        for ((_, candidate) in recentOptions) add(ModelOption(candidate, recent = true))
        for (candidate in otherOptions) add(ModelOption(candidate, recent = false))
    }
}

/**
 * Model ID field that lists the models the selected connection reports: ChatGPT models for a signed-in
 * account, or `GET /models` for an API key. Previously used models are floated to the top.
 * The ID can always be typed, for providers that do not list models.
 */
@Composable
fun ModelField(connection: ApiConnection?, model: String, onChange: (String) -> Unit) {
    val app = LocalContext.current.applicationContext as TouchAiApplication
    val manager = app.chatGptAccounts
    val accounts by manager.accounts.collectAsStateWithLifecycle()
    val recentModels by app.settingsRepository.recentModels.collectAsStateWithLifecycle(initialValue = emptyList())
    val chatGpt = connection?.authentication == AuthenticationMethod.ChatGpt
    val account = accounts.firstOrNull { chatGpt && it.id == connection.chatGptAccountId }
    val endpoint = connection?.endpoint()
    val endpointKey = endpoint?.recentEndpointKey()
    val latestModel by rememberUpdatedState(model)
    val latestChange by rememberUpdatedState(onChange)
    val latestRecent by rememberUpdatedState(recentModels)
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
    LaunchedEffect(endpoint?.authentication, endpoint?.baseUrl, endpoint?.apiKey, endpoint?.protocol, account?.id, account?.tokens != null, reload) {
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
                else app.modelCatalog.models(endpoint.baseUrl, endpoint.apiKey, endpoint.protocol)
            if (models.isEmpty()) { message = "No models are listed for this connection."; failed = true }
            else if (chatGpt && latestModel.isBlank()) {
                val preferred = prioritizeModels(models, latestRecent, endpointKey, allowRecentFallback = false)
                latestChange(preferred.first().model.id)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            message = failure.message ?: "Could not load models."; failed = true
        } finally { loading = false }
    }
    val prioritized = remember(models, recentModels, endpointKey, chatGpt) {
        prioritizeModels(models, recentModels, endpointKey, allowRecentFallback = !chatGpt)
    }
    // While typing, narrow the list to matching models; an exact match shows the full list again.
    val shown = prioritized.takeIf { list -> model.isBlank() || list.any { it.model.id == model } }
        ?: prioritized.filter { it.model.id.contains(model, ignoreCase = true) || it.model.name.contains(model, ignoreCase = true) }
            .ifEmpty { prioritized }
    val supporting = message ?: when {
        models.isNotEmpty() -> "${models.size} models available"
        prioritized.isNotEmpty() -> "${prioritized.size} recent ${if (prioritized.size == 1) "model" else "models"}"
        else -> null
    }
    // Refresh sits inside the field so the box spans the full width like the other fields.
    Box(Modifier.fillMaxWidth()) {
        AppTextField(
            value = model,
            onValueChange = onChange,
            label = "Model",
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            isError = failed,
            supportingText = supporting,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (connection != null) AppIconButton(R.drawable.ic_refresh, "Refresh models", { reload++ })
                    AppIconButton(R.drawable.ic_expand, "Choose model", { menu = true }, enabled = prioritized.isNotEmpty())
                }
            },
        )
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            modifier = Modifier.heightIn(max = 360.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        ) {
            shown.forEach { option ->
                val candidate = option.model
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(candidate.name)
                            if (candidate.name != candidate.id) Text(
                                candidate.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    trailingIcon = if (option.recent) {
                        { Text("Recent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                    } else null,
                    onClick = { menu = false; onChange(candidate.id) },
                )
            }
        }
    }
}
