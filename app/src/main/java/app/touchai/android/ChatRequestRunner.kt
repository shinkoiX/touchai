package app.touchai.android

import app.touchai.core.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ActiveChatRequest internal constructor(chat: SavedChat) {
    internal val mutableState = MutableStateFlow(chat)
    val state = mutableState.asStateFlow()
    val chatId: String = chat.id
    internal lateinit var job: Job
    internal var stopJob: Job? = null
    internal var creationStarted = false
    internal var flush: () -> Unit = {}
    internal val saveMutex = Mutex()
}

/** Owns network work independently of activities and their view models. */
class ChatRequestRunner(
    private val history: ChatHistoryRepository,
    private val client: ChatClient,
    private val cancelResponse: suspend (OpenAIModelConfig, String) -> JsonObject,
    private val scope: CoroutineScope,
    private val onWorkStarted: () -> Unit,
    private val onIdle: () -> Unit,
) {
    private val mutableActive = MutableStateFlow<Map<String, ActiveChatRequest>>(emptyMap())
    val active = mutableActive.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val recoveryError = mutableError.asStateFlow()
    private val restoreMutex = Mutex()

    fun find(chatId: String): ActiveChatRequest? = active.value[chatId]

    fun start(chat: SavedChat, purpose: RequestPurpose = RequestPurpose.Chat): ActiveChatRequest {
        find(chat.id)?.let { return it }
        if (active.value.isEmpty()) onWorkStarted()
        val handle = ActiveChatRequest(chat)
        mutableActive.value = active.value + (chat.id to handle)
        handle.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            handle.job = currentCoroutineContext().job
            run(handle, purpose)
        }
        if (chat.turns.last().cancelRequested) stop(chat.id)
        return handle
    }

    suspend fun restorePending() = restoreMutex.withLock {
        try {
            for (entry in history.list().filter { it.pending }) {
                if (find(entry.id) != null) continue
                val saved = history.load(entry.id)
                val turn = saved.turns.last()
                if (turn.responseId != null && turn.ai.api.backgroundResponses && turn.ai.api.protocol == ApiProtocol.Responses) {
                    start(saved.copy(turns = saved.turns.dropLast(1) + turn.copy(
                        status = if (turn.cancelRequested) TurnStatus.Cancelling else TurnStatus.Recovering,
                        error = null)), RequestPurpose.Resume)
                } else {
                    history.save(saved.copy(turns = saved.turns.dropLast(1) + turn.copy(status = TurnStatus.Interrupted,
                        error = "This interrupted request has no recoverable response ID. Retry starts a new request.")))
                }
            }
            mutableError.value = null
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableError.value = "Could not restore pending requests: ${error.message}" }
    }

    private suspend fun run(handle: ActiveChatRequest, purpose: RequestPurpose) = coroutineScope {
        val initial = handle.state.value
        val turn = initial.turns.last()
        val buffer = StreamingTextBuffer(this) { delta -> update(handle) { it.copy(answer = it.answer + delta) } }
        handle.flush = buffer::flush
        var status = TurnStatus.Completed
        var errorMessage: String? = null
        var lastSave = System.nanoTime()
        try {
            save(handle)
            if (handle.state.value.turns.last().cancelRequested && !handle.creationStarted && turn.responseId == null)
                throw UserRequestedCancellation()
            val messages = initial.turns.dropLast(1).filter { it.status == TurnStatus.Completed }.flatMap {
                buildList {
                    add(it.user)
                    if (it.answer.isNotEmpty()) add(ChatMessage(MessageRole.Assistant, it.answer))
                    if (it.generatedImages.isNotEmpty()) add(ChatMessage(MessageRole.User, "", it.generatedImages.map { output -> output.image }))
                }
            } + turn.user
            handle.creationStarted = true
            client.stream(turn.ai.api, OpenAIRequest(messages, turn.ai.instructions, purpose, turn.presetName,
                resumeResponseId = if (purpose == RequestPurpose.Resume) turn.responseId else null)).collect { event ->
                when (event) {
                    is OpenAIStreamEvent.TextDelta -> buffer.append(event.text)
                    is OpenAIStreamEvent.TextSnapshot -> { buffer.clear(); update(handle) { it.copy(answer = event.text) } }
                    is OpenAIStreamEvent.Completed -> buffer.flush()
                    is OpenAIStreamEvent.SearchStatus -> update(handle) { it.copy(searchStatus = event.status) }
                    is OpenAIStreamEvent.Citation -> update(handle) { it.copy(citations = (it.citations + event.source).distinct()) }
                    is OpenAIStreamEvent.ImageGenerated -> update(handle) { it.copy(generatedImages = (it.generatedImages + event.value).distinctBy { image -> image.id }) }
                    is OpenAIStreamEvent.ImageGenerationStatus -> update(handle) { it.copy(imageGenerationStatus = event.status) }
                    is OpenAIStreamEvent.ResponseCheckpoint -> update(handle) { it.copy(responseId = event.id) }
                    is OpenAIStreamEvent.RecoveryStatus -> update(handle) { it.copy(
                        status = if (it.cancelRequested) TurnStatus.Cancelling else if (event.status != null) TurnStatus.Recovering else TurnStatus.Streaming,
                        recoveryStatus = event.status) }
                    is OpenAIStreamEvent.ResponsePayload -> Unit
                }
                val now = System.nanoTime()
                if (event is OpenAIStreamEvent.ResponseCheckpoint || event is OpenAIStreamEvent.ImageGenerated || now - lastSave >= 1_000_000_000L) {
                    buffer.flush(); save(handle); lastSave = now
                }
            }
        } catch (error: UserRequestedCancellation) {
            buffer.flush()
            error.response?.let { applyResponse(handle, it) }
            status = if (error.response?.get("status")?.jsonPrimitive?.content == "completed") TurnStatus.Completed else TurnStatus.Stopped
        } catch (error: CancellationException) {
            buffer.flush()
            status = if (handle.state.value.turns.last().responseId != null && turn.ai.api.backgroundResponses) TurnStatus.Recovering else TurnStatus.Interrupted
            errorMessage = "Request interrupted on this device. It was not cancelled on the provider."
        } catch (error: ResponseCancelledException) {
            buffer.flush(); applyResponse(handle, error.response)
            status = if (handle.state.value.turns.last().cancelRequested) TurnStatus.Stopped else TurnStatus.Interrupted
            errorMessage = if (status == TurnStatus.Stopped) null else error.message
        } catch (error: ResponseInterruptedException) {
            status = TurnStatus.Interrupted; errorMessage = error.message
        } catch (error: IncompleteResponseException) {
            status = TurnStatus.Incomplete; errorMessage = error.message
        } catch (error: Exception) {
            status = TurnStatus.Failed; errorMessage = error.message ?: "Request failed."
        } finally {
            buffer.flush()
            handle.stopJob?.cancel()
            withContext(NonCancellable) {
                handle.saveMutex.withLock {
                    val saved = handle.state.value
                    val finished = saved.copy(updatedAt = System.currentTimeMillis(), turns = saved.turns.dropLast(1) +
                        saved.turns.last().copy(status = status, error = errorMessage, imageGenerationStatus = null, recoveryStatus = null))
                    try { history.save(finished) }
                    catch (error: Exception) { mutableError.value = "Could not save chat: ${error.message}" }
                    handle.mutableState.value = finished
                }
            }
            mutableActive.value = active.value - handle.chatId
            if (active.value.isEmpty()) onIdle()
        }
    }

    fun stop(chatId: String) {
        val handle = find(chatId) ?: return
        if (handle.stopJob?.isActive == true) return
        update(handle) { it.copy(cancelRequested = true, status = TurnStatus.Cancelling, recoveryStatus = "Stopping…") }
        val turn = handle.state.value.turns.last()
        if (turn.ai.api.protocol != ApiProtocol.Responses || !turn.ai.api.backgroundResponses || (!handle.creationStarted && turn.responseId == null)) {
            handle.job.cancel(UserRequestedCancellation())
            return
        }
        handle.stopJob = scope.launch {
            save(handle)
            val state = handle.state.first { it.turns.last().responseId != null || !it.turns.last().isRunning }
            val id = state.turns.last().responseId ?: return@launch
            var retryMillis = 1_000L
            while (handle.job.isActive) {
                try {
                    val result = cancelResponse(turn.ai.api, id)
                    if (result["status"]?.jsonPrimitive?.content !in listOf("completed", "cancelled"))
                        throw OpenAIStreamException("The provider did not confirm cancellation.")
                    handle.flush()
                    handle.job.cancel(UserRequestedCancellation(result))
                    return@launch
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (!isTransientResponseError(error)) {
                        update(handle) { it.copy(error = "Cancellation was not confirmed: ${error.message}", recoveryStatus = "Cancellation not confirmed") }
                        save(handle)
                        return@launch
                    }
                    update(handle) { it.copy(recoveryStatus = "Waiting to cancel…") }
                    delay(retryMillis)
                    retryMillis = (retryMillis * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    fun stopAll() { active.value.keys.toList().forEach(::stop) }
    fun pauseForSystem() { active.value.values.toList().forEach { it.job.cancel(CancellationException("Android paused background work")) } }

    private fun applyResponse(handle: ActiveChatRequest, response: JsonObject) {
        val output = readResponseOutput(response)
        update(handle) { it.copy(
            answer = if (output.text.isNotEmpty() || response["status"]?.jsonPrimitive?.content == "completed") output.text else it.answer,
            generatedImages = (it.generatedImages + output.images).distinctBy { image -> image.id },
            citations = (it.citations + output.citations).distinct()) }
    }

    private fun update(handle: ActiveChatRequest, transform: (ChatTurn) -> ChatTurn) {
        handle.mutableState.update { it.copy(updatedAt = System.currentTimeMillis(), turns = it.turns.dropLast(1) + transform(it.turns.last())) }
    }

    private suspend fun save(handle: ActiveChatRequest) = handle.saveMutex.withLock {
        try { history.save(handle.state.value) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableError.value = "Could not save request progress: ${error.message}" }
    }
}
