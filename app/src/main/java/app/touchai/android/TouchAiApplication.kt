package app.touchai.android

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.touchai.core.openai.OpenAIModel
import app.touchai.core.openai.LoggingChatClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TouchAiApplication : Application() {
    val settingsRepository by lazy { DataStoreSettingsRepository(settingsDataStore, ApiKeyCipher()) }
    val quickAccess by lazy { QuickAccessRuntime(this, settingsRepository) }
    val requestLogs by lazy { RequestLogStore(this) }
    val chatHistory by lazy { ChatHistoryStore(File(filesDir, "chats"), ApiKeyCipher()) }
    internal val chatSessionTransfer = ChatSessionTransfer()
    internal val collapsedChatSession = ChatSessionTransfer()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityResumed(activity: Activity) { quickAccess.refreshPermissions() }
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) { started++; quickAccess.setAppVisible(true) }
            override fun onActivityStopped(activity: Activity) {
                started--
                if (started > 0 || !activity.isChangingConfigurations) quickAccess.setAppVisible(started > 0)
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val httpClient by lazy {
        HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 120_000 }
            engine { config { retryOnConnectionFailure(false) } }
        }
    }
    private val responseClient by lazy { OpenAIModel(httpClient) }
    private val chatClient by lazy { LoggingChatClient(responseClient, requestLogs) }
    val requests by lazy {
        ChatRequestRunner(chatHistory, chatClient, responseClient::cancelResponse, requestScope,
            onWorkStarted = { RequestService.start(this) },
            onIdle = { stopService(Intent(this, RequestService::class.java)) })
    }

    fun restorePendingRequests() { requestScope.launch { requests.restorePending() } }

    fun chatViewModelFactory(invoked: Boolean = false) = viewModelFactory {
        initializer { OpenAIChatViewModel(chatHistory, settingsRepository, chatClient, requests, invoked) }
    }
}
