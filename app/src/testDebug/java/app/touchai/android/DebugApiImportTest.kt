package app.touchai.android

import app.touchai.core.openai.ApiProtocol
import app.touchai.core.openai.GeminiBaseUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DebugApiImportTest {
    private class MemorySettingsRepository(var current: AppSettings = AppSettings()) : SettingsRepository {
        override suspend fun load(): AppSettings = current
        override suspend fun save(settings: AppSettings) { current = settings }
        override suspend fun rememberPreset(id: String?) { current = current.copy(lastPresetId = id) }
    }

    @Test fun jsonImportCreatesAndUpdatesConnectionWithoutDuplicates() = runBlocking {
        val repo = MemorySettingsRepository()
        val payload = parseDebugApiImportJson(
            """
            {
              "name": "Local Test",
              "api_key": "sk-debug-initial",
              "base_url": "https://api.example.com/v1/",
              "model": "gpt-test",
              "protocol": "responses",
              "reasoning_effort": "high",
              "web_search": false
            }
            """.trimIndent(),
        )
        val first = applyDebugApiImport(repo, payload)
        val resolved = first.resolve(first.defaultAi).api
        assertEquals("sk-debug-initial", resolved.apiKey)
        assertEquals("https://api.example.com/v1", resolved.baseUrl)
        assertEquals("gpt-test", resolved.model)
        assertEquals(ApiProtocol.Responses, resolved.protocol)
        assertEquals("high", resolved.reasoningEffort)
        assertFalse(resolved.webSearch)

        val second = applyDebugApiImport(
            repo,
            DebugApiImportPayload(
                name = "Local Test",
                apiKey = "sk-debug-rotated",
                model = "gpt-test-2",
            ),
        )
        assertEquals(1, second.connections.count { it.name == "Local Test" })
        assertEquals("sk-debug-rotated", second.resolve(second.defaultAi).api.apiKey)
        assertEquals("gpt-test-2", second.resolve(second.defaultAi).api.model)
    }

    @Test fun geminiProtocolUsesOfficialDefaultBaseUrlAndRejectsInvalidEndpoints() {
        val updated = mergeDebugApiImport(
            AppSettings(),
            DebugApiImportPayload(apiKey = "gm-key", protocol = ApiProtocol.Gemini, model = "gemini-2.5-pro"),
        )
        val api = updated.resolve(updated.defaultAi).api
        assertEquals(GeminiBaseUrl, api.baseUrl)
        assertEquals(ApiProtocol.Gemini, api.protocol)

        assertThrows(IllegalArgumentException::class.java) {
            mergeDebugApiImport(
                AppSettings(),
                DebugApiImportPayload(apiKey = "sk-test", baseUrl = "http://insecure.example.com/v1"),
            )
        }
    }
}
