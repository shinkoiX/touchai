package app.touchai.android

import app.touchai.core.openai.*
import app.touchai.core.openui.OpenUiAssets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

data class EffortAdjustmentNotice(
    val chatId: String,
    val presetId: String?,
    val requested: String,
    val applied: String?,
    val isSecondary: Boolean = false,
)

data class ServiceTierAdjustmentNotice(
    val chatId: String,
    val presetId: String?,
    val requested: String,
    val applied: String?,
    val isSecondary: Boolean = false,
)

internal class NodeExecution(val nodeId: String?, val isSecondary: Boolean) {
    @Volatile var job: Job? = null
    @Volatile var stopJob: Job? = null
    @Volatile var creationStarted: Boolean = false
    @Volatile var flush: () -> Unit = {}
}

class ActiveChatRequest internal constructor(chat: SavedChat) {
    internal val mutableState = MutableStateFlow(chat)
    val state = mutableState.asStateFlow()
    val chatId: String = chat.id
    internal lateinit var job: Job
    internal val executions = ConcurrentHashMap<String, NodeExecution>()
    internal val saveMutex = Mutex()
}

private const val LINEAR_EXECUTION_KEY = "__linear__"

/** Owns network work independently of activities and their view models. */
class ChatRequestRunner(
    private val history: ChatHistoryRepository,
    private val client: ChatClient,
    private val cancelResponse: suspend (OpenAIModelConfig, String) -> JsonObject,
    private val scope: CoroutineScope,
    private val onWorkStarted: () -> Unit,
    private val onIdle: () -> Unit,
    private val openUiPromptProvider: suspend () -> String? = { null },
) {
    private val mutableActive = MutableStateFlow<Map<String, ActiveChatRequest>>(emptyMap())
    val active = mutableActive.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val recoveryError = mutableError.asStateFlow()
    private val mutableEffortAdjustments = MutableSharedFlow<EffortAdjustmentNotice>(extraBufferCapacity = 8)
    val effortAdjustments = mutableEffortAdjustments.asSharedFlow()
    private val mutableServiceTierAdjustments = MutableSharedFlow<ServiceTierAdjustmentNotice>(extraBufferCapacity = 8)
    val serviceTierAdjustments = mutableServiceTierAdjustments.asSharedFlow()
    private val restoreMutex = Mutex()

    fun find(chatId: String): ActiveChatRequest? = active.value[chatId]

    fun start(
        chat: SavedChat,
        purpose: RequestPurpose = RequestPurpose.Chat,
        targetNodeIds: List<String>? = null,
    ): ActiveChatRequest {
        find(chat.id)?.let { return it }
        if (active.value.isEmpty()) onWorkStarted()
        val handle = ActiveChatRequest(chat)
        val targets: List<String?> = when {
            !targetNodeIds.isNullOrEmpty() -> targetNodeIds
            chat.tree != null -> {
                val running = chat.tree.runningNodes().map { it.id }
                running.ifEmpty { listOfNotNull(chat.tree.activePath().lastOrNull()?.id) }.ifEmpty { listOf(null) }
            }
            else -> listOf(null)
        }
        targets.forEachIndexed { index, nodeId ->
            val key = nodeId ?: LINEAR_EXECUTION_KEY
            handle.executions[key] = NodeExecution(nodeId, isSecondary = chat.pairSlot == 1 || (targets.size > 1 && index > 0))
        }
        mutableActive.value = active.value + (chat.id to handle)
        handle.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            handle.job = currentCoroutineContext().job
            try {
                coroutineScope {
                    targets.mapIndexed { index, nodeId ->
                        val execution = handle.executions.getValue(nodeId ?: LINEAR_EXECUTION_KEY)
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            execution.job = currentCoroutineContext().job
                            if (index == 0) save(handle)
                            runNode(handle, execution, purpose)
                        }
                    }.joinAll()
                }
            } finally {
                mutableActive.value = active.value - handle.chatId
                if (active.value.isEmpty()) onIdle()
            }
        }
        targets.forEach { nodeId ->
            if (turnFor(chat, nodeId)?.cancelRequested == true) {
                if (nodeId != null) stopNode(chat.id, nodeId) else stop(chat.id)
            }
        }
        return handle
    }

    fun selectNode(chatId: String, nodeId: String) {
        val handle = find(chatId) ?: return
        handle.mutableState.update { it.selectBranch(nodeId) }
    }

    suspend fun restorePending() = restoreMutex.withLock {
        try {
            for (entry in history.list().filter { it.pending }) {
                if (find(entry.id) != null) continue
                val saved = history.load(entry.id)
                val tree = saved.tree
                if (tree != null) {
                    val runningNodes = tree.runningNodes()
                    val recoverableIds = mutableListOf<String>()
                    var updated = saved
                    for (node in runningNodes) {
                        val turn = node.turn
                        if (turn.responseId != null && turn.ai.api.recoverResponses) {
                            recoverableIds += node.id
                            updated = updated.updateNode(node.id, updated.updatedAt) {
                                it.copy(
                                    status = if (it.cancelRequested) TurnStatus.Cancelling else TurnStatus.Recovering,
                                    error = null,
                                )
                            }
                        } else {
                            updated = updated.updateNode(node.id, updated.updatedAt) {
                                it.copy(
                                    status = TurnStatus.Interrupted,
                                    error = "This interrupted request has no recoverable response ID. Retry starts a new request.",
                                )
                            }
                        }
                    }
                    if (recoverableIds.isNotEmpty()) {
                        start(updated, RequestPurpose.Resume, targetNodeIds = recoverableIds)
                    } else {
                        history.save(updated)
                    }
                } else {
                    val turn = saved.turns.last()
                    if (turn.responseId != null && turn.ai.api.recoverResponses) {
                        start(saved.updateActiveLeaf(saved.updatedAt) {
                            it.copy(
                                status = if (it.cancelRequested) TurnStatus.Cancelling else TurnStatus.Recovering,
                                error = null,
                            )
                        }, RequestPurpose.Resume)
                    } else {
                        history.save(saved.updateActiveLeaf(saved.updatedAt) {
                            it.copy(
                                status = TurnStatus.Interrupted,
                                error = "This interrupted request has no recoverable response ID. Retry starts a new request.",
                            )
                        })
                    }
                }
            }
            mutableError.value = null
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableError.value = "Could not restore pending requests: ${error.message}" }
    }

    private suspend fun runNode(handle: ActiveChatRequest, execution: NodeExecution, purpose: RequestPurpose) = coroutineScope {
        val nodeId = execution.nodeId
        val initial = handle.state.value
        val turn = turnFor(initial, nodeId) ?: return@coroutineScope
        val reasoningBuffer = StreamingTextBuffer(this) { delta ->
            update(handle, nodeId) { it.copy(reasoning = it.reasoning + delta, isThinking = it.answer.isEmpty() && it.generatedImages.isEmpty()) }
        }
        val buffer = StreamingTextBuffer(this) { delta ->
            update(handle, nodeId) { it.copy(answer = it.answer + delta, isThinking = false) }
        }
        execution.flush = { reasoningBuffer.flush(); buffer.flush() }
        var status = TurnStatus.Completed
        var errorMessage: String? = null
        var lastSave = System.nanoTime()
        try {
            if (turnFor(handle.state.value, nodeId)?.cancelRequested == true && !execution.creationStarted && turn.responseId == null)
                throw UserRequestedCancellation()
            val priorTurns = if (nodeId != null && initial.tree != null) {
                initial.tree.pathTo(nodeId).dropLast(1).map { it.turn }
            } else {
                initial.turns.dropLast(1)
            }
            val messages = priorTurns.filter { it.status == TurnStatus.Completed }.flatMap {
                buildList {
                    add(it.user)
                    if (it.answer.isNotEmpty()) add(ChatMessage(MessageRole.Assistant, it.answer))
                    if (it.generatedImages.isNotEmpty()) add(ChatMessage(MessageRole.User, "", it.generatedImages.map { output -> output.image }))
                }
            } + turn.user
            execution.creationStarted = true
            val openUiPrompt = openUiPromptProvider()
            val effectiveInstructions = if (openUiPrompt != null) {
                OpenUiAssets.mergeInstructions(
                    userInstructions = turn.ai.instructions,
                    openUiSystemPrompt = openUiPrompt,
                    enabled = true,
                    webSearchEnabled = turn.ai.api.webSearch,
                )
            } else {
                turn.ai.instructions
            }
            client.stream(turn.ai.api, OpenAIRequest(messages, effectiveInstructions, purpose, turn.presetName,
                resumeResponseId = if (purpose == RequestPurpose.Resume) turn.responseId else null)).collect { event ->
                when (event) {
                    is OpenAIStreamEvent.ThinkingStarted -> update(handle, nodeId) {
                        if (it.answer.isEmpty() && it.generatedImages.isEmpty()) it.copy(isThinking = true) else it
                    }
                    is OpenAIStreamEvent.ReasoningDelta -> {
                        val immediate = turnFor(handle.state.value, nodeId)?.reasoning.isNullOrEmpty()
                        reasoningBuffer.append(event.text)
                        if (immediate) reasoningBuffer.flush()
                    }
                    is OpenAIStreamEvent.ReasoningSnapshot -> {
                        reasoningBuffer.clear()
                        update(handle, nodeId) { it.copy(reasoning = event.text) }
                    }
                    is OpenAIStreamEvent.TextDelta -> {
                        reasoningBuffer.flush()
                        if (turnFor(handle.state.value, nodeId)?.isThinking == true) update(handle, nodeId) { it.copy(isThinking = false) }
                        buffer.append(event.text)
                    }
                    is OpenAIStreamEvent.TextSnapshot -> {
                        reasoningBuffer.flush(); buffer.clear()
                        update(handle, nodeId) { it.copy(answer = event.text, isThinking = false) }
                    }
                    is OpenAIStreamEvent.Completed -> { reasoningBuffer.flush(); buffer.flush() }
                    is OpenAIStreamEvent.SearchStatus -> update(handle, nodeId) { it.copy(searchStatus = event.status) }
                    is OpenAIStreamEvent.Citation -> update(handle, nodeId) { it.copy(citations = (it.citations + event.source).distinct()) }
                    is OpenAIStreamEvent.ImageGenerated -> {
                        reasoningBuffer.flush()
                        update(handle, nodeId) { it.copy(generatedImages = (it.generatedImages + event.value).distinctBy { image -> image.id }, isThinking = false) }
                    }
                    is OpenAIStreamEvent.ImageGenerationStatus -> {
                        reasoningBuffer.flush()
                        update(handle, nodeId) { it.copy(imageGenerationStatus = event.status, isThinking = false) }
                    }
                    is OpenAIStreamEvent.ResponseCheckpoint -> update(handle, nodeId) { it.copy(responseId = event.id) }
                    is OpenAIStreamEvent.RecoveryStatus -> update(handle, nodeId) { it.copy(
                        status = if (it.cancelRequested) TurnStatus.Cancelling else if (event.status != null) TurnStatus.Recovering else TurnStatus.Streaming,
                        recoveryStatus = event.status) }
                    is OpenAIStreamEvent.EffortAdjusted -> {
                        update(handle, nodeId) { it.copy(ai = it.ai.copy(api = it.ai.api.copy(reasoningEffort = event.applied))) }
                        mutableEffortAdjustments.tryEmit(
                            EffortAdjustmentNotice(handle.chatId, initial.selectedPreset, event.requested, event.applied, execution.isSecondary),
                        )
                    }
                    is OpenAIStreamEvent.ServiceTierAdjusted -> {
                        update(handle, nodeId) { it.copy(ai = it.ai.copy(api = it.ai.api.copy(serviceTier = event.applied))) }
                        mutableServiceTierAdjustments.tryEmit(
                            ServiceTierAdjustmentNotice(handle.chatId, initial.selectedPreset, event.requested, event.applied, execution.isSecondary),
                        )
                    }
                    is OpenAIStreamEvent.ResponsePayload -> Unit
                }
                val now = System.nanoTime()
                if (event is OpenAIStreamEvent.ResponseCheckpoint || event is OpenAIStreamEvent.ImageGenerated || now - lastSave >= 1_000_000_000L) {
                    reasoningBuffer.flush(); buffer.flush(); save(handle); lastSave = now
                }
            }
        } catch (error: UserRequestedCancellation) {
            reasoningBuffer.flush(); buffer.flush()
            error.response?.let { applyResponse(handle, nodeId, it) }
            status = if (error.response?.get("status")?.jsonPrimitive?.content == "completed") TurnStatus.Completed else TurnStatus.Stopped
        } catch (error: CancellationException) {
            reasoningBuffer.flush(); buffer.flush()
            status = if (turnFor(handle.state.value, nodeId)?.responseId != null && turn.ai.api.recoverResponses) TurnStatus.Recovering else TurnStatus.Interrupted
            errorMessage = "Request interrupted on this device. It was not cancelled on the provider."
        } catch (error: ResponseCancelledException) {
            reasoningBuffer.flush(); buffer.flush(); applyResponse(handle, nodeId, error.response)
            status = if (turnFor(handle.state.value, nodeId)?.cancelRequested == true) TurnStatus.Stopped else TurnStatus.Interrupted
            errorMessage = if (status == TurnStatus.Stopped) null else error.message
        } catch (error: ResponseInterruptedException) {
            status = TurnStatus.Interrupted; errorMessage = error.message
        } catch (error: IncompleteResponseException) {
            status = TurnStatus.Incomplete; errorMessage = error.message
        } catch (error: Exception) {
            status = TurnStatus.Failed; errorMessage = error.message ?: "Request failed."
        } finally {
            reasoningBuffer.flush(); buffer.flush()
            execution.stopJob?.cancel()
            withContext(NonCancellable) {
                handle.saveMutex.withLock {
                    val finished = handle.mutableState.updateAndGet { saved ->
                        saved.updateNode(nodeId, System.currentTimeMillis()) {
                            it.copy(status = status, isThinking = false, error = errorMessage, imageGenerationStatus = null, recoveryStatus = null)
                        }
                    }
                    try { history.save(finished) }
                    catch (error: Exception) { mutableError.value = "Could not save chat: ${error.message}" }
                }
            }
        }
    }

    fun stop(chatId: String) {
        val handle = find(chatId) ?: return
        handle.executions.values.toList().forEach { execution ->
            stopExecution(handle, execution)
        }
    }

    fun stopNode(chatId: String, nodeId: String) {
        val handle = find(chatId) ?: return
        val execution = handle.executions[nodeId] ?: return
        stopExecution(handle, execution)
    }

    private fun stopExecution(handle: ActiveChatRequest, execution: NodeExecution) {
        val nodeId = execution.nodeId
        val currentTurn = turnFor(handle.state.value, nodeId) ?: return
        if (!currentTurn.isRunning) return
        if (execution.stopJob?.isActive == true) return
        update(handle, nodeId) { it.copy(cancelRequested = true, status = TurnStatus.Cancelling, recoveryStatus = "Stopping…") }
        val turn = turnFor(handle.state.value, nodeId) ?: return
        if (!turn.ai.api.recoverResponses || (!execution.creationStarted && turn.responseId == null)) {
            execution.job?.cancel(UserRequestedCancellation())
            return
        }
        execution.stopJob = scope.launch {
            save(handle)
            val state = handle.state.first {
                val t = turnFor(it, nodeId)
                t == null || t.responseId != null || !t.isRunning
            }
            val id = turnFor(state, nodeId)?.responseId ?: return@launch
            var retryMillis = 1_000L
            while (execution.job?.isActive == true) {
                try {
                    val result = cancelResponse(turn.ai.api, id)
                    if (result["status"]?.jsonPrimitive?.content !in listOf("completed", "cancelled"))
                        throw OpenAIStreamException("The provider did not confirm cancellation.")
                    execution.flush()
                    execution.job?.cancel(UserRequestedCancellation(result))
                    return@launch
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (!isTransientResponseError(error)) {
                        update(handle, nodeId) { it.copy(error = "Cancellation was not confirmed: ${error.message}", recoveryStatus = "Cancellation not confirmed") }
                        save(handle)
                        return@launch
                    }
                    update(handle, nodeId) { it.copy(recoveryStatus = "Waiting to cancel…") }
                    delay(retryMillis)
                    retryMillis = (retryMillis * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    fun stopAll() { active.value.keys.toList().forEach(::stop) }
    fun pauseForSystem() { active.value.values.toList().forEach { it.job.cancel(CancellationException("Android paused background work")) } }

    private fun turnFor(chat: SavedChat, nodeId: String?): ChatTurn? =
        if (nodeId != null && chat.tree != null) chat.tree.node(nodeId)?.turn else chat.turns.lastOrNull()

    private fun applyResponse(handle: ActiveChatRequest, nodeId: String?, response: JsonObject) {
        val protocol = turnFor(handle.state.value, nodeId)?.ai?.api?.requestProtocol ?: return
        val output = readResponseOutput(protocol, response)
        update(handle, nodeId) { it.copy(
            answer = if (output.text.isNotEmpty() || response["status"]?.jsonPrimitive?.content == "completed") output.text else it.answer,
            reasoning = if (output.reasoning.isNotEmpty()) output.reasoning else it.reasoning,
            isThinking = false,
            generatedImages = (it.generatedImages + output.images).distinctBy { image -> image.id },
            citations = (it.citations + output.citations).distinct()) }
    }

    private fun update(handle: ActiveChatRequest, nodeId: String?, transform: (ChatTurn) -> ChatTurn) {
        handle.mutableState.update { it.updateNode(nodeId, System.currentTimeMillis(), transform) }
    }

    private suspend fun save(handle: ActiveChatRequest) = handle.saveMutex.withLock {
        try { history.save(handle.state.value) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { mutableError.value = "Could not save request progress: ${error.message}" }
    }
}
