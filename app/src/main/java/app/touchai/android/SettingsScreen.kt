package app.touchai.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import app.touchai.core.openai.ApiProtocol
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.GeminiBaseUrl
import app.touchai.core.openai.GeminiServiceTiers
import app.touchai.core.openai.OpenAIModelConfig
import app.touchai.core.openai.OpenAiServiceTiers
import app.touchai.core.openai.ReasoningEffortTiers
import app.touchai.core.openai.wireServiceTier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import java.net.URI
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: OpenAIChatUiState, runtime: QuickAccessRuntime, onSave: (AppSettings) -> Unit,
    onDraftChange: (AppSettings) -> Unit, onTest: (AiConfiguration, String) -> Unit, onLogs: () -> Unit, onClose: () -> Unit) {
    val draft = state.settingsDraft!!
    var expandedPreset by rememberSaveable { mutableStateOf(state.selectedPreset) }
    var expandedConnection by rememberSaveable { mutableStateOf<String?>(null) }
    // Settings from the repository are already migrated; this covers drafts built elsewhere.
    LaunchedEffect(draft.connectionId == null) { if (draft.connectionId == null) onDraftChange(draft.withConnections()) }
    BackHandler(enabled = !state.savingSettings) { onClose() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        AppTopBar("Settings", navigation = { AppIconButton(R.drawable.ic_back, "Back", onClose, enabled = !state.savingSettings) }) {
            Button(onClick = { onSave(draft) }, enabled = !state.savingSettings, modifier = Modifier.padding(end = 8.dp)) {
                Text(if (state.savingSettings) "Saving…" else "Save")
            }
        }
        if (state.testingConnection) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (state.error != null || state.connectionResult != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.error?.let { MessageBanner(it, error = true) }
                state.connectionResult?.let { MessageBanner(it, error = false) }
            }
            Column {
                SectionHeader("Quick access")
                QuickAccessSettings(draft.quickAccess, { onDraftChange(draft.copy(quickAccess = it)) }, runtime)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Connections", Modifier.padding(bottom = 0.dp))
                Text("Add each API key or ChatGPT account once, then choose it for the default AI or a preset.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                draft.connections.forEach { connection ->
                    key(connection.id) {
                        val usedBy = buildList {
                            if (draft.connectionId == connection.id) add("Default AI")
                            draft.presets.forEach { preset ->
                                val presetLabel = preset.name.ifBlank { "New preset" }
                                if (preset.customAi?.connectionId == connection.id) add(presetLabel)
                                if (preset.secondaryAi?.connectionId == connection.id) add("$presetLabel (2nd AI)")
                            }
                        }
                        ConnectionEditor(connection, expanded = expandedConnection == connection.id, usedBy = usedBy,
                            onExpand = { expandedConnection = if (expandedConnection == connection.id) null else connection.id },
                            onChange = { value -> onDraftChange(draft.copy(connections = draft.connections.map { if (it.id == connection.id) value else it })) },
                            onRemove = { onDraftChange(draft.copy(connections = draft.connections.filterNot { it.id == connection.id })) })
                    }
                }
                OutlinedButton(onClick = {
                    val name = generateSequence(1) { it + 1 }.map { "Connection $it" }.first { candidate -> draft.connections.none { it.name == candidate } }
                    val connection = ApiConnection(name = name)
                    onDraftChange(draft.copy(connections = draft.connections + connection))
                    expandedConnection = connection.id
                }, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(R.drawable.ic_add, null, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Add connection")
                }
            }
            Column {
                SectionHeader("Default AI")
                SettingsGroup {
                    AiConfigurationEditor(draft.defaultAi, draft.connections,
                        { onDraftChange(draft.copy(api = it.api, instructions = it.instructions, connectionId = it.connectionId)) })
                    TestButton(state.testingConnection) { onTest(draft.resolve(draft.defaultAi), "Default AI") }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeader("Intelligent UI", Modifier.padding(bottom = 0.dp))
                SettingsGroup {
                    SwitchRow("Intelligent UI (OpenUI)", draft.intelligentUi, { onDraftChange(draft.copy(intelligentUi = it)) })
                    Text(
                        "Render rich cards, side-by-side media rows, SVG diagrams, charts, and interactive tools natively using the official OpenUI prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
            Column {
                SectionHeader("Image size")
                Choices(ImageQuality.entries, { it == draft.imageQuality }, { it.label }) { onDraftChange(draft.copy(imageQuality = it)) }
            }
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Image limit", style = MaterialTheme.typography.titleSmall)
                    Text(draft.imageLimit.toString(), style = MaterialTheme.typography.bodyMedium)
                }
                Slider(draft.imageLimit.toFloat(), { onDraftChange(draft.copy(imageLimit = it.roundToInt())) },
                    valueRange = 1f..10f, steps = 8, modifier = Modifier.semantics { contentDescription = "Image limit" })
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Presets", Modifier.padding(bottom = 0.dp))
                draft.presets.forEachIndexed { index, preset ->
                    key(preset.id) {
                        fun change(value: PromptPreset) { onDraftChange(draft.copy(presets = draft.presets.map { if (it.id == preset.id) value else it })) }
                        val expanded = expandedPreset == preset.id
                        val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                        SettingsGroup {
                            Row(Modifier.fillMaxWidth().clickable { expandedPreset = if (expanded) null else preset.id }.padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(preset.name.ifBlank { "New preset" }, style = MaterialTheme.typography.titleMedium)
                                    val primarySummary = preset.customAi?.let { ai ->
                                        listOfNotNull(draft.connection(ai.connectionId)?.name, ai.api.model.takeIf(String::isNotBlank)).joinToString(" · ").ifBlank { "Custom AI" }
                                    } ?: "Default AI"
                                    val subtitleText = preset.secondaryAi?.let { secondary ->
                                        val secondaryLabel = secondary.api.model.takeIf(String::isNotBlank)
                                            ?: draft.connection(secondary.connectionId)?.name
                                            ?: "2nd AI"
                                        val primaryLabel = preset.customAi?.api?.model?.takeIf(String::isNotBlank)
                                            ?: draft.api.model.takeIf(String::isNotBlank)
                                            ?: primarySummary
                                        "2× · $primaryLabel ⇄ $secondaryLabel"
                                    } ?: primarySummary
                                    Text(
                                        subtitleText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (preset.secondaryAi != null) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                AppIconButton(R.drawable.ic_arrow_up, "Move ${preset.name} up", {
                                    onDraftChange(draft.copy(presets = draft.presets.toMutableList().apply { add(index - 1, removeAt(index)) }))
                                }, enabled = index > 0)
                                AppIconButton(R.drawable.ic_arrow_down, "Move ${preset.name} down", {
                                    onDraftChange(draft.copy(presets = draft.presets.toMutableList().apply { add(index + 1, removeAt(index)) }))
                                }, enabled = index < draft.presets.lastIndex)
                                AppIcon(R.drawable.ic_expand, if (expanded) "Collapse" else "Expand", Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (expanded) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedTextField(preset.name, { change(preset.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(preset.prompt, { change(preset.copy(prompt = it)) }, label = { Text("Prompt") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                                    Text("Screenshot attachment", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Choices(listOf(null, true, false), { it == preset.attachScreenshot }, {
                                        when (it) { null -> "Default"; true -> "Always"; false -> "Never" }
                                    }) { change(preset.copy(attachScreenshot = it)) }
                                }
                                SwitchRow("Custom AI", preset.customAi != null, { custom ->
                                    change(preset.copy(customAi = if (custom) draft.defaultAi.copy(api = draft.api.copy(webSearch = true)) else null))
                                })
                                preset.customAi?.let { configuration ->
                                    AiConfigurationEditor(configuration, draft.connections, { change(preset.copy(customAi = it)) })
                                    TestButton(state.testingConnection) { onTest(draft.resolve(configuration), preset.name) }
                                }
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                                SwitchRow("Dual send (2nd AI)", preset.secondaryAi != null, { dual ->
                                    val seed = preset.customAi ?: draft.defaultAi
                                    change(preset.copy(secondaryAi = if (dual) seed.copy(api = seed.api.copy(webSearch = true)) else null))
                                })
                                preset.secondaryAi?.let { secondary ->
                                    AiConfigurationEditor(secondary, draft.connections, { change(preset.copy(secondaryAi = it)) })
                                    TestButton(state.testingConnection) { onTest(draft.resolve(secondary), "${preset.name} (2nd AI)") }
                                }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                                    AppIconButton(R.drawable.ic_delete, "Remove", {
                                        onDraftChange(draft.copy(presets = draft.presets.filterNot { it.id == preset.id },
                                            lastPresetId = draft.lastPresetId?.takeUnless { it == preset.id }))
                                    })
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    val id = UUID.randomUUID().toString()
                    onDraftChange(draft.copy(presets = draft.presets + PromptPreset(id, "", "")))
                    expandedPreset = id
                }, modifier = Modifier.fillMaxWidth()) {
                    AppIcon(R.drawable.ic_add, null, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Add preset")
                }
            }
            SettingsGroup {
                Row(Modifier.fillMaxWidth().clickable(onClick = onLogs).heightIn(min = 56.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppIcon(R.drawable.ic_list, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Request logs", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    AppIcon(R.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AboutAndUpdatesSection()
        }
    }
}

@Composable
private fun AboutAndUpdatesSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var updateDialog by remember { mutableStateOf<UpdateCheckResult.UpdateAvailable?>(null) }

    fun checkNow() {
        if (checking) return
        val app = context.applicationContext as? TouchAiApplication ?: return
        checking = true
        scope.launch {
            try {
                val result = app.updateChecker.check()
                updateResult = result
                if (result is UpdateCheckResult.UpdateAvailable) updateDialog = result
            } finally {
                checking = false
            }
        }
    }

    Column {
        SectionHeader("About")
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Version", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !checking) {
                        val available = updateResult as? UpdateCheckResult.UpdateAvailable
                        if (available != null) updateDialog = available else checkNow()
                    }
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    AppIcon(
                        R.drawable.ic_refresh,
                        null,
                        tint = if (updateResult is UpdateCheckResult.UpdateAvailable) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Check for updates", style = MaterialTheme.typography.bodyLarge)
                    val (subtitle, subtitleColor) = when (val res = updateResult) {
                        null -> (if (checking) "Checking GitHub Releases…" else "Check GitHub Releases") to MaterialTheme.colorScheme.onSurfaceVariant
                        is UpdateCheckResult.UpToDate -> "Up to date (v${res.latestVersion})" to MaterialTheme.colorScheme.onSurfaceVariant
                        is UpdateCheckResult.UpdateAvailable -> "New version v${res.latestVersion} available" to MaterialTheme.colorScheme.primary
                        is UpdateCheckResult.Error -> res.message to MaterialTheme.colorScheme.error
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
                }
                if (updateResult is UpdateCheckResult.UpdateAvailable) {
                    TextButton(onClick = { checkNow() }, enabled = !checking) { Text("Recheck") }
                } else {
                    AppIcon(R.drawable.ic_chevron_right, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    updateDialog?.let { available ->
        AlertDialog(
            onDismissRequest = { updateDialog = null },
            title = { Text("Update available: v${available.latestVersion}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Current version: v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        available.releaseNotes ?: "A newer release is available on GitHub.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    updateDialog = null
                    uriHandler.openUri(available.apkUrl ?: available.releaseUrl)
                }) {
                    Text(if (available.apkUrl != null) "Download APK" else "Open release")
                }
            },
            dismissButton = {
                Row {
                    if (available.apkUrl != null && available.apkUrl != available.releaseUrl) {
                        TextButton(onClick = {
                            updateDialog = null
                            uriHandler.openUri(available.releaseUrl)
                        }) { Text("Release notes") }
                    }
                    TextButton(onClick = { updateDialog = null }) { Text("Later") }
                }
            },
        )
    }
}

@Composable
private fun TestButton(testing: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = !testing, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(if (testing) "Testing…" else "Test connection")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choices(options: List<T>, selected: (T) -> Boolean, label: (T) -> String, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(selected(option), { onSelect(option) }, SegmentedButtonDefaults.itemShape(index, options.size), icon = {}) {
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConnectionEditor(value: ApiConnection, expanded: Boolean, usedBy: List<String>, onExpand: () -> Unit,
    onChange: (ApiConnection) -> Unit, onRemove: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    SettingsGroup {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand).heightIn(min = 56.dp).padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(value.name.ifBlank { "New connection" }, style = MaterialTheme.typography.titleMedium)
                Text(connectionSummary(value), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            AppIcon(R.drawable.ic_expand, if (expanded) "Collapse" else "Expand", Modifier.rotate(rotation), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value.name, { onChange(value.copy(name = it)) }, label = { Text("Connection name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Choices(AuthenticationMethod.entries, { it == value.authentication }, { it.label }) { method ->
                    if (method != value.authentication) onChange(ApiConnection(value.id, value.name, method))
                }
                if (value.authentication == AuthenticationMethod.ChatGpt) {
                    ChatGptAccountConfiguration(value.chatGptAccountId) { onChange(value.copy(chatGptAccountId = it)) }
                } else {
                    OutlinedTextField(value.baseUrl, { onChange(value.copy(baseUrl = it)) }, label = { Text("Base URL") },
                        placeholder = { Text("https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                    Choices(ApiProtocol.entries, { it == value.protocol }, { it.label }) { onChange(value.withProtocol(it)) }
                    OutlinedTextField(value.apiKey, { onChange(value.copy(apiKey = it)) }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (usedBy.isNotEmpty()) Text("Used by ${usedBy.joinToString()}. Choose another connection there to remove it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                AppIconButton(R.drawable.ic_delete, "Remove connection", onRemove, enabled = usedBy.isEmpty())
            }
        }
    }
}

/** Moves between Google's OpenAI-compatible and native base URLs when switching to or from Gemini. */
private fun ApiConnection.withProtocol(protocol: ApiProtocol): ApiConnection {
    val url = baseUrl.trim().trimEnd('/')
    val openAiDefault = OpenAIModelConfig().baseUrl
    val baseUrl = when {
        protocol == ApiProtocol.Gemini && (url == openAiDefault || url.isEmpty()) -> GeminiBaseUrl
        protocol == ApiProtocol.Gemini && url.endsWith("/openai") && "googleapis.com" in url -> url.removeSuffix("/openai")
        protocol != ApiProtocol.Gemini && url == GeminiBaseUrl -> "$GeminiBaseUrl/openai"
        else -> baseUrl
    }
    return copy(protocol = protocol, baseUrl = baseUrl)
}

private fun connectionSummary(connection: ApiConnection): String = when (connection.authentication) {
    AuthenticationMethod.ChatGpt -> if (connection.chatGptAccountId == null) "ChatGPT · Not signed in" else "ChatGPT"
    AuthenticationMethod.ApiKey -> listOf(runCatching { URI(connection.baseUrl).host }.getOrNull() ?: connection.baseUrl,
        connection.protocol.label, if (connection.apiKey.isBlank()) "No API key" else "API key").joinToString(" · ")
}

@Composable
private fun ConnectionPicker(selected: ApiConnection?, connections: List<ApiConnection>, onSelect: (ApiConnection) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { menu = true }, enabled = connections.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(selected?.name ?: if (connections.isEmpty()) "Add a connection first" else "Select connection", maxLines = 1, overflow = TextOverflow.Ellipsis)
                selected?.let { Text(connectionSummary(it), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            AppIcon(R.drawable.ic_expand, null, size = 18.dp)
        }
        DropdownMenu(menu, { menu = false }) {
            connections.forEach { connection -> DropdownMenuItem(
                text = { Column {
                    Text(connection.name)
                    Text(connectionSummary(connection), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                onClick = { menu = false; onSelect(connection) })
            }
        }
    }
}

@Composable
private fun AiConfigurationEditor(value: AiConfiguration, connections: List<ApiConnection>, onChange: (AiConfiguration) -> Unit) {
    fun api(change: (OpenAIModelConfig) -> OpenAIModelConfig) = onChange(value.copy(api = change(value.api)))
    val connection = connections.find { it.id == value.connectionId }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Connection", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ConnectionPicker(connection, connections) { selected ->
            // Model IDs rarely carry over between ChatGPT accounts and API providers.
            val sameModels = connection != null && connection.authentication == selected.authentication &&
                if (selected.authentication == AuthenticationMethod.ApiKey) connection.baseUrl.trimEnd('/') == selected.baseUrl.trimEnd('/')
                else connection.chatGptAccountId == selected.chatGptAccountId
            onChange(value.copy(connectionId = selected.id, api = if (sameModels) value.api else value.api.copy(model = "")))
        }
        ModelField(connection, value.api.model) { model -> api { it.copy(model = model) } }
        ReasoningEffortEditor(value.api.reasoningEffort) { effort -> api { it.copy(reasoningEffort = effort) } }
        if (connection?.authentication != AuthenticationMethod.ChatGpt) {
            val protocol = connection?.protocol ?: value.api.protocol
            ServiceTierEditor(protocol, value.api.serviceTier) { tier -> api { it.copy(serviceTier = tier) } }
        }
        OutlinedTextField(value.instructions, { onChange(value.copy(instructions = it)) }, label = { Text("Instructions") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    }
    SwitchRow("Web search", value.api.webSearch, { search -> api { it.copy(webSearch = search) } })
    if (connection?.supportsBackgroundResponses == true) SwitchRow("Recover interrupted responses", value.api.backgroundResponses,
        { enabled -> api { it.copy(backgroundResponses = enabled) } })
}

@Composable
internal fun ReasoningEffortEditor(
    effort: String?,
    showCustomInput: Boolean = true,
    onChange: (String?) -> Unit,
) {
    val tiers = ReasoningEffortTiers
    val normalized = effort?.trim()?.lowercase()
    val exactIndex = tiers.indexOf(normalized)
    val sliderIndex = when {
        exactIndex >= 0 -> exactIndex
        normalized == "minimal" -> 0
        else -> 2
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Reasoning effort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    effort ?: "Default",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (effort != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (effort != null) {
                    TextButton(onClick = { onChange(null) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 28.dp)) {
                        Text("Reset", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Slider(
            value = sliderIndex.toFloat(),
            onValueChange = { raw -> onChange(tiers[raw.roundToInt().coerceIn(0, tiers.lastIndex)]) },
            valueRange = 0f..tiers.lastIndex.toFloat(),
            steps = tiers.size - 2,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Reasoning effort" },
        )
        Row(Modifier.fillMaxWidth()) {
            tiers.forEachIndexed { index, tier ->
                val selected = index == exactIndex
                val label = if (tier == "medium") "med" else tier
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).clickable { onChange(tier) }.padding(vertical = 2.dp),
                )
            }
        }
        if (showCustomInput) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = effort ?: "",
                onValueChange = { text -> onChange(text.takeIf(String::isNotBlank)) },
                label = { Text("Custom effort") },
                placeholder = { Text("Default (none / minimal / low / medium / high / xhigh / max)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ServiceTierEditor(
    protocol: ApiProtocol,
    serviceTier: String?,
    showHeader: Boolean = true,
    onChange: (String?) -> Unit,
) {
    val options = if (protocol == ApiProtocol.Gemini) GeminiServiceTiers else OpenAiServiceTiers
    val effective = wireServiceTier(protocol, serviceTier)
    val selectedOption = when {
        effective in options -> effective
        protocol != ApiProtocol.Gemini && effective == "priority" -> "fast"
        else -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showHeader) {
            Text("Service tier", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Choices(
            options = options,
            selected = { it == selectedOption },
            label = { tier ->
                when (tier) {
                    null -> "Default"
                    "flex" -> "Flex"
                    "fast" -> "Fast"
                    "priority" -> "Priority"
                    "ultrafast" -> "Ultra"
                    else -> tier.replaceFirstChar(Char::uppercase)
                }
            },
            onSelect = onChange,
        )
    }
}
