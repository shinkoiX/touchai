package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApiModelCatalogTest {
    @Test fun listsOpenAiCompatibleModelsSortedWithDisplayNames() = runBlocking {
        var url: String? = null
        var authorization: String? = null
        val http = HttpClient(MockEngine { request ->
            url = request.url.toString(); authorization = request.headers[HttpHeaders.Authorization]
            respond("""{"object":"list","data":[{"id":"gpt-b","object":"model"},{"id":"claude-x","display_name":"Claude X"},{"id":"gpt-b"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }) { expectSuccess = false }
        val models = ApiModelCatalog(http).models("https://provider.example.com/v1/", "test-only-key")
        assertEquals("https://provider.example.com/v1/models", url)
        assertEquals("Bearer test-only-key", authorization)
        assertEquals(listOf(AvailableModel("claude-x", "Claude X"), AvailableModel("gpt-b")), models)
    }

    @Test fun acceptsOtherCommonListShapes() {
        assertEquals(listOf("a", "b"), parseModelList("""{"models":[{"name":"b"},{"slug":"a"}]}""").map { it.id })
        assertEquals(listOf("x"), parseModelList("""["x"]""").map { it.id })
    }

    @Test fun failuresUseSafeMessagesWithoutTheKey() = runBlocking {
        listOf(HttpStatusCode.Unauthorized to "rejected", HttpStatusCode.NotFound to "Type the model ID").forEach { (status, text) ->
            val http = HttpClient(MockEngine { respond("""{"error":{"message":"bad key test-only-key"}}""", status) }) { expectSuccess = false }
            val error = assertThrows(OpenAIRequestException::class.java) {
                runBlocking { ApiModelCatalog(http).models("https://provider.example.com/v1", "test-only-key") }
            }
            assertTrue(error.message!!, error.message!!.contains(text))
            assertFalse(error.message!!.contains("test-only-key"))
        }
        val garbled = HttpClient(MockEngine { respond("not json", HttpStatusCode.OK) }) { expectSuccess = false }
        assertThrows(OpenAIRequestException::class.java) { runBlocking { ApiModelCatalog(garbled).models("https://p.example.com/v1", "k") } }
        Unit
    }
}
