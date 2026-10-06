package app.touchai.android

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import app.touchai.core.openai.ChatGptAccount
import app.touchai.core.openai.ChatGptTokens
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** The complete account record is encrypted, separate from settings and portable history. */
class ChatGptAccountStore(private val store: DataStore<Preferences>, private val cipher: ApiKeyCipher) {
    suspend fun hostId(): String {
        val prefs = store.edit { if (it[HostId] == null) it[HostId] = "urn:uuid:${UUID.randomUUID()}" }
        return prefs[HostId]!!
    }

    suspend fun load(): List<ChatGptAccount> = store.data.first()[Accounts]?.let { encrypted ->
        Json.parseToJsonElement(cipher.decrypt(encrypted)).jsonArray.map { element ->
            val value = element.jsonObject
            ChatGptAccount(value.string("id"), value.string("clientId"), value.string("subject"), value.string("email"),
                (value["tokens"] as? JsonObject)?.let { tokens ->
                    ChatGptTokens(tokens.string("accessToken"), tokens.string("refreshToken"), tokens.string("idToken"),
                        tokens.getValue("expiresAt").jsonPrimitive.long,
                        tokens.getValue("scopes").jsonArray.map { it.jsonPrimitive.content }.toSet())
                })
        }
    }.orEmpty()

    suspend fun save(accounts: List<ChatGptAccount>) {
        val encrypted = cipher.encrypt(buildJsonArray {
            accounts.forEach { account -> add(buildJsonObject {
                put("id", account.id); put("clientId", account.clientId); put("subject", account.subject); put("email", account.email)
                account.tokens?.let { tokens -> putJsonObject("tokens") {
                    put("accessToken", tokens.accessToken); put("refreshToken", tokens.refreshToken); put("idToken", tokens.idToken)
                    put("expiresAt", tokens.expiresAt); putJsonArray("scopes") { tokens.scopes.forEach { add(it) } }
                } }
            }) }
        }.toString())
        store.edit { it[Accounts] = encrypted }
    }

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
    private companion object {
        val HostId = stringPreferencesKey("chatgpt_host_id")
        val Accounts = stringPreferencesKey("encrypted_chatgpt_accounts")
    }
}
