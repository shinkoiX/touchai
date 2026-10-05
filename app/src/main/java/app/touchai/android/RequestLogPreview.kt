package app.touchai.android

import kotlinx.serialization.json.*

internal fun requestLogPreviewText(value: JsonObject): String {
    val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), requestLogPreview(value).jsonObject)
    return if (text.length > 64_000) text.take(64_000) + "\n…" else text
}

/** Bound the displayed/copyable JSON; exporting uses the complete stored record. */
internal fun requestLogPreview(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.mapValues { requestLogPreview(it.value) })
    is JsonArray -> JsonArray(value.map(::requestLogPreview))
    is JsonPrimitive -> if (value.isString && value.content.length > 4_096)
        JsonPrimitive(value.content.take(512) + "… [${value.content.length} characters]") else value
}
