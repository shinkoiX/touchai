package app.touchai.android

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.touchai.core.openai.*
import java.net.URI
import java.net.URISyntaxException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TurnStatus { Streaming, Recovering, Cancelling, Completed, Stopped, Interrupted, Incomplete, Failed }

data class ChatTurn(
    val user: ChatMessage,
    val ai: AiConfiguration,
    val presetName: String? = null,
    val answer: String = "",
    val reasoning: String = "",
    val isThinking: Boolean = false,
    val status: TurnStatus = TurnStatus.Streaming,
    val error: String? = null,
    val searchStatus: String? = null,
    val citations: List<WebCitation> = emptyList(),
    val generatedImages: List<GeneratedImage> = emptyList(),
    val imageGenerationStatus: String? = null,
    val responseId: String? = null,
    val cancelRequested: Boolean = false,
    val recoveryStatus: String? = null,
) {
    val isRunning: Boolean get() = status in listOf(TurnStatus.Streaming, TurnStatus.Recovering, TurnStatus.Cancelling)
}

data class ImageAttachment(
    val id: String = UUID.randomUUID().toString(),
    val original: Bitmap? = null,
    val preview: Bitmap? = original,
    val image: OpenAIImage? = null,
    val screenshot: Boolean = false,
    val crop: ImageCrop = ImageCrop.Full,
    val selectionOrder: Int? = null,
    val preparing: Boolean = false,
) {
    val attached: Boolean get() = selectionOrder != null
}

private val List<ImageAttachment>.nextSelectionOrder: Int get() = (mapNotNull { it.selectionOrder }.maxOrNull() ?: 0) + 1

private fun List<ImageAttachment>.withScreenshotAttachment(attached: Boolean): List<ImageAttachment> {
    var nextOrder = nextSelectionOrder
    return map { image ->
        if (!image.screenshot || image.attached == attached) image
        else image.copy(selectionOrder = if (attached) nextOrder++ else null)
    }
}

data class BranchInfo(val nodeId: String, val index: Int, val count: Int)

data class ChatPaneState(
    val chatId: String = UUID.randomUUID().toString(),
    val chatTree: ChatTree = ChatTree(),
    val turns: List<ChatTurn> = emptyList(),
) {
    val isRunning: Boolean get() = chatTree.hasRunningNodes || turns.any { it.isRunning }
}

data class OpenAIChatUiState(
    val settings: AppSettings = AppSettings(),
    val ready: Boolean = false,
    val settingsOpen: Boolean = false,
    val logsOpen: Boolean = false,
    val historyOpen: Boolean = false,
    val historyLoading: Boolean = false,
    val historySearching: Boolean = false,
    val historyTransferring: Boolean = false,
    val historyNotice: String? = null,
    val historyQuery: String = "",
    val historyEntries: List<ChatHistoryEntry> = emptyList(),
    val historySnippets: Map<String, String> = emptyMap(),
    val chatId: String = UUID.randomUUID().toString(),
    val settingsDraft: AppSettings? = null,
    val savingSettings: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionResult: String? = null,
    val effortNotice: String? = null,
    val invoking: Boolean = false,
    val capturingScreenshot: Boolean = false,
    val attachments: List<ImageAttachment> = emptyList(),
    val croppingImageId: String? = null,
    val prompt: String = "",
    val selectedPreset: String? = null,
    val temporaryAi: AiConfiguration? = null,
    val temporaryDualMode: Boolean? = null,
    val temporarySecondaryAi: AiConfiguration? = null,
    val lastSecondaryAi: AiConfiguration? = null,
    val chatTree: ChatTree = ChatTree(),
    val turns: List<ChatTurn> = emptyList(),
    val pairedPane: ChatPaneState? = null,
    val activePairSlot: Int = 0,
    val searchHighlightQuery: String = "",
    val searchMatchIndex: Int = 0,
    val error: String? = null,
) {
    val isPaired: Boolean get() = pairedPane != null
    val primaryPane: ChatPaneState
        get() = if (activePairSlot == 0 || pairedPane == null) ChatPaneState(chatId, chatTree, turns) else pairedPane
    val secondaryPane: ChatPaneState?
        get() = when {
            pairedPane == null -> null
            activePairSlot == 1 -> ChatPaneState(chatId, chatTree, turns)
            else -> pairedPane
        }
    val activePaneIsStreaming: Boolean get() = chatTree.hasRunningNodes || turns.any { it.isRunning }
    val isStreaming: Boolean get() = activePaneIsStreaming || (pairedPane?.isRunning == true)
    val runningCount: Int get() = (if (activePaneIsStreaming) 1 else 0) + (if (pairedPane?.isRunning == true) 1 else 0)
    val selectedAi: AiConfiguration get() = temporaryAi?.let(settings::resolve) ?: settings.aiFor(selectedPreset)
    val presetSecondaryAi: AiConfiguration? get() = settings.secondaryAiFor(selectedPreset)
    val isDualMode: Boolean get() = temporaryDualMode ?: (presetSecondaryAi != null)
    val resolvedSecondaryAi: AiConfiguration
        get() = temporarySecondaryAi?.let(settings::resolve)
            ?: presetSecondaryAi
            ?: lastSecondaryAi?.let(settings::resolve)
            ?: selectedAi
    val selectedSecondaryAi: AiConfiguration? get() = if (isDualMode) resolvedSecondaryAi else null
    val activeSlotAi: AiConfiguration get() = if (isPaired && activePairSlot == 1) resolvedSecondaryAi else selectedAi
    val hasTemporaryAi: Boolean get() = temporaryAi != null || temporaryDualMode != null || temporarySecondaryAi != null
    val sendingImages: List<ImageAttachment> get() = attachments.filter { it.attached }.sortedBy { it.selectionOrder }
    val preparingImages: Boolean get() = attachments.any { it.preparing }
    val canSendImages: Boolean get() = attachments.none { it.attached && (it.preparing || it.image == null) }

    fun branchInfo(turnIndex: Int): BranchInfo? {
        val node = chatTree.activePath().getOrNull(turnIndex) ?: return null
        val siblings = chatTree.siblingsOf(node.id)
        if (siblings.isEmpty()) return null
        val position = siblings.indexOfFirst { it.id == node.id }.coerceAtLeast(0)
        return BranchInfo(node.id, position, siblings.size)
    }
}

class OpenAIChatViewModel(
    private val history: ChatHistoryRepository,
    private val repository: SettingsRepository,
    private val client: ChatClient,
    private val requests: ChatRequestRunner,
    invoked: Boolean = false,
) : ViewModel() {
    private val _uiState = MutableStateFlow(OpenAIChatUiState(invoking = invoked))
    val uiState = _uiState.asStateFlow()
    private val requestUpdates = mutableMapOf<String, Job>()
    private val imageJobs = mutableMapOf<String, Job>()
    private var captureJob: Job? = null
    private var captureRequested = false
    private var presetSaveJob: Job? = null
    private var historyJob: Job? = null
    private var historySearchJob: Job? = null

    init {
        loadSettings()
        viewModelScope.launch { requests.recoveryError.collect { error -> error?.let(::reportError) } }
        viewModelScope.launch { requests.effortAdjustments.collect(::handleEffortAdjustment) }
        viewModelScope.launch { requests.serviceTierAdjustments.collect(::handleServiceTierAdjustment) }
    }

    fun loadSettings() {
        viewModelScope.launch {
            try {
                presetSaveJob?.join()
                val settings = repository.load()
                val images = _uiState.value.attachments.takeLast(settings.imageLimit)
                cancelRemovedImages(images)
                update { it.copy(settings = settings, ready = true, error = null,
                    attachments = images, croppingImageId = it.croppingImageId?.takeIf { id -> images.any { image -> image.id == id } },
                    settingsDraft = if (it.settingsOpen && it.settingsDraft == it.settings) settings else it.settingsDraft,
                    selectedPreset = if (!it.ready) rememberedPreset(settings) else it.selectedPreset,
                    prompt = if (!it.ready) presetText(settings, rememberedPreset(settings)) else it.prompt) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not load settings: ${error.message}") }
        }
    }

    fun setPrompt(value: String) = update { it.copy(prompt = value) }
    fun setImage(value: OpenAIImage) = addAttachment(ImageAttachment(image = value))
    fun setTemporaryAi(configuration: AiConfiguration?) {
        update { state ->
            val resolved = configuration?.let { candidate ->
                val normalized = candidate.copy(api = candidate.api.normalized())
                val resolvedCandidate = state.settings.resolve(normalized)
                if (resolvedCandidate == state.settings.aiFor(state.selectedPreset)) null else normalized
            }
            state.copy(temporaryAi = resolved, error = null)
        }
    }
    fun setDualMode(enabled: Boolean) {
        update { state ->
            val presetHasDual = state.presetSecondaryAi != null
            val nextDualOverride = if (enabled == presetHasDual) null else enabled
            val nextLastSecondary = if (enabled) {
                state.temporarySecondaryAi ?: state.presetSecondaryAi ?: state.lastSecondaryAi ?: state.selectedAi
            } else {
                state.temporarySecondaryAi ?: state.lastSecondaryAi
            }
            state.copy(
                temporaryDualMode = nextDualOverride,
                lastSecondaryAi = nextLastSecondary,
                error = null,
            )
        }
    }
    fun setTemporarySecondaryAi(configuration: AiConfiguration?, enableDual: Boolean = false) {
        update { state ->
            val normalized = configuration?.copy(api = configuration.api.normalized())
            val resolvedCandidate = normalized?.let(state.settings::resolve)
            val presetSecondary = state.presetSecondaryAi
            val tempSecondary = if (resolvedCandidate == presetSecondary) null else normalized
            val nextDualOverride = if (enableDual && normalized != null) {
                if (presetSecondary != null) null else true
            } else {
                state.temporaryDualMode
            }
            state.copy(
                temporaryDualMode = nextDualOverride,
                temporarySecondaryAi = tempSecondary,
                lastSecondaryAi = normalized ?: state.lastSecondaryAi,
                error = null,
            )
        }
    }
    fun selectPairSlot(slot: Int) {
        val target = slot.coerceIn(0, 1)
        update { state ->
            val other = state.pairedPane ?: return@update state
            if (state.activePairSlot == target) return@update state
            val currentPane = ChatPaneState(state.chatId, state.chatTree, state.turns)
            state.copy(
                chatId = other.chatId,
                chatTree = other.chatTree,
                turns = other.turns,
                pairedPane = currentPane,
                activePairSlot = target,
                searchMatchIndex = 0,
                error = null,
            )
        }
    }
    fun stepSearchMatch(delta: Int, totalMatches: Int) {
        if (totalMatches <= 0) return
        update { state ->
            val current = state.searchMatchIndex.coerceIn(0, totalMatches - 1)
            val next = ((current + delta) % totalMatches + totalMatches) % totalMatches
            state.copy(searchMatchIndex = next)
        }
    }
    fun clearSearchHighlight() = update { it.copy(searchHighlightQuery = "", searchMatchIndex = 0) }
    /**
     * Toggles dual-send mode on long-press of the Send button.
     * Returns true when enabling dual mode for the first time without a distinct 2nd AI configured,
     * signaling the UI to open the switcher directly on slot B.
     */
    fun toggleDualModeByLongPress(): Boolean {
        val state = _uiState.value
        if (state.isDualMode) {
            setDualMode(false)
            return false
        }
        val existingSecondary = state.temporarySecondaryAi?.let(state.settings::resolve)
            ?: state.presetSecondaryAi
            ?: state.lastSecondaryAi?.let(state.settings::resolve)
        val hasDistinctSecondary = existingSecondary != null &&
            existingSecondary.api.model.isNotBlank() &&
            existingSecondary != state.selectedAi
        setDualMode(true)
        return !hasDistinctSecondary
    }
    fun clearTemporaryAi() = update { it.copy(temporaryAi = null, temporaryDualMode = null, temporarySecondaryAi = null, error = null) }
    fun selectPreset(id: String?) {
        update { it.copy(selectedPreset = id, temporaryAi = null, temporaryDualMode = null, temporarySecondaryAi = null,
            prompt = presetText(it.settings, id), settings = it.settings.copy(lastPresetId = id),
            attachments = if (id != it.selectedPreset) it.attachments.withScreenshotAttachment(it.settings.attachScreenshotFor(id)) else it.attachments) }
        presetSaveJob?.cancel()
        presetSaveJob = viewModelScope.launch {
            try { repository.rememberPreset(id) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not remember preset: ${error.message}") }
        }
    }
    fun reportError(message: String) = update { it.copy(error = message) }
    fun consumeEffortNotice() = update { it.copy(effortNotice = null) }
    fun showSettings(open: Boolean) = update { it.copy(settingsOpen = open,
        settingsDraft = if (open) it.settings else null, error = null, connectionResult = null) }
    fun showLogs(open: Boolean) = update { it.copy(logsOpen = open) }
    fun showHistory(open: Boolean) {
        if (_uiState.value.historyTransferring) return
        historyJob?.cancel()
        historySearchJob?.cancel()
        val query = if (open) _uiState.value.historyQuery else ""
        update {
            it.copy(
                historyOpen = open,
                historyLoading = open,
                historySearching = false,
                historyQuery = query,
                historySnippets = if (open) it.historySnippets else emptyMap(),
                historyNotice = null,
                error = null,
            )
        }
        if (open) historyJob = viewModelScope.launch {
            try {
                if (query.isBlank()) {
                    val entries = loadHistoryEntries()
                    update { it.copy(historyEntries = entries, historySnippets = emptyMap()) }
                } else {
                    val results = searchHistoryResults(query)
                    update {
                        it.copy(
                            historyEntries = results.map { result -> result.entry },
                            historySnippets = results.toSnippetMap(),
                        )
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not load chat history: ${error.message}") }
            finally { if (currentCoroutineContext().isActive) update { it.copy(historyLoading = false) } }
        }
    }

    fun setHistoryQuery(query: String) {
        if (_uiState.value.historyTransferring) return
        if (query == _uiState.value.historyQuery) return
        historyJob?.cancel()
        historySearchJob?.cancel()
        val blank = query.isBlank()
        update { it.copy(historyQuery = query, historyLoading = false, historySearching = true, error = null) }
        historySearchJob = viewModelScope.launch {
            try {
                if (!blank) delay(SEARCH_DEBOUNCE_MILLIS)
                val results = searchHistoryResults(query)
                update {
                    it.copy(
                        historyEntries = results.map { result -> result.entry },
                        historySnippets = results.toSnippetMap(),
                    )
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not search chat history: ${error.message}") }
            finally { if (currentCoroutineContext().isActive) update { it.copy(historySearching = false) } }
        }
    }

    private suspend fun loadHistoryEntries(): List<ChatHistoryEntry> {
        val all = (history.list() + requests.active.value.values.map { it.state.value.entry })
            .associateBy { it.id }.values.sortedByDescending { it.updatedAt }
        return collapsePairedEntries(all)
    }

    private suspend fun searchHistoryResults(query: String): List<ChatHistorySearchResult> {
        val terms = tokenizeSearchQuery(query)
        if (terms.isEmpty()) return loadHistoryEntries().map { ChatHistorySearchResult(it) }
        val stored = history.search(query).associateBy { it.entry.id }.toMutableMap()
        for (active in requests.active.value.values) {
            val chat = active.state.value
            val matched = matchChat(chat, query)
            if (matched != null) stored[chat.id] = matched else stored.remove(chat.id)
        }
        return stored.values.sortedWith(
            compareByDescending<ChatHistorySearchResult> { result ->
                val titleLower = result.entry.title.lowercase(Locale.ROOT)
                terms.all { titleLower.contains(it) }
            }.thenByDescending { it.entry.updatedAt }
                .thenBy { it.entry.pairSlot ?: Int.MAX_VALUE },
        )
    }

    private fun collapsePairedEntries(entries: List<ChatHistoryEntry>): List<ChatHistoryEntry> {
        val byId = entries.associateBy { it.id }
        val seen = HashSet<String>(entries.size)
        val result = ArrayList<ChatHistoryEntry>(entries.size)
        for (entry in entries) {
            if (!seen.add(entry.id)) continue
            val partner = entry.pairedChatId?.let(byId::get)
            if (partner != null && seen.add(partner.id)) {
                val primary = if (partner.pairSlot == 0 && entry.pairSlot != 0) partner else entry
                val other = if (primary.id == entry.id) partner else entry
                result.add(
                    primary.copy(
                        updatedAt = maxOf(primary.updatedAt, other.updatedAt),
                        pending = primary.pending || other.pending,
                        pairedChatId = other.id,
                    ),
                )
            } else {
                result.add(entry)
            }
        }
        return result.sortedByDescending { it.updatedAt }
    }

    private fun List<ChatHistorySearchResult>.toSnippetMap(): Map<String, String> =
        mapNotNull { result -> result.snippet?.let { snippet -> result.entry.id to snippet } }.toMap()

    fun exportHistory(openOutput: () -> OutputStream) = transferHistory("Exported") {
        ChatHistoryArchive(history).exportTo(openOutput())
    }

    fun importHistory(openInput: () -> InputStream) = transferHistory("Imported") {
        ChatHistoryArchive(history).importFrom(openInput()) { image -> ImageProcessor.decode(image, 64).recycle() }
    }

    private fun transferHistory(action: String, transfer: suspend () -> Int) {
        if (_uiState.value.historyLoading || _uiState.value.isStreaming) return
        historySearchJob?.cancel()
        update { it.copy(historyLoading = true, historySearching = false, historyTransferring = true, historyNotice = null, error = null) }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { transfer() }
                val query = _uiState.value.historyQuery
                val notice = "$action $count ${if (count == 1) "chat" else "chats"}"
                if (query.isBlank()) {
                    val entries = loadHistoryEntries()
                    update { it.copy(historyEntries = entries, historySnippets = emptyMap(), historyNotice = notice) }
                } else {
                    val results = searchHistoryResults(query)
                    update {
                        it.copy(
                            historyEntries = results.map { result -> result.entry },
                            historySnippets = results.toSnippetMap(),
                            historyNotice = notice,
                        )
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                reportError("${if (action == "Imported") "Import" else "Export"} failed: ${error.message}")
            } finally { update { it.copy(historyLoading = false, historyTransferring = false) } }
        }
    }

    fun openChat(id: String) {
        if (_uiState.value.historyLoading) return
        val openingSearchQuery = _uiState.value.historyQuery.trim()
        historySearchJob?.cancel()
        update { it.copy(historyLoading = true, historySearching = false, error = null) }
        historyJob = viewModelScope.launch {
            try {
                requests.restorePending()
                val active = requests.find(id)
                val chat = active?.state?.value ?: history.load(id)
                val partnerId = chat.pairedChatId
                val partnerActive = partnerId?.let(requests::find)
                val partner = partnerActive?.state?.value ?: partnerId?.let { runCatching { history.load(it) }.getOrNull() }
                val settings = repository.load()
                val selected = chat.selectedPreset?.takeIf { presetId -> settings.presets.any { it.id == presetId } }
                val loadedTree = chat.resolvedTree
                val openingTree = if (active == null) loadedTree.copy(activeLeafId = loadedTree.latestLeaf()?.id) else loadedTree
                val partnerPane = partner?.let { p ->
                    val pTree = p.resolvedTree
                    val openedPartnerTree = if (partnerActive == null) pTree.copy(activeLeafId = pTree.latestLeaf()?.id) else pTree
                    ChatPaneState(p.id, openedPartnerTree, openedPartnerTree.activeTurns())
                }
                val activeSlot = when {
                    partnerPane == null -> 0
                    chat.pairSlot in 0..1 -> chat.pairSlot!!
                    partner?.pairSlot == 0 -> 1
                    else -> 0
                }
                val primaryTurns = if (activeSlot == 0 || partnerPane == null) openingTree.activeTurns() else partnerPane.turns
                val secondaryTurns = when {
                    partnerPane == null -> null
                    activeSlot == 1 -> openingTree.activeTurns()
                    else -> partnerPane.turns
                }
                val restoredPrimaryAi = if (partnerPane != null) {
                    primaryTurns.lastOrNull()?.ai?.let { restoreAiWithSettings(it, settings) }
                } else null
                val restoredSecondaryAi = secondaryTurns?.lastOrNull()?.ai?.let { restoreAiWithSettings(it, settings) }
                val tempPrimary = restoredPrimaryAi?.takeIf { settings.resolve(it) != settings.aiFor(selected) }
                val tempSecondary = restoredSecondaryAi?.takeIf { settings.resolve(it) != settings.secondaryAiFor(selected) }
                val tempDual = if (partnerPane != null) {
                    if (settings.secondaryAiFor(selected) != null) null else true
                } else null
                clearImageJobs()
                clearRequestObservers()
                update {
                    it.copy(
                        chatId = chat.id,
                        chatTree = openingTree,
                        turns = openingTree.activeTurns(),
                        pairedPane = partnerPane,
                        activePairSlot = activeSlot,
                        settings = settings,
                        ready = true,
                        selectedPreset = selected,
                        temporaryAi = tempPrimary,
                        temporaryDualMode = tempDual,
                        temporarySecondaryAi = tempSecondary,
                        lastSecondaryAi = restoredSecondaryAi ?: it.lastSecondaryAi,
                        prompt = "",
                        attachments = emptyList(),
                        croppingImageId = null,
                        historyOpen = false,
                        historyQuery = "",
                        historySnippets = emptyMap(),
                        searchHighlightQuery = openingSearchQuery,
                        searchMatchIndex = 0,
                    )
                }
                active?.let(::observeRequest)
                partnerActive?.let(::observeRequest)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not open chat: ${error.message}") }
            finally { if (currentCoroutineContext().isActive) update { it.copy(historyLoading = false) } }
        }
    }

    fun deleteChat(id: String) {
        if (_uiState.value.historyLoading) return
        val listedPartnerId = _uiState.value.historyEntries.firstOrNull { it.id == id }?.pairedChatId
        if (requests.find(id) != null || (listedPartnerId != null && requests.find(listedPartnerId) != null)) {
            reportError("Stop the request before deleting this chat.")
            return
        }
        update { it.copy(historyLoading = true, error = null) }
        historyJob = viewModelScope.launch {
            try {
                val partnerId = listedPartnerId ?: runCatching { history.load(id).pairedChatId }.getOrNull()
                if (partnerId != null && requests.find(partnerId) != null) {
                    reportError("Stop the request before deleting this chat.")
                    return@launch
                }
                history.delete(id)
                if (partnerId != null) runCatching { history.delete(partnerId) }
                val deletedIds = setOfNotNull(id, partnerId)
                update { state ->
                    val affectsCurrent = state.chatId in deletedIds || state.pairedPane?.chatId in deletedIds
                    state.copy(
                        historyEntries = state.historyEntries.filterNot { entry -> entry.id in deletedIds },
                        historySnippets = state.historySnippets - deletedIds,
                        chatId = if (affectsCurrent) UUID.randomUUID().toString() else state.chatId,
                        chatTree = if (affectsCurrent) ChatTree() else state.chatTree,
                        turns = if (affectsCurrent) emptyList() else state.turns,
                        pairedPane = if (affectsCurrent) null else state.pairedPane,
                        activePairSlot = if (affectsCurrent) 0 else state.activePairSlot,
                    )
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not delete chat: ${error.message}") }
            finally { update { it.copy(historyLoading = false) } }
        }
    }
    fun editSettings(value: AppSettings) = update { it.copy(settingsDraft = value, error = null, connectionResult = null) }

    fun prepareInvocation() {
        if (captureJob?.isActive == true) return
        captureRequested = false
        showHistory(false)
        update { it.copy(invoking = true, settingsOpen = false, logsOpen = false, historyOpen = false, settingsDraft = null) }
    }

    fun captureOnInvocation(capture: suspend () -> Bitmap) {
        if (captureJob?.isActive == true || captureRequested) return
        captureRequested = true
        update { it.copy(invoking = true, settingsOpen = false, logsOpen = false, settingsDraft = null) }
        captureJob = viewModelScope.launch {
            clearRequestObservers()
            imageJobs.values.toList().forEach { it.cancelAndJoin() }
            imageJobs.clear()
            update { it.copy(chatId = UUID.randomUUID().toString(), chatTree = ChatTree(), turns = emptyList(), pairedPane = null, activePairSlot = 0, attachments = emptyList(), croppingImageId = null, error = null) }
            try {
                val bitmap = capture()
                presetSaveJob?.join()
                val settings = repository.load()
                update { it.copy(settings = settings, ready = true, selectedPreset = rememberedPreset(settings),
                    temporaryAi = null, temporaryDualMode = null, temporarySecondaryAi = null, prompt = presetText(settings, rememberedPreset(settings)), invoking = false) }
                attachBitmap(bitmap, screenshot = true, attached = settings.attachScreenshotFor(rememberedPreset(settings)))
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { it.copy(invoking = false, error = error.message ?: "Screen capture failed. Continue without an image.") } }
        }
    }

    fun attachImage(bitmap: Bitmap) = attachBitmap(bitmap, screenshot = false)

    private fun attachBitmap(bitmap: Bitmap, screenshot: Boolean, attached: Boolean = true) {
        val image = ImageAttachment(original = bitmap, screenshot = screenshot)
        addAttachment(image, attached)
        prepareImage(image.id)
    }

    private fun addAttachment(image: ImageAttachment, attached: Boolean = true) {
        val current = _uiState.value
        val selected = image.copy(selectionOrder = if (attached) current.attachments.nextSelectionOrder else null)
        val images = (current.attachments + selected).takeLast(current.settings.imageLimit)
        cancelRemovedImages(images)
        update { it.copy(attachments = images, croppingImageId = null, error = null) }
    }

    private fun cancelRemovedImages(images: List<ImageAttachment>) {
        val retained = images.map { it.id }.toSet()
        imageJobs.keys.filter { it !in retained }.forEach { imageJobs.remove(it)?.cancel() }
    }

    private fun clearImageJobs() {
        imageJobs.values.forEach { it.cancel() }
        imageJobs.clear()
    }

    fun attachScreenshot(capture: suspend () -> Bitmap) {
        if (captureJob?.isActive == true) return
        update { it.copy(capturingScreenshot = true, error = null) }
        captureJob = viewModelScope.launch {
            try {
                val bitmap = capture()
                attachBitmap(bitmap, screenshot = true)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError(error.message ?: "Screen capture failed. Try again.") }
            finally { update { it.copy(capturingScreenshot = false) } }
        }
    }

    fun toggleImageAttachment(id: String) = update { state ->
        state.copy(attachments = state.attachments.map {
            if (it.id == id) it.copy(selectionOrder = if (it.attached) null else state.attachments.nextSelectionOrder) else it
        }, error = null)
    }

    fun openCrop(id: String) = update { it.copy(croppingImageId = id) }
    fun closeCrop() = update { it.copy(croppingImageId = null) }

    fun applyCrop(id: String, crop: ImageCrop) {
        updateImage(id) { it.copy(crop = crop) }
        closeCrop()
        prepareImage(id)
    }

    private fun prepareImage(id: String) {
        imageJobs[id]?.cancel()
        val image = _uiState.value.attachments.first { it.id == id }
        val quality = _uiState.value.settings.imageQuality
        updateImage(id) { it.copy(preparing = true, image = null) }
        imageJobs[id] = viewModelScope.launch {
            try {
                val prepared = ImageProcessor.prepare(image.original!!, image.crop, quality)
                updateImage(id) { it.copy(image = prepared.input, preview = prepared.preview, preparing = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                updateImage(id) { it.copy(preparing = false) }
                reportError("Could not prepare image: ${error.message}")
            }
        }
    }

    private fun updateImage(id: String, transform: (ImageAttachment) -> ImageAttachment) = update { state ->
        state.copy(attachments = state.attachments.map { if (it.id == id) transform(it) else it })
    }

    fun removeImage(id: String) {
        imageJobs.remove(id)?.cancel()
        update { it.copy(attachments = it.attachments.filterNot { image -> image.id == id },
            croppingImageId = if (it.croppingImageId == id) null else it.croppingImageId, error = null) }
    }

    fun saveSettings(settings: AppSettings) {
        if (_uiState.value.isStreaming || _uiState.value.savingSettings) return
        val normalized = settings.copy(
            api = settings.api.normalized(),
            connections = settings.connections.map { it.normalized() },
            presets = settings.presets.map { preset -> preset.copy(name = preset.name.trim(),
                customAi = preset.customAi?.let { it.copy(api = it.api.normalized()) },
                secondaryAi = preset.secondaryAi?.let { it.copy(api = it.api.normalized()) }) },
            lastPresetId = settings.lastPresetId?.takeIf { id -> settings.presets.any { it.id == id } },
        )
        val error = settingsError(normalized)
        if (error != null) { reportError(error); return }
        update { it.copy(savingSettings = true, error = null) }
        viewModelScope.launch {
            try {
                repository.save(normalized)
                val saved = repository.load()
                val before = _uiState.value
                recordSavedModels(before.settings, saved)
                val images = before.attachments.takeLast(saved.imageLimit)
                cancelRemovedImages(images)
                update { current ->
                    val selected = current.selectedPreset?.takeIf { id -> saved.presets.any { it.id == id } }
                    val conversationChanged = saved.copy(imageLimit = current.settings.imageLimit) != current.settings
                    current.copy(settings = saved, ready = true, settingsOpen = false, settingsDraft = null,
                        temporaryAi = null, temporaryDualMode = null, temporarySecondaryAi = null,
                        chatTree = if (conversationChanged) ChatTree() else current.chatTree,
                        turns = if (conversationChanged) emptyList() else current.turns, selectedPreset = selected,
                        chatId = if (conversationChanged) UUID.randomUUID().toString() else current.chatId,
                        pairedPane = if (conversationChanged) null else current.pairedPane,
                        activePairSlot = if (conversationChanged) 0 else current.activePairSlot,
                        attachments = if (saved.attachScreenshotFor(selected) != current.settings.attachScreenshotFor(current.selectedPreset))
                            images.withScreenshotAttachment(saved.attachScreenshotFor(selected)) else images,
                        croppingImageId = current.croppingImageId?.takeIf { id -> images.any { it.id == id } },
                        prompt = if (current.prompt == presetText(current.settings, current.selectedPreset)) presetText(saved, selected) else current.prompt)
                }
                if (saved.imageQuality != before.settings.imageQuality) {
                    images.filter { it.original != null }.forEach { prepareImage(it.id) }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not save settings: ${error.message}") }
            finally { update { it.copy(savingSettings = false) } }
        }
    }

    fun testConnection(configuration: AiConfiguration, label: String) {
        if (_uiState.value.testingConnection) return
        val ai = configuration.copy(api = configuration.api.normalized())
        val error = apiError(ai.api, requireCredentials = true)
        if (error != null) { reportError(error); return }
        update { it.copy(testingConnection = true, connectionResult = null, error = null) }
        viewModelScope.launch {
            try {
                var adjustedEffort: OpenAIStreamEvent.EffortAdjusted? = null
                var adjustedTier: OpenAIStreamEvent.ServiceTierAdjusted? = null
                client.stream(ai.api, OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Reply with OK only. This is a connection test.")), ai.instructions, RequestPurpose.ConnectionTest, label)).collect { event ->
                    when (event) {
                        is OpenAIStreamEvent.EffortAdjusted -> adjustedEffort = event
                        is OpenAIStreamEvent.ServiceTierAdjusted -> adjustedTier = event
                        else -> Unit
                    }
                }
                rememberModelQuietly(ai.api)
                if (adjustedEffort != null || adjustedTier != null) {
                    val notices = buildList {
                        adjustedEffort?.let { add(formatEffortAdjustmentNotice(it.requested, it.applied)) }
                        adjustedTier?.let { add(formatServiceTierAdjustmentNotice(it.requested, it.applied)) }
                    }
                    val details = buildList {
                        adjustedEffort?.let { add("${it.requested} → ${it.applied ?: "Default"}") }
                        adjustedTier?.let { add("tier ${it.requested} → ${it.applied ?: "Default"}") }
                    }
                    update { current ->
                        var updatedDraft = current.settingsDraft
                        adjustedEffort?.let { fb -> updatedDraft = updatedDraft?.let { d -> applyApiTransformToSettings(d, label) { it.copy(reasoningEffort = fb.applied) } } }
                        adjustedTier?.let { fb -> updatedDraft = updatedDraft?.let { d -> applyApiTransformToSettings(d, label) { it.copy(serviceTier = fb.applied) } } }
                        current.copy(
                            settingsDraft = updatedDraft,
                            effortNotice = notices.joinToString("\n"),
                            connectionResult = "$label: connection succeeded (${details.joinToString("; ")}).",
                        )
                    }
                } else {
                    update { it.copy(connectionResult = "$label: connection and streaming succeeded.") }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("$label: ${error.message}") }
            finally { update { it.copy(testingConnection = false) } }
        }
    }

    fun submit() {
        val snapshot = _uiState.value
        val blockedByStreaming = if (snapshot.isDualMode) snapshot.isStreaming else snapshot.activePaneIsStreaming
        if (!snapshot.ready || blockedByStreaming || snapshot.savingSettings || snapshot.croppingImageId != null ||
            snapshot.capturingScreenshot || !snapshot.canSendImages) return
        val preset = snapshot.settings.presets.find { it.id == snapshot.selectedPreset }
        val prompt = snapshot.prompt.trim()
        val images = snapshot.sendingImages.map { it.image!! }
        if (prompt.isBlank() && images.isEmpty()) { reportError("Enter a message, choose a preset, or attach an image."); return }
        val user = ChatMessage(MessageRole.User, prompt, images)
        if (snapshot.isDualMode) {
            val primaryAi = snapshot.selectedAi
            val primaryError = apiError(primaryAi.api, requireCredentials = true)
            if (primaryError != null) { update { it.copy(settingsOpen = true, settingsDraft = it.settings, error = primaryError) }; return }
            val secondaryAi = snapshot.resolvedSecondaryAi
            val secondaryError = apiError(secondaryAi.api, requireCredentials = true)
            if (secondaryError != null) {
                update { it.copy(settingsOpen = true, settingsDraft = it.settings, error = "2nd AI: $secondaryError") }
                return
            }
            clearImageJobs()
            val primaryTurn = ChatTurn(user, primaryAi, preset?.name)
            val secondaryTurn = ChatTurn(user, secondaryAi, preset?.name)
            lateinit var nextPrimary: ChatPaneState
            lateinit var nextSecondary: ChatPaneState
            update { state ->
                val currentPrimary = state.primaryPane
                val primaryBase = if (currentPrimary.chatTree.isEmpty && currentPrimary.turns.isNotEmpty()) {
                    ChatTree.fromLinear(currentPrimary.turns)
                } else {
                    currentPrimary.chatTree
                }
                val existingSecondary = state.secondaryPane
                val secondaryId = existingSecondary?.chatId ?: UUID.randomUUID().toString()
                val secondaryBase = if (existingSecondary != null) {
                    if (existingSecondary.chatTree.isEmpty && existingSecondary.turns.isNotEmpty()) {
                        ChatTree.fromLinear(existingSecondary.turns)
                    } else {
                        existingSecondary.chatTree
                    }
                } else {
                    primaryBase.cloneWithFreshIds()
                }
                val nextPrimaryTree = primaryBase.appendTurn(primaryTurn)
                val nextSecondaryTree = secondaryBase.appendTurn(secondaryTurn)
                nextPrimary = ChatPaneState(currentPrimary.chatId, nextPrimaryTree, nextPrimaryTree.activeTurns())
                nextSecondary = ChatPaneState(secondaryId, nextSecondaryTree, nextSecondaryTree.activeTurns())
                val activePane = if (state.activePairSlot == 1) nextSecondary else nextPrimary
                val otherPane = if (state.activePairSlot == 1) nextPrimary else nextSecondary
                state.copy(
                    prompt = "",
                    attachments = emptyList(),
                    croppingImageId = null,
                    error = null,
                    lastSecondaryAi = state.temporarySecondaryAi ?: state.presetSecondaryAi ?: state.lastSecondaryAi ?: secondaryAi,
                    chatId = activePane.chatId,
                    chatTree = activePane.chatTree,
                    turns = activePane.turns,
                    pairedPane = otherPane,
                    searchHighlightQuery = "",
                    searchMatchIndex = 0,
                )
            }
            rememberModelQuietly(primaryAi.api)
            rememberModelQuietly(secondaryAi.api)
            startPaneRequest(nextPrimary, pairedChatId = nextSecondary.chatId, pairSlot = 0)
            startPaneRequest(nextSecondary, pairedChatId = nextPrimary.chatId, pairSlot = 1)
        } else {
            val ai = snapshot.activeSlotAi
            val error = apiError(ai.api, requireCredentials = true)
            if (error != null) { update { it.copy(settingsOpen = true, settingsDraft = it.settings, error = error) }; return }
            clearImageJobs()
            val newTurn = ChatTurn(user, ai, preset?.name)
            lateinit var nextPane: ChatPaneState
            var partnerId: String? = null
            var pairSlot: Int? = null
            update { state ->
                val baseTree = if (state.chatTree.isEmpty && state.turns.isNotEmpty()) ChatTree.fromLinear(state.turns) else state.chatTree
                val nextTree = baseTree.appendTurn(newTurn)
                nextPane = ChatPaneState(state.chatId, nextTree, nextTree.activeTurns())
                partnerId = state.pairedPane?.chatId
                pairSlot = if (state.pairedPane != null) state.activePairSlot else null
                state.copy(
                    prompt = "",
                    attachments = emptyList(),
                    croppingImageId = null,
                    error = null,
                    chatTree = nextTree,
                    turns = nextTree.activeTurns(),
                    searchHighlightQuery = "",
                    searchMatchIndex = 0,
                )
            }
            rememberModelQuietly(ai.api)
            startPaneRequest(nextPane, pairedChatId = partnerId, pairSlot = pairSlot)
        }
    }

    fun switchBranch(turnIndex: Int, delta: Int) {
        val snapshot = _uiState.value
        if (snapshot.activePaneIsStreaming) return
        val node = snapshot.chatTree.activePath().getOrNull(turnIndex) ?: return
        val siblings = snapshot.chatTree.siblingsOf(node.id)
        val nextIndex = siblings.indexOfFirst { it.id == node.id } + delta
        if (nextIndex !in siblings.indices) return
        val nextTree = snapshot.chatTree.selectBranch(siblings[nextIndex].id)
        update { it.copy(chatTree = nextTree, turns = nextTree.activeTurns(), error = null) }
    }

    fun retry(override: AiConfiguration? = null, turnIndex: Int? = null) {
        val snapshot = _uiState.value
        if (snapshot.activePaneIsStreaming || snapshot.turns.isEmpty() || snapshot.savingSettings) return
        val targetIndex = turnIndex ?: snapshot.turns.lastIndex
        if (targetIndex !in snapshot.turns.indices) return
        val targetTurn = snapshot.turns[targetIndex]
        val baseAi = if (override != null) {
            snapshot.settings.resolve(override.copy(api = override.api.normalized()))
        } else {
            targetTurn.ai
        }
        val api = resolveApiCredentials(baseAi.api, snapshot.settings)
        val error = apiError(api, requireCredentials = true)
        if (error != null) { reportError(error); return }
        val resume = override == null && targetIndex == snapshot.turns.lastIndex &&
            targetTurn.status == TurnStatus.Interrupted && targetTurn.responseId != null && api.recoverResponses
        rememberModelQuietly(api)
        if (override != null) {
            val normalizedOverride = override.copy(api = override.api.normalized())
            if (snapshot.isPaired && snapshot.activePairSlot == 1) {
                setTemporarySecondaryAi(normalizedOverride, enableDual = false)
            } else {
                setTemporaryAi(normalizedOverride)
            }
        }
        val partnerId = snapshot.pairedPane?.chatId
        val pairSlot = if (snapshot.pairedPane != null) snapshot.activePairSlot else null
        if (resume) {
            updateLast { it.copy(ai = baseAi.copy(api = api), status = TurnStatus.Recovering, error = null, cancelRequested = false) }
            val current = _uiState.value
            startPaneRequest(
                ChatPaneState(current.chatId, current.chatTree, current.turns),
                purpose = RequestPurpose.Resume,
                pairedChatId = partnerId,
                pairSlot = pairSlot,
            )
        } else {
            val retriedTurn = targetTurn.copy(
                ai = baseAi.copy(api = api),
                answer = "",
                reasoning = "",
                isThinking = false,
                status = TurnStatus.Streaming,
                error = null,
                searchStatus = null,
                citations = emptyList(),
                generatedImages = emptyList(),
                imageGenerationStatus = null,
                responseId = null,
                cancelRequested = false,
            )
            lateinit var nextPane: ChatPaneState
            update { state ->
                val baseTree = if (state.chatTree.isEmpty && state.turns.isNotEmpty()) ChatTree.fromLinear(state.turns) else state.chatTree
                val targetNode = baseTree.activePath().getOrNull(targetIndex)
                val nextTree = if (targetNode != null) baseTree.branchRetry(targetNode.id, retriedTurn)
                    else baseTree.appendTurn(retriedTurn)
                nextPane = ChatPaneState(state.chatId, nextTree, nextTree.activeTurns())
                state.copy(chatTree = nextTree, turns = nextTree.activeTurns(), error = null)
            }
            startPaneRequest(nextPane, purpose = RequestPurpose.Retry, pairedChatId = partnerId, pairSlot = pairSlot)
        }
    }

    private fun startPaneRequest(
        pane: ChatPaneState,
        purpose: RequestPurpose = RequestPurpose.Chat,
        pairedChatId: String? = null,
        pairSlot: Int? = null,
    ) {
        val snapshot = _uiState.value
        try {
            observeRequest(requests.start(
                SavedChat(
                    id = pane.chatId,
                    updatedAt = System.currentTimeMillis(),
                    selectedPreset = snapshot.selectedPreset,
                    turns = pane.turns,
                    tree = pane.chatTree.takeIf { it.hasBranches },
                    pairedChatId = pairedChatId,
                    pairSlot = pairSlot,
                ),
                purpose,
            ))
        } catch (error: Exception) {
            val failedMessage = "Could not start background work: ${error.message}"
            updatePaneById(pane.chatId) { current ->
                if (!current.chatTree.isEmpty) {
                    val nextTree = current.chatTree.updateActiveLeaf { it.copy(status = TurnStatus.Failed, error = failedMessage) }
                    current.copy(chatTree = nextTree, turns = nextTree.activeTurns())
                } else if (current.turns.isNotEmpty()) {
                    current.copy(turns = current.turns.dropLast(1) + current.turns.last().copy(status = TurnStatus.Failed, error = failedMessage))
                } else current
            }
        }
    }

    private fun observeRequest(handle: ActiveChatRequest) {
        requestUpdates.remove(handle.chatId)?.cancel()
        requestUpdates[handle.chatId] = viewModelScope.launch {
            handle.state.collect { saved ->
                update { state ->
                    when {
                        state.chatId == saved.id -> state.copy(chatTree = saved.resolvedTree, turns = saved.turns)
                        state.pairedPane?.chatId == saved.id -> state.copy(
                            pairedPane = ChatPaneState(saved.id, saved.resolvedTree, saved.turns),
                        )
                        else -> state
                    }
                }
            }
        }
    }

    private fun clearRequestObservers() {
        requestUpdates.values.forEach { it.cancel() }
        requestUpdates.clear()
    }

    private fun updatePaneById(targetChatId: String, transform: (ChatPaneState) -> ChatPaneState) = update { state ->
        when {
            state.chatId == targetChatId -> {
                val updated = transform(ChatPaneState(state.chatId, state.chatTree, state.turns))
                state.copy(chatTree = updated.chatTree, turns = updated.turns)
            }
            state.pairedPane?.chatId == targetChatId -> state.copy(pairedPane = transform(state.pairedPane))
            else -> state
        }
    }

    fun cancel() {
        val state = _uiState.value
        requests.stop(state.chatId)
        state.pairedPane?.let { requests.stop(it.chatId) }
    }

    fun cancelSlot(slot: Int) {
        val state = _uiState.value
        val targetId = if (slot == 0) state.primaryPane.chatId else state.secondaryPane?.chatId
        if (targetId != null) requests.stop(targetId)
    }

    fun newChat() {
        if (_uiState.value.preparingImages || _uiState.value.historyLoading || _uiState.value.capturingScreenshot) return
        clearRequestObservers()
        clearImageJobs()
        showHistory(false)
        update { it.copy(chatId = UUID.randomUUID().toString(), ready = false, temporaryAi = null,
            chatTree = ChatTree(), turns = emptyList(), pairedPane = null, activePairSlot = 0,
            searchHighlightQuery = "", searchMatchIndex = 0,
            prompt = "", attachments = emptyList(), croppingImageId = null, error = null) }
        loadSettings()
    }

    private fun resolveApiCredentials(original: OpenAIModelConfig, settings: AppSettings): OpenAIModelConfig {
        val configurations = settings.credentialSources()
        return if (original.authentication == AuthenticationMethod.ChatGpt) original.copy(
            chatGptAccountId = original.chatGptAccountId ?: configurations.firstOrNull {
                it.authentication == AuthenticationMethod.ChatGpt && it.chatGptAccountId != null
            }?.chatGptAccountId,
        ) else if (original.apiKey.isNotBlank()) original else original.copy(apiKey = configurations.firstOrNull {
            it.authentication == AuthenticationMethod.ApiKey && it.apiKey.isNotBlank() &&
                it.baseUrl.trimEnd('/') == original.baseUrl.trimEnd('/') && it.protocol == original.protocol
        }?.apiKey.orEmpty())
    }

    private fun restoreAiWithSettings(ai: AiConfiguration, settings: AppSettings): AiConfiguration {
        val api = resolveApiCredentials(ai.api, settings)
        val matchedConnection = settings.connections.firstOrNull { conn ->
            conn.authentication == api.authentication &&
                if (conn.authentication == AuthenticationMethod.ApiKey) {
                    conn.baseUrl.trimEnd('/') == api.baseUrl.trimEnd('/') && conn.protocol == api.protocol
                } else {
                    conn.chatGptAccountId == api.chatGptAccountId
                }
        }
        return if (matchedConnection != null) {
            ai.copy(api = api.using(matchedConnection), connectionId = matchedConnection.id)
        } else {
            ai.copy(api = api)
        }
    }

    private fun updateLast(transform: (ChatTurn) -> ChatTurn) = update { state ->
        if (!state.chatTree.isEmpty) {
            val nextTree = state.chatTree.updateActiveLeaf(transform)
            state.copy(chatTree = nextTree, turns = nextTree.activeTurns())
        } else {
            state.copy(turns = state.turns.dropLast(1) + transform(state.turns.last()))
        }
    }
    private fun update(transform: (OpenAIChatUiState) -> OpenAIChatUiState) { _uiState.update(transform) }
    private fun rememberedPreset(settings: AppSettings) = settings.lastPresetId?.takeIf { id -> settings.presets.any { it.id == id } }
    private fun presetText(settings: AppSettings, id: String?) = settings.presets.find { it.id == id }?.prompt.orEmpty()

    private fun rememberModelQuietly(api: OpenAIModelConfig) {
        val model = api.model.trim()
        if (model.isEmpty()) return
        val endpointKey = api.recentEndpointKey()
        viewModelScope.launch {
            try { repository.rememberModel(model, endpointKey) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { }
        }
    }

    private suspend fun recordSavedModels(before: AppSettings, saved: AppSettings) {
        try {
            val existingRecent = repository.loadRecentModels().toSet()
            val toRecord = ArrayList<RecentModelEntry>()
            val beforePresets = before.presets.associateBy { it.id }
            for (preset in saved.presets) {
                for ((slot, custom) in listOf(false to preset.customAi, true to preset.secondaryAi)) {
                    if (custom == null) continue
                    val api = saved.resolve(custom).api
                    val model = api.model.trim()
                    if (model.isEmpty()) continue
                    val entry = RecentModelEntry(model, api.recentEndpointKey())
                    val prevConfig = if (slot) beforePresets[preset.id]?.secondaryAi else beforePresets[preset.id]?.customAi
                    val prevApi = prevConfig?.let { before.resolve(it).api }
                    val changed = prevApi?.model?.trim() != model || prevApi.recentEndpointKey() != entry.endpointKey
                    if (changed || entry !in existingRecent) toRecord.add(entry)
                }
            }
            val defaultApi = saved.resolve(saved.defaultAi).api
            val defaultModel = defaultApi.model.trim()
            if (defaultModel.isNotEmpty()) {
                val entry = RecentModelEntry(defaultModel, defaultApi.recentEndpointKey())
                val prevDefaultApi = before.resolve(before.defaultAi).api
                val changed = prevDefaultApi.model.trim() != defaultModel || prevDefaultApi.recentEndpointKey() != entry.endpointKey
                if (changed || entry !in existingRecent) toRecord.add(entry)
            }
            if (toRecord.isNotEmpty()) repository.rememberModels(toRecord)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { }
    }

    private fun handleEffortAdjustment(adjustment: EffortAdjustmentNotice) {
        applyRuntimeApiAdjustment(
            presetId = adjustment.presetId,
            isSecondary = adjustment.isSecondary,
            notice = formatEffortAdjustmentNotice(adjustment.requested, adjustment.applied),
        ) { it.copy(reasoningEffort = adjustment.applied) }
    }

    private fun handleServiceTierAdjustment(adjustment: ServiceTierAdjustmentNotice) {
        applyRuntimeApiAdjustment(
            presetId = adjustment.presetId,
            isSecondary = adjustment.isSecondary,
            notice = formatServiceTierAdjustmentNotice(adjustment.requested, adjustment.applied),
        ) { it.copy(serviceTier = adjustment.applied) }
    }

    private fun applyRuntimeApiAdjustment(
        presetId: String?,
        isSecondary: Boolean,
        notice: String,
        transform: (OpenAIModelConfig) -> OpenAIModelConfig,
    ) {
        val currentState = _uiState.value
        val isTemporaryTarget = if (isSecondary) {
            currentState.temporarySecondaryAi != null || currentState.presetSecondaryAi == null
        } else {
            currentState.temporaryAi != null
        }
        update { current ->
            if (isSecondary) {
                if (current.temporarySecondaryAi != null || current.presetSecondaryAi == null) {
                    val base = current.temporarySecondaryAi ?: current.lastSecondaryAi
                    val updatedBase = base?.copy(api = transform(base.api))
                    current.copy(
                        temporarySecondaryAi = if (current.temporarySecondaryAi != null) updatedBase else current.temporarySecondaryAi,
                        lastSecondaryAi = updatedBase ?: current.lastSecondaryAi,
                        effortNotice = notice,
                    )
                } else {
                    val updatedSettings = applyApiTransformToSecondaryPreset(current.settings, presetId, transform)
                    val updatedDraft = current.settingsDraft?.let { draft -> applyApiTransformToSecondaryPreset(draft, presetId, transform) }
                    current.copy(settings = updatedSettings, settingsDraft = updatedDraft, effortNotice = notice)
                }
            } else if (current.temporaryAi != null) {
                current.copy(
                    temporaryAi = current.temporaryAi.copy(api = transform(current.temporaryAi.api)),
                    effortNotice = notice,
                )
            } else {
                val updatedSettings = applyApiTransformToActivePreset(current.settings, presetId, transform)
                val updatedDraft = current.settingsDraft?.let { draft -> applyApiTransformToActivePreset(draft, presetId, transform) }
                current.copy(settings = updatedSettings, settingsDraft = updatedDraft, effortNotice = notice)
            }
        }
        if (isTemporaryTarget) return
        viewModelScope.launch {
            try {
                val loaded = repository.load()
                val persisted = if (isSecondary) {
                    applyApiTransformToSecondaryPreset(loaded, presetId, transform)
                } else {
                    applyApiTransformToActivePreset(loaded, presetId, transform)
                }
                repository.save(persisted)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { }
        }
    }

    private fun applyApiTransformToSecondaryPreset(
        settings: AppSettings,
        presetId: String?,
        transform: (OpenAIModelConfig) -> OpenAIModelConfig,
    ): AppSettings {
        val targetPreset = presetId?.let { id -> settings.presets.find { it.id == id && it.secondaryAi != null } } ?: return settings
        return settings.copy(presets = settings.presets.map { preset ->
            if (preset.id == targetPreset.id && preset.secondaryAi != null) {
                preset.copy(secondaryAi = preset.secondaryAi.copy(api = transform(preset.secondaryAi.api)))
            } else preset
        })
    }

    private fun applyApiTransformToActivePreset(
        settings: AppSettings,
        presetId: String?,
        transform: (OpenAIModelConfig) -> OpenAIModelConfig,
    ): AppSettings {
        val targetPreset = presetId?.let { id -> settings.presets.find { it.id == id && it.customAi != null } }
        return if (targetPreset != null) {
            settings.copy(presets = settings.presets.map { preset ->
                if (preset.id == targetPreset.id && preset.customAi != null) {
                    preset.copy(customAi = preset.customAi.copy(api = transform(preset.customAi.api)))
                } else preset
            })
        } else {
            settings.copy(api = transform(settings.api))
        }
    }

    private fun applyApiTransformToSettings(
        settings: AppSettings,
        label: String,
        transform: (OpenAIModelConfig) -> OpenAIModelConfig,
    ): AppSettings {
        if (label == "Default AI") return settings.copy(api = transform(settings.api))
        if (label.endsWith(" (2nd AI)")) {
            val presetName = label.removeSuffix(" (2nd AI)")
            val matched = settings.presets.firstOrNull { it.name == presetName && it.secondaryAi != null } ?: return settings
            return applyApiTransformToSecondaryPreset(settings, matched.id, transform)
        }
        val matched = settings.presets.firstOrNull { it.name == label && it.customAi != null } ?: return settings
        return applyApiTransformToActivePreset(settings, matched.id, transform)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 120L
    }
}

internal fun formatEffortAdjustmentNotice(requested: String, applied: String?): String =
    if (applied != null) "Effort \"$requested\" unsupported; switched to \"$applied\""
    else "Effort \"$requested\" unsupported; switched to Default"

internal fun formatServiceTierAdjustmentNotice(requested: String, applied: String?): String =
    if (applied != null) "Service tier \"$requested\" unsupported; switched to \"$applied\""
    else "Service tier \"$requested\" unsupported; switched to Default"

private fun ApiConnection.normalized() = copy(name = name.trim(), baseUrl = baseUrl.trim().trimEnd('/'), apiKey = apiKey.trim())

private fun OpenAIModelConfig.normalized() = copy(baseUrl = baseUrl.trim().trimEnd('/'), apiKey = apiKey.trim(), model = model.trim(),
    reasoningEffort = reasoningEffort?.trim()?.takeIf(String::isNotEmpty),
    serviceTier = serviceTier?.trim()?.takeIf(String::isNotEmpty))

internal fun settingsError(settings: AppSettings): String? {
    if (settings.imageLimit !in 1..10) return "Image limit must be between 1 and 10."
    if (settings.quickAccess.cornerSwipe && settings.quickAccess.cornerGestures.isEmpty()) return "Select at least one corner gesture."
    settings.connections.forEach { connection ->
        if (connection.name.isBlank()) return "Each connection needs a name."
        if (settings.connections.count { it.name == connection.name } > 1) return "Connection names must be different."
        apiError(OpenAIModelConfig().using(connection), requireCredentials = false)?.let { return "${connection.name}: $it" }
    }
    aiError(settings, settings.defaultAi)?.let { return "Default AI: $it" }
    settings.presets.forEach { preset ->
        if (preset.name.isBlank() || preset.prompt.isBlank()) return "Each preset needs a name and prompt."
        preset.customAi?.let { ai -> aiError(settings, ai)?.let { return "${preset.name}: $it" } }
        preset.secondaryAi?.let { ai -> aiError(settings, ai)?.let { return "${preset.name} (2nd AI): $it" } }
    }
    return null
}

private fun aiError(settings: AppSettings, ai: AiConfiguration): String? =
    if (ai.connectionId != null && settings.connection(ai.connectionId) == null) "Select a connection."
    else apiError(settings.resolve(ai).api, requireCredentials = false)

internal fun apiError(api: OpenAIModelConfig, requireCredentials: Boolean): String? {
    if (api.authentication == AuthenticationMethod.ChatGpt) return when {
        api.baseUrl != ChatGptOAuth.Resource || api.protocol != ApiProtocol.Responses || api.backgroundResponses || api.apiKey.isNotEmpty() ->
            "ChatGPT requires the official Responses endpoint without background recovery or an API key."
        requireCredentials && api.chatGptAccountId.isNullOrBlank() -> "Select a ChatGPT account in Settings."
        requireCredentials && api.model.isBlank() -> "Select a ChatGPT model in Settings."
        else -> null
    }
    val uri = try { URI(api.baseUrl) } catch (_: URISyntaxException) { return "Enter a valid API base URL." }
    return when {
        uri.scheme != "https" || uri.host.isNullOrBlank() -> "Use an HTTPS API base URL, including its version path."
        uri.userInfo != null || uri.query != null || uri.fragment != null -> "The base URL must not contain credentials, a query, or a fragment."
        uri.path.trimEnd('/').let { it.endsWith("/responses") || it.endsWith("/chat/completions") || it.endsWith("/interactions") } ->
            "Enter the base URL only; the selected API protocol adds the endpoint path."
        api.protocol == ApiProtocol.Gemini && uri.path.trimEnd('/').endsWith("/openai") ->
            "For the Gemini protocol, use Google's base URL without /openai, such as $GeminiBaseUrl."
        requireCredentials && api.apiKey.isBlank() -> "Enter the API key for this configuration in Settings."
        requireCredentials && api.model.isBlank() -> "Enter a model ID for this configuration in Settings."
        else -> null
    }
}
