package app.touchai.android

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.touchai.core.openai.*
import java.net.URI
import java.net.URISyntaxException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
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

data class OpenAIChatUiState(
    val settings: AppSettings = AppSettings(),
    val ready: Boolean = false,
    val settingsOpen: Boolean = false,
    val logsOpen: Boolean = false,
    val historyOpen: Boolean = false,
    val historyLoading: Boolean = false,
    val historyTransferring: Boolean = false,
    val historyNotice: String? = null,
    val historyEntries: List<ChatHistoryEntry> = emptyList(),
    val chatId: String = UUID.randomUUID().toString(),
    val settingsDraft: AppSettings? = null,
    val savingSettings: Boolean = false,
    val testingConnection: Boolean = false,
    val connectionResult: String? = null,
    val invoking: Boolean = false,
    val originalImage: Bitmap? = null,
    val capturedScreenshot: Bitmap? = null,
    val imageCrop: ImageCrop = ImageCrop.Full,
    val cropping: Boolean = false,
    val preparingImage: Boolean = false,
    val prompt: String = "",
    val image: OpenAIImage? = null,
    val imageAttached: Boolean = false,
    val imagePreview: Bitmap? = null,
    val selectedPreset: String? = null,
    val turns: List<ChatTurn> = emptyList(),
    val error: String? = null,
) {
    val isStreaming: Boolean get() = turns.lastOrNull()?.isRunning == true
    val selectedAi: AiConfiguration get() = settings.aiFor(selectedPreset)
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
    private var requestUpdates: Job? = null
    private var imageJob: Job? = null
    private var captureJob: Job? = null
    private var captureRequested = false
    private var presetSaveJob: Job? = null
    private var historyJob: Job? = null

    init {
        loadSettings()
        viewModelScope.launch { requests.recoveryError.collect { error -> error?.let(::reportError) } }
    }

    fun loadSettings() {
        viewModelScope.launch {
            try {
                presetSaveJob?.join()
                val settings = repository.load()
                update { it.copy(settings = settings, ready = true, error = null,
                    settingsDraft = if (it.settingsOpen && it.settingsDraft == it.settings) settings else it.settingsDraft,
                    selectedPreset = if (!it.ready) rememberedPreset(settings) else it.selectedPreset,
                    prompt = if (!it.ready) presetText(settings, rememberedPreset(settings)) else it.prompt) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not load settings: ${error.message}") }
        }
    }

    fun setPrompt(value: String) = update { it.copy(prompt = value) }
    fun setImage(value: OpenAIImage?) = update { it.copy(image = value, imageAttached = value != null, imagePreview = null, error = null) }
    fun selectPreset(id: String?) {
        update { it.copy(selectedPreset = id, prompt = presetText(it.settings, id), settings = it.settings.copy(lastPresetId = id)) }
        presetSaveJob?.cancel()
        presetSaveJob = viewModelScope.launch {
            try { repository.rememberPreset(id) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not remember preset: ${error.message}") }
        }
    }
    fun reportError(message: String) = update { it.copy(error = message) }
    fun showSettings(open: Boolean) = update { it.copy(settingsOpen = open,
        settingsDraft = if (open) it.settings else null, error = null, connectionResult = null) }
    fun showLogs(open: Boolean) = update { it.copy(logsOpen = open) }
    fun showHistory(open: Boolean) {
        if (_uiState.value.historyTransferring) return
        historyJob?.cancel()
        update { it.copy(historyOpen = open, historyLoading = open, historyNotice = null, error = null) }
        if (open) historyJob = viewModelScope.launch {
            try {
                val entries = (history.list() + requests.active.value.values.map { it.state.value.entry })
                    .associateBy { it.id }.values.sortedByDescending { it.updatedAt }
                update { it.copy(historyEntries = entries) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not load chat history: ${error.message}") }
            finally { if (currentCoroutineContext().isActive) update { it.copy(historyLoading = false) } }
        }
    }

    fun exportHistory(openOutput: () -> OutputStream) = transferHistory("Exported") {
        ChatHistoryArchive(history).exportTo(openOutput())
    }

    fun importHistory(openInput: () -> InputStream) = transferHistory("Imported") {
        ChatHistoryArchive(history).importFrom(openInput()) { image -> ImageProcessor.decode(image, 64).recycle() }
    }

    private fun transferHistory(action: String, transfer: suspend () -> Int) {
        if (_uiState.value.historyLoading || _uiState.value.isStreaming) return
        update { it.copy(historyLoading = true, historyTransferring = true, historyNotice = null, error = null) }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { transfer() }
                val entries = history.list()
                update { it.copy(historyEntries = entries, historyNotice = "$action $count ${if (count == 1) "chat" else "chats"}") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                reportError("${if (action == "Imported") "Import" else "Export"} failed: ${error.message}")
            } finally { update { it.copy(historyLoading = false, historyTransferring = false) } }
        }
    }

    fun openChat(id: String) {
        if (_uiState.value.historyLoading) return
        update { it.copy(historyLoading = true, error = null) }
        historyJob = viewModelScope.launch {
            try {
                requests.restorePending()
                val active = requests.find(id)
                val chat = active?.state?.value ?: history.load(id)
                val settings = repository.load()
                val selected = chat.selectedPreset?.takeIf { id -> settings.presets.any { it.id == id } }
                imageJob?.cancel()
                update { it.copy(chatId = chat.id, turns = chat.turns, settings = settings, ready = true,
                    selectedPreset = selected, prompt = "", image = null, imageAttached = false, imagePreview = null, originalImage = null, capturedScreenshot = null,
                    imageCrop = ImageCrop.Full, cropping = false, preparingImage = false, historyOpen = false) }
                requestUpdates?.cancel()
                active?.let(::observeRequest)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("Could not open chat: ${error.message}") }
            finally { if (currentCoroutineContext().isActive) update { it.copy(historyLoading = false) } }
        }
    }

    fun deleteChat(id: String) {
        if (_uiState.value.historyLoading) return
        if (requests.find(id) != null) { reportError("Stop the request before deleting this chat."); return }
        update { it.copy(historyLoading = true, error = null) }
        historyJob = viewModelScope.launch {
            try {
                history.delete(id)
                update { it.copy(historyEntries = it.historyEntries.filterNot { entry -> entry.id == id },
                    chatId = if (it.chatId == id) UUID.randomUUID().toString() else it.chatId,
                    turns = if (it.chatId == id) emptyList() else it.turns) }
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
            requestUpdates?.cancel()
            imageJob?.cancelAndJoin()
            update { it.copy(chatId = UUID.randomUUID().toString(), turns = emptyList(), image = null, imageAttached = false, imagePreview = null, originalImage = null, capturedScreenshot = null,
                cropping = false, preparingImage = false, error = null) }
            try {
                val bitmap = capture()
                presetSaveJob?.join()
                val settings = repository.load()
                update { it.copy(settings = settings, ready = true, selectedPreset = rememberedPreset(settings),
                    prompt = presetText(settings, rememberedPreset(settings)), invoking = false, capturedScreenshot = bitmap) }
                attachImage(bitmap, attached = settings.quickAccess.attachScreenshotAutomatically)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { update { it.copy(invoking = false, error = error.message ?: "Screen capture failed. Continue without an image.") } }
        }
    }

    fun attachImage(bitmap: Bitmap, attached: Boolean = true) {
        imageJob?.cancel()
        update { it.copy(originalImage = bitmap, imagePreview = bitmap, image = null, imageAttached = attached, imageCrop = ImageCrop.Full,
            cropping = false, error = null) }
        prepareImage(bitmap, ImageCrop.Full)
    }

    fun attachScreenshot() { _uiState.value.capturedScreenshot?.let { attachImage(it) } }

    fun toggleImageAttachment() = update { it.copy(imageAttached = !it.imageAttached, error = null) }

    fun openCrop() { update { it.copy(cropping = it.originalImage != null) } }
    fun closeCrop() { update { it.copy(cropping = false) } }

    fun applyCrop(crop: ImageCrop) {
        val original = _uiState.value.originalImage ?: return
        update { it.copy(cropping = false, imageCrop = crop) }
        prepareImage(original, crop)
    }

    private fun prepareImage(original: Bitmap, crop: ImageCrop) {
        imageJob?.cancel()
        val quality = _uiState.value.settings.imageQuality
        update { it.copy(preparingImage = true, image = null, error = null) }
        imageJob = viewModelScope.launch {
            try {
                val prepared = ImageProcessor.prepare(original, crop, quality)
                update { it.copy(image = prepared.input, imagePreview = prepared.preview, preparingImage = false) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                update { it.copy(preparingImage = false, error = "Could not prepare image: ${error.message}") }
            }
        }
    }

    fun removeImage() {
        update { it.copy(imageAttached = false, cropping = false, error = null) }
    }

    fun saveSettings(settings: AppSettings) {
        if (_uiState.value.isStreaming || _uiState.value.savingSettings) return
        val normalized = settings.copy(
            api = settings.api.normalized(),
            presets = settings.presets.map { preset -> preset.copy(name = preset.name.trim(),
                customAi = preset.customAi?.let { it.copy(api = it.api.normalized()) }) },
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
                update { current ->
                    val selected = current.selectedPreset?.takeIf { id -> saved.presets.any { it.id == id } }
                    current.copy(settings = saved, ready = true, settingsOpen = false, settingsDraft = null,
                        turns = if (saved == current.settings) current.turns else emptyList(), selectedPreset = selected,
                        chatId = if (saved == current.settings) current.chatId else UUID.randomUUID().toString(),
                        prompt = if (current.prompt == presetText(current.settings, current.selectedPreset)) presetText(saved, selected) else current.prompt)
                }
                if (saved.imageQuality != before.settings.imageQuality) {
                    before.originalImage?.let { prepareImage(it, before.imageCrop) }
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
                client.stream(ai.api, OpenAIRequest(listOf(ChatMessage(MessageRole.User, "Reply with OK only. This is a connection test.")), ai.instructions, RequestPurpose.ConnectionTest, label)).collect { }
                update { it.copy(connectionResult = "$label: connection and streaming succeeded.") }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { reportError("$label: ${error.message}") }
            finally { update { it.copy(testingConnection = false) } }
        }
    }

    fun submit() {
        val snapshot = _uiState.value
        if (!snapshot.ready || snapshot.isStreaming || snapshot.savingSettings || snapshot.cropping ||
            (snapshot.imageAttached && (snapshot.preparingImage || (snapshot.originalImage != null && snapshot.image == null)))) return
        val ai = snapshot.selectedAi
        val error = apiError(ai.api, requireCredentials = true)
        if (error != null) { update { it.copy(settingsOpen = true, settingsDraft = it.settings, error = error) }; return }
        val preset = snapshot.settings.presets.find { it.id == snapshot.selectedPreset }
        val prompt = snapshot.prompt.trim()
        val image = snapshot.image.takeIf { snapshot.imageAttached }
        if (prompt.isBlank() && image == null) { reportError("Enter a message, choose a preset, or attach an image."); return }
        val user = ChatMessage(MessageRole.User, prompt, listOfNotNull(image))
        imageJob?.cancel()
        update { it.copy(prompt = "", image = null, imageAttached = false, imagePreview = null, originalImage = null, capturedScreenshot = null, imageCrop = ImageCrop.Full, error = null,
            preparingImage = false, turns = it.turns + ChatTurn(user, ai, preset?.name)) }
        startRequest()
    }

    fun retry() {
        val snapshot = _uiState.value
        if (snapshot.isStreaming || snapshot.turns.isEmpty() || snapshot.savingSettings) return
        val original = snapshot.turns.last().ai.api
        val configurations = listOf(snapshot.settings.api) + snapshot.settings.presets.mapNotNull { it.customAi?.api }
        val api = if (original.authentication == AuthenticationMethod.ChatGpt) original.copy(
            chatGptAccountId = original.chatGptAccountId ?: configurations.firstOrNull {
                it.authentication == AuthenticationMethod.ChatGpt && it.chatGptAccountId != null
            }?.chatGptAccountId,
        ) else if (original.apiKey.isNotBlank()) original else original.copy(apiKey = configurations.firstOrNull {
            it.authentication == AuthenticationMethod.ApiKey && it.apiKey.isNotBlank() &&
                it.baseUrl.trimEnd('/') == original.baseUrl.trimEnd('/') && it.protocol == original.protocol
        }?.apiKey.orEmpty())
        val error = apiError(api, requireCredentials = true)
        if (error != null) { reportError(error); return }
        val resume = snapshot.turns.last().status == TurnStatus.Interrupted && snapshot.turns.last().responseId != null && api.backgroundResponses
        if (resume) {
            updateLast { it.copy(ai = it.ai.copy(api = api), status = TurnStatus.Recovering, error = null, cancelRequested = false) }
            startRequest(RequestPurpose.Resume)
        } else {
            updateLast { it.copy(ai = it.ai.copy(api = api), answer = "", status = TurnStatus.Streaming, error = null, searchStatus = null,
                citations = emptyList(), generatedImages = emptyList(), imageGenerationStatus = null, responseId = null, cancelRequested = false) }
            startRequest(RequestPurpose.Retry)
        }
    }

    private fun startRequest(purpose: RequestPurpose = RequestPurpose.Chat) {
        val snapshot = _uiState.value
        try {
            observeRequest(requests.start(SavedChat(snapshot.chatId, System.currentTimeMillis(), snapshot.selectedPreset, snapshot.turns), purpose))
        } catch (error: Exception) {
            updateLast { it.copy(status = TurnStatus.Failed, error = "Could not start background work: ${error.message}") }
        }
    }

    private fun observeRequest(handle: ActiveChatRequest) {
        requestUpdates?.cancel()
        requestUpdates = viewModelScope.launch {
            handle.state.collect { saved ->
                update { if (it.chatId == saved.id) it.copy(turns = saved.turns) else it }
            }
        }
    }

    fun cancel() { requests.stop(_uiState.value.chatId) }
    fun newChat() {
        if (_uiState.value.preparingImage || _uiState.value.historyLoading) return
        requestUpdates?.cancel()
        showHistory(false)
        update { it.copy(chatId = UUID.randomUUID().toString(), ready = false, turns = emptyList(), prompt = "", image = null, imageAttached = false, imagePreview = null,
            originalImage = null, capturedScreenshot = null, imageCrop = ImageCrop.Full, cropping = false, error = null) }
        loadSettings()
    }

    private fun updateLast(transform: (ChatTurn) -> ChatTurn) = update { it.copy(turns = it.turns.dropLast(1) + transform(it.turns.last())) }
    private fun update(transform: (OpenAIChatUiState) -> OpenAIChatUiState) { _uiState.update(transform) }
    private fun rememberedPreset(settings: AppSettings) = settings.lastPresetId?.takeIf { id -> settings.presets.any { it.id == id } }
    private fun presetText(settings: AppSettings, id: String?) = settings.presets.find { it.id == id }?.prompt.orEmpty()
}

private fun OpenAIModelConfig.normalized() = copy(baseUrl = baseUrl.trim().trimEnd('/'), apiKey = apiKey.trim(), model = model.trim(),
    reasoningEffort = reasoningEffort?.trim()?.takeIf(String::isNotEmpty))

internal fun settingsError(settings: AppSettings): String? {
    apiError(settings.api, requireCredentials = false)?.let { return "Default AI: $it" }
    settings.presets.forEach { preset ->
        if (preset.name.isBlank() || preset.prompt.isBlank()) return "Each preset needs a name and prompt."
        preset.customAi?.let { ai -> apiError(ai.api, requireCredentials = false)?.let { return "${preset.name}: $it" } }
    }
    return null
}

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
        uri.path.endsWith("/responses") || uri.path.endsWith("/chat/completions") -> "Enter the base URL only; the selected API protocol adds the endpoint path."
        requireCredentials && api.apiKey.isBlank() -> "Enter the API key for this configuration in Settings."
        requireCredentials && api.model.isBlank() -> "Enter a model ID for this configuration in Settings."
        else -> null
    }
}
