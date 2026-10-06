package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.*
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChatGptOAuthTest {
    private val now = 1_800_000_000_000L
    private val clientId = "oaiapp_test"
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val jwks = buildJsonObject { putJsonArray("keys") { add(buildJsonObject {
        val key = pair.public as RSAPublicKey
        put("kid", "test"); put("kty", "RSA"); put("use", "sig"); put("alg", "RS256")
        put("n", base64Url(key.modulus.toByteArray())); put("e", base64Url(key.publicExponent.toByteArray()))
    }) } }
    private fun claims(nonce: String = "nonce") = buildJsonObject {
        put("iss", ChatGptOAuth.Issuer); put("sub", "test-subject"); put("aud", clientId)
        put("exp", now / 1000 + 3600); put("nonce", nonce); put("email", "person@example.com")
    }
    private fun token(claims: JsonObject, algorithm: String = "RS256"): String {
        val header = buildJsonObject { put("alg", algorithm); put("kid", "test") }
        val body = "${base64Url(header.toString().toByteArray())}.${base64Url(claims.toString().toByteArray())}"
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(body.toByteArray()) }.sign()
        return "$body.${base64Url(signature)}"
    }
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun idTokensRequireSignatureIssuerAudienceExpiryNonceAndAuthorizedParty() {
        assertEquals(ChatGptIdentity("test-subject", "person@example.com"), verifyChatGptIdToken(token(claims()), jwks, clientId, "nonce", now))
        for ((name, value) in mapOf("iss" to JsonPrimitive("https://other.example.com"), "aud" to JsonPrimitive("other-client"),
            "exp" to JsonPrimitive(now / 1000), "nonce" to JsonPrimitive("other-nonce"), "azp" to JsonPrimitive("other-client"))) {
            reject { verifyChatGptIdToken(token(JsonObject(claims() + (name to value))), jwks, clientId, "nonce", now) }
        }
        reject { verifyChatGptIdToken(token(claims(), "none"), jwks, clientId, "nonce", now) }
        val signed = token(claims()).split('.')
        val changedClaims = base64Url(JsonObject(claims() + ("sub" to JsonPrimitive("attacker"))).toString().toByteArray())
        reject { verifyChatGptIdToken("${signed[0]}.$changedClaims.${signed[2]}", jwks, clientId, "nonce", now) }
        val multiAudience = JsonObject(claims() + ("aud" to JsonArray(listOf(JsonPrimitive(clientId), JsonPrimitive("second")))))
        reject { verifyChatGptIdToken(token(multiAudience), jwks, clientId, "nonce", now) }
    }

    @Test fun registrationUsesPkceAndReturningSignInRetainsItsClientAndHost() {
        HttpClient(MockEngine { error("No network expected") }).use { http ->
            val oauth = ChatGptOAuth(http)
            val attempt = oauth.authorization("http://127.0.0.1:3456/auth/callback")
            val params = Url(oauth.authorizationUrl(attempt, "urn:uuid:test-host")).parameters
            assertEquals("dynamic_agent_client", params["client_id"])
            assertEquals("TouchAI", params["agent_name_hint"])
            assertEquals("urn:uuid:test-host", params["ext_agent_host_id"])
            assertEquals("S256", params["code_challenge_method"])
            assertEquals(base64Url(MessageDigest.getInstance("SHA-256").digest(attempt.verifier.toByteArray())), params["code_challenge"])
            assertNotEquals(attempt.state, oauth.authorization(attempt.redirectUri).state)
            val account = ChatGptAccount("account", clientId, "test-subject", "person@example.com",
                ChatGptTokens("access", "refresh", "id-hint", now, emptySet()))
            val returning = oauth.authorization(attempt.redirectUri, account)
            val returningParams = Url(oauth.authorizationUrl(returning, "urn:uuid:test-host")).parameters
            assertEquals(clientId, returningParams["client_id"])
            assertNull(returningParams["agent_name_hint"])
            assertEquals("id-hint", returningParams["id_token_hint"])
            val callback = "${returning.redirectUri}?state=${returning.state}&code=test-code"
            assertEquals(clientId, oauth.authorizationCode(returning, callback).clientId)
            reject { oauth.authorizationCode(returning, "$callback&client_id=another-client") }
            reject { oauth.authorizationCode(returning, callback.replace(returning.state, "wrong")) }
            reject { oauth.authorizationCode(returning, "$callback&state=${returning.state}") }
            reject { oauth.authorizationCode(returning, "$callback&error=access_denied") }
            reject { oauth.authorizationCode(attempt, "${attempt.redirectUri}?state=${attempt.state}&code=test-code") }
        }
    }

    @Test fun exchangeUsesIssuedClientAndChecksGrantedPlanScopesAndIdentity() = runBlocking {
        lateinit var attempt: ChatGptAuthorization
        var scope = "openid resource.invoke chatgpt.tokens.use.direct offline_access"
        var subject = "test-subject"
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/accounts/oauth/token" -> {
                    val form = (request.body as FormDataContent).formData
                    assertEquals(clientId, form["client_id"])
                    assertEquals("authorization_code", form["grant_type"])
                    assertEquals(attempt.redirectUri, form["redirect_uri"])
                    assertEquals(attempt.verifier, form["code_verifier"])
                    assertEquals(ChatGptOAuth.Resource, form["resource"])
                    assertNull(form["client_secret"])
                    respond(buildJsonObject {
                        put("access_token", "access"); put("refresh_token", "refresh"); put("token_type", "Bearer")
                        put("id_token", token(JsonObject(claims(attempt.nonce) + ("sub" to JsonPrimitive(subject)))))
                        put("expires_in", 3600); put("scope", scope)
                    }.toString())
                }
                "/.well-known/openid-configuration" -> respond("""{"issuer":"${ChatGptOAuth.Issuer}","jwks_uri":"${ChatGptOAuth.Issuer}/.well-known/jwks.json"}""")
                "/.well-known/jwks.json" -> respond(jwks.toString())
                else -> error("Unexpected URL")
            }
        })
        http.use {
            val oauth = ChatGptOAuth(it) { now }
            attempt = oauth.authorization("http://127.0.0.1:3456/auth/callback")
            val account = oauth.exchange(attempt, ChatGptAuthorizationCode("code", clientId))
            assertEquals(clientId, account.clientId)
            assertEquals("test-subject", account.subject)
            assertEquals(now + 3_600_000, account.tokens!!.expiresAt)
            scope = "openid"
            try { oauth.exchange(attempt, ChatGptAuthorizationCode("code", clientId)); fail("Plan permission required") }
            catch (_: IllegalArgumentException) { }
            scope = "resource.invoke chatgpt.tokens.use.direct"
            attempt = oauth.authorization(attempt.redirectUri, account)
            subject = "different-user"
            try { oauth.exchange(attempt, ChatGptAuthorizationCode("code", clientId)); fail("Account substitution must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun callbackIgnoresUnrelatedRequestsThenAcceptsOneMatchingAttempt() = runBlocking {
        HttpClient(MockEngine { error("No network") }).use { http ->
            val oauth = ChatGptOAuth(http)
            ChatGptCallbackServer().use { server ->
                val attempt = oauth.authorization(server.redirectUri)
                val listener = async(Dispatchers.IO) { server.awaitCode(oauth, attempt) }
                suspend fun get(path: String): String = withContext(Dispatchers.IO) {
                    Socket("127.0.0.1", Url(server.redirectUri).port).use { socket ->
                        socket.soTimeout = 2000
                        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                        socket.getInputStream().bufferedReader().readLine()
                    }
                }
                assertTrue(get("/favicon.ico").contains("400"))
                assertTrue(get("/auth/callback?state=wrong&code=bad&client_id=$clientId").contains("400"))
                assertTrue(get("/auth/callback?state=${attempt.state}&code=good&client_id=$clientId").contains("200"))
                assertEquals(ChatGptAuthorizationCode("good", clientId), listener.await())
            }
        }
    }

    @Test fun oauthCannotSendTokensToCustomEndpointsOrEnableStoredBackgroundResponses() {
        val config = OpenAIModelConfig(authentication = AuthenticationMethod.ChatGpt,
            baseUrl = "https://untrusted.example.com/v1", protocol = ApiProtocol.ChatCompletions, backgroundResponses = true)
        assertEquals("https://api.openai.com/v1/responses", buildRequestUrl(config))
        val body = buildRequestBody(config, OpenAIRequest(listOf(ChatMessage(MessageRole.User, "hello")), instructions = "Be concise"))
        assertFalse(body.getValue("store").jsonPrimitive.boolean)
        assertTrue(body.getValue("stream").jsonPrimitive.boolean)
        assertEquals("Be concise", body["instructions"]?.jsonPrimitive?.content)
        assertFalse(body.containsKey("background"))
        assertFalse(body.containsKey("messages"))
        assertTrue(body["input"] is JsonArray)
    }
}
