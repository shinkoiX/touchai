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

    @Test fun switchingToChatGptShowsSignInAndRemovesIncompatibleControls() {
        var draft by mutableStateOf(AppSettings(api = OpenAIModelConfig(apiKey = "test-key", model = "test-model",
            protocol = ApiProtocol.Responses, backgroundResponses = true), presets = emptyList()))
        compose.setContent {
            TouchAiTheme {
                SettingsScreen(OpenAIChatUiState(settingsDraft = draft),
                    (compose.activity.application as TouchAiApplication).quickAccess,
                    onSave = {}, onDraftChange = { draft = it }, onTest = { _, _ -> }, onLogs = {}, onClose = {})
            }
        }
        compose.onNodeWithText("ChatGPT").performScrollTo().performClick()
        compose.onNodeWithText("Continue with ChatGPT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Base URL").assertDoesNotExist()
        compose.onNodeWithText("Recover interrupted responses").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("API key")).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(AuthenticationMethod.ChatGpt, draft.api.authentication)
            assertEquals(ChatGptOAuth.Resource, draft.api.baseUrl)
            assertEquals(ApiProtocol.Responses, draft.api.protocol)
            assertEquals("", draft.api.apiKey)
            assertFalse(draft.api.backgroundResponses)
        }
        compose.onNodeWithText("API key").performScrollTo().performClick()
        compose.onNodeWithText("Base URL").assertExists()
        compose.onNodeWithText("Continue with ChatGPT").assertDoesNotExist()
        compose.onNodeWithText("Recover interrupted responses").assertExists()
    }
}
