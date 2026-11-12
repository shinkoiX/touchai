package app.touchai.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.touchai.core.openai.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatGptSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun switchingAConnectionToChatGptShowsSignInAndRemovesIncompatibleControls() {
        val connection = ApiConnection("main", "Main", baseUrl = "https://api.openai.com/v1", protocol = ApiProtocol.Responses, apiKey = "test-key")
        var draft by mutableStateOf(AppSettings(api = OpenAIModelConfig(model = "test-model", backgroundResponses = true),
            connectionId = connection.id, connections = listOf(connection), presets = emptyList()))
        compose.setContent {
            TouchAiTheme {
                SettingsScreen(OpenAIChatUiState(settingsDraft = draft),
                    (compose.activity.application as TouchAiApplication).quickAccess,
                    onSave = {}, onDraftChange = { draft = it }, onTest = { _, _ -> }, onLogs = {}, onClose = {})
            }
        }
        compose.onNodeWithText("Recover interrupted responses").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Main").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("ChatGPT").performScrollTo().performClick()
        compose.onNodeWithText("Continue with ChatGPT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Base URL").assertDoesNotExist()
        compose.onNodeWithText("Recover interrupted responses").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("API key")).assertDoesNotExist()
        compose.runOnIdle {
            val connection = draft.connections.single()
            assertEquals(AuthenticationMethod.ChatGpt, connection.authentication)
            assertEquals("", connection.apiKey)
            val resolved = draft.aiFor(null).api
            assertEquals(ChatGptOAuth.Resource, resolved.baseUrl)
            assertEquals(ApiProtocol.Responses, resolved.protocol)
            assertFalse(resolved.backgroundResponses)
        }
        compose.onNodeWithText("API key").performScrollTo().performClick()
        compose.onNodeWithText("Base URL").assertExists()
        compose.onNodeWithText("Continue with ChatGPT").assertDoesNotExist()
    }

    @Test fun presetsChooseASharedConnectionInsteadOfTypingAKey() {
        val first = ApiConnection("first", "First", apiKey = "first-test-key")
        val second = ApiConnection("second", "Second", baseUrl = "https://second.example.com/v1", apiKey = "second-test-key")
        val preset = PromptPreset("preset", "Preset", "Explain", AiConfiguration(OpenAIModelConfig(model = "m"), connectionId = first.id))
        var draft by mutableStateOf(AppSettings(api = OpenAIModelConfig(model = "default"), connectionId = first.id,
            connections = listOf(first, second), presets = listOf(preset)))
        compose.setContent {
            TouchAiTheme {
                SettingsScreen(OpenAIChatUiState(settingsDraft = draft, selectedPreset = preset.id),
                    (compose.activity.application as TouchAiApplication).quickAccess,
                    onSave = {}, onDraftChange = { draft = it }, onTest = { _, _ -> }, onLogs = {}, onClose = {})
            }
        }
        // Only connections have API key fields; the preset just picks one.
        compose.onNode(hasSetTextAction() and hasText("API key")).assertDoesNotExist()
        compose.onAllNodesWithText("First").onLast().performScrollTo().performClick()
        compose.onAllNodesWithText("Second").onLast().performClick()
        compose.runOnIdle {
            assertEquals(second.id, draft.presets.single().customAi!!.connectionId)
            assertEquals("second-test-key", draft.aiFor(preset.id).api.apiKey)
            assertEquals("first-test-key", draft.aiFor(null).api.apiKey)
        }
    }
}
