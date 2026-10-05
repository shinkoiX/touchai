package app.touchai.android

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.touchai.core.openai.RequestLogRecord
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RequestLogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val database = "test-request-logs-${UUID.randomUUID()}.db"
    private var store: RequestLogStore? = null
    private fun open() = RequestLogStore(compose.activity, database).also { store = it }
    private fun record(status: String = "Running") = RequestLogRecord("test-request", 1_700_000_000_000, buildJsonObject {
        put("status", status); put("purpose", "Chat"); put("model", "test-model")
        put("messageCount", 1); put("imageCount", 0); put("instructions", "Test instructions")
    })

    @After fun cleanup() { store?.close(); compose.activity.deleteDatabase(database) }

    @Test fun persistenceMarksInterruptedAndClearDoesNotResurrectActiveRequests() = runBlocking {
        var logs = open()
        val running = record()
        logs.started(running)
        assertEquals("Running", logs.read(10).single().status)
        logs.close()
        logs = open()
        assertEquals("Interrupted", logs.read(10).single().status)
        logs.finished(record("Completed"))
        logs.close()
        logs = open()
        assertEquals("Completed", logs.read(10).single().status)
        logs.clear()
        logs.finished(record("Completed"))
        assertTrue(logs.read(10).isEmpty())
        logs.started(running)
        assertEquals(1, logs.read(10).size)
    }

    @Test fun logsCanBeInspectedAndClearedInUi() {
        val logs = open()
        runBlocking { logs.started(record()); logs.finished(record("Completed")) }
        compose.setContent { TouchAiTheme { RequestLogScreen(logs) {} } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Completed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Chat").assertExists()
        compose.onNodeWithText("test-model").performClick()
        compose.onNodeWithContentDescription("Copy log").assertExists()
        compose.onNodeWithText("Test instructions", substring = true).assertExists()
        compose.onNodeWithContentDescription("Clear logs").performClick()
        compose.onNodeWithText("Clear", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("No requests yet").fetchSemanticsNodes().isNotEmpty() }
    }
}
