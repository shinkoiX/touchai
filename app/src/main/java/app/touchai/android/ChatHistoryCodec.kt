package app.touchai.android

import app.touchai.core.openai.*
import kotlinx.serialization.json.*

internal fun encodeChatTurn(turn: ChatTurn, encryptedApiKey: String? = null) = buildJsonObject {
    put("text", turn.user.text)
    putJsonArray("images") { turn.user.images.forEach { image -> add(buildJsonObject {
        put("url", image.url); put("detail", image.detail)
    }) } }
    put("presetName", turn.presetName); put("answer", turn.answer)
    if (turn.reasoning.isNotEmpty()) put("reasoning", turn.reasoning)
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
        api.serviceTier?.let { put("serviceTier", it) }
        put("timeoutMillis", api.timeoutMillis); put("instructions", turn.ai.instructions)
        put("backgroundResponses", api.backgroundResponses)
        put("authentication", api.authentication.name)
        if (encryptedApiKey != null) api.chatGptAccountId?.let { put("chatGptAccountId", it) }
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
            serviceTier = ai["serviceTier"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty),
            webSearch = ai.getValue("webSearch").jsonPrimitive.boolean, timeoutMillis = ai.getValue("timeoutMillis").jsonPrimitive.long,
            backgroundResponses = ai["backgroundResponses"]?.jsonPrimitive?.boolean ?: false,
            authentication = ai["authentication"]?.jsonPrimitive?.content?.let(AuthenticationMethod::valueOf) ?: AuthenticationMethod.ApiKey,
            chatGptAccountId = ai["chatGptAccountId"]?.jsonPrimitive?.content,
        ), ai.string("instructions")),
        presetName = value.optionalString("presetName"), answer = value.string("answer"),
        reasoning = value["reasoning"]?.jsonPrimitive?.contentOrNull.orEmpty(),
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

internal data class DecodedChatBody(
    val selectedPreset: String?,
    val turns: List<ChatTurn>,
    val tree: ChatTree?,
)

/**
 * Encodes chat turns and optional branch tree.
 * Top-level `turns` always stores the latest version so history search (and legacy readers)
 * automatically see only the latest version. Nodes on the latest path reference `turnIndex`
 * to avoid duplicating large base64 attachments.
 */
internal fun encodeChatBody(
    selectedPreset: String?,
    turns: List<ChatTurn>,
    tree: ChatTree?,
    encryptApiKey: (ChatTurn) -> String?,
): JsonObject = buildJsonObject {
    put("selectedPreset", selectedPreset)
    val branchedTree = tree?.takeIf { it.hasBranches }
    val latestPath = branchedTree?.latestPath()
    val latestTurns = latestPath?.map { it.turn } ?: turns
    putJsonArray("turns") { latestTurns.forEach { add(encodeChatTurn(it, encryptApiKey(it))) } }
    if (branchedTree != null && latestPath != null) {
        val latestIndexById = latestPath.mapIndexed { index, node -> node.id to index }.toMap()
        putJsonObject("tree") {
            branchedTree.activeLeafId?.let { put("activeLeafId", it) }
            putJsonArray("nodes") {
                branchedTree.nodes.forEach { node ->
                    add(buildJsonObject {
                        put("id", node.id)
                        put("parentId", node.parentId)
                        put("createdAt", node.createdAt)
                        val latestIdx = latestIndexById[node.id]
                        if (latestIdx != null) {
                            put("turnIndex", latestIdx)
                        } else {
                            put("turn", encodeChatTurn(node.turn, encryptApiKey(node.turn)))
                        }
                    })
                }
            }
        }
    }
}

internal fun decodeChatBody(
    body: JsonObject,
    decryptApiKey: (JsonObject) -> String,
): DecodedChatBody {
    val selectedPreset = body.optionalString("selectedPreset")
    val latestTurns = body.getValue("turns").jsonArray.map { item ->
        val obj = item.jsonObject
        decodeChatTurn(obj, decryptApiKey(obj))
    }
    val treeObj = body["tree"] as? JsonObject
    if (treeObj != null) {
        val nodes = treeObj.getValue("nodes").jsonArray.map { item ->
            val obj = item.jsonObject
            val turnIndex = obj["turnIndex"]?.jsonPrimitive?.intOrNull
            val turn = if (turnIndex != null) {
                latestTurns[turnIndex]
            } else {
                val turnJson = obj.getValue("turn").jsonObject
                decodeChatTurn(turnJson, decryptApiKey(turnJson))
            }
            ChatTreeNode(
                id = obj.string("id"),
                parentId = obj["parentId"]?.jsonPrimitive?.contentOrNull,
                turn = turn,
                createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
        val activeLeafId = treeObj["activeLeafId"]?.jsonPrimitive?.contentOrNull
        val tree = ChatTree(nodes, activeLeafId)
        val activeTurns = tree.activeTurns().takeIf { it.isNotEmpty() } ?: latestTurns
        return DecodedChatBody(selectedPreset, activeTurns, tree.takeIf { it.hasBranches })
    }
    return DecodedChatBody(selectedPreset, latestTurns, null)
}


private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
private fun JsonObject.optionalString(key: String) = getValue(key).jsonPrimitive.contentOrNull
