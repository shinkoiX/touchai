package app.touchai.android

import app.touchai.core.openai.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenAIChatViewModelTest {
    private val history = MemoryChatHistoryRepository()
    private val settings = AppSettings(api = OpenAIModelConfig(apiKey = "test-only-key", model = "test"))
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class Repository(var settings: AppSettings) : SettingsRepository {
        override suspend fun load() = settings
        override suspend fun save(settings: AppSettings) { this.settings = settings }
        override suspend fun rememberPreset(id: String?) { settings = settings.copy(lastPresetId = id) }
    }
    private fun client(block: (OpenAIRequest) -> Flow<OpenAIStreamEvent>) = object : ChatClient {
        override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = block(request)
    }
    private val completed = OpenAIStreamEvent.Completed(JsonObject(emptyMap()))

    @Test fun generatedImagesAreSavedReopenedAndIncludedInFollowUpContext() = runTest {
        val image = GeneratedImage("generated", OpenAIImage("data:image/png;base64,test"))
        val requests = mutableListOf<OpenAIRequest>()
        val vm = testChatViewModel(history, Repository(settings), client { request ->
            requests += request
            flow { emit(OpenAIStreamEvent.ImageGenerated(image)); emit(completed) }
        })
        runCurrent(); vm.setPrompt("Draw an illustration"); vm.submit(); advanceUntilIdle()
        val id = vm.uiState.value.chatId
        assertEquals(listOf(image), history.chats.getValue(id).turns.single().generatedImages)
        vm.newChat(); advanceUntilIdle(); vm.openChat(id); advanceUntilIdle()
        assertEquals(listOf(image), vm.uiState.value.turns.single().generatedImages)
        vm.setPrompt("Describe the image"); vm.submit(); advanceUntilIdle()
        assertEquals(listOf(image.image), requests.last().messages[1].images)
        assertEquals(MessageRole.User, requests.last().messages[1].role)
        assertEquals("Describe the image", requests.last().messages.last().text)
        vm.retry(); advanceUntilIdle()
        assertEquals(listOf(image), vm.uiState.value.turns.last().generatedImages)
    }

    @Test fun excludingAnImageKeepsItAvailableButDoesNotSendIt() = runTest {
        val requests = mutableListOf<OpenAIRequest>()
        val vm = testChatViewModel(history, Repository(settings), client { request ->
            requests += request; flow { emit(completed) }
        })
        runCurrent()
        val image = OpenAIImage("data:image/png;base64,test")
        vm.setImage(image)
        vm.removeImage()
        assertSame(image, vm.uiState.value.image)
        assertFalse(vm.uiState.value.imageAttached)
        vm.setPrompt("Only text"); vm.submit(); advanceUntilIdle()
        assertTrue(requests.single().messages.single().images.isEmpty())
        assertNull(vm.uiState.value.image)
        assertFalse(vm.uiState.value.imageAttached)
    }

    @Test fun addToggleIncludesTheSamePreparedImageAndExcludedImageCannotSendAnEmptyMessage() = runTest {
        val requests = mutableListOf<OpenAIRequest>()
        val vm = testChatViewModel(history, Repository(settings), client { request ->
            requests += request; flow { emit(completed) }
        })
        runCurrent()
        val image = OpenAIImage("data:image/png;base64,test")
        vm.setImage(image); vm.toggleImageAttachment(); vm.setPrompt("")
        vm.submit(); advanceUntilIdle()
        assertTrue(requests.isEmpty())
        assertNotNull(vm.uiState.value.error)
        vm.toggleImageAttachment()
        assertTrue(vm.uiState.value.imageAttached)
        assertSame(image, vm.uiState.value.image)
        vm.submit(); advanceUntilIdle()
        assertEquals(listOf(image), requests.single().messages.single().images)
    }

    @Test fun importedChatRetryUsesCredentialsOnlyForAMatchingConfiguredEndpoint() = runTest {
        val importedTurn = ChatTurn(ChatMessage(MessageRole.User, "Imported message"),
            AiConfiguration(settings.api.copy(apiKey = "")), answer = "Old answer", status = TurnStatus.Completed)
        history.save(SavedChat("imported", 1, null, listOf(importedTurn)))
        var usedKey: String? = null
        val api = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow { usedKey = config.apiKey; emit(completed) }
        }
        val vm = testChatViewModel(history, Repository(settings), api)
        runCurrent(); vm.openChat("imported"); advanceUntilIdle()
        vm.retry(); advanceUntilIdle()
        assertEquals(settings.api.apiKey, usedKey)
        history.save(SavedChat("different-endpoint", 2, null, listOf(importedTurn.copy(ai = AiConfiguration(
            importedTurn.ai.api.copy(baseUrl = "https://different.example.com/v1"))))))
        usedKey = null
        vm.openChat("different-endpoint"); advanceUntilIdle()
        vm.retry(); advanceUntilIdle()
        assertNull(usedKey)
        assertTrue(vm.uiState.value.error!!.contains("API key"))
        assertEquals("Old answer", vm.uiState.value.turns.single().answer)
    }

    @Test fun cancelledHistoryLoadDoesNotClearTheNextLoadsBusyState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val delayedHistory = object : ChatHistoryRepository by history {
            override suspend fun list(): List<ChatHistoryEntry> { gate.await(); return emptyList() }
        }
        val vm = testChatViewModel(delayedHistory, Repository(settings), client { flow { emit(completed) } })
        runCurrent()
        vm.showHistory(true); runCurrent()
        vm.showHistory(false); vm.showHistory(true); runCurrent()
        assertTrue(vm.uiState.value.historyLoading)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(vm.uiState.value.historyLoading)
    }

    @Test fun savedChatReopensWithImagesAndContinuesUsingCompletedContext() = runTest {
        val requests = mutableListOf<OpenAIRequest>()
        val api = client { request -> requests += request; flow { emit(OpenAIStreamEvent.TextDelta("Saved answer")); emit(completed) } }
        val repository = Repository(settings)
        val vm = testChatViewModel(history, repository, api)
        runCurrent()
        val image = OpenAIImage("data:image/png;base64,test")
        vm.setImage(image); vm.setPrompt("First message"); vm.submit(); advanceUntilIdle()
        val savedId = vm.uiState.value.chatId
        assertEquals(TurnStatus.Completed, history.chats.getValue(savedId).turns.single().status)
        vm.newChat(); advanceUntilIdle()
        assertTrue(vm.uiState.value.turns.isEmpty())
        assertEquals(1, history.chats.size)
        val reopened = testChatViewModel(history, repository, api)
        runCurrent()
        reopened.showHistory(true); advanceUntilIdle()
        assertEquals("First message", reopened.uiState.value.historyEntries.single().title)
        reopened.openChat(savedId); advanceUntilIdle()
        assertEquals(listOf(image), reopened.uiState.value.turns.single().user.images)
        assertFalse(reopened.uiState.value.historyOpen)
        reopened.setPrompt("Follow-up"); reopened.submit(); advanceUntilIdle()
        assertEquals(listOf("First message", "Saved answer", "Follow-up"), requests.last().messages.map { it.text })
        assertEquals(listOf(image), requests.last().messages.first().images)
        assertEquals(2, history.chats.getValue(savedId).turns.size)
    }

    @Test fun deletingTheCurrentChatCannotResurrectItsOldMessages() = runTest {
        val vm = testChatViewModel(history, Repository(settings), client { flow { emit(completed) } })
        runCurrent()
        vm.setPrompt("Delete me"); vm.submit(); advanceUntilIdle()
        val deletedId = vm.uiState.value.chatId
        vm.showHistory(true); advanceUntilIdle()
        vm.deleteChat(deletedId); advanceUntilIdle()
        assertTrue(history.chats.isEmpty())
        assertTrue(vm.uiState.value.turns.isEmpty())
        assertNotEquals(deletedId, vm.uiState.value.chatId)
        vm.showHistory(false); vm.setPrompt("New message"); vm.submit(); advanceUntilIdle()
        assertFalse(history.chats.containsKey(deletedId))
        assertEquals("New message", history.chats.values.single().turns.single().user.text)
    }

    @Test fun presetConfigOverridesAllAiFieldsAndRetryKeepsTheOriginalConfiguration() = runTest {
        val custom = AiConfiguration(OpenAIModelConfig(apiKey = "preset-test-key", model = "preset-model",
            baseUrl = "https://preset.example.com/v1", protocol = ApiProtocol.Responses, reasoningEffort = "high", webSearch = false), "Preset instructions")
        val configured = settings.copy(presets = listOf(PromptPreset("custom", "Custom", "Custom prompt", custom)))
        val configs = mutableListOf<OpenAIModelConfig>()
        val requests = mutableListOf<OpenAIRequest>()
        val api = object : ChatClient {
            override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
                configs += config; requests += request
                emit(OpenAIStreamEvent.TextDelta("Answer")); emit(completed)
            }
        }
        val vm = testChatViewModel(history, Repository(configured), api)
        runCurrent()
        vm.selectPreset("custom"); vm.submit(); advanceUntilIdle()
        vm.selectPreset(null); vm.retry(); advanceUntilIdle()
        assertEquals(listOf(custom.api, custom.api), configs)
        assertEquals(listOf("Preset instructions", "Preset instructions"), requests.map { it.instructions })
        vm.setPrompt("Default request"); vm.submit(); advanceUntilIdle()
        assertEquals(settings.api, configs.last())
        assertEquals("Custom prompt", requests.last().messages.first().text)
    }

    @Test fun failedCaptureOpensTextOnlyAndDoesNotRepeatAfterRecreation() = runTest {
        val vm = testChatViewModel(history, Repository(settings), client { flow { emit(completed) } }, invoked = true)
        runCurrent()
        var captures = 0
        vm.captureOnInvocation { captures++; throw ScreenCaptureException("Protected screen") }
        advanceUntilIdle()
        assertFalse(vm.uiState.value.invoking)
        assertEquals("Protected screen", vm.uiState.value.error)
        assertNull(vm.uiState.value.originalImage)
        vm.captureOnInvocation { captures++; throw ScreenCaptureException("Should not run") }
        advanceUntilIdle()
        assertEquals(1, captures)
        vm.prepareInvocation()
        assertTrue(vm.uiState.value.invoking)
        assertEquals(1, captures) // A new intent hides the panel; capture waits for window focus.
        vm.captureOnInvocation { captures++; throw ScreenCaptureException("Protected screen") }
        advanceUntilIdle()
        assertEquals(2, captures)
        vm.setPrompt("Text only"); vm.submit(); advanceUntilIdle()
        assertTrue(vm.uiState.value.turns.single().user.images.isEmpty())
    }

    @Test fun unsavedSettingsDraftSurvivesReloadWithoutPersistingSecretsInSavedState() = runTest {
        val vm = testChatViewModel(history, Repository(settings), client { flow { emit(completed) } })
        runCurrent()
        vm.showSettings(true)
        val draft = settings.copy(api = settings.api.copy(model = "draft-model"))
        vm.editSettings(draft)
        vm.loadSettings()
        advanceUntilIdle()
        assertEquals(draft, vm.uiState.value.settingsDraft)
        assertEquals(settings, vm.uiState.value.settings)
        vm.showSettings(false)
        assertNull(vm.uiState.value.settingsDraft)
    }

    @Test fun followUpIncludesTheCompletedConversationAndOriginalImage() = runTest {
        val requests = mutableListOf<OpenAIRequest>()
        val vm = testChatViewModel(history, Repository(settings), client { request ->
            requests += request
            flow { emit(OpenAIStreamEvent.TextDelta("Answer ${requests.size}")); emit(completed) }
        })
        runCurrent()
        vm.setImage(OpenAIImage("data:image/png;base64,dGVzdA=="))
        vm.setPrompt("First question")
        vm.submit()
        advanceUntilIdle()
        vm.setPrompt("Follow up")
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("First question", "Answer 1", "Follow up"), requests[1].messages.map { it.text })
        assertEquals(1, requests[1].messages[0].images.size)
        assertTrue(requests[1].messages[2].images.isEmpty())
        assertEquals(2, vm.uiState.value.turns.size)
        assertTrue(vm.uiState.value.turns.all { it.status == TurnStatus.Completed })
    }

    @Test fun stopFlushesTextThatHasNotReachedTheDisplayInterval() = runTest {
        val vm = testChatViewModel(history, Repository(settings), client {
            flow { emit(OpenAIStreamEvent.TextDelta("Already received")); awaitCancellation() }
        })
        runCurrent()
        vm.setPrompt("Question")
        vm.submit()
        runCurrent()
        assertEquals("", vm.uiState.value.turns.single().answer)
        vm.cancel()
        runCurrent()
        assertEquals("Already received", vm.uiState.value.turns.single().answer)
        assertEquals(TurnStatus.Stopped, vm.uiState.value.turns.single().status)
        assertFalse(vm.uiState.value.isStreaming)
    }

    @Test fun retryReusesTheOriginalInputWithoutDuplicatingTheFailedTurn() = runTest {
        val requests = mutableListOf<OpenAIRequest>()
        val vm = testChatViewModel(history, Repository(settings), client { request ->
            requests += request
            flow {
                emit(OpenAIStreamEvent.TextDelta(if (requests.size == 1) "Partial" else "Complete"))
                if (requests.size == 1) throw IncompleteResponseException("output limit")
                emit(completed)
            }
        })
        runCurrent()
        vm.selectPreset("explain")
        vm.setPrompt("What does it mean?")
        vm.setImage(OpenAIImage("data:image/png;base64,dGVzdA=="))
        vm.submit()
        advanceUntilIdle()
        assertEquals(TurnStatus.Incomplete, vm.uiState.value.turns.single().status)
        assertEquals("Partial", vm.uiState.value.turns.single().answer)
        vm.retry()
        advanceUntilIdle()
        assertEquals(requests[0].copy(purpose = RequestPurpose.Retry), requests[1])
        assertEquals(1, vm.uiState.value.turns.size)
        assertEquals("Complete", vm.uiState.value.turns.single().answer)
    }

    @Test fun failedTurnsStayVisibleButDoNotBecomeFollowUpContext() = runTest {
        val requests = mutableListOf<OpenAIRequest>()
        val vm = testChatViewModel(history, Repository(settings), client { request ->
            requests += request
            flow {
                if (requests.size == 1) {
                    emit(OpenAIStreamEvent.TextDelta("Partial"))
                    throw OpenAIRequestException("Provider error")
                }
                emit(OpenAIStreamEvent.TextDelta("Good answer")); emit(completed)
            }
        })
        runCurrent()
        vm.setPrompt("First"); vm.submit(); advanceUntilIdle()
        vm.setPrompt("Next"); vm.submit(); advanceUntilIdle()
        assertEquals(listOf("Next"), requests[1].messages.map { it.text })
        assertEquals("Partial", vm.uiState.value.turns.first().answer)
        assertEquals(TurnStatus.Failed, vm.uiState.value.turns.first().status)
    }

    @Test fun savedSettingsAreLoadedAndChangingThemStartsANewConversation() = runTest {
        val repository = Repository(settings)
        val vm = testChatViewModel(history, repository, client { flow { emit(completed) } })
        runCurrent()
        vm.setPrompt("Question"); vm.submit(); advanceUntilIdle()
        val changed = settings.copy(api = settings.api.copy(model = "another-model"))
        vm.saveSettings(changed)
        advanceUntilIdle()
        assertEquals(changed, repository.settings)
        assertEquals(changed, vm.uiState.value.settings)
        assertTrue(vm.uiState.value.turns.isEmpty())
        vm.setImage(OpenAIImage("data:image/png;base64,dGVzdA=="))
        vm.newChat()
        assertNull(vm.uiState.value.image)
    }

    @Test fun storageFailuresAreShownAndDoNotOverwriteCurrentSettings() = runTest {
        val repository = object : SettingsRepository {
            override suspend fun load() = settings
            override suspend fun save(settings: AppSettings) { throw java.io.IOException("Storage unavailable") }
            override suspend fun rememberPreset(id: String?) = Unit
        }
        val vm = testChatViewModel(history, repository, client { flow { emit(completed) } })
        runCurrent()
        vm.saveSettings(settings.copy(instructions = "New instructions"))
        advanceUntilIdle()
        assertEquals(settings, vm.uiState.value.settings)
        assertTrue(vm.uiState.value.error!!.contains("Storage unavailable"))
        assertFalse(vm.uiState.value.savingSettings)
    }

    @Test fun lastPresetIsRememberedAndItsEditableTextIsSentExactlyOnce() = runTest {
        val repository = Repository(settings)
        val requests = mutableListOf<OpenAIRequest>()
        val api = client { request -> requests += request; flow { emit(completed) } }
        val vm = testChatViewModel(history, repository, api)
        runCurrent()
        vm.selectPreset("translate")
        assertEquals(settings.presets[1].prompt, vm.uiState.value.prompt)
        vm.setPrompt("Translate only the heading")
        vm.submit()
        advanceUntilIdle()
        assertEquals("Translate only the heading", requests.single().messages.single().text)
        assertEquals(RequestPurpose.Chat, requests.single().purpose)
        vm.newChat()
        advanceUntilIdle()
        assertEquals("translate", vm.uiState.value.selectedPreset)
        assertEquals(settings.presets[1].prompt, vm.uiState.value.prompt)
        val reopened = testChatViewModel(history, repository, api)
        runCurrent()
        assertEquals("translate", reopened.uiState.value.selectedPreset)
        assertEquals(settings.presets[1].prompt, reopened.uiState.value.prompt)
        reopened.selectPreset(null)
        advanceUntilIdle()
        val textOnly = testChatViewModel(history, repository, api)
        runCurrent()
        assertNull(textOnly.uiState.value.selectedPreset)
        assertEquals("", textOnly.uiState.value.prompt)
    }

    @Test fun connectionTestsCarryTheirLoggingPurposeAndInstructions() = runTest {
        val requests = mutableListOf<OpenAIRequest>()
        val vm = testChatViewModel(history, Repository(settings), client { request -> requests += request; flow { emit(completed) } })
        runCurrent()
        vm.testConnection(AiConfiguration(settings.api, "Test instructions"), "Default AI")
        advanceUntilIdle()
        assertEquals(RequestPurpose.ConnectionTest, requests.single().purpose)
        assertEquals("Test instructions", requests.single().instructions)
        assertEquals("Default AI", requests.single().presetName)
    }

    @Test fun editingSelectedPresetRefreshesPrefillButPreservesUserEdits() = runTest {
        val vm = testChatViewModel(history, Repository(settings), client { flow { emit(completed) } })
        runCurrent()
        val updated = settings.copy(presets = settings.presets.map { it.copy(prompt = "Updated preset") })
        vm.saveSettings(updated)
        advanceUntilIdle()
        assertEquals("Updated preset", vm.uiState.value.prompt)
        vm.setPrompt("My edited message")
        vm.saveSettings(settings)
        advanceUntilIdle()
        assertEquals("My edited message", vm.uiState.value.prompt)
    }

    @Test fun invalidSettingsAreRejectedAtTheUserInputBoundary() {
        assertNotNull(settingsError(settings.copy(api = settings.api.copy(baseUrl = "http://example.com/v1"))))
        assertNotNull(settingsError(settings.copy(api = settings.api.copy(baseUrl = "https://user:secret@example.com/v1"))))
        assertNotNull(settingsError(settings.copy(api = settings.api.copy(baseUrl = "https://example.com/v1/responses"))))
        assertNull(settingsError(settings.copy(api = settings.api.copy(reasoningEffort = "custom-effort"))))
    }
}
