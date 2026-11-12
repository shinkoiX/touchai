package app.touchai.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import app.touchai.core.openai.ApiProtocol
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.OpenAIModelConfig
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsPersistenceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = ApiKeyCipher { key }

    @Test fun defaultAndPresetOAuthAccountsPersistWithoutAnApiKey() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("oauth.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { file }
            val api = OpenAIModelConfig(authentication = AuthenticationMethod.ChatGpt, chatGptAccountId = "account-one",
                protocol = ApiProtocol.Responses, model = "available")
            val settings = AppSettings(api = api, presets = listOf(PromptPreset("custom", "Custom", "Explain",
                AiConfiguration(api.copy(chatGptAccountId = "account-two")))))
            DataStoreSettingsRepository(data, cipher).save(settings)
            val loaded = DataStoreSettingsRepository(data, cipher).load()
            assertEquals(api, loaded.aiFor(null).api)
            assertEquals(api.copy(chatGptAccountId = "account-two"), loaded.aiFor("custom").api)
            assertEquals(listOf("account-one", "account-two"), loaded.connections.map { it.chatGptAccountId })
            assertNull(apiError(api, requireCredentials = true))
            assertNotNull(apiError(api.copy(chatGptAccountId = null), requireCredentials = true))
            assertNotNull(apiError(api.copy(baseUrl = "https://other.example.com"), requireCredentials = true))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun settingsSurviveRepositoryRecreationWithoutStoringThePlainApiKey() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val settings = AppSettings(
            api = OpenAIModelConfig(apiKey = "test-secret-never-plaintext", model = "test", protocol = ApiProtocol.Responses, reasoningEffort = "low", backgroundResponses = true),
            instructions = "Be concise",
            presets = listOf(PromptPreset("test", "Explain", "Explain this image", AiConfiguration(
                OpenAIModelConfig(apiKey = "another-preset-secret", model = "another-model", baseUrl = "https://preset.example.com/v1", webSearch = false), "Preset instructions")),
                PromptPreset("always", "With screenshot", "Explain this screen", attachScreenshot = true),
                PromptPreset("never", "Without screenshot", "Answer this question", attachScreenshot = false)),
            lastPresetId = "test", imageQuality = ImageQuality.Original, imageLimit = 7,
            quickAccess = QuickAccessSettings(floatingButton = false, notificationCaptureDelayMillis = 650,
                buttonSizeDp = 80, attachScreenshotAutomatically = false, cornerSwipe = true,
                cornerGestures = setOf(CornerGesture.SwipeUp, CornerGesture.DoubleTap),
                cornerOpacityPercent = 0, cornerAreaSizeDp = 128),
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = firstScope) { file }
            val repository = DataStoreSettingsRepository(store, cipher)
            assertEquals(QuickAccessSettings(), repository.load().quickAccess)
            assertEquals(3, repository.load().imageLimit)
            repository.save(settings)
            repository.rememberPreset("test")
            assertEquals(settings.withConnections(), repository.load())
            assertEquals(settings.aiFor("test"), repository.load().aiFor("test").copy(connectionId = null))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(settings.api.apiKey))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("another-preset-secret"))
        } finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = secondScope) { file }
            val repository = DataStoreSettingsRepository(store, cipher)
            assertEquals(settings.withConnections(), repository.load())
            repository.saveButtonPosition(false, 0.75f)
            repository.save(settings.copy(api = settings.api.copy(apiKey = "")))
            assertEquals("", repository.load().aiFor(null).api.apiKey)
            assertEquals(false, repository.load().quickAccess.buttonOnRight)
            assertEquals(0.75f, repository.load().quickAccess.buttonY)
            repository.rememberPreset(null)
            repository.save(settings) // A stale settings form must not overwrite the latest selection.
            assertNull(repository.load().lastPresetId)
            assertEquals(settings.api, repository.load().aiFor(null).api)
            repository.pauseQuickAccess()
            val paused = repository.load().quickAccess
            assertFalse(paused.floatingButton)
            assertFalse(paused.notification)
            assertFalse(paused.cornerSwipe)
            assertEquals(settings.quickAccess.buttonSizeDp, paused.buttonSizeDp)
            assertEquals(settings.quickAccess.cornerGestures, paused.cornerGestures)
            assertEquals(0, paused.cornerOpacityPercent)
            assertEquals(128, paused.cornerAreaSizeDp)
            repository.rememberPreset("test")
            assertEquals("test", repository.load().lastPresetId)
            repository.save(settings.copy(presets = emptyList()))
            assertNull(repository.load().lastPresetId)
        } finally { secondScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun presetsShareOneConnectionAndOneStoredKey() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("shared.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = DataStoreSettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, cipher)
            val shared = ApiConnection("shared", "Provider", baseUrl = "https://provider.example.com/v1",
                protocol = ApiProtocol.Responses, apiKey = "shared-test-secret")
            val settings = AppSettings(api = OpenAIModelConfig(model = "default-model", backgroundResponses = true), connectionId = "shared",
                connections = listOf(shared), presets = listOf(
                    PromptPreset("one", "One", "Explain", AiConfiguration(OpenAIModelConfig(model = "one-model"), "One", "shared")),
                    PromptPreset("two", "Two", "Explain", AiConfiguration(OpenAIModelConfig(model = "two-model", webSearch = false), "Two", "shared"))), lastPresetId = "one")
            repository.save(settings)
            repository.rememberPreset("one")
            val loaded = repository.load()
            assertEquals(settings, loaded)
            assertEquals(OpenAIModelConfig(apiKey = "shared-test-secret", model = "two-model", baseUrl = "https://provider.example.com/v1",
                protocol = ApiProtocol.Responses, webSearch = false), loaded.aiFor("two").api)
            assertTrue(loaded.aiFor(null).api.backgroundResponses)
            // Editing the shared key once applies to every preset that uses it.
            val rotated = loaded.copy(connections = listOf(shared.copy(apiKey = "rotated-test-secret")))
            repository.save(rotated)
            assertEquals(listOf("rotated-test-secret"), repository.load().let { r -> listOf(null, "one", "two").map { r.aiFor(it).api.apiKey }.distinct() })
            val stored = file.readBytes().toString(Charsets.UTF_8)
            assertFalse(stored.contains("rotated-test-secret"))
            assertFalse(stored.contains("shared-test-secret"))
            assertNull(settingsError(rotated))
            assertNotNull(settingsError(rotated.copy(connections = emptyList())))
            assertNotNull(settingsError(rotated.copy(connections = listOf(shared.copy(baseUrl = "http://insecure.example.com")))))
            assertEquals(ApiProtocol.Responses, ApiConnection().protocol)
            val gemini = shared.copy(protocol = ApiProtocol.Gemini, baseUrl = app.touchai.core.openai.GeminiBaseUrl)
            assertNull(settingsError(rotated.copy(connections = listOf(gemini))))
            assertNotNull(settingsError(rotated.copy(connections = listOf(gemini.copy(baseUrl = "${gemini.baseUrl}/openai")))))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun olderInlineCredentialsMigrateIntoDeduplicatedConnections() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("legacy.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val key = cipher.encrypt("legacy-test-secret")
            val inline = """{"baseUrl":"https://api.openai.com/v1","encryptedApiKey":"$key","model":"preset-model","protocol":"ChatCompletions","webSearch":true,"instructions":""}"""
            val other = """{"baseUrl":"https://other.example.com/v1","encryptedApiKey":"${cipher.encrypt("other-test-secret")}","model":"other","protocol":"Responses","webSearch":false,"backgroundResponses":true,"instructions":"Other"}"""
            store.edit { prefs ->
                prefs[stringPreferencesKey("base_url")] = "https://api.openai.com/v1"
                prefs[stringPreferencesKey("encrypted_api_key")] = key
                prefs[stringPreferencesKey("model")] = "default-model"
                prefs[stringPreferencesKey("default_preset")] = "same"
                prefs[stringPreferencesKey("presets")] = """[{"id":"same","name":"Same","prompt":"Explain","ai":$inline},""" +
                    """{"id":"other","name":"Other","prompt":"Explain","ai":$other},{"id":"plain","name":"Plain","prompt":"Explain"}]"""
            }
            val repository = DataStoreSettingsRepository(store, cipher)
            val loaded = repository.load()
            assertEquals(loaded, repository.load())
            assertEquals(listOf("api.openai.com", "other.example.com"), loaded.connections.map { it.name })
            assertEquals(loaded.connectionId, loaded.presets.first { it.id == "same" }.customAi!!.connectionId)
            assertEquals("legacy-test-secret", loaded.aiFor("same").api.apiKey)
            assertEquals("preset-model", loaded.aiFor("same").api.model)
            assertEquals("default-model", loaded.aiFor(null).api.model)
            assertEquals(OpenAIModelConfig(apiKey = "other-test-secret", model = "other", baseUrl = "https://other.example.com/v1",
                protocol = ApiProtocol.Responses, webSearch = false, backgroundResponses = true), loaded.aiFor("other").api)
            repository.save(loaded)
            assertEquals(loaded, repository.load())
            assertNull(store.data.first()[stringPreferencesKey("encrypted_api_key")])
            assertFalse(store.data.first()[stringPreferencesKey("presets")]!!.contains("encryptedApiKey"))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun reorderedPresetsKeepTheirSelectedIdAndPersistTheirSequence() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("reordered.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val repository = DataStoreSettingsRepository(store, cipher)
            val original = AppSettings()
            repository.save(original)
            repository.rememberPreset("translate")
            val reordered = original.copy(presets = listOf(original.presets[2], original.presets[0], original.presets[1]))
            repository.save(reordered)
            val loaded = DataStoreSettingsRepository(store, cipher).load()
            assertEquals(listOf("summarize", "explain", "translate"), loaded.presets.map { it.id })
            assertEquals("translate", loaded.lastPresetId)
            assertEquals(original.presets.associateBy { it.id }, loaded.presets.associateBy { it.id })
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun encryptionUsesFreshNoncesAndRoundTrips() {
        val first = cipher.encrypt("test-only-secret")
        val second = cipher.encrypt("test-only-secret")
        assertNotEquals(first, second)
        assertEquals("test-only-secret", cipher.decrypt(first))
        assertEquals("test-only-secret", cipher.decrypt(second))
    }

    @Test(expected = AEADBadTagException::class) fun modifiedCiphertextIsRejected() {
        val bytes = Base64.getDecoder().decode(cipher.encrypt("test-only-secret"))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        cipher.decrypt(Base64.getEncoder().encodeToString(bytes))
    }
}
