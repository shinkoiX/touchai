package app.touchai.android

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import app.touchai.core.openai.ApiProtocol
import app.touchai.core.openai.AuthenticationMethod
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
                authentication = prefs[Authentication]?.let(AuthenticationMethod::valueOf) ?: AuthenticationMethod.ApiKey,
                chatGptAccountId = prefs[ChatGptAccountId]?.takeIf(String::isNotBlank),
            ),
            instructions = prefs[Instructions] ?: "",
            presets = prefs[Presets]?.let { serialized ->
                Json.parseToJsonElement(serialized).jsonArray.map { item ->
                    val preset = item.jsonObject
                    PromptPreset(preset.getValue("id").jsonPrimitive.content,
                        preset.getValue("name").jsonPrimitive.content,
                        preset.getValue("prompt").jsonPrimitive.content,
                        (preset["ai"] as? JsonObject)?.let(::decodeAi),
                        attachScreenshot = preset["attachScreenshot"]?.jsonPrimitive?.booleanOrNull)
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
                put("attachScreenshot", preset.attachScreenshot)
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
            prefs[Authentication] = settings.api.authentication.name
            prefs[ChatGptAccountId] = settings.api.chatGptAccountId.orEmpty()
            prefs[Instructions] = settings.instructions
            prefs[Presets] = presets
            val selected = prefs[LastPreset] ?: AppSettings().lastPresetId
            if (settings.presets.none { it.id == selected }) prefs[LastPreset] = ""
            prefs[Quality] = settings.imageQuality.name
            prefs[FloatingButton] = settings.quickAccess.floatingButton
            prefs[CornerSwipe] = settings.quickAccess.cornerSwipe
            prefs[GestureCornerPosition] = settings.quickAccess.gestureCorner.name
            prefs[CornerGestures] = settings.quickAccess.cornerGestures.map { it.name }.toSet()
            prefs[CornerOpacity] = settings.quickAccess.cornerOpacityPercent
            prefs[CornerAreaSize] = settings.quickAccess.cornerAreaSizeDp
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
        store.edit { it[FloatingButton] = false; it[Notification] = false; it[CornerSwipe] = false }
    }

    private fun readQuickAccess(prefs: Preferences) = QuickAccessSettings(
        prefs[FloatingButton] ?: true, prefs[Notification] ?: true,
        prefs[ButtonOnRight] ?: true, prefs[ButtonY] ?: 0.35f,
        notificationCaptureDelayMillis = prefs[NotificationCaptureDelay] ?: QuickAccessSettings().notificationCaptureDelayMillis,
        buttonSizeDp = prefs[ButtonSize] ?: QuickAccessSettings().buttonSizeDp,
        attachScreenshotAutomatically = prefs[AttachScreenshotAutomatically] ?: QuickAccessSettings().attachScreenshotAutomatically,
        cornerSwipe = prefs[CornerSwipe] ?: QuickAccessSettings().cornerSwipe,
        gestureCorner = prefs[GestureCornerPosition]?.let(GestureCorner::valueOf) ?: QuickAccessSettings().gestureCorner,
        cornerGestures = prefs[CornerGestures]?.map(CornerGesture::valueOf)?.toSet() ?: QuickAccessSettings().cornerGestures,
        cornerOpacityPercent = prefs[CornerOpacity] ?: QuickAccessSettings().cornerOpacityPercent,
        cornerAreaSizeDp = prefs[CornerAreaSize] ?: QuickAccessSettings().cornerAreaSizeDp,
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
        put("authentication", api.authentication.name)
        api.chatGptAccountId?.let { put("chatGptAccountId", it) }
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
            authentication = value["authentication"]?.jsonPrimitive?.content?.let(AuthenticationMethod::valueOf) ?: AuthenticationMethod.ApiKey,
            chatGptAccountId = value["chatGptAccountId"]?.jsonPrimitive?.content,
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
        val Authentication = stringPreferencesKey("authentication")
        val ChatGptAccountId = stringPreferencesKey("chatgpt_account_id")
        val Instructions = stringPreferencesKey("instructions")
        val Presets = stringPreferencesKey("presets")
        // Keep the existing selection slot; it now tracks the most recent choice.
        val LastPreset = stringPreferencesKey("default_preset")
        val Quality = stringPreferencesKey("image_quality")
        val FloatingButton = booleanPreferencesKey("floating_button")
        val CornerSwipe = booleanPreferencesKey("corner_swipe")
        val GestureCornerPosition = stringPreferencesKey("gesture_corner")
        val CornerGestures = stringSetPreferencesKey("corner_gestures")
        val CornerOpacity = intPreferencesKey("corner_opacity_percent")
        val CornerAreaSize = intPreferencesKey("corner_area_size_dp")
        val Notification = booleanPreferencesKey("notification")
        val NotificationCaptureDelay = intPreferencesKey("notification_capture_delay_ms")
        val ButtonSize = intPreferencesKey("button_size_dp")
        val AttachScreenshotAutomatically = booleanPreferencesKey("attach_screenshot_automatically")
        val ButtonOnRight = booleanPreferencesKey("button_on_right")
        val ButtonY = floatPreferencesKey("button_y")
    }
}
