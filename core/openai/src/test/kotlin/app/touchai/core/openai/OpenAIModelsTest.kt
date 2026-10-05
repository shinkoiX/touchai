package app.touchai.core.openai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenAIModelsTest {
    @Test fun webSearchIsOnByDefaultAndUsesTheSelectedProtocol() {
        ApiProtocol.entries.forEach { protocol ->
            val config = OpenAIModelConfig(model = "test", protocol = protocol)
            assertTrue(config.webSearch)
            val body = buildRequestBody(config, request)
            if (protocol == ApiProtocol.ChatCompletions) {
                assertEquals(JsonObject(emptyMap()), body["web_search_options"])
                assertFalse(body.containsKey("tools"))
            } else {
                assertEquals("web_search", body.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
                assertFalse(body.containsKey("web_search_options"))
            }
            val disabled = buildRequestBody(config.copy(webSearch = false), request)
            assertFalse(disabled.containsKey("tools"))
            assertFalse(disabled.containsKey("web_search_options"))
        }
    }
    private val image = OpenAIImage("data:image/png;base64,aW1hZ2U=", "high")
    private val request = OpenAIRequest(
        listOf(ChatMessage(MessageRole.User, "Describe this", listOf(image)),
            ChatMessage(MessageRole.Assistant, "A picture"), ChatMessage(MessageRole.User, "Why?")),
        "Be concise.",
    )

    @Test fun chatCompletionsIncludesHistoryImagesAndEffort() {
        val config = OpenAIModelConfig(model = "test", reasoningEffort = "low")
        val body = buildRequestBody(config, request)
        val messages = body.getValue("messages").jsonArray
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals("A picture", messages[2].jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("Why?", messages[3].jsonObject.getValue("content").jsonPrimitive.content)
        val content = messages[1].jsonObject.getValue("content").jsonArray
        assertEquals(image.url, content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("low", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning"))
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
    }

    @Test fun responsesIncludesHistoryAndUsesItsOwnSchema() {
        val config = OpenAIModelConfig(model = "test", protocol = ApiProtocol.Responses, reasoningEffort = "high")
        val body = buildRequestBody(config, request)
        val input = body.getValue("input").jsonArray
        assertEquals(3, input.size)
        val imageContent = input[0].jsonObject.getValue("content").jsonArray[1].jsonObject
        assertEquals("input_image", imageContent.getValue("type").jsonPrimitive.content)
        assertEquals(image.url, imageContent.getValue("image_url").jsonPrimitive.content)
        assertEquals("high", body.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
        assertFalse(body.getValue("store").jsonPrimitive.boolean)
        assertFalse(body.containsKey("messages"))
    }

    @Test fun providerDefaultDoesNotSendAnEffortParameter() {
        ApiProtocol.entries.forEach { protocol ->
            val body = buildRequestBody(OpenAIModelConfig(model = "test", protocol = protocol), request)
            assertFalse(body.containsKey("reasoning"))
            assertFalse(body.containsKey("reasoning_effort"))
        }
    }

    @Test fun buildsTheExplicitEndpointUnderTheConfiguredBasePath() {
        ApiProtocol.entries.forEach { protocol ->
            val config = OpenAIModelConfig(baseUrl = "https://api.example.com/custom/v1/", protocol = protocol)
            assertEquals("https://api.example.com/custom/v1/${protocol.path}", buildRequestUrl(config))
        }
    }
}
