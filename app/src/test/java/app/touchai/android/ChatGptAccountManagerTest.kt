package app.touchai.android

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.touchai.core.openai.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatGptAccountManagerTest {
    @get:Rule val folder = TemporaryFolder()
    private val cipher = ApiKeyCipher { key }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val account = ChatGptAccount("test-account", "oaiapp_test", "subject", "person@example.com",
        ChatGptTokens("private-access", "private-refresh", "private-id-token", System.currentTimeMillis() + 3_600_000,
            setOf("resource.invoke", "chatgpt.tokens.use.direct")))

    private suspend fun withStore(block: suspend (ChatGptAccountStore, java.io.File) -> Unit) {
        val file = folder.newFolder().resolve("settings.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val data = PreferenceDataStoreFactory.create(scope = scope) { file }
            block(ChatGptAccountStore(data, cipher), file)
        } finally { scope.coroutineContext.job.cancelAndJoin() }
    }

    @Test fun accountRecordsAreEncryptedAndHostIdentitySurvivesReloads() = runBlocking {
        withStore { store, file ->
            store.save(listOf(account, account.copy(id = "second-account", clientId = "oaiapp_workspace")))
            val host = store.hostId()
            assertTrue(host.startsWith("urn:uuid:"))
            assertEquals(host, store.hostId())
            assertEquals(2, store.load().size)
            assertEquals(account, store.load().first())
            val raw = file.readBytes().toString(Charsets.UTF_8)
            listOf("private-access", "private-refresh", "private-id-token", "person@example.com", "oaiapp_test").forEach {
                assertFalse(raw.contains(it))
            }
        }
    }

    @Test fun simultaneousRequestsRotateTokensOnceAndKeepTheNewRefreshToken() = runBlocking {
        withStore { store, _ ->
            store.save(listOf(account.copy(tokens = account.tokens!!.copy(expiresAt = 0))))
            val refreshes = AtomicInteger()
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath.endsWith("/token")) {
                    refreshes.incrementAndGet()
                    val form = (request.body as FormDataContent).formData
                    assertEquals("refresh_token", form["grant_type"])
                    assertEquals("oaiapp_test", form["client_id"])
                    assertEquals("private-refresh", form["refresh_token"])
                    assertNull(form["scope"])
                    delay(20)
                    respond("""{"access_token":"new-access","refresh_token":"new-refresh","token_type":"Bearer","expires_in":3600}""")
                } else {
                    assertEquals("Bearer new-access", request.headers["Authorization"])
                    respond("""{"models":[{"slug":"available","display_name":"Available","visibility":"list"},{"slug":"hidden","visibility":"hide"}]}""")
                }
            }).use { http ->
                val manager = ChatGptAccountManager(store, ChatGptOAuth(http))
                val result = coroutineScope { List(3) { async { manager.models(account.id) } }.awaitAll() }
                assertEquals(1, refreshes.get())
                result.forEach { assertEquals(listOf(ChatGptModel("available", "Available")), it) }
                val saved = store.load().single().tokens!!
                assertEquals("new-refresh", saved.refreshToken)
                assertEquals("private-id-token", saved.idToken)
                assertEquals(account.tokens!!.scopes, saved.scopes)
            }
        }
    }

    @Test fun freshCredentialsReachTheLoggedRequestButNotTheSavedConfigurationOrLogs() = runBlocking {
        withStore { store, _ ->
            store.save(listOf(account))
            HttpClient(MockEngine { error("No OAuth requests expected") }).use { http ->
                val manager = ChatGptAccountManager(store, ChatGptOAuth(http))
                val records = mutableListOf<RequestLogRecord>()
                val sink = object : RequestLogSink {
                    override suspend fun started(record: RequestLogRecord) { records += record }
                    override suspend fun finished(record: RequestLogRecord) { records += record }
                }
                val raw = object : ChatClient {
                    override fun stream(config: OpenAIModelConfig, request: OpenAIRequest) = flow {
                        assertEquals("private-access", config.apiKey)
                        assertEquals("https://api.openai.com/v1", config.baseUrl)
                        assertEquals(ApiProtocol.Responses, config.protocol)
                        assertFalse(config.backgroundResponses)
                        emit(OpenAIStreamEvent.TextDelta("private-access"))
                        emit(OpenAIStreamEvent.Completed(buildJsonObject { put("id_token", "private-id-token") }))
                    }
                }
                val config = OpenAIModelConfig(authentication = AuthenticationMethod.ChatGpt, chatGptAccountId = account.id)
                val client = ChatGptChatClient(LoggingChatClient(raw, sink), manager)
                val events = client.stream(config, OpenAIRequest(listOf(ChatMessage(MessageRole.User, "hello")))).toList()
                assertEquals(2, events.size)
                assertEquals("", config.apiKey)
                val log = records.last().json().toString()
                assertFalse(log.contains("private-access"))
                assertFalse(log.contains("private-id-token"))
            }
        }
    }

    @Test fun signOutCancelsStreamsRevokesAndRetainsTheAccountRegistration() = runBlocking {
        withStore { store, _ ->
            store.save(listOf(account))
            var revoked = false
            HttpClient(MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("openid-configuration") -> respond("""{"issuer":"https://auth.openai.com","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}""")
                    else -> {
                        val form = (request.body as FormDataContent).formData
                        assertEquals("oaiapp_test", form["client_id"])
                        assertEquals("private-refresh", form["token"])
                        assertEquals("refresh_token", form["token_type_hint"])
                        revoked = true
                        respond("")
                    }
                }
            }).use { http ->
                val manager = ChatGptAccountManager(store, ChatGptOAuth(http))
                val started = CompletableDeferred<Unit>()
                val active = launch { manager.authorized(account.id) { started.complete(Unit); awaitCancellation() } }
                started.await()
                assertNull(manager.signOut(account.id))
                active.join()
                assertTrue(active.isCancelled)
                assertTrue(revoked)
                assertEquals(account.copy(tokens = null), store.load().single())
                try { manager.models(account.id); fail("Signed-out tokens cannot be reused") }
                catch (_: IllegalStateException) { }
            }
        }
    }

    @Test fun failedRevocationStillClearsLocalTokensAndReportsIt() = runBlocking {
        withStore { store, _ ->
            store.save(listOf(account))
            HttpClient(MockEngine { respond("", status = HttpStatusCode.BadRequest) }).use { http ->
                val manager = ChatGptAccountManager(store, ChatGptOAuth(http))
                assertTrue(manager.signOut(account.id)!!.contains("Revocation could not be confirmed"))
                assertNull(store.load().single().tokens)
            }
        }
    }

    @Test fun portableHistoryOmitsAccountBindingsAndCredentials() = runBlocking {
        val history = MemoryChatHistoryRepository()
        val config = OpenAIModelConfig(authentication = AuthenticationMethod.ChatGpt, chatGptAccountId = account.id,
            protocol = ApiProtocol.Responses, model = "available")
        val turn = ChatTurn(ChatMessage(MessageRole.User, "hello"), AiConfiguration(config), null, status = TurnStatus.Completed)
        val local = encodeChatTurn(turn, "")
        assertEquals(account.id, decodeChatTurn(local, "").ai.api.chatGptAccountId)
        history.save(SavedChat("chat", 1, null, listOf(turn)))
        val output = ByteArrayOutputStream()
        ChatHistoryArchive(history).exportTo(output)
        val encoded = output.toString(Charsets.UTF_8)
        assertFalse(encoded.contains(account.id))
        assertFalse(encoded.contains("private-access"))
        assertTrue(encoded.contains("ChatGpt"))
        val imported = MemoryChatHistoryRepository()
        ChatHistoryArchive(imported).importFrom(encoded.byteInputStream()) { }
        val importedConfig = imported.load(imported.list().single().id).turns.single().ai.api
        assertEquals(AuthenticationMethod.ChatGpt, importedConfig.authentication)
        assertNull(importedConfig.chatGptAccountId)
        assertEquals("", importedConfig.apiKey)
    }
}
