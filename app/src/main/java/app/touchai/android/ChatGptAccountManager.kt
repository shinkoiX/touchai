package app.touchai.android

import app.touchai.core.openai.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatGptAccountManager(private val store: ChatGptAccountStore, val oauth: ChatGptOAuth) {
    private val mutex = Mutex()
    private val mutableAccounts = MutableStateFlow<List<ChatGptAccount>>(emptyList())
    val accounts = mutableAccounts.asStateFlow()
    private var loaded = false
    private val signingOut = mutableSetOf<String>()
    private val active = mutableMapOf<String, MutableSet<Job>>()

    suspend fun load() = mutex.withLock { loadLocked() }
    private suspend fun loadLocked() {
        if (!loaded) { mutableAccounts.value = store.load(); loaded = true }
    }

    suspend fun signIn(accountId: String?, openBrowser: suspend (String) -> Unit): String {
        load()
        val account = accountId?.let { id -> accounts.value.first { it.id == id } }
        val hostId = store.hostId()
        val connected = oauthResult {
            withContext(Dispatchers.IO) {
                ChatGptCallbackServer().use { server ->
                    val attempt = oauth.authorization(server.redirectUri, account)
                    openBrowser(oauth.authorizationUrl(attempt, hostId))
                    oauth.exchange(attempt, server.awaitCode(oauth, attempt))
                }
            }
        }
        mutex.withLock {
            // The issued registration, not the email, defines the account/workspace boundary.
            val existing = accounts.value.firstOrNull { it.clientId == connected.clientId }
            require(existing == null || existing.subject == connected.subject) { "ChatGPT registration identity changed." }
            val saved = connected.copy(id = existing?.id ?: connected.id)
            persist(accounts.value.filterNot { it.id == saved.id } + saved)
            return saved.id
        }
    }

    suspend fun models(id: String): List<ChatGptModel> = authorized(id) { token -> oauthResult { oauth.models(token) } }

    /** Tracks connection tests and streams too, so sign-out stops every use of this account. */
    suspend fun <T> authorized(id: String, action: suspend (String) -> T): T = coroutineScope {
        val job = currentCoroutineContext().job
        val token = mutex.withLock {
            loadLocked()
            check(id !in signingOut) { "This ChatGPT account is signing out." }
            val account = accounts.value.firstOrNull { it.id == id } ?: error("Select a ChatGPT account in Settings.")
            var tokens = account.tokens ?: error("Continue with ChatGPT to sign in again.")
            if (tokens.expiresAt <= System.currentTimeMillis() + 60_000) {
                // Store rotating credentials even if the requesting chat is cancelled during refresh.
                withContext(NonCancellable) {
                    tokens = oauthResult { oauth.refresh(account) }
                    persist(accounts.value.map { if (it.id == id) it.copy(tokens = tokens) else it })
                }
            }
            active.getOrPut(id) { mutableSetOf() }.add(job)
            tokens.accessToken
        }
        try { action(token) }
        finally { withContext(NonCancellable) { mutex.withLock { active[id]?.remove(job) } } }
    }

    /** Returns a short warning only when remote revocation could not be confirmed. */
    suspend fun signOut(id: String): String? {
        val (account, jobs) = mutex.withLock {
            loadLocked()
            signingOut.add(id)
            accounts.value.first { it.id == id } to active[id].orEmpty().toList()
        }
        return withContext(NonCancellable) {
            try {
                jobs.forEach { it.cancel(UserRequestedCancellation()) }
                jobs.joinAll()
                var revoked = account.tokens == null
                if (!revoked) {
                    for (attempt in 0..2) {
                        try { oauth.revoke(account); revoked = true; break }
                        catch (error: Exception) {
                            val transient = error is IOException || (error is OpenAIRequestException && (error.httpStatus ?: 0) >= 500)
                            if (!transient || attempt == 2) break
                            delay(500L shl attempt)
                        }
                    }
                }
                mutex.withLock { persist(accounts.value.map { if (it.id == id) it.copy(tokens = null) else it }) }
                if (revoked) null else "Signed out locally. Revocation could not be confirmed; disconnect TouchAI in ChatGPT Settings."
            } finally { mutex.withLock { signingOut.remove(id) } }
        }
    }

    private suspend fun persist(value: List<ChatGptAccount>) {
        store.save(value)
        mutableAccounts.value = value
    }

    // Parser/transport exceptions can contain the token response. Only expose our safe HTTP errors.
    private suspend fun <T> oauthResult(block: suspend () -> T): T = try { block() }
    catch (error: CancellationException) { throw error }
    catch (error: OpenAIRequestException) { throw error }
    catch (_: Exception) { throw OpenAIRequestException("Could not complete the ChatGPT request. Try again or sign in again.") }
}

/** Resolve credentials immediately before logging/dispatch; never copy them into chat history. */
class ChatGptChatClient(private val delegate: ChatClient, private val accounts: ChatGptAccountManager) : ChatClient {
    override fun stream(config: OpenAIModelConfig, request: OpenAIRequest): Flow<OpenAIStreamEvent> = flow {
        if (config.authentication == AuthenticationMethod.ApiKey) emitAll(delegate.stream(config, request))
        else accounts.authorized(config.chatGptAccountId ?: error("Select a ChatGPT account in Settings.")) { token ->
            emitAll(delegate.stream(config.copy(apiKey = token, baseUrl = ChatGptOAuth.Resource,
                protocol = ApiProtocol.Responses, backgroundResponses = false), request))
        }
    }
}
