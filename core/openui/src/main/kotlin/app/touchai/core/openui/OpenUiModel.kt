package app.touchai.core.openui

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OpenUiElement(
    val typeName: String,
    val props: JsonObject,
    val partial: Boolean = false,
    val componentId: String? = null,
    val statementId: String? = null,
)

data class OpenUiMeta(
    val incomplete: Boolean = false,
    val statementCount: Int = 0,
    val errorCount: Int = 0,
)

sealed interface OpenUiEffect {
    data class OpenUrl(val url: String) : OpenUiEffect
    data class ToAssistant(val message: String, val context: JsonElement? = null) : OpenUiEffect
}

data class OpenUiRenderState(
    val root: OpenUiElement? = null,
    val state: JsonObject = JsonObject(emptyMap()),
    val meta: OpenUiMeta? = null,
    val effects: List<OpenUiEffect> = emptyList(),
    val error: String? = null,
)

fun JsonElement?.asOpenUiElementOrNull(): OpenUiElement? {
    val obj = this as? JsonObject ?: return null
    if (obj["type"]?.jsonPrimitive?.contentOrNull != "element") return null
    val typeName = obj["typeName"]?.jsonPrimitive?.contentOrNull ?: return null
    val props = (obj["props"] as? JsonObject) ?: JsonObject(emptyMap())
    return OpenUiElement(
        typeName = typeName,
        props = props,
        partial = obj["partial"]?.jsonPrimitive?.booleanOrNull == true,
        componentId = obj["componentId"]?.jsonPrimitive?.contentOrNull,
        statementId = obj["statementId"]?.jsonPrimitive?.contentOrNull,
    )
}

fun JsonObject.stringProp(key: String): String? {
    val el = this[key] ?: return null
    if (el is JsonNull) return null
    return (el as? JsonPrimitive)?.contentOrNull
}

fun JsonObject.intProp(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

fun JsonObject.doubleProp(key: String): Double? =
    (this[key] as? JsonPrimitive)?.doubleOrNull

fun JsonObject.boolProp(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

fun JsonObject.objectProp(key: String): JsonObject? =
    this[key] as? JsonObject

fun JsonObject.arrayProp(key: String): JsonArray? =
    this[key] as? JsonArray

fun JsonObject.elementListProp(key: String): List<OpenUiElement> =
    (this[key] as? JsonArray)?.mapNotNull { it.asOpenUiElementOrNull() }.orEmpty()

fun JsonObject.bindingTarget(key: String): String? {
    val obj = this[key] as? JsonObject ?: return null
    if (obj["__reactive"]?.jsonPrimitive?.contentOrNull != "assign") return null
    return obj["target"]?.jsonPrimitive?.contentOrNull
}

fun JsonObject.boundValue(key: String, state: JsonObject): JsonElement? {
    val target = bindingTarget(key)
    if (target != null) return state[target]
    return this[key]?.takeUnless { it is JsonNull }
}

fun parseOpenUiPayload(json: JsonObject): OpenUiRenderState {
    val root = json["tree"].asOpenUiElementOrNull()
    val state = (json["state"] as? JsonObject) ?: JsonObject(emptyMap())
    val metaObj = json["meta"] as? JsonObject
    val meta = metaObj?.let { m ->
        OpenUiMeta(
            incomplete = m["incomplete"]?.jsonPrimitive?.booleanOrNull == true,
            statementCount = m["statementCount"]?.jsonPrimitive?.intOrNull ?: 0,
            errorCount = (m["errors"] as? JsonArray)?.size ?: 0,
        )
    }
    val effects = (json["effects"] as? JsonArray)?.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "open_url" -> obj["url"]?.jsonPrimitive?.contentOrNull?.let(OpenUiEffect::OpenUrl)
            "to_assistant" -> OpenUiEffect.ToAssistant(
                message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                context = obj["context"]?.takeUnless { it is JsonNull },
            )
            else -> null
        }
    }.orEmpty()
    return OpenUiRenderState(root = root, state = state, meta = meta, effects = effects)
}
