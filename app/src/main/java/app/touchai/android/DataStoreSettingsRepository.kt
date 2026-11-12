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
            connectionId = prefs[DefaultConnection]?.takeIf(String::isNotBlank),
            connections = prefs[Connections]?.let { serialized ->
                Json.parseToJsonElement(serialized).jsonArray.map { item -> decodeConnection(item.jsonObject) }
            }.orEmpty(),
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
            imageLimit = prefs[ImageLimit] ?: defaults.imageLimit,
            quickAccess = readQuickAccess(prefs),
        ).withConnections()
    }

    override suspend fun save(settings: AppSettings) = withContext(Dispatchers.IO) {
        val saved = settings.withConnections()
        val connections = buildJsonArray { saved.connections.forEach { add(encodeConnection(it)) } }.toString()
        val presets = buildJsonArray {
            saved.presets.forEach { preset -> add(buildJsonObject {
                put("id", preset.id)
                put("name", preset.name)
                put("prompt", preset.prompt)
                put("attachScreenshot", preset.attachScreenshot)
                preset.customAi?.let { put("ai", encodeAi(it)) }
            }) }
        }.toString()
        store.edit { prefs ->
            // Endpoints and credentials now live only in connections.
            listOf(BaseUrl, ApiKey, Protocol, Authentication, ChatGptAccountId).forEach { prefs.remove(it) }
            prefs[Connections] = connections
            prefs[DefaultConnection] = saved.connectionId.orEmpty()
            prefs[Model] = saved.api.model
            prefs[Effort] = saved.api.reasoningEffort ?: ""
            prefs[WebSearch] = saved.api.webSearch
            prefs[BackgroundResponses] = saved.api.backgroundResponses
            prefs[Instructions] = saved.instructions
            prefs[Presets] = presets
            val selected = prefs[LastPreset] ?: AppSettings().lastPresetId
            if (saved.presets.none { it.id == selected }) prefs[LastPreset] = ""
            prefs[Quality] = saved.imageQuality.name
            prefs[ImageLimit] = saved.imageLimit
            prefs[FloatingButton] = saved.quickAccess.floatingButton
            prefs[CornerSwipe] = saved.quickAccess.cornerSwipe
            prefs[GestureCornerPosition] = saved.quickAccess.gestureCorner.name
            prefs[CornerGestures] = saved.quickAccess.cornerGestures.map { it.name }.toSet()
            prefs[CornerOpacity] = saved.quickAccess.cornerOpacityPercent
            prefs[CornerAreaSize] = saved.quickAccess.cornerAreaSizeDp
            prefs[Notification] = saved.quickAccess.notification
            prefs[NotificationCaptureDelay] = saved.quickAccess.notificationCaptureDelayMillis
            prefs[ButtonSize] = saved.quickAccess.buttonSizeDp
            prefs[AttachScreenshotAutomatically] = saved.quickAccess.attachScreenshotAutomatically
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

    /** Saved after [withConnections], so only model options and the connection id are written. */
    private fun encodeAi(configuration: AiConfiguration) = buildJsonObject {
        val api = configuration.api
        put("connectionId", configuration.connectionId)
        put("model", api.model)
        api.reasoningEffort?.let { put("effort", it) }
        put("webSearch", api.webSearch)
        put("backgroundResponses", api.backgroundResponses)
        put("instructions", configuration.instructions)
    }

    /** Also reads older presets that carried their own endpoint and encrypted key; load migrates them. */
    private fun decodeAi(value: JsonObject): AiConfiguration {
        val legacy = OpenAIModelConfig()
        return AiConfiguration(
            api = OpenAIModelConfig(
                baseUrl = value["baseUrl"]?.jsonPrimitive?.content ?: legacy.baseUrl,
                apiKey = cipher.decrypt(value["encryptedApiKey"]?.jsonPrimitive?.content ?: ""),
                model = value.getValue("model").jsonPrimitive.content,
                protocol = value["protocol"]?.jsonPrimitive?.content?.let(ApiProtocol::valueOf) ?: legacy.protocol,
                reasoningEffort = value["effort"]?.jsonPrimitive?.content,
                webSearch = value.getValue("webSearch").jsonPrimitive.boolean,
                backgroundResponses = value["backgroundResponses"]?.jsonPrimitive?.boolean ?: false,
                authentication = value["authentication"]?.jsonPrimitive?.content?.let(AuthenticationMethod::valueOf) ?: AuthenticationMethod.ApiKey,
                chatGptAccountId = value["chatGptAccountId"]?.jsonPrimitive?.content,
            ),
            instructions = value.getValue("instructions").jsonPrimitive.content,
            connectionId = value["connectionId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
        )
    }

    private fun encodeConnection(connection: ApiConnection) = buildJsonObject {
        put("id", connection.id)
        put("name", connection.name)
        put("authentication", connection.authentication.name)
        put("baseUrl", connection.baseUrl)
        put("protocol", connection.protocol.name)
        put("encryptedApiKey", cipher.encrypt(connection.apiKey))
        put("chatGptAccountId", connection.chatGptAccountId)
    }

    private fun decodeConnection(value: JsonObject) = ApiConnection(
        id = value.getValue("id").jsonPrimitive.content,
        name = value.getValue("name").jsonPrimitive.content,
        authentication = AuthenticationMethod.valueOf(value.getValue("authentication").jsonPrimitive.content),
        baseUrl = value.getValue("baseUrl").jsonPrimitive.content,
        protocol = ApiProtocol.valueOf(value.getValue("protocol").jsonPrimitive.content),
        apiKey = cipher.decrypt(value.getValue("encryptedApiKey").jsonPrimitive.content),
        chatGptAccountId = value["chatGptAccountId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
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
        val Connections = stringPreferencesKey("connections")
        val DefaultConnection = stringPreferencesKey("default_connection_id")
        val Presets = stringPreferencesKey("presets")
        // Keep the existing selection slot; it now tracks the most recent choice.
        val LastPreset = stringPreferencesKey("default_preset")
        val Quality = stringPreferencesKey("image_quality")
        val ImageLimit = intPreferencesKey("image_limit")
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
