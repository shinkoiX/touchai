package app.touchai.android

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.touchai.core.markdown.StreamingMarkdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OpenAIChatScreen(viewModel: OpenAIChatViewModel, runtime: QuickAccessRuntime, onClose: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val quickAccess by runtime.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val keyboard = LocalSoftwareKeyboardController.current
    val compact = LocalWindowInfo.current.containerSize.height / LocalDensity.current.density < 450f
    val compactKeyboard = compact && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val scope = rememberCoroutineScope()
    var loadingImage by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            loadingImage = true
            try { viewModel.attachImage(ImageProcessor.load(context, uri)) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { viewModel.reportError("Could not load image: ${error.message}") }
            finally { loadingImage = false }
        }
    }

    if (state.logsOpen) {
        RequestLogScreen((context.applicationContext as TouchAiApplication).requestLogs) { viewModel.showLogs(false) }
        return
    }
    if (state.settingsOpen) {
        SettingsScreen(state, runtime, viewModel::saveSettings, viewModel::editSettings, viewModel::testConnection, { viewModel.showLogs(true) }) { viewModel.showSettings(false) }
        return
    }
    if (state.cropping) {
        ImageCropScreen(state.originalImage!!, state.imageCrop, viewModel::applyCrop, viewModel::closeCrop)
        return
    }
    val scrollState = rememberScrollState()
    var followOutput by remember { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.isScrollInProgress }.collect { (position, scrolling) ->
            if (scrolling) followOutput = position >= scrollState.maxValue - 48
        }
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collect { maximum ->
            if (followOutput && !scrollState.isScrollInProgress) scrollState.scrollTo(maximum)
        }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        if (!compactKeyboard) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TouchAI", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = viewModel::newChat, enabled = !state.isStreaming && !loadingImage) { Text("New") }
            TextButton(onClick = { viewModel.loadSettings(); viewModel.showSettings(true) }, enabled = !state.isStreaming && state.ready) { Text("Settings") }
            onClose?.let { TextButton(onClick = it) { Text("Close") } }
        }
        val selectedAi = state.selectedAi
        Text(
            "${selectedAi.api.model.ifBlank { "Configure an AI model in Settings" }} · ${selectedAi.api.reasoningEffort ?: "Default effort"} · Search ${if (selectedAi.api.webSearch) "on" else "off"}",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.ready) {
                Text("Loading settings…")
                if (state.error != null) TextButton(onClick = viewModel::loadSettings) { Text("Retry loading settings") }
            } else if (state.turns.isEmpty()) {
                if (!quickAccess.connected || (quickAccess.options.notification && !quickAccess.notificationsAllowed)) {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Capture from any screen", style = MaterialTheme.typography.titleMedium)
                            Text("Set up the floating button and quick-access notification.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { viewModel.loadSettings(); viewModel.showSettings(true) }) { Text("Set up quick access") }
                        }
                    }
                }
                if (state.imagePreview == null) Text("Choose a preset, edit the message, and Send.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.imagePreview?.let { bitmap ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().clickable { keyboard?.hide(); viewModel.openCrop() }
                            .semantics { contentDescription = "Crop image" }.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(remember(bitmap) { bitmap.asImageBitmap() }, "Image to send",
                                Modifier.fillMaxWidth().height(if (compact) 140.dp else 220.dp), contentScale = ContentScale.Fit)
                            Text("Tap image to crop · sent only with Send", style = MaterialTheme.typography.bodySmall)
                        }
                        FilledIconButton(onClick = viewModel::removeImage,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).semantics { contentDescription = "Remove image" }) {
                            Text("×", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    if (state.preparingImage) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.turns.forEachIndexed { index, turn ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(turn.presetName?.let { "You · $it" } ?: "You", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(turn.user.text.ifBlank { "Image request" }) }
                        if (turn.user.images.isNotEmpty()) Text("Image attached", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column {
                    Text("Assistant · ${turn.ai.api.model}", style = MaterialTheme.typography.labelMedium)
                    turn.searchStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                    if (turn.answer.isEmpty() && turn.status == TurnStatus.Streaming) Text("Waiting for response…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val markdown = remember(turn.answer, turn.citations) { citationMarkdown(turn.answer, turn.citations) }
                    SelectionContainer { StreamingMarkdown(markdown, turn.status == TurnStatus.Streaming, Modifier.fillMaxWidth()) }
                    turn.citations.distinctBy { it.url }.forEachIndexed { sourceIndex, citation ->
                        TextButton(onClick = { uriHandler.openUri(citation.url) }, contentPadding = PaddingValues(vertical = 2.dp)) { Text("[${sourceIndex + 1}] ${citation.title}") }
                    }
                    if (turn.status == TurnStatus.Stopped) Text("Stopped", style = MaterialTheme.typography.labelMedium)
                    turn.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row {
                        if (turn.answer.isNotEmpty()) TextButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AI response", turn.answer))
                        }) { Text("Copy") }
                        if (index == state.turns.lastIndex && !state.isStreaming) TextButton(onClick = viewModel::retry) { Text("Retry") }
                    }
                }
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (!compactKeyboard) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.selectedPreset == null, { viewModel.selectPreset(null) }, label = { Text("No preset") }, enabled = !state.isStreaming)
                state.settings.presets.forEach { preset -> FilterChip(state.selectedPreset == preset.id, { viewModel.selectPreset(preset.id) }, label = { Text(preset.name) }, enabled = !state.isStreaming) }
            }
            OutlinedTextField(state.prompt, viewModel::setPrompt, label = { Text("Message") }, modifier = Modifier.fillMaxWidth(),
                minLines = if (compact) 1 else 2, maxLines = if (compact) 1 else 5, enabled = state.ready && !state.isStreaming,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!loadingImage) { followOutput = true; keyboard?.hide(); viewModel.submit() } }))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { picker.launch("image/*") }, enabled = state.ready && !state.isStreaming && !loadingImage) {
                    Text(if (loadingImage) "Loading…" else if (state.originalImage != null) "Replace image" else "Attach image")
                }
                Spacer(Modifier.weight(1f))
                if (state.isStreaming) OutlinedButton(onClick = viewModel::cancel) { Text("Stop") }
                else Button(onClick = { followOutput = true; keyboard?.hide(); viewModel.submit() }, enabled = state.ready && !loadingImage && !state.preparingImage && (state.originalImage == null || state.image != null)) { Text("Send") }
            }
        }
    }
}
