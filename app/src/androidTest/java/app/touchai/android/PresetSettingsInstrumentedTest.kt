package app.touchai.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PresetSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun screenshotAttachmentCanFollowSettingsOrOverrideEitherWay() {
        val preset = PromptPreset("test", "Test", "Explain")
        var draft by mutableStateOf(AppSettings(presets = listOf(preset)))
        var saved: AppSettings? = null
        compose.setContent {
            TouchAiTheme {
                SettingsScreen(OpenAIChatUiState(settingsDraft = draft, selectedPreset = preset.id),
                    (compose.activity.application as TouchAiApplication).quickAccess,
                    onSave = { saved = it }, onDraftChange = { draft = it }, onTest = { _, _ -> }, onLogs = {}, onClose = {})
            }
        }
        compose.onNodeWithText("Always").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(true, draft.presets.single().attachScreenshot) }
        compose.onNodeWithText("Never").performClick().assertIsSelected()
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(false, saved!!.presets.single().attachScreenshot) }
        compose.onAllNodesWithText("Default").onLast().performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(preset, draft.presets.single()) }
    }
}
