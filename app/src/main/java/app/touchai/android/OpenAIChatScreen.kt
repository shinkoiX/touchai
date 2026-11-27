package app.touchai.android

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.widget.Toast
import java.io.IOException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.touchai.core.markdown.StreamingMarkdown
import app.touchai.core.markdown.applyHighlightSpans
import app.touchai.core.markdown.findHighlightRanges
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.OpenAIImage
import app.touchai.core.openai.wireServiceTier
import app.touchai.core.openui.OpenUiDetector
import app.touchai.core.openui.OpenUiMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun OpenAIChatScreen(viewModel: OpenAIChatViewModel, runtime: QuickAccessRuntime,
    captureScreenshot: suspend () -> Bitmap, onClose: (() -> Unit)? = null) {
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
    LaunchedEffect(state.effortNotice) {
        state.effortNotice?.let { notice ->
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            viewModel.consumeEffortNotice()
        }
    }

    if (state.logsOpen) {
        RequestLogScreen((context.applicationContext as TouchAiApplication).requestLogs) { viewModel.showLogs(false) }
        return
    }
    if (state.historyOpen) {
        ChatHistoryScreen(state, viewModel::setHistoryQuery, viewModel::openChat, viewModel::deleteChat, viewModel::newChat,
            { viewModel.showHistory(true) }, { exportHistory.launch("touchai-chat-history.json") },
            { importHistory.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
            { viewModel.showHistory(false) })
        return
    }
    if (state.settingsOpen) {
        SettingsScreen(state, runtime, viewModel::saveSettings, viewModel::editSettings, viewModel::testConnection, { viewModel.showLogs(true) }) { viewModel.showSettings(false) }
        return
    }
    if (state.croppingImageId != null) {
        val image = state.attachments.first { it.id == state.croppingImageId }
        ImageCropScreen(image.original!!, image.crop, { viewModel.applyCrop(image.id, it) }, viewModel::closeCrop)
        return
    }
    val scrollState = rememberScrollState()
    var followOutput by remember { mutableStateOf(true) }
    val searchTerms = remember(state.searchHighlightQuery) { tokenizeSearchQuery(state.searchHighlightQuery) }
    val searchMatches = remember(state.turns, state.searchHighlightQuery) {
        if (state.searchHighlightQuery.isBlank()) emptyList()
        else findChatSearchMatches(state.turns, state.searchHighlightQuery)
    }
    val clampedMatchIndex = if (searchMatches.isEmpty()) 0 else state.searchMatchIndex.coerceIn(0, searchMatches.lastIndex)
    val activeSearchMatch = searchMatches.getOrNull(clampedMatchIndex)
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    var scrollContentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val activeMatchKey = activeSearchMatch?.let { "${state.chatId}:${state.searchHighlightQuery}:$clampedMatchIndex" }
    var activeMatchAnchor by remember(activeMatchKey) { mutableStateOf<Pair<LayoutCoordinates, Float>?>(null) }
    var scrolledKey by remember { mutableStateOf<String?>(null) }
    var lastScrolledTarget by remember(activeMatchKey) { mutableIntStateOf(-1) }

    LaunchedEffect(state.chatId, state.searchHighlightQuery) {
        if (state.searchHighlightQuery.isNotBlank()) {
            followOutput = false
        }
    }
    LaunchedEffect(activeMatchKey, activeMatchAnchor, scrollContentCoords, viewportHeightPx, scrollState.maxValue) {
        val key = activeMatchKey ?: return@LaunchedEffect
        val (anchorCoords, yOffsetPx) = activeMatchAnchor ?: return@LaunchedEffect
        val contentCoords = scrollContentCoords ?: return@LaunchedEffect
        val maxScroll = scrollState.maxValue
        if (maxScroll == Int.MAX_VALUE || viewportHeightPx <= 0f || !anchorCoords.isAttached || !contentCoords.isAttached) return@LaunchedEffect
        val yInContent = contentCoords.localPositionOf(anchorCoords, Offset(0f, yOffsetPx)).y
        val desiredOffset = viewportHeightPx * 0.30f
        val clampedTarget = (yInContent - desiredOffset).roundToInt().coerceIn(0, maxScroll)
        if (scrolledKey != key || abs(clampedTarget - lastScrolledTarget) > 24) {
            followOutput = false
            scrollState.animateScrollTo(clampedTarget)
            scrolledKey = key
            lastScrolledTarget = clampedTarget
        }
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.isScrollInProgress }.collect { (position, scrolling) ->
            if (scrolling) followOutput = position >= scrollState.maxValue - 48
        }
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collect { maximum ->
            if (followOutput && state.searchHighlightQuery.isBlank() && !scrollState.isScrollInProgress) {
                scrollState.scrollTo(maximum)
            }
        }
    }
    val openSettings = { viewModel.loadSettings(); viewModel.showSettings(true) }
    val send = { followOutput = true; keyboard?.hide(); viewModel.submit() }
    val haptics = LocalHapticFeedback.current
    var switcherOpen by remember { mutableStateOf(false) }
    var switcherInitialSlot by remember { mutableIntStateOf(0) }
    var retrySwitchTarget by remember { mutableStateOf<Pair<Int, AiConfiguration>?>(null) }
    val density = LocalDensity.current
    var topOverlayHeightPx by remember { mutableIntStateOf(0) }
    var bottomOverlayHeightPx by remember { mutableIntStateOf(0) }
    val topOverlayDp = with(density) { topOverlayHeightPx.toDp() }
    val bottomOverlayDp = with(density) { bottomOverlayHeightPx.toDp() }
    val showScrollToBottom by remember {
        derivedStateOf {
            state.turns.isNotEmpty() &&
                scrollState.maxValue > 120 &&
                scrollState.value < scrollState.maxValue - 140
        }
    }
    val surfaceBg = MaterialTheme.colorScheme.background
    val floatingPillColor = if (MaterialTheme.colorScheme.surface.red < 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    Box(Modifier.fillMaxSize().background(surfaceBg).imePadding()) {
        // Full-bleed edge-to-edge chat canvas scrolling smoothly under floating translucent top/bottom chrome.
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    viewportHeightPx = coords.size.height.toFloat()
                },
        ) {
            when {
                !state.ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (state.error == null) CircularProgressIndicator(strokeWidth = 2.dp)
                    else FilledTonalButton(onClick = viewModel::loadSettings) { Text("Retry") }
                }
                state.turns.isEmpty() && state.attachments.isEmpty() -> EmptyState(
                    showSetup = !quickAccess.connected || (quickAccess.options.notification && !quickAccess.notificationsAllowed),
                    onSetup = openSettings,
                    modifier = Modifier.padding(top = topOverlayDp, bottom = bottomOverlayDp),
                )
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .onGloballyPositioned { scrollContentCoords = it }
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = topOverlayDp + 12.dp,
                            bottom = bottomOverlayDp + 20.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    val reportActivePosition: (LayoutCoordinates, Float) -> Unit = { coords, yOffsetPx ->
                        activeMatchAnchor = coords to yOffsetPx
                    }
                    val secondaryPane = state.secondaryPane
                    state.turns.forEachIndexed { index, turn ->
                        val branch = state.branchInfo(index)
                        val turnSessionKey = "${state.chatId}:${state.branchPathKey(index)}"
                        val turnActiveMatch = activeSearchMatch?.takeIf { it.turnIndex == index }
                        key(turnSessionKey) {
                            UserMessage(
                                turn = turn,
                                highlightTerms = searchTerms,
                                activeMatch = turnActiveMatch?.takeIf { it.section == SearchMatchSection.User },
                                onActiveMatchPositioned = reportActivePosition,
                            )
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (secondaryPane != null && index == state.turns.lastIndex) {
                                    PairedConversationTabsBar(
                                        primaryPane = state.primaryPane,
                                        secondaryPane = secondaryPane,
                                        primaryFallbackAi = state.selectedAi,
                                        secondaryFallbackAi = state.resolvedSecondaryAi,
                                        activeSlot = state.activePairSlot,
                                        showPerTabStop = state.runningCount > 1,
                                        onSelectSlot = viewModel::selectPairSlot,
                                        onStopSlot = viewModel::cancelSlot,
                                    )
                                }
                                AssistantMessage(
                                    sessionKey = turnSessionKey,
                                    turn = turn,
                                    canRetry = !state.activePaneIsStreaming,
                                    onRetry = { viewModel.retry(turnIndex = index) },
                                    onRetryWithSwitch = { keyboard?.hide(); retrySwitchTarget = index to turn.ai },
                                    branchIndex = branch?.index ?: 0,
                                    branchCount = branch?.count ?: 1,
                                    canSwitchBranch = !state.activePaneIsStreaming,
                                    onPreviousBranch = { viewModel.switchBranch(index, -1) },
                                    onNextBranch = { viewModel.switchBranch(index, 1) },
                                    onOpenUiToAssistant = { msg, ctx ->
                                        followOutput = true
                                        keyboard?.hide()
                                        viewModel.submitFromOpenUi(msg, ctx)
                                    },
                                    highlightTerms = searchTerms,
                                    activeMatch = turnActiveMatch?.takeIf { it.section != SearchMatchSection.User },
                                    onActiveMatchPositioned = reportActivePosition,
                                )
                            }
                        }
                    }
                    val images = state.attachments.filter { it.preview != null }
                    if (images.isNotEmpty()) {
                        val sendingSequence = state.sendingImages.mapIndexed { index, image -> image.id to index + 1 }.toMap()
                        val imageScroll = rememberScrollState()
                        LaunchedEffect(images.lastOrNull()?.id, imageScroll.maxValue) { imageScroll.scrollTo(imageScroll.maxValue) }
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val imageWidth = if (images.size > 1) 140.dp else maxWidth
                            Row(Modifier.fillMaxWidth().horizontalScroll(imageScroll), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                images.forEach { image -> key(image.id) {
                                    Attachment(image.preview!!, maxHeight = if (compact) 160.dp else if (images.size > 1) 240.dp else if (state.turns.isEmpty()) 440.dp else 280.dp,
                                        preparing = image.preparing, attached = image.attached, sequence = sendingSequence[image.id],
                                        onCrop = { keyboard?.hide(); viewModel.openCrop(image.id) },
                                        onToggle = { viewModel.toggleImageAttachment(image.id) }, onDelete = { viewModel.removeImage(image.id) },
                                        modifier = Modifier.width(imageWidth))
                                } }
                            }
                        }
                    }
                }
            }
        }

        // Floating top chrome with soft vertical fade-out gradient (ChatGPT mobile style).
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to surfaceBg,
                            0.72f to surfaceBg.copy(alpha = 0.90f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                )
                .statusBarsPadding()
                .onGloballyPositioned { topOverlayHeightPx = it.size.height }
                .padding(bottom = 10.dp),
        ) {
            if (!compactKeyboard) {
                val primaryApi = state.selectedAi.api
                val secondaryApi = state.resolvedSecondaryAi.api
                val activeApi = state.activeSlotAi.api
                val activeTier = if (activeApi.authentication == AuthenticationMethod.ChatGpt) null
                    else wireServiceTier(activeApi.requestProtocol, activeApi.serviceTier)
                val canOpenSwitcher = state.ready && if (state.isDualMode) !state.isStreaming else !state.activePaneIsStreaming
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Left floating circle: Chat history
                    Surface(
                        onClick = { keyboard?.hide(); viewModel.showHistory(true) },
                        enabled = state.ready,
                        shape = CircleShape,
                        color = floatingPillColor,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            AppIcon(R.drawable.ic_history, "Chat history", size = 20.dp, tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    // Center floating model switcher pill
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            onClick = {
                                keyboard?.hide()
                                switcherInitialSlot = state.activePairSlot
                                switcherOpen = true
                            },
                            enabled = canOpenSwitcher,
                            shape = CircleShape,
                            color = floatingPillColor,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                if (state.isDualMode) {
                                    Text(
                                        "2×",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                } else if (state.isPaired) {
                                    Text(
                                        if (state.activePairSlot == 1) "B" else "A",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val modelTitle = if (state.isDualMode) {
                                    "${primaryApi.model.ifBlank { "A" }} ⇄ ${secondaryApi.model.ifBlank { "B" }}"
                                } else {
                                    listOfNotNull(
                                        activeApi.model.ifBlank { "TouchAI" },
                                        activeApi.reasoningEffort,
                                        activeTier,
                                    ).joinToString(" · ")
                                }
                                Text(
                                    text = modelTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = if (state.isDualMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (!state.isDualMode && activeApi.webSearch) {
                                    AppIcon(R.drawable.ic_search, "Web search on", size = 13.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (canOpenSwitcher) {
                                    AppIcon(R.drawable.ic_expand, "Switch model", size = 15.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // Right floating action capsule: New chat + Settings (+ optional Close)
                    Surface(
                        shape = CircleShape,
                        color = floatingPillColor,
                    ) {
                        Row(
                            modifier = Modifier.height(40.dp).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = viewModel::newChat,
                                enabled = !state.preparingImages && !loadingImage && !state.capturingScreenshot,
                                modifier = Modifier.size(36.dp),
                            ) {
                                AppIcon(R.drawable.ic_add, "New chat", size = 20.dp, tint = MaterialTheme.colorScheme.onSurface)
                            }
                            IconButton(
                                onClick = openSettings,
                                enabled = !state.isStreaming && state.ready,
                                modifier = Modifier.size(36.dp),
                            ) {
                                AppIcon(R.drawable.ic_settings, "Settings", size = 19.dp, tint = MaterialTheme.colorScheme.onSurface)
                            }
                            onClose?.let { close ->
                                IconButton(onClick = close, modifier = Modifier.size(36.dp)) {
                                    AppIcon(R.drawable.ic_close, "Close", size = 19.dp, tint = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
            if (state.searchHighlightQuery.isNotBlank()) {
                ChatSearchHighlightBar(
                    query = state.searchHighlightQuery,
                    matchIndex = clampedMatchIndex,
                    matchCount = searchMatches.size,
                    locationLabel = activeSearchMatch?.locationLabel(),
                    onPrevious = { viewModel.stepSearchMatch(-1, searchMatches.size) },
                    onNext = { viewModel.stepSearchMatch(1, searchMatches.size) },
                    onClear = viewModel::clearSearchHighlight,
                )
            }
        }

        // Floating scroll-to-bottom button centered just above the composer.
        AnimatedVisibility(
            visible = showScrollToBottom,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.85f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomOverlayDp + 8.dp),
        ) {
            Surface(
                onClick = {
                    followOutput = true
                    scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_arrow_down, "Scroll to bottom", size = 19.dp, tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // Floating bottom composer with soft bottom fade gradient.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Transparent,
                            0.28f to surfaceBg.copy(alpha = 0.92f),
                            1.0f to surfaceBg,
                        ),
                    ),
                )
                .navigationBarsPadding()
                .onGloballyPositioned { bottomOverlayHeightPx = it.size.height }
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.error?.let { MessageBanner(it, error = true) }
            if (!compactKeyboard && state.settings.presets.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PresetChip("No preset", state.selectedPreset == null, !state.isStreaming, isDual = false) { viewModel.selectPreset(null) }
                    state.settings.presets.forEach { preset ->
                        PresetChip(preset.name, state.selectedPreset == preset.id, !state.isStreaming, isDual = preset.secondaryAi != null) { viewModel.selectPreset(preset.id) }
                    }
                }
            }
            Surface(
                color = floatingPillColor,
                shape = RoundedCornerShape(26.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(6.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box {
                        IconButton(
                            onClick = { attachmentMenuOpen = true },
                            enabled = state.ready && !state.isStreaming && !loadingImage && !state.capturingScreenshot,
                            modifier = Modifier.size(40.dp),
                        ) {
                            if (loadingImage || state.capturingScreenshot) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else AppIcon(R.drawable.ic_add, "Attach image", size = 21.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (attachmentMenuOpen) DropdownMenu(
                            expanded = true,
                            onDismissRequest = { attachmentMenuOpen = false },
                            containerColor = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            DropdownMenuItem(text = { Text("Attach screenshot") }, onClick = {
                                attachmentMenuOpen = false
                                viewModel.attachScreenshot(captureScreenshot)
                            })
                            DropdownMenuItem(text = { Text("Choose image") }, onClick = {
                                attachmentMenuOpen = false
                                picker.launch("image/*")
                            })
                        }
                    }
                    val inputBlocked = if (state.isDualMode) state.isStreaming else state.activePaneIsStreaming
                    val inputTextStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp,
                    )
                    BasicTextField(
                        value = state.prompt,
                        onValueChange = viewModel::setPrompt,
                        enabled = state.ready && !inputBlocked,
                        textStyle = inputTextStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = if (compact) 2 else 6,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (!loadingImage) send() }),
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 40.dp),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 40.dp)
                                    .padding(horizontal = 4.dp, vertical = 9.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (state.prompt.isEmpty()) {
                                    Text(
                                        text = "Ask anything",
                                        style = inputTextStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )
                    val activeStatuses = listOfNotNull(
                        state.primaryPane.turns.lastOrNull()?.takeIf { it.isRunning }?.status,
                        state.secondaryPane?.turns?.lastOrNull()?.takeIf { it.isRunning }?.status,
                    )
                    val allCancelling = activeStatuses.isNotEmpty() && activeStatuses.all { it == TurnStatus.Cancelling }
                    SendActionButton(
                        isStreaming = inputBlocked,
                        runningCount = if (state.isDualMode) state.runningCount else 1,
                        stopEnabled = !allCancelling,
                        isDualMode = state.isDualMode,
                        sendEnabled = state.ready && !loadingImage && !state.capturingScreenshot && state.canSendImages,
                        onSend = send,
                        onStop = {
                            if (state.isDualMode) viewModel.cancel()
                            else viewModel.cancelSlot(state.activePairSlot)
                        },
                        onLongPressSend = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val openSlotB = viewModel.toggleDualModeByLongPress()
                            if (openSlotB) {
                                keyboard?.hide()
                                switcherInitialSlot = 1
                                switcherOpen = true
                            } else {
                                val updated = viewModel.uiState.value
                                val notice = if (updated.isDualMode) {
                                    val a = updated.selectedAi.api.model.ifBlank { "A" }
                                    val b = updated.resolvedSecondaryAi.api.model.ifBlank { "B" }
                                    "Dual send ON · $a ⇄ $b"
                                } else {
                                    "Dual send OFF"
                                }
                                Toast.makeText(context, notice, Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
        }
    }
    if (switcherOpen) {
        QuickAiSwitcherSheet(
            title = "Switch model",
            confirmLabel = "Done",
            connections = state.settings.connections,
            initial = state.selectedAi,
            canReset = state.hasTemporaryAi,
            liveUpdate = true,
            onApply = viewModel::setTemporaryAi,
            onReset = viewModel::clearTemporaryAi,
            allowDual = true,
            dualEnabled = state.isDualMode,
            initialSecondary = state.resolvedSecondaryAi,
            initialSlot = switcherInitialSlot,
            editingSecondaryInSingleMode = state.isPaired && !state.isDualMode && state.activePairSlot == 1,
            onDualModeChange = viewModel::setDualMode,
            onApplySecondary = { viewModel.setTemporarySecondaryAi(it, enableDual = false) },
            onDismiss = { switcherOpen = false },
        )
    }
    retrySwitchTarget?.let { (turnIndex, targetAi) ->
        QuickAiSwitcherSheet(
            title = "Retry with…",
            confirmLabel = "Retry",
            connections = state.settings.connections,
            initial = targetAi,
            canReset = false,
            liveUpdate = false,
            onApply = { updated -> viewModel.retry(updated, turnIndex = turnIndex) },
            onDismiss = { retrySwitchTarget = null },
        )
    }
}

@Composable
private fun BrandMark(size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(R.drawable.ic_spark, null, size = size * 0.54f, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun EmptyState(showSetup: Boolean, onSetup: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandMark(size = 48.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "What can I help with?",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (showSetup) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(
                onClick = onSetup,
                shape = CircleShape,
            ) { Text("Set up quick access") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendActionButton(
    isStreaming: Boolean,
    runningCount: Int,
    stopEnabled: Boolean,
    isDualMode: Boolean,
    sendEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onLongPressSend: () -> Unit,
) {
    if (isStreaming) {
        if (runningCount > 1) {
            Surface(
                onClick = onStop,
                enabled = stopEnabled,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { contentDescription = "Stop" },
            ) {
                Row(
                    modifier = Modifier.height(40.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    AppIcon(R.drawable.ic_stop, null, size = 18.dp)
                    Text("$runningCount", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            FilledTonalIconButton(onClick = onStop, enabled = stopEnabled) {
                AppIcon(R.drawable.ic_stop, "Stop")
            }
        }
        return
    }

    val containerColor by animateColorAsState(
        targetValue = when {
            !sendEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            isDualMode -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        label = "sendButtonContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !sendEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            isDualMode -> MaterialTheme.colorScheme.onTertiary
            else -> MaterialTheme.colorScheme.onPrimary
        },
        label = "sendButtonContent",
    )
    Surface(
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .animateContentSize()
            .clip(CircleShape)
            .combinedClickable(
                enabled = sendEnabled,
                role = Role.Button,
                onClickLabel = if (isDualMode) "Send to 2 configs" else "Send",
                onLongClickLabel = if (isDualMode) "Turn off dual send" else "Turn on dual send",
                onClick = onSend,
                onLongClick = onLongPressSend,
            )
            .semantics { contentDescription = if (isDualMode) "Send dual" else "Send" },
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .widthIn(min = 40.dp)
                .padding(horizontal = if (isDualMode) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (isDualMode) {
                Text(
                    "2×",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.width(5.dp))
            }
            AppIcon(R.drawable.ic_send, null, size = if (isDualMode) 18.dp else 20.dp)
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, enabled: Boolean, isDual: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        selected -> scheme.primary
        else -> scheme.surfaceContainerLow
    }
    val fg = when {
        selected -> scheme.onPrimary
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> scheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = bg,
        contentColor = fg,
        modifier = Modifier.semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (isDual) {
                Text(
                    "2×",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) scheme.onPrimary.copy(alpha = 0.85f) else scheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun ChatSearchHighlightBar(
    query: String,
    matchIndex: Int,
    matchCount: Int,
    locationLabel: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIcon(R.drawable.ic_search, null, size = 16.dp, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    text = query,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val statusText = if (matchCount > 0) {
                    listOfNotNull("${matchIndex + 1}/$matchCount", locationLabel).joinToString(" · ")
                } else {
                    "No matches in this conversation"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (matchCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious, enabled = matchCount > 1, modifier = Modifier.size(34.dp)) {
                AppIcon(R.drawable.ic_arrow_up, "Previous match", size = 18.dp)
            }
            IconButton(onClick = onNext, enabled = matchCount > 1, modifier = Modifier.size(34.dp)) {
                AppIcon(R.drawable.ic_arrow_down, "Next match", size = 18.dp)
            }
            IconButton(onClick = onClear, modifier = Modifier.size(34.dp)) {
                AppIcon(R.drawable.ic_close, "Clear search highlight", size = 18.dp)
            }
        }
    }
}

@Composable
private fun UserMessage(
    turn: ChatTurn,
    highlightTerms: List<String> = emptyList(),
    activeMatch: ChatSearchMatch? = null,
    onActiveMatchPositioned: ((coords: LayoutCoordinates, yOffsetPx: Float) -> Unit)? = null,
) {
    var textLayout by remember(turn.user.text) { mutableStateOf<TextLayoutResult?>(null) }
    var textCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    LaunchedEffect(activeMatch, textLayout, textCoords) {
        val match = activeMatch ?: return@LaunchedEffect
        val coords = textCoords ?: return@LaunchedEffect
        if (!coords.isAttached) return@LaunchedEffect
        val layout = textLayout
        val yOffset = if (layout != null && turn.user.text.isNotEmpty()) {
            val offset = match.start.coerceIn(0, turn.user.text.lastIndex)
            layout.getLineTop(layout.getLineForOffset(offset))
        } else {
            0f
        }
        onActiveMatchPositioned?.invoke(coords, yOffset)
    }
    Box(Modifier.fillMaxWidth().padding(start = 48.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            border = if (activeMatch != null) BorderStroke(1.5.dp, Color(0xFFFFB300)) else null) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                turn.presetName?.let { Text(it, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) }
                turn.user.images.forEach { SentImage(it) }
                if (turn.user.text.isNotBlank()) {
                    val activeOrdinal = remember(turn.user.text, highlightTerms, activeMatch) {
                        activeMatch?.let { match ->
                            findHighlightRanges(turn.user.text.substring(0, match.start.coerceIn(0, turn.user.text.length)), highlightTerms).size
                        }
                    }
                    val displayText = remember(turn.user.text, highlightTerms, activeOrdinal) {
                        if (highlightTerms.isEmpty()) AnnotatedString(turn.user.text)
                        else applyHighlightSpans(AnnotatedString(turn.user.text), highlightTerms, activeOrdinal)
                    }
                    SelectionContainer {
                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.bodyLarge,
                            onTextLayout = { textLayout = it },
                            modifier = if (activeMatch != null) {
                                Modifier.onGloballyPositioned { coords ->
                                    textCoords = coords
                                }
                            } else Modifier,
                        )
                    }
                }
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
private fun AssistantMessage(
    sessionKey: String,
    turn: ChatTurn,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onRetryWithSwitch: () -> Unit,
    branchIndex: Int,
    branchCount: Int,
    canSwitchBranch: Boolean,
    onPreviousBranch: () -> Unit,
    onNextBranch: () -> Unit,
    onOpenUiToAssistant: (message: String, context: JsonElement?) -> Unit,
    highlightTerms: List<String> = emptyList(),
    activeMatch: ChatSearchMatch? = null,
    onActiveMatchPositioned: ((coords: LayoutCoordinates, yOffsetPx: Float) -> Unit)? = null,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val activelyThinking = turn.isRunning && turn.answer.isEmpty() && turn.generatedImages.isEmpty() &&
        (turn.isThinking || turn.reasoning.isNotEmpty())
    val turnTier = if (turn.ai.api.authentication == AuthenticationMethod.ChatGpt) null
        else wireServiceTier(turn.ai.api.requestProtocol, turn.ai.api.serviceTier)
    val footerMeta = remember(turn.ai.api.model, turn.ai.api.reasoningEffort, turnTier) {
        listOfNotNull(
            turn.ai.api.model.takeIf(String::isNotBlank),
            turn.ai.api.reasoningEffort,
            turnTier,
        ).joinToString(" · ")
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        turn.searchStatus?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AppIcon(R.drawable.ic_search, null, size = 14.dp, tint = muted)
                Text(it, color = muted, style = MaterialTheme.typography.labelMedium)
            }
        }
        turn.imageGenerationStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = muted) }
        turn.recoveryStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = muted) }
        if (turn.reasoning.isNotEmpty() || activelyThinking) {
            val reasoningMatch = activeMatch?.takeIf { it.section == SearchMatchSection.Reasoning }
            ReasoningSection(
                reasoning = turn.reasoning,
                activelyThinking = activelyThinking,
                defaultExpanded = turn.answer.isEmpty() && turn.generatedImages.isEmpty(),
                forceExpanded = reasoningMatch != null,
                highlightTerms = highlightTerms,
                activeMatchOffset = reasoningMatch?.start,
                onActiveMatchPositioned = onActiveMatchPositioned,
            )
        }
        if (turn.answer.isEmpty() && turn.reasoning.isEmpty() && !activelyThinking && turn.generatedImages.isEmpty() && turn.isRunning) {
            CircularProgressIndicator(Modifier.padding(vertical = 4.dp).size(18.dp), strokeWidth = 2.dp)
        }
        if (turn.answer.isNotEmpty()) {
            val openUiProgram = remember(turn.answer) { OpenUiDetector.extractProgram(turn.answer) }
            val streamingOpenUiPrefix = remember(turn.answer, turn.status, openUiProgram) {
                turn.status == TurnStatus.Streaming && openUiProgram == null && OpenUiDetector.looksLikeStreamingOpenUi(turn.answer)
            }
            val app = context.applicationContext as? TouchAiApplication
            val markdown = remember(turn.answer, turn.citations) { citationMarkdown(turn.answer, turn.citations) }
            val answerMatch = activeMatch?.takeIf { it.section == SearchMatchSection.Answer }
            when {
                openUiProgram != null && app != null && highlightTerms.isEmpty() -> {
                    OpenUiMessage(
                        sessionId = sessionKey,
                        program = openUiProgram,
                        isStreaming = turn.status == TurnStatus.Streaming,
                        engine = app.openUiEngine,
                        onOpenUrl = { url -> runCatching { uriHandler.openUri(url) } },
                        onToAssistant = onOpenUiToAssistant,
                        modifier = Modifier.fillMaxWidth(),
                        fallbackContent = {
                            SelectionContainer {
                                StreamingMarkdown(
                                    text = markdown,
                                    isStreaming = turn.status == TurnStatus.Streaming,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        },
                    )
                }
                streamingOpenUiPrefix -> {
                    CircularProgressIndicator(Modifier.padding(vertical = 4.dp).size(18.dp), strokeWidth = 2.dp)
                }
                else -> {
                    SelectionContainer {
                        StreamingMarkdown(
                            text = markdown,
                            isStreaming = turn.status == TurnStatus.Streaming,
                            modifier = Modifier.fillMaxWidth(),
                            highlightTerms = highlightTerms,
                            activeMatchOffset = answerMatch?.start,
                            onActiveMatchPositioned = onActiveMatchPositioned,
                        )
                    }
                }
            }
        }
        turn.generatedImages.forEach { output -> key(output.id) { SentImage(output.image, "Generated image") } }
        val sources = turn.citations.distinctBy { it.url }
        if (sources.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sources.forEachIndexed { sourceIndex, citation ->
                    val citationMatch = activeMatch?.takeIf { it.section == SearchMatchSection.Citation && it.citationIndex == sourceIndex }
                    val activeOrdinal = remember(citation.title, highlightTerms, citationMatch) {
                        citationMatch?.let { match ->
                            findHighlightRanges(citation.title.substring(0, match.start.coerceIn(0, citation.title.length)), highlightTerms).size
                        }
                    }
                    val titleDisplay = remember(citation.title, highlightTerms, activeOrdinal) {
                        if (highlightTerms.isEmpty()) AnnotatedString(citation.title)
                        else applyHighlightSpans(AnnotatedString(citation.title), highlightTerms, activeOrdinal)
                    }
                    Surface(
                        onClick = { uriHandler.openUri(citation.url) },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.then(
                            if (citationMatch != null && onActiveMatchPositioned != null) {
                                Modifier.onGloballyPositioned { coords ->
                                    onActiveMatchPositioned(coords, 0f)
                                }
                            } else Modifier,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "${sourceIndex + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = muted,
                            )
                            Text(
                                titleDisplay,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 180.dp),
                            )
                        }
                    }
                }
            }
        }
        if (turn.status == TurnStatus.Stopped) Text("Stopped", style = MaterialTheme.typography.labelMedium, color = muted)
        turn.error?.let { MessageBanner(it, error = true) }
        val copyText = turn.answer.ifEmpty { turn.reasoning }
        if (copyText.isNotEmpty() || canRetry || branchCount > 1) Row(
            modifier = Modifier.fillMaxWidth().offset(x = (-10).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (copyText.isNotEmpty()) IconButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("AI response", copyText))
                },
                modifier = Modifier.size(34.dp),
            ) { AppIcon(R.drawable.ic_copy, "Copy", size = 16.dp, tint = muted) }
            if (canRetry) {
                IconButton(onClick = onRetry, modifier = Modifier.size(34.dp)) {
                    AppIcon(R.drawable.ic_refresh, "Retry", size = 16.dp, tint = muted)
                }
                IconButton(onClick = onRetryWithSwitch, modifier = Modifier.size(34.dp)) {
                    AppIcon(R.drawable.ic_tune, "Switch model and retry", size = 16.dp, tint = muted)
                }
            }
            if (branchCount > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onPreviousBranch,
                        enabled = canSwitchBranch && branchIndex > 0,
                        modifier = Modifier.size(30.dp),
                    ) {
                        AppIcon(R.drawable.ic_chevron_right, "Previous reply", Modifier.rotate(180f), size = 16.dp, tint = muted)
                    }
                    Text(
                        "${branchIndex + 1}/$branchCount",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                    IconButton(
                        onClick = onNextBranch,
                        enabled = canSwitchBranch && branchIndex < branchCount - 1,
                        modifier = Modifier.size(30.dp),
                    ) {
                        AppIcon(R.drawable.ic_chevron_right, "Next reply", size = 16.dp, tint = muted)
                    }
                }
            }
            if (footerMeta.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = footerMeta,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PairedConversationTabsBar(
    primaryPane: ChatPaneState,
    secondaryPane: ChatPaneState,
    primaryFallbackAi: AiConfiguration,
    secondaryFallbackAi: AiConfiguration,
    activeSlot: Int,
    showPerTabStop: Boolean,
    onSelectSlot: (Int) -> Unit,
    onStopSlot: (Int) -> Unit,
) {
    val entries = listOf(
        Triple(0, primaryPane, primaryFallbackAi),
        Triple(1, secondaryPane, secondaryFallbackAi),
    )
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            entries.forEach { (slot, pane, fallbackAi) ->
                val isSelected = activeSlot == slot
                val lastTurn = pane.turns.lastOrNull()
                val displayAi = lastTurn?.ai ?: fallbackAi
                val slotTag = if (slot == 0) "A" else "B"
                val status = lastTurn?.status ?: TurnStatus.Completed
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                        .clickable { onSelectSlot(slot) }
                        .semantics {
                            selected = isSelected
                            contentDescription = "Conversation $slotTag: ${displayAi.api.model}"
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CompareTabStatusDot(status)
                        Text(
                            text = displayAi.api.model.ifBlank { "Model $slotTag" },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) scheme.primary else scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (showPerTabStop && pane.isRunning && status != TurnStatus.Cancelling) {
                            IconButton(
                                onClick = { onStopSlot(slot) },
                                modifier = Modifier.size(22.dp),
                            ) {
                                AppIcon(R.drawable.ic_stop, "Stop conversation $slotTag", size = 14.dp, tint = scheme.error)
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) scheme.primary else Color.Transparent),
                    )
                }
            }
        }
        HorizontalDivider(
            thickness = 0.75.dp,
            color = scheme.outlineVariant,
        )
    }
}

@Composable
private fun CompareTabStatusDot(status: TurnStatus) {
    when {
        status in listOf(TurnStatus.Streaming, TurnStatus.Recovering, TurnStatus.Cancelling) -> {
            CircularProgressIndicator(
                modifier = Modifier.size(11.dp),
                strokeWidth = 1.6.dp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        status == TurnStatus.Completed -> {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        status == TurnStatus.Stopped -> {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
        else -> {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}

@Composable
private fun ReasoningSection(
    reasoning: String,
    activelyThinking: Boolean,
    defaultExpanded: Boolean,
    forceExpanded: Boolean = false,
    highlightTerms: List<String> = emptyList(),
    activeMatchOffset: Int? = null,
    onActiveMatchPositioned: ((coords: LayoutCoordinates, yOffsetPx: Float) -> Unit)? = null,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = if (activelyThinking) MaterialTheme.colorScheme.primary else muted
    if (reasoning.isEmpty()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = MaterialTheme.colorScheme.primary)
            Text("Thinking…", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(forceExpanded) { if (forceExpanded) userExpanded = true }
    val expanded = userExpanded ?: (defaultExpanded || forceExpanded)
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "thoughtsChevron")
    val railColor = if (activelyThinking) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier.offset(x = (-6).dp)
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = if (expanded) "Collapse thoughts" else "Expand thoughts") { userExpanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (activelyThinking) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                AppIcon(R.drawable.ic_spark, null, size = 14.dp, tint = muted.copy(alpha = 0.85f))
            }
            Text(
                text = if (activelyThinking) "Thinking…" else "Thought process",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = accent,
            )
            AppIcon(R.drawable.ic_expand, if (expanded) "Collapse thoughts" else "Expand thoughts",
                Modifier.rotate(rotation), size = 16.dp, tint = accent)
        }
        if (expanded) {
            val trimmed = remember(reasoning) { reasoning.trim() }
            if (trimmed.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth()
                        .padding(start = 6.dp, top = 2.dp, bottom = 4.dp)
                        .drawBehind {
                            val stroke = 2.dp.toPx()
                            val inset = 2.dp.toPx()
                            drawLine(
                                color = railColor,
                                start = Offset(stroke / 2f, inset),
                                end = Offset(stroke / 2f, (size.height - inset).coerceAtLeast(inset)),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                        }
                        .padding(start = 14.dp),
                ) {
                    SelectionContainer {
                        StreamingMarkdown(
                            text = trimmed,
                            isStreaming = activelyThinking,
                            modifier = Modifier.fillMaxWidth(),
                            textColor = muted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            highlightTerms = highlightTerms,
                            activeMatchOffset = activeMatchOffset,
                            onActiveMatchPositioned = onActiveMatchPositioned,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Attachment(bitmap: Bitmap, maxHeight: Dp, preparing: Boolean, attached: Boolean, sequence: Int?, onCrop: () -> Unit, onToggle: () -> Unit,
    onDelete: () -> Unit, modifier: Modifier) {
    val shape = MaterialTheme.shapes.large
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Image(remember(bitmap) { bitmap.asImageBitmap() }, "Image preview",
                Modifier.heightIn(min = 96.dp, max = maxHeight).widthIn(min = 96.dp)
                    .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .then(if (attached) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), shape) else Modifier)
                    .clickable(onClick = onToggle).semantics {
                        contentDescription = "Image preview"
                        selected = attached
                        stateDescription = if (sequence != null) "Send image $sequence" else "Not attached"
                    },
                contentScale = ContentScale.Fit)
            Surface(onClick = onToggle, shape = CircleShape,
                color = if (attached) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (attached) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp).semantics {
                    contentDescription = if (attached) "Remove image" else "Add image"
                    selected = attached
                    stateDescription = if (sequence != null) "Send image $sequence" else "Not attached"
                }) {
                Box(contentAlignment = Alignment.Center) {
                    if (sequence != null) Text(sequence.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    else AppIcon(R.drawable.ic_add, null, size = 20.dp)
                }
            }
            Surface(onClick = onDelete, shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).size(28.dp).semantics { contentDescription = "Delete image" }) {
                Box(contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_delete, null, size = 16.dp) }
            }
            Surface(onClick = onCrop, shape = CircleShape, color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(36.dp).semantics { contentDescription = "Crop image" }) {
                Box(contentAlignment = Alignment.Center) { AppIcon(R.drawable.ic_crop, null, size = 20.dp) }
            }
        }
        if (preparing) LinearProgressIndicator(Modifier.width(96.dp))
    }
}
