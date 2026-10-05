package app.touchai.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.touchai.core.openai.ApiProtocol
import app.touchai.core.openai.OpenAIModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

val Context.settingsDataStore by preferencesDataStore(name = "settings")

class DataStoreSettingsRepository(
    private val store: DataStore<Preferences>,
    private val cipher: ApiKeyCipher,
) : SettingsRepository {
    val quickAccess = store.data.map(::readQuickAccess).distinctUntilChanged()

    override suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        val prefs = store.data.first()
        val defaults = AppSettings()
        AppSettings(
            api = OpenAIModelConfig(
                baseUrl = prefs[BaseUrl] ?: defaults.api.baseUrl,
                apiKey = cipher.decrypt(prefs[ApiKey] ?: ""),
                model = prefs[Model] ?: "",
                protocol = prefs[Protocol]?.let(ApiProtocol::valueOf) ?: defaults.api.protocol,
                reasoningEffort = prefs[Effort]?.takeIf(String::isNotEmpty),
                webSearch = prefs[WebSearch] ?: true,
                backgroundResponses = prefs[BackgroundResponses] ?: false,
            ),
            instructions = prefs[Instructions] ?: "",
            presets = prefs[Presets]?.let { serialized ->
                Json.parseToJsonElement(serialized).jsonArray.map { item ->
                    val preset = item.jsonObject
                    PromptPreset(preset.getValue("id").jsonPrimitive.content,
                        preset.getValue("name").jsonPrimitive.content,
                        preset.getValue("prompt").jsonPrimitive.content,
                        (preset["ai"] as? JsonObject)?.let(::decodeAi))
                }
            } ?: defaults.presets,
            lastPresetId = prefs[LastPreset]?.takeIf(String::isNotEmpty) ?: if (prefs.contains(LastPreset)) null else defaults.lastPresetId,
            imageQuality = prefs[Quality]?.let(ImageQuality::valueOf) ?: defaults.imageQuality,
            quickAccess = readQuickAccess(prefs),
        )
    }

    override suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        val encryptedKey = cipher.encrypt(settings.api.apiKey)
        val presets = buildJsonArray {
            settings.presets.forEach { preset -> add(buildJsonObject {
                put("id", preset.id)
                put("name", preset.name)
                put("prompt", preset.prompt)
                preset.customAi?.let { put("ai", encodeAi(it)) }
            }) }
        }.toString()
        store.edit { prefs ->
            prefs[BaseUrl] = settings.api.baseUrl
            prefs[ApiKey] = encryptedKey
            prefs[Model] = settings.api.model
            prefs[Protocol] = settings.api.protocol.name
            prefs[Effort] = settings.api.reasoningEffort ?: ""
            prefs[WebSearch] = settings.api.webSearch
            prefs[BackgroundResponses] = settings.api.backgroundResponses
            prefs[Instructions] = settings.instructions
            prefs[Presets] = presets
            val selected = prefs[LastPreset] ?: AppSettings().lastPresetId
            if (settings.presets.none { it.id == selected }) prefs[LastPreset] = ""
            prefs[Quality] = settings.imageQuality.name
            prefs[FloatingButton] = settings.quickAccess.floatingButton
            prefs[Notification] = settings.quickAccess.notification
            prefs[NotificationCaptureDelay] = settings.quickAccess.notificationCaptureDelayMillis
            prefs[ButtonSize] = settings.quickAccess.buttonSizeDp
            prefs[AttachScreenshotAutomatically] = settings.quickAccess.attachScreenshotAutomatically
            // Position is saved independently by dragging the button, not by the settings form.
        }
        Unit
    }

    override suspend fun rememberPreset(id: String?) {
        store.edit { it[LastPreset] = id ?: "" }
    }

    suspend fun saveButtonPosition(onRight: Boolean, y: Float) {
        store.edit { it[ButtonOnRight] = onRight; it[ButtonY] = y }
    }

    suspend fun pauseQuickAccess() {
        store.edit { it[FloatingButton] = false; it[Notification] = false }
    }

    private fun readQuickAccess(prefs: Preferences) = QuickAccessSettings(
        prefs[FloatingButton] ?: true, prefs[Notification] ?: true,
        prefs[ButtonOnRight] ?: true, prefs[ButtonY] ?: 0.35f,
        notificationCaptureDelayMillis = prefs[NotificationCaptureDelay] ?: QuickAccessSettings().notificationCaptureDelayMillis,
        buttonSizeDp = prefs[ButtonSize] ?: QuickAccessSettings().buttonSizeDp,
        attachScreenshotAutomatically = prefs[AttachScreenshotAutomatically] ?: QuickAccessSettings().attachScreenshotAutomatically,
    )

    private fun encodeAi(configuration: AiConfiguration) = buildJsonObject {
        val api = configuration.api
        put("baseUrl", api.baseUrl)
        put("encryptedApiKey", cipher.encrypt(api.apiKey))
        put("model", api.model)
        put("protocol", api.protocol.name)
        api.reasoningEffort?.let { put("effort", it) }
        put("webSearch", api.webSearch)
        put("backgroundResponses", api.backgroundResponses)
        put("instructions", configuration.instructions)
    }

    private fun decodeAi(value: JsonObject) = AiConfiguration(
        api = OpenAIModelConfig(
            baseUrl = value.getValue("baseUrl").jsonPrimitive.content,
            apiKey = cipher.decrypt(value.getValue("encryptedApiKey").jsonPrimitive.content),
            model = value.getValue("model").jsonPrimitive.content,
            protocol = ApiProtocol.valueOf(value.getValue("protocol").jsonPrimitive.content),
            reasoningEffort = value["effort"]?.jsonPrimitive?.content,
            webSearch = value.getValue("webSearch").jsonPrimitive.boolean,
            backgroundResponses = value["backgroundResponses"]?.jsonPrimitive?.boolean ?: false,
        ),
        instructions = value.getValue("instructions").jsonPrimitive.content,
    )

    private companion object {
        val BaseUrl = stringPreferencesKey("base_url")
        val ApiKey = stringPreferencesKey("encrypted_api_key")
        val Model = stringPreferencesKey("model")
        val Protocol = stringPreferencesKey("protocol")
        val Effort = stringPreferencesKey("reasoning_effort")
        val WebSearch = booleanPreferencesKey("web_search")
        val BackgroundResponses = booleanPreferencesKey("background_responses")
        val Instructions = stringPreferencesKey("instructions")
        val Presets = stringPreferencesKey("presets")
        // Keep the existing selection slot; it now tracks the most recent choice.
        val LastPreset = stringPreferencesKey("default_preset")
        val Quality = stringPreferencesKey("image_quality")
        val FloatingButton = booleanPreferencesKey("floating_button")
        val Notification = booleanPreferencesKey("notification")
        val NotificationCaptureDelay = intPreferencesKey("notification_capture_delay_ms")
        val ButtonSize = intPreferencesKey("button_size_dp")
        val AttachScreenshotAutomatically = booleanPreferencesKey("attach_screenshot_automatically")
        val ButtonOnRight = booleanPreferencesKey("button_on_right")
        val ButtonY = floatPreferencesKey("button_y")
    }
}
