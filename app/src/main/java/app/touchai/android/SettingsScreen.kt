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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.touchai.core.openai.ApiProtocol
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.ChatGptOAuth
import app.touchai.core.openai.OpenAIModelConfig
import java.util.UUID

@Composable
fun SettingsScreen(state: OpenAIChatUiState, runtime: QuickAccessRuntime, onSave: (AppSettings) -> Unit,
    onDraftChange: (AppSettings) -> Unit, onTest: (AiConfiguration, String) -> Unit, onLogs: () -> Unit, onClose: () -> Unit) {
    val draft = state.settingsDraft!!
    var expandedPreset by rememberSaveable { mutableStateOf(state.selectedPreset) }
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
            Column {
                SectionHeader("Default AI")
                SettingsGroup {
                    AiConfigurationEditor(AiConfiguration(draft.api, draft.instructions), { onDraftChange(draft.copy(api = it.api, instructions = it.instructions)) })
                    TestButton(state.testingConnection) { onTest(AiConfiguration(draft.api, draft.instructions), "Default AI") }
                }
            }
            Column {
                SectionHeader("Image size")
                Choices(ImageQuality.entries, { it == draft.imageQuality }, { it.label }) { onDraftChange(draft.copy(imageQuality = it)) }
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
                                    Text(if (preset.customAi == null) "Default AI" else preset.customAi.api.model.ifBlank { "Custom AI" },
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                }
                                SwitchRow("Custom AI", preset.customAi != null, { custom ->
                                    change(preset.copy(customAi = if (custom) AiConfiguration(draft.api.copy(webSearch = true), draft.instructions) else null))
                                })
                                preset.customAi?.let { configuration ->
                                    AiConfigurationEditor(configuration, { change(preset.copy(customAi = it)) })
                                    TestButton(state.testingConnection) { onTest(configuration, preset.name) }
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
        }
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
private fun AiConfigurationEditor(value: AiConfiguration, onChange: (AiConfiguration) -> Unit) {
    fun api(change: (OpenAIModelConfig) -> OpenAIModelConfig) = onChange(value.copy(api = change(value.api)))
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Choices(AuthenticationMethod.entries, { it == value.api.authentication }, { it.label }) { method ->
            if (method != value.api.authentication) api {
                it.copy(authentication = method, apiKey = "", model = "", chatGptAccountId = null,
                    baseUrl = ChatGptOAuth.Resource, protocol = ApiProtocol.Responses, backgroundResponses = false)
            }
        }
        if (value.api.authentication == AuthenticationMethod.ChatGpt) {
            ChatGptConfiguration(value.api) { configuration -> onChange(value.copy(api = configuration)) }
        } else {
            OutlinedTextField(value.api.baseUrl, { url -> api { it.copy(baseUrl = url) } }, label = { Text("Base URL") },
                placeholder = { Text("https://api.example.com/v1") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            Choices(ApiProtocol.entries, { it == value.api.protocol }, { it.label }) { protocol -> api { it.copy(protocol = protocol) } }
            OutlinedTextField(value.api.apiKey, { key -> api { it.copy(apiKey = key) } }, label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value.api.model, { model -> api { it.copy(model = model) } }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        }
        Text("Reasoning effort", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val efforts = listOf(null, "low", "medium", "high")
        Choices(efforts, { it == value.api.reasoningEffort }, { it?.replaceFirstChar(Char::uppercase) ?: "Default" }) { effort ->
            api { it.copy(reasoningEffort = effort) }
        }
        OutlinedTextField(value.api.reasoningEffort ?: "", { effort -> api { it.copy(reasoningEffort = effort.takeIf(String::isNotBlank)) } },
            label = { Text("Custom effort") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value.instructions, { onChange(value.copy(instructions = it)) }, label = { Text("Instructions") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    }
    SwitchRow("Web search", value.api.webSearch, { search -> api { it.copy(webSearch = search) } })
    if (value.api.authentication == AuthenticationMethod.ApiKey && value.api.protocol == ApiProtocol.Responses) SwitchRow("Recover interrupted responses", value.api.backgroundResponses,
        { enabled -> api { it.copy(backgroundResponses = enabled) } })
}
