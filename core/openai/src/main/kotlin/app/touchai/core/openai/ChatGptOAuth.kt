package app.touchai.core.openai

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlinx.serialization.json.*

data class ChatGptTokens(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresAt: Long,
    val scopes: Set<String>,
)

data class ChatGptAccount(
    val id: String,
    val clientId: String,
    val subject: String,
    val email: String,
    val tokens: ChatGptTokens?,
) {
    val label: String get() = "${email.ifBlank { "ChatGPT account" }} · ${id.take(6)}"
}

data class ChatGptModel(val slug: String, val name: String)

data class ChatGptAuthorization(
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val verifier: String,
    val account: ChatGptAccount?,
)

data class ChatGptAuthorizationCode(val code: String, val clientId: String)

/** Public-client OAuth for local apps. No client secret or Codex client ID is used. */
class ChatGptOAuth(private val http: HttpClient, private val now: () -> Long = System::currentTimeMillis) {
    fun authorization(redirectUri: String, account: ChatGptAccount? = null) = ChatGptAuthorization(
        redirectUri, randomValue(), randomValue(), randomValue(), account,
    )

    fun authorizationUrl(attempt: ChatGptAuthorization, hostId: String): String = URLBuilder("$Issuer/api/accounts/authorize").apply {
        parameters.apply {
            append("client_id", attempt.account?.clientId ?: DynamicClient)
            if (attempt.account == null) append("agent_name_hint", "TouchAI")
            append("ext_agent_host_id", hostId)
            attempt.account?.tokens?.idToken?.let { append("id_token_hint", it) }
            append("response_type", "code")
            append("redirect_uri", attempt.redirectUri)
            append("scope", "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
            append("resource", Resource)
            append("state", attempt.state)
            append("nonce", attempt.nonce)
            append("code_challenge_method", "S256")
            append("code_challenge", base64Url(MessageDigest.getInstance("SHA-256").digest(attempt.verifier.toByteArray(Charsets.US_ASCII))))
        }
    }.buildString()

    fun authorizationCode(attempt: ChatGptAuthorization, callback: String): ChatGptAuthorizationCode {
        val url = Url(callback)
        val expected = Url(attempt.redirectUri)
        require(url.protocol == expected.protocol && url.host == expected.host && url.port == expected.port &&
            url.encodedPath == expected.encodedPath && url.fragment.isEmpty()) { "Unexpected sign-in callback." }
        fun parameter(name: String): String? {
            val values = url.parameters.getAll(name) ?: return null
            require(values.size == 1) { "Invalid sign-in callback." }
            return values.single()
        }
        require(parameter("state") == attempt.state) { "Sign-in state did not match. Try again." }
        val error = parameter("error")
        require(error == null) {
            if (error == "access_denied") "ChatGPT access was not granted." else "ChatGPT sign-in failed. Try again."
        }
        val returnedClient = parameter("client_id")
        val clientId = attempt.account?.clientId?.also {
            require(returnedClient == null || returnedClient == it) { "Sign-in returned a different account registration." }
        } ?: returnedClient
        require(!clientId.isNullOrBlank() && clientId != DynamicClient) { "ChatGPT registration did not return a client ID." }
        val code = parameter("code")
        require(!code.isNullOrBlank()) { "Sign-in did not return an authorization code." }
        return ChatGptAuthorizationCode(code, clientId)
    }

    suspend fun exchange(attempt: ChatGptAuthorization, code: ChatGptAuthorizationCode): ChatGptAccount {
        val body = tokenRequest(parameters {
            append("grant_type", "authorization_code")
            append("client_id", code.clientId)
            append("code", code.code)
            append("code_verifier", attempt.verifier)
            append("redirect_uri", attempt.redirectUri)
            append("resource", Resource)
        })
        val tokens = readTokens(body)
        val identity = validateIdToken(tokens.idToken, code.clientId, attempt.nonce)
        require(attempt.account == null || attempt.account.subject == identity.subject) { "Sign in to the selected ChatGPT account." }
        return ChatGptAccount(attempt.account?.id ?: java.util.UUID.randomUUID().toString(), code.clientId,
            identity.subject, identity.email, tokens)
    }

    suspend fun refresh(account: ChatGptAccount): ChatGptTokens {
        val previous = account.tokens ?: error("Continue with ChatGPT to sign in again.")
        val body = tokenRequest(parameters {
            append("grant_type", "refresh_token")
            append("client_id", account.clientId)
            append("refresh_token", previous.refreshToken)
            append("resource", Resource)
        })
        val tokens = readTokens(body, previous)
        if (body["id_token"] != null) {
            val identity = validateIdToken(tokens.idToken, account.clientId, nonce = null)
            require(identity.subject == account.subject) { "ChatGPT refresh returned a different account." }
        }
        return tokens
    }

    suspend fun models(accessToken: String): List<ChatGptModel> {
        val response = http.get("$Resource/models") { bearerAuth(accessToken) }
        if (!response.status.isSuccess()) throw OpenAIRequestException("Could not load ChatGPT models: HTTP ${response.status.value}.", response.status.value)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("models").jsonArray
            .map { it.jsonObject }.filter { it["visibility"]?.jsonPrimitive?.content == "list" }
            .map { ChatGptModel(it.getValue("slug").jsonPrimitive.content, it.getValue("display_name").jsonPrimitive.content) }
    }

    suspend fun revoke(account: ChatGptAccount) {
        val metadata = discovery()
        val endpoint = metadata.getValue("revocation_endpoint").jsonPrimitive.content
        require(Url(endpoint).let { it.protocol == URLProtocol.HTTPS && it.host == "auth.openai.com" }) { "Unexpected ChatGPT revocation endpoint." }
        val response = http.submitForm(endpoint, parameters {
            append("token", account.tokens!!.refreshToken)
            append("token_type_hint", "refresh_token")
            append("client_id", account.clientId)
        })
        if (response.status != HttpStatusCode.OK) throw OpenAIRequestException("Could not revoke ChatGPT session: HTTP ${response.status.value}.", response.status.value)
    }

    private suspend fun tokenRequest(form: Parameters): JsonObject {
        val response = http.submitForm("$Issuer/api/accounts/oauth/token", form)
        if (response.status != HttpStatusCode.OK) throw OpenAIRequestException(
            "ChatGPT authorization failed (HTTP ${response.status.value}). Continue with ChatGPT to sign in again.", response.status.value)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun readTokens(body: JsonObject, previous: ChatGptTokens? = null): ChatGptTokens {
        require(body["token_type"]?.jsonPrimitive?.content.equals("Bearer", ignoreCase = true)) { "Unsupported ChatGPT token type." }
        val scopes = body["scope"]?.jsonPrimitive?.content?.split(' ')?.filter(String::isNotEmpty)?.toSet() ?: previous?.scopes.orEmpty()
        require(scopes.containsAll(setOf("chatgpt.tokens.use.direct", "resource.invoke"))) { "ChatGPT plan usage was not authorized." }
        val expiresIn = body.getValue("expires_in").jsonPrimitive.long
        require(expiresIn in 1..86_400) { "Invalid ChatGPT token expiry." }
        return ChatGptTokens(
            body.getValue("access_token").jsonPrimitive.content.also { require(it.isNotBlank()) },
            body.getValue("refresh_token").jsonPrimitive.content.also { require(it.isNotBlank()) },
            body["id_token"]?.jsonPrimitive?.content ?: previous!!.idToken,
            now() + expiresIn * 1000, scopes,
        )
    }

    private suspend fun discovery(): JsonObject {
        val response = http.get("$Issuer/.well-known/openid-configuration")
        require(response.status.isSuccess()) { "Could not load ChatGPT sign-in metadata." }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject.also {
            require(it["issuer"]?.jsonPrimitive?.content == Issuer) { "Unexpected ChatGPT issuer." }
        }
    }

    private suspend fun validateIdToken(token: String, clientId: String, nonce: String?): ChatGptIdentity {
        val metadata = discovery()
        val jwksUri = metadata.getValue("jwks_uri").jsonPrimitive.content
        require(Url(jwksUri).let { it.protocol == URLProtocol.HTTPS && it.host == "auth.openai.com" }) { "Unexpected ChatGPT signing-key endpoint." }
        val response = http.get(jwksUri)
        require(response.status.isSuccess()) { "Could not load ChatGPT signing keys." }
        return verifyChatGptIdToken(token, Json.parseToJsonElement(response.bodyAsText()).jsonObject, clientId, nonce, now())
    }

    companion object {
        const val Issuer = "https://auth.openai.com"
        const val Resource = "https://api.openai.com/v1"
        const val DynamicClient = "dynamic_agent_client"
        private fun randomValue(): String = base64Url(ByteArray(32).also(SecureRandom()::nextBytes))
    }
}

internal data class ChatGptIdentity(val subject: String, val email: String)
internal fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** OpenAI discovery advertises RS256. Verify with JCA before reading identity claims. */
internal fun verifyChatGptIdToken(token: String, jwks: JsonObject, clientId: String, nonce: String?, now: Long): ChatGptIdentity {
    val parts = token.split('.')
    require(parts.size == 3) { "Invalid ChatGPT ID token." }
    val decoder = Base64.getUrlDecoder()
    fun json(part: String) = Json.parseToJsonElement(decoder.decode(part).toString(Charsets.UTF_8)).jsonObject
    val header = json(parts[0])
    require(header["alg"]?.jsonPrimitive?.content == "RS256" && header["crit"] == null) { "Unsupported ChatGPT ID-token signature." }
    val key = jwks.getValue("keys").jsonArray.map { it.jsonObject }.singleOrNull {
        it["kid"] == header["kid"] && it["kty"]?.jsonPrimitive?.content == "RSA" &&
            (it["use"] == null || it["use"]?.jsonPrimitive?.content == "sig") &&
            (it["alg"] == null || it["alg"]?.jsonPrimitive?.content == "RS256")
    } ?: error("ChatGPT ID-token signing key was not found.")
    val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
        BigInteger(1, decoder.decode(key.getValue("n").jsonPrimitive.content)),
        BigInteger(1, decoder.decode(key.getValue("e").jsonPrimitive.content)),
    ))
    val signature = Signature.getInstance("SHA256withRSA").apply {
        initVerify(publicKey)
        update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
    }
    require(signature.verify(decoder.decode(parts[2]))) { "Invalid ChatGPT ID-token signature." }
    val claims = json(parts[1])
    require(claims["iss"]?.jsonPrimitive?.content == ChatGptOAuth.Issuer) { "Unexpected ChatGPT ID-token issuer." }
    val audience = claims["aud"]
    val audiences = if (audience is JsonArray) audience.map { it.jsonPrimitive.content } else listOf(audience?.jsonPrimitive?.content)
    require(clientId in audiences) { "ChatGPT ID token belongs to a different client." }
    require((audiences.size == 1 && claims["azp"] == null) || claims["azp"]?.jsonPrimitive?.content == clientId) { "Unexpected ChatGPT authorized party." }
    require(claims["exp"]?.jsonPrimitive?.longOrNull?.let { it > now / 1000 } == true) { "ChatGPT ID token expired." }
    require(claims["nbf"]?.jsonPrimitive?.longOrNull?.let { it <= now / 1000 } != false) { "ChatGPT ID token is not valid yet." }
    if (nonce != null) require(claims["nonce"]?.jsonPrimitive?.content == nonce) { "ChatGPT sign-in nonce did not match." }
    val subject = claims["sub"]?.jsonPrimitive?.content
    require(!subject.isNullOrBlank()) { "ChatGPT ID token has no account identity." }
    return ChatGptIdentity(subject, claims["email"]?.jsonPrimitive?.content.orEmpty())
}
