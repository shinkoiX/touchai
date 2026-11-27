package app.touchai.core.openui

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Thread-safe headless QuickJS runner hosting the official @openuidev/lang-core +
 * @openuidev/intelligent-ui-library function runtime.
 */
class OpenUiEngine(
    private val bundleProvider: () -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var quickJs: QuickJs? = null

    private suspend fun ensureEngine(): QuickJs {
        quickJs?.let { return it }
        val instance = QuickJs.create(dispatcher)
        instance.evaluate<Any?>(bundleProvider())
        quickJs = instance
        return instance
    }

    suspend fun pushText(
        sessionId: String,
        fullText: String,
        persistedState: JsonObject? = null,
    ): OpenUiRenderState = mutex.withLock {
        runCatching {
            val engine = ensureEngine()
            val sid = jsString(sessionId)
            val txt = jsString(fullText)
            val persisted = persistedState?.let { jsString(it.toString()) } ?: "null"
            val raw = engine.evaluate<String>("OpenUIHeadless.pushText($sid, $txt, $persisted)")
            parseOpenUiPayload(json.parseToJsonElement(raw).jsonObject)
        }.getOrElse { error ->
            OpenUiRenderState(error = error.message ?: "OpenUI evaluation failed")
        }
    }

    suspend fun setState(
        sessionId: String,
        varName: String,
        value: JsonElement,
    ): OpenUiRenderState = mutex.withLock {
        runCatching {
            val engine = ensureEngine()
            val sid = jsString(sessionId)
            val key = jsString(varName)
            val valueJson = jsString(value.toString())
            val raw = engine.evaluate<String>("OpenUIHeadless.setState($sid, $key, $valueJson)")
            parseOpenUiPayload(json.parseToJsonElement(raw).jsonObject)
        }.getOrElse { error ->
            OpenUiRenderState(error = error.message ?: "OpenUI setState failed")
        }
    }

    suspend fun dispatchAction(
        sessionId: String,
        action: JsonObject,
    ): OpenUiRenderState = mutex.withLock {
        runCatching {
            val engine = ensureEngine()
            val sid = jsString(sessionId)
            val actionJson = jsString(action.toString())
            val raw = engine.evaluate<String>("OpenUIHeadless.dispatchAction($sid, $actionJson)")
            parseOpenUiPayload(json.parseToJsonElement(raw).jsonObject)
        }.getOrElse { error ->
            OpenUiRenderState(error = error.message ?: "OpenUI action failed")
        }
    }

    suspend fun disposeSession(sessionId: String) = mutex.withLock {
        val engine = quickJs ?: return@withLock
        runCatching {
            engine.evaluate<Any?>("OpenUIHeadless.disposeSession(${jsString(sessionId)})")
        }
    }

    override fun close() {
        quickJs?.close()
        quickJs = null
    }

    private fun jsString(value: String): String =
        json.encodeToString(String.serializer(), value)
}
