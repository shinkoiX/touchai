package app.touchai.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.touchai.core.openai.ApiProtocol
import app.touchai.core.openai.AuthenticationMethod
import app.touchai.core.openai.GeminiBaseUrl
import app.touchai.core.openai.OpenAIModelConfig
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class DebugApiImportPayload(
    val apiKey: String,
    val baseUrl: String? = null,
    val model: String? = null,
    val protocol: ApiProtocol? = null,
    val name: String = "Debug API",
    val reasoningEffort: String? = null,
    val webSearch: Boolean? = null,
    val backgroundResponses: Boolean? = null,
    val instructions: String? = null,
    val setDefault: Boolean = true,
)

internal fun parseDebugApiProtocol(raw: String?): ApiProtocol? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return when (value.lowercase(Locale.ROOT).replace("-", "_").replace(" ", "_")) {
        "responses" -> ApiProtocol.Responses
        "chatcompletions", "chat_completions", "completions" -> ApiProtocol.ChatCompletions
        "gemini", "interactions" -> ApiProtocol.Gemini
        else -> throw IllegalArgumentException("Unsupported protocol '$raw'. Use Responses, ChatCompletions, or Gemini.")
    }
}

internal fun parseDebugApiImportJson(rawJson: String): DebugApiImportPayload {
    val obj = Json.parseToJsonElement(rawJson).jsonObject
    val apiKey = obj.optString("apiKey", "api_key")?.trim().orEmpty()
    require(apiKey.isNotEmpty()) { "Missing apiKey/api_key in import payload." }
    return DebugApiImportPayload(
        apiKey = apiKey,
        baseUrl = obj.optString("baseUrl", "base_url"),
        model = obj.optString("model"),
        protocol = parseDebugApiProtocol(obj.optString("protocol")),
        name = obj.optString("name")?.trim()?.takeIf(String::isNotEmpty) ?: "Debug API",
        reasoningEffort = obj.optString("reasoningEffort", "reasoning_effort"),
        webSearch = obj.optBoolean("webSearch", "web_search"),
        backgroundResponses = obj.optBoolean("backgroundResponses", "background_responses"),
        instructions = obj.optString("instructions"),
        setDefault = obj.optBoolean("setDefault", "set_default") ?: true,
    )
}

internal fun mergeDebugApiImport(current: AppSettings, payload: DebugApiImportPayload): AppSettings {
    val existing = current.withConnections()
    val resolvedName = payload.name.trim().ifEmpty { "Debug API" }
    val matched = existing.connections.firstOrNull { it.name.equals(resolvedName, ignoreCase = true) }
    val protocol = payload.protocol ?: matched?.protocol ?: ApiProtocol.Responses
    val defaultUrl = if (protocol == ApiProtocol.Gemini) GeminiBaseUrl else OpenAIModelConfig().baseUrl
    val baseUrl = payload.baseUrl?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty)
        ?: matched?.baseUrl
        ?: defaultUrl
    val apiKey = payload.apiKey.trim()
    val model = payload.model?.trim()?.takeIf(String::isNotEmpty) ?: existing.api.model
    val candidateConfig = OpenAIModelConfig(
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl,
        protocol = protocol,
        reasoningEffort = payload.reasoningEffort?.trim()?.takeIf(String::isNotEmpty) ?: existing.api.reasoningEffort,
        webSearch = payload.webSearch ?: existing.api.webSearch,
        backgroundResponses = payload.backgroundResponses ?: existing.api.backgroundResponses,
        authentication = AuthenticationMethod.ApiKey,
    )
    apiError(candidateConfig, requireCredentials = false)?.let { throw IllegalArgumentException(it) }
    require(apiKey.isNotBlank()) { "API key must not be blank." }

    val connectionId = matched?.id ?: "debug-${UUID.randomUUID()}"
    val connection = ApiConnection(
        id = connectionId,
        name = resolvedName,
        authentication = AuthenticationMethod.ApiKey,
        baseUrl = baseUrl,
        protocol = protocol,
        apiKey = apiKey,
    )
    val updatedConnections = if (matched != null) {
        existing.connections.map { if (it.id == matched.id) connection else it }
    } else {
        existing.connections + connection
    }
    val updatedApi = if (payload.setDefault) {
        existing.api.copy(
            model = model,
            reasoningEffort = payload.reasoningEffort?.trim()?.takeIf(String::isNotEmpty) ?: existing.api.reasoningEffort,
            webSearch = payload.webSearch ?: existing.api.webSearch,
            backgroundResponses = (payload.backgroundResponses ?: existing.api.backgroundResponses) && connection.supportsBackgroundResponses,
        ).withoutConnection()
    } else {
        existing.api
    }
    return existing.copy(
        connections = updatedConnections,
        connectionId = if (payload.setDefault) connectionId else existing.connectionId,
        api = updatedApi,
        instructions = if (payload.setDefault && payload.instructions != null) payload.instructions else existing.instructions,
    )
}

internal suspend fun applyDebugApiImport(repository: SettingsRepository, payload: DebugApiImportPayload): AppSettings {
    val updated = mergeDebugApiImport(repository.load(), payload)
    repository.save(updated)
    return repository.load()
}

/**
 * Debug-only ADB receiver for injecting API credentials dynamically without storing secrets in Git or APKs.
 *
 * Usage (file mode — automatically deletes the temporary file after encrypting into DataStore):
 *   adb push api-import.json /sdcard/Android/data/app.touchai.android.debug/files/api-import.json
 *   adb shell am broadcast -n app.touchai.android.debug/app.touchai.android.DebugApiImportReceiver \
 *     -a app.touchai.android.debug.IMPORT_API_CONFIG
 *
 * Usage (inline extras mode):
 *   adb shell am broadcast -n app.touchai.android.debug/app.touchai.android.DebugApiImportReceiver \
 *     -a app.touchai.android.debug.IMPORT_API_CONFIG \
 *     --es api_key "$KEY" --es base_url "https://api.openai.com/v1" --es model "gpt-5.4" --es protocol "Responses"
 */
class DebugApiImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_IMPORT_API_CONFIG) return
        val pending = goAsync()
        val app = context.applicationContext as TouchAiApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val payload = resolvePayload(context, intent)
                val saved = applyDebugApiImport(app.settingsRepository, payload)
                val active = saved.connection(saved.connectionId)
                // Never include the API key in broadcast output or logs.
                val summary = "Imported connection '${payload.name}' (${active?.protocol ?: payload.protocol ?: ApiProtocol.Responses}, " +
                    "model='${saved.api.model.ifBlank { "(unchanged)" }}')"
                pending.setResult(Activity.RESULT_OK, summary, null)
            } catch (error: Exception) {
                pending.setResult(Activity.RESULT_CANCELED, "Import failed: ${error.message}", null)
            } finally {
                pending.finish()
            }
        }
    }

    private fun resolvePayload(context: Context, intent: Intent): DebugApiImportPayload {
        val inlineKey = intent.getStringExtra(EXTRA_API_KEY)?.trim()
        if (!inlineKey.isNullOrEmpty()) {
            return DebugApiImportPayload(
                apiKey = inlineKey,
                baseUrl = intent.getStringExtra(EXTRA_BASE_URL),
                model = intent.getStringExtra(EXTRA_MODEL),
                protocol = parseDebugApiProtocol(intent.getStringExtra(EXTRA_PROTOCOL)),
                name = intent.getStringExtra(EXTRA_NAME)?.trim()?.takeIf(String::isNotEmpty) ?: "Debug API",
                reasoningEffort = intent.getStringExtra(EXTRA_REASONING_EFFORT),
                webSearch = if (intent.hasExtra(EXTRA_WEB_SEARCH)) intent.getBooleanExtra(EXTRA_WEB_SEARCH, true) else null,
                backgroundResponses = if (intent.hasExtra(EXTRA_BACKGROUND_RESPONSES)) intent.getBooleanExtra(EXTRA_BACKGROUND_RESPONSES, false) else null,
                instructions = intent.getStringExtra(EXTRA_INSTRUCTIONS),
                setDefault = intent.getBooleanExtra(EXTRA_SET_DEFAULT, true),
            )
        }
        val fileName = intent.getStringExtra(EXTRA_FILE)?.trim()?.takeIf(String::isNotEmpty) ?: DEFAULT_IMPORT_FILE
        val externalDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("App external files directory is unavailable.")
        val target = File(externalDir, File(fileName).name)
        require(target.isFile) { "Provide --es api_key or push ${target.absolutePath}." }
        val raw = try {
            target.readText(Charsets.UTF_8)
        } finally {
            target.delete()
        }
        return parseDebugApiImportJson(raw)
    }

    companion object {
        const val ACTION_IMPORT_API_CONFIG = "app.touchai.android.debug.IMPORT_API_CONFIG"
        const val DEFAULT_IMPORT_FILE = "api-import.json"
        const val EXTRA_FILE = "file"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_BASE_URL = "base_url"
        const val EXTRA_MODEL = "model"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_NAME = "name"
        const val EXTRA_REASONING_EFFORT = "reasoning_effort"
        const val EXTRA_WEB_SEARCH = "web_search"
        const val EXTRA_BACKGROUND_RESPONSES = "background_responses"
        const val EXTRA_INSTRUCTIONS = "instructions"
        const val EXTRA_SET_DEFAULT = "set_default"
    }
}

private fun JsonObject.optString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitive?.contentOrNull }

private fun JsonObject.optBoolean(vararg keys: String): Boolean? =
    keys.firstNotNullOfOrNull { key -> this[key]?.jsonPrimitive?.booleanOrNull }
