package app.touchai.android

import app.touchai.core.openai.*
import kotlinx.serialization.json.*

internal fun encodeChatTurn(turn: ChatTurn, encryptedApiKey: String? = null) = buildJsonObject {
    put("text", turn.user.text)
    putJsonArray("images") { turn.user.images.forEach { image -> add(buildJsonObject {
        put("url", image.url); put("detail", image.detail)
    }) } }
    put("presetName", turn.presetName); put("answer", turn.answer)
    put("status", if (encryptedApiKey == null && turn.isRunning) TurnStatus.Interrupted.name else turn.status.name)
    if (encryptedApiKey != null) {
        put("responseId", turn.responseId); put("cancelRequested", turn.cancelRequested)
    }
    put("error", turn.error); put("searchStatus", turn.searchStatus)
    putJsonArray("generatedImages") { turn.generatedImages.forEach { output -> add(buildJsonObject {
        put("id", output.id); put("url", output.image.url); put("detail", output.image.detail)
    }) } }
    putJsonArray("citations") { turn.citations.forEach { citation -> add(buildJsonObject {
        put("url", citation.url); put("title", citation.title); put("startIndex", citation.startIndex); put("endIndex", citation.endIndex)
    }) } }
    putJsonObject("ai") {
        val api = turn.ai.api
        encryptedApiKey?.let { put("encryptedApiKey", it) }; put("model", api.model); put("baseUrl", api.baseUrl)
        put("protocol", api.protocol.name); put("reasoningEffort", api.reasoningEffort); put("webSearch", api.webSearch)
        put("timeoutMillis", api.timeoutMillis); put("instructions", turn.ai.instructions)
        put("backgroundResponses", api.backgroundResponses)
    }
}

internal fun decodeChatTurn(value: JsonObject, apiKey: String): ChatTurn {
    val ai = value.getValue("ai").jsonObject
    val status = TurnStatus.valueOf(value.string("status"))
    return ChatTurn(
        user = ChatMessage(MessageRole.User, value.string("text"), value.getValue("images").jsonArray.map { item ->
            item.jsonObject.let { OpenAIImage(it.string("url"), it.string("detail")) }
        }),
        ai = AiConfiguration(OpenAIModelConfig(
            apiKey = apiKey, model = ai.string("model"), baseUrl = ai.string("baseUrl"),
            protocol = ApiProtocol.valueOf(ai.string("protocol")), reasoningEffort = ai.optionalString("reasoningEffort"),
            webSearch = ai.getValue("webSearch").jsonPrimitive.boolean, timeoutMillis = ai.getValue("timeoutMillis").jsonPrimitive.long,
            backgroundResponses = ai["backgroundResponses"]?.jsonPrimitive?.boolean ?: false,
        ), ai.string("instructions")),
        presetName = value.optionalString("presetName"), answer = value.string("answer"),
        status = status,
        responseId = value["responseId"]?.jsonPrimitive?.contentOrNull,
        cancelRequested = value["cancelRequested"]?.jsonPrimitive?.boolean ?: false,
        error = value.optionalString("error"), searchStatus = value.optionalString("searchStatus"),
        generatedImages = (value["generatedImages"] as? JsonArray).orEmpty().map { item -> item.jsonObject.let {
            GeneratedImage(it.string("id"), OpenAIImage(it.string("url"), it.string("detail")))
        } },
        citations = value.getValue("citations").jsonArray.map { item -> item.jsonObject.let {
            WebCitation(it.string("url"), it.string("title"), it.getValue("startIndex").jsonPrimitive.intOrNull,
                it.getValue("endIndex").jsonPrimitive.intOrNull)
        } },
    )
}


private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String) = getValue(key).jsonPrimitive.contentOrNull
