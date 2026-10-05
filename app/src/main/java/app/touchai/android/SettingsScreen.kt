package app.touchai.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.touchai.core.openai.ApiProtocol
import java.util.UUID

@Composable
fun SettingsScreen(state: OpenAIChatUiState, runtime: QuickAccessRuntime, onSave: (AppSettings) -> Unit,
    onDraftChange: (AppSettings) -> Unit, onTest: (AiConfiguration, String) -> Unit, onLogs: () -> Unit, onClose: () -> Unit) {
    val draft = state.settingsDraft!!
    var expandedPreset by rememberSaveable { mutableStateOf(state.selectedPreset) }
    BackHandler(enabled = !state.savingSettings) { onClose() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onClose, enabled = !state.savingSettings) { Text("Back") }
            TextButton(onClick = { onSave(draft) }, enabled = !state.savingSettings) { Text(if (state.savingSettings) "Saving…" else "Save") }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        state.connectionResult?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (state.testingConnection) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Text("Saving changes starts a new conversation. API keys are encrypted on this device.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onLogs) { Text("Request logs") }
            QuickAccessSettings(draft.quickAccess, { onDraftChange(draft.copy(quickAccess = it)) }, runtime)
            Text("Default AI configuration", style = MaterialTheme.typography.titleLarge)
            AiConfigurationEditor(AiConfiguration(draft.api, draft.instructions), { onDraftChange(draft.copy(api = it.api, instructions = it.instructions)) })
            OutlinedButton(onClick = { onTest(AiConfiguration(draft.api, draft.instructions), "Default AI") }, enabled = !state.testingConnection) {
                Text(if (state.testingConnection) "Testing…" else "Test connection")
            }
            Text("Image upload size", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageQuality.entries.forEach { quality -> FilterChip(draft.imageQuality == quality, { onDraftChange(draft.copy(imageQuality = quality)) }, label = { Text(quality.label) }) }
            }
            Text("Cropping happens before resizing. Images stay on the device until Send.", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Preset prompts", style = MaterialTheme.typography.titleLarge)
            Text("Each preset can inherit the default AI or use its own configuration.", style = MaterialTheme.typography.bodySmall)
            draft.presets.forEachIndexed { index, preset ->
                key(preset.id) {
                    fun change(value: PromptPreset) { onDraftChange(draft.copy(presets = draft.presets.map { if (it.id == preset.id) value else it })) }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { expandedPreset = if (expandedPreset == preset.id) null else preset.id }) {
                                Text("${preset.name.ifBlank { "New preset" }} · ${if (preset.customAi == null) "Default AI" else preset.customAi.api.model.ifBlank { "Custom AI" }}")
                            }
                            if (expandedPreset == preset.id) {
                                OutlinedTextField(preset.name, { change(preset.copy(name = it)) }, label = { Text("Preset name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(preset.prompt, { change(preset.copy(prompt = it)) }, label = { Text("Preset prompt") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Custom AI configuration")
                                    Switch(preset.customAi != null, { custom -> change(preset.copy(customAi = if (custom) AiConfiguration(draft.api.copy(webSearch = true), draft.instructions) else null)) })
                                }
                                preset.customAi?.let { configuration ->
                                    AiConfigurationEditor(configuration, { change(preset.copy(customAi = it)) })
                                    OutlinedButton(onClick = { onTest(configuration, preset.name) }, enabled = !state.testingConnection) { Text("Test preset connection") }
                                }
                                Row {
                                    TextButton(onClick = { onDraftChange(draft.copy(presets = draft.presets.toMutableList().apply { add(index - 1, removeAt(index)) })) }, enabled = index > 0) { Text("Move up") }
                                    TextButton(onClick = {
                                        onDraftChange(draft.copy(presets = draft.presets.filterNot { it.id == preset.id },
                                            lastPresetId = draft.lastPresetId?.takeUnless { it == preset.id }))
                                    }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = {
                val id = UUID.randomUUID().toString()
                onDraftChange(draft.copy(presets = draft.presets + PromptPreset(id, "", "")))
                expandedPreset = id
            }) { Text("Add preset") }
            Text("The last selected preset is remembered automatically and fills the message box for each new capture or conversation.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AiConfigurationEditor(value: AiConfiguration, onChange: (AiConfiguration) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value.api.baseUrl, { onChange(value.copy(api = value.api.copy(baseUrl = it))) }, label = { Text("API base URL, including version path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApiProtocol.entries.forEach { protocol -> FilterChip(value.api.protocol == protocol, { onChange(value.copy(api = value.api.copy(protocol = protocol))) }, label = { Text(protocol.label) }) }
        }
        OutlinedTextField(value.api.apiKey, { onChange(value.copy(api = value.api.copy(apiKey = it))) }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.api.model, { onChange(value.copy(api = value.api.copy(model = it))) }, label = { Text("Model ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Reasoning effort", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null, "low", "medium", "high").forEach { effort ->
                FilterChip(value.api.reasoningEffort == effort, { onChange(value.copy(api = value.api.copy(reasoningEffort = effort))) }, label = { Text(effort ?: "Provider default") })
            }
        }
        OutlinedTextField(value.api.reasoningEffort ?: "", { onChange(value.copy(api = value.api.copy(reasoningEffort = it.takeIf(String::isNotBlank)))) }, label = { Text("Custom effort value (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Web search", style = MaterialTheme.typography.titleSmall)
                Text("Requires support from your model and provider.", style = MaterialTheme.typography.bodySmall)
            }
            Switch(value.api.webSearch, { onChange(value.copy(api = value.api.copy(webSearch = it))) })
        }
        if (value.api.protocol == ApiProtocol.ChatCompletions) Text("Off omits the search option. Dedicated search models may still search.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value.instructions, { onChange(value.copy(instructions = it)) }, label = { Text("General instructions (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    }
}
