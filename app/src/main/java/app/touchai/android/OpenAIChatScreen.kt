package app.touchai.android

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.widget.Toast
import java.io.IOException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.touchai.core.markdown.StreamingMarkdown
import app.touchai.core.openai.OpenAIImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun OpenAIChatScreen(viewModel: OpenAIChatViewModel, runtime: QuickAccessRuntime, onClose: (() -> Unit)? = null) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val quickAccess by runtime.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val compact = LocalWindowInfo.current.containerSize.height / LocalDensity.current.density < 450f
    val compactKeyboard = compact && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val scope = rememberCoroutineScope()
    var loadingImage by remember { mutableStateOf(false) }
    var attachmentMenuOpen by remember { mutableStateOf(false) }
    val exportHistory = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) viewModel.exportHistory {
            context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("The export file cannot be opened.")
        }
    }
    val importHistory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importHistory {
            context.contentResolver.openInputStream(uri) ?: throw IOException("The history file cannot be opened.")
        }
    }
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
    if (state.historyOpen) {
        ChatHistoryScreen(state, viewModel::openChat, viewModel::deleteChat, viewModel::newChat,
            { viewModel.showHistory(true) }, { exportHistory.launch("touchai-chat-history.json") },
            { importHistory.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            { viewModel.showHistory(false) })
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
    val openSettings = { viewModel.loadSettings(); viewModel.showSettings(true) }
    val send = { followOutput = true; keyboard?.hide(); viewModel.submit() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        if (!compactKeyboard) Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("TouchAI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                val api = state.selectedAi.api
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(api.model.ifBlank { "No model" }, api.reasoningEffort).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (api.webSearch) AppIcon(R.drawable.ic_search, "Web search on", Modifier.padding(start = 4.dp), 14.dp, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            AppIconButton(R.drawable.ic_add, "New chat", viewModel::newChat, enabled = !state.preparingImage && !loadingImage)
            AppIconButton(R.drawable.ic_history, "Chat history", { keyboard?.hide(); viewModel.showHistory(true) }, enabled = state.ready)
            AppIconButton(R.drawable.ic_settings, "Settings", openSettings, enabled = !state.isStreaming && state.ready)
            onClose?.let { AppIconButton(R.drawable.ic_close, "Close", it) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.error == null) CircularProgressIndicator()
                    else FilledTonalButton(onClick = viewModel::loadSettings) { Text("Retry") }
                }
                state.turns.isEmpty() && state.imagePreview == null -> EmptyState(
                    showSetup = !quickAccess.connected || (quickAccess.options.notification && !quickAccess.notificationsAllowed),
                    onSetup = openSettings,
                )
                else -> Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    state.turns.forEachIndexed { index, turn ->
                        UserMessage(turn)
                        AssistantMessage(turn, canRetry = index == state.turns.lastIndex && !state.isStreaming, onRetry = viewModel::retry)
                    }
                    state.imagePreview?.let { bitmap ->
                        Attachment(bitmap, maxHeight = if (compact) 160.dp else if (state.turns.isEmpty()) 440.dp else 280.dp,
                            preparing = state.preparingImage, attached = state.imageAttached,
                            onCrop = { keyboard?.hide(); viewModel.openCrop() }, onToggle = viewModel::toggleImageAttachment)
                    }
                }
            }
        }
        state.error?.let { MessageBanner(it, error = true, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compactKeyboard) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetChip("No preset", state.selectedPreset == null, !state.isStreaming) { viewModel.selectPreset(null) }
                state.settings.presets.forEach { preset ->
                    PresetChip(preset.name, state.selectedPreset == preset.id, !state.isStreaming) { viewModel.selectPreset(preset.id) }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
                Row(Modifier.padding(4.dp), verticalAlignment = Alignment.Bottom) {
                    Box(Modifier.padding(bottom = 4.dp)) {
                        IconButton(onClick = {
                            if (state.capturedScreenshot != null) attachmentMenuOpen = true else picker.launch("image/*")
                        }, enabled = state.ready && !state.isStreaming && !loadingImage) {
                            if (loadingImage) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else AppIcon(R.drawable.ic_image, if (state.originalImage != null) "Replace image" else "Attach image")
                        }
                        DropdownMenu(expanded = attachmentMenuOpen, onDismissRequest = { attachmentMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Attach screenshot") }, onClick = {
                                attachmentMenuOpen = false
                                viewModel.attachScreenshot()
                            })
                            DropdownMenuItem(text = { Text("Choose image") }, onClick = {
                                attachmentMenuOpen = false
                                picker.launch("image/*")
                            })
                        }
                    }
                    TextField(state.prompt, viewModel::setPrompt, placeholder = { Text("Message") }, modifier = Modifier.weight(1f),
                        minLines = 1, maxLines = if (compact) 2 else 6, enabled = state.ready && !state.isStreaming,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!loadingImage) send() }))
                    Box(Modifier.padding(bottom = 4.dp)) {
                        if (state.isStreaming) FilledTonalIconButton(onClick = viewModel::cancel,
                            enabled = state.turns.last().status != TurnStatus.Cancelling) { AppIcon(R.drawable.ic_stop, "Stop") }
                        else FilledIconButton(onClick = send,
                            enabled = state.ready && !loadingImage && (!state.imageAttached ||
                                (!state.preparingImage && (state.originalImage == null || state.image != null)))) {
                            AppIcon(R.drawable.ic_send, "Send", size = 20.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrandMark(size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF7B6CFF), Color(0xFF3424C4)))),
        contentAlignment = Alignment.Center) {
        AppIcon(R.drawable.ic_spark, null, size = size * 0.55f, tint = Color.White)
    }
}

@Composable
private fun EmptyState(showSetup: Boolean, onSetup: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            AppIcon(R.drawable.ic_spark, null, size = 36.dp, tint = MaterialTheme.colorScheme.primary)
        }
        if (showSetup) {
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = onSetup) { Text("Set up quick access") }
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected, onClick, label = { Text(label) }, enabled = enabled, shape = CircleShape,
        border = if (selected) null else FilterChipDefaults.filterChipBorder(enabled, selected, borderColor = MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun UserMessage(turn: ChatTurn) {
    Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                turn.presetName?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                turn.user.images.forEach { SentImage(it) }
                if (turn.user.text.isNotBlank()) SelectionContainer { Text(turn.user.text, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

@Composable
private fun SentImage(image: OpenAIImage, description: String = "Attached image") {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember(image) { mutableStateOf(false) }
    val save = {
        saving = true
        scope.launch(Dispatchers.Main.immediate) {
            try {
                ImageGallery.save(context, image)
                Toast.makeText(context, "Saved to Pictures/TouchAI", Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { Toast.makeText(context, "Could not save picture.", Toast.LENGTH_LONG).show() }
            finally { saving = false }
        }
        Unit
    }
    var imageError by remember(image) { mutableStateOf<String?>(null) }
    val preview by produceState<Bitmap?>(null, image) {
        try { value = ImageProcessor.decode(image, 1024) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { imageError = "Could not decode the image." }
    }
    var expanded by remember(image) { mutableStateOf(false) }
    preview?.let { bitmap ->
        Box {
            Image(remember(bitmap) { bitmap.asImageBitmap() }, description,
                Modifier.sizeIn(minWidth = 128.dp, minHeight = 96.dp, maxWidth = 280.dp, maxHeight = 240.dp).clip(MaterialTheme.shapes.medium)
                    .clickable { expanded = true }, contentScale = ContentScale.Fit)
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                IconButton(onClick = save, enabled = !saving) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else AppIcon(R.drawable.ic_download, "Save picture", size = 20.dp)
                }
            }
        }
    }
    imageError?.let { MessageBanner(it, error = true) }
    if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val fullImage by produceState(preview, image) {
            try { value = ImageProcessor.decode(image, 4096) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { imageError = "Could not decode the image."; expanded = false }
        }
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                fullImage?.let { bitmap -> Image(remember(bitmap) { bitmap.asImageBitmap() }, "$description preview",
                    Modifier.fillMaxSize().padding(16.dp), contentScale = ContentScale.Fit) }
                Row(Modifier.align(Alignment.TopEnd)) {
                    IconButton(onClick = save, enabled = !saving) {
                        if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else AppIcon(R.drawable.ic_download, "Save picture", tint = Color.White)
                    }
                    IconButton(onClick = { expanded = false }) {
                        AppIcon(R.drawable.ic_close, "Close image", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(turn: ChatTurn, canRetry: Boolean, onRetry: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(22.dp)
            Text(turn.ai.api.model, style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.padding(start = 8.dp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        turn.searchStatus?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppIcon(R.drawable.ic_search, null, size = 14.dp, tint = MaterialTheme.colorScheme.primary)
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
        }
        turn.imageGenerationStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        turn.recoveryStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        if (turn.answer.isEmpty() && turn.generatedImages.isEmpty() && turn.isRunning) CircularProgressIndicator(Modifier.padding(vertical = 4.dp).size(18.dp), strokeWidth = 2.dp)
        val markdown = remember(turn.answer, turn.citations) { citationMarkdown(turn.answer, turn.citations) }
        SelectionContainer { StreamingMarkdown(markdown, turn.status == TurnStatus.Streaming, Modifier.fillMaxWidth()) }
        turn.generatedImages.forEach { output -> key(output.id) { SentImage(output.image, "Generated image") } }
        val sources = turn.citations.distinctBy { it.url }
        if (sources.isNotEmpty()) Column {
            sources.forEachIndexed { sourceIndex, citation ->
                Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { uriHandler.openUri(citation.url) }.padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        Text("${sourceIndex + 1}", style = MaterialTheme.typography.labelSmall)
                    }
                    Text(citation.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (turn.status == TurnStatus.Stopped) Text("Stopped", style = MaterialTheme.typography.labelMedium, color = muted)
        turn.error?.let { MessageBanner(it, error = true) }
        if (turn.answer.isNotEmpty() || canRetry) Row(Modifier.offset(x = (-12).dp)) {
            if (turn.answer.isNotEmpty()) IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AI response", turn.answer))
            }) { AppIcon(R.drawable.ic_copy, "Copy", size = 18.dp, tint = muted) }
            if (canRetry) IconButton(onClick = onRetry) { AppIcon(R.drawable.ic_refresh, "Retry", size = 18.dp, tint = muted) }
        }
    }
}

@Composable
private fun Attachment(bitmap: Bitmap, maxHeight: Dp, preparing: Boolean, attached: Boolean, onCrop: () -> Unit, onToggle: () -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Image(remember(bitmap) { bitmap.asImageBitmap() }, "Image preview",
                Modifier.heightIn(min = 96.dp, max = maxHeight).widthIn(min = 96.dp)
                    .clip(shape).background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(BorderStroke(if (attached) 2.dp else 1.dp, if (attached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant), shape)
                    .clickable(onClick = onCrop).semantics { contentDescription = "Crop image" },
                contentScale = ContentScale.Fit)
            Surface(onClick = onToggle, shape = CircleShape,
                color = if (attached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (attached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp).semantics {
                    contentDescription = if (attached) "Remove image" else "Add image"
                    selected = attached
                    stateDescription = if (attached) "Attached" else "Not attached"
                }) {
                Box(contentAlignment = Alignment.Center) { AppIcon(if (attached) R.drawable.ic_close else R.drawable.ic_add, null, size = 20.dp) }
            }
            Surface(shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(28.dp)) {
                Box(contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_crop, null, size = 16.dp) }
            }
        }
        if (preparing) LinearProgressIndicator(Modifier.width(96.dp))
    }
}
