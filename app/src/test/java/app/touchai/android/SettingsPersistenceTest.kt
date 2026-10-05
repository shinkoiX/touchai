package app.touchai.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.touchai.core.openai.ApiProtocol
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

    @Test fun settingsSurviveRepositoryRecreationWithoutStoringThePlainApiKey() = runBlocking {
        val file = temporaryFolder.newFolder().resolve("settings.preferences_pb")
        val settings = AppSettings(
            api = OpenAIModelConfig(apiKey = "test-secret-never-plaintext", model = "test", protocol = ApiProtocol.Responses, reasoningEffort = "low"),
            instructions = "Be concise",
            presets = listOf(PromptPreset("test", "Explain", "Explain this image", AiConfiguration(
                OpenAIModelConfig(apiKey = "another-preset-secret", model = "another-model", baseUrl = "https://preset.example.com/v1", webSearch = false), "Preset instructions"))),
            lastPresetId = "test", imageQuality = ImageQuality.Original,
            quickAccess = QuickAccessSettings(floatingButton = false, notificationCaptureDelayMillis = 650),
        )
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = firstScope) { file }
            val repository = DataStoreSettingsRepository(store, cipher)
            assertEquals(200, repository.load().quickAccess.notificationCaptureDelayMillis)
            repository.save(settings)
            repository.rememberPreset("test")
            assertEquals(settings, repository.load())
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(settings.api.apiKey))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("another-preset-secret"))
        } finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = secondScope) { file }
            val repository = DataStoreSettingsRepository(store, cipher)
            assertEquals(settings, repository.load())
            repository.saveButtonPosition(false, 0.75f)
            repository.save(settings.copy(api = settings.api.copy(apiKey = "")))
            assertEquals("", repository.load().api.apiKey)
            assertEquals(false, repository.load().quickAccess.buttonOnRight)
            assertEquals(0.75f, repository.load().quickAccess.buttonY)
            repository.rememberPreset(null)
            repository.save(settings) // A stale settings form must not overwrite the latest selection.
            assertNull(repository.load().lastPresetId)
            assertEquals(settings.api, repository.load().api)
            repository.rememberPreset("test")
            assertEquals("test", repository.load().lastPresetId)
            repository.save(settings.copy(presets = emptyList()))
            assertNull(repository.load().lastPresetId)
        } finally { secondScope.coroutineContext[Job]!!.cancelAndJoin() }
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
