package app.touchai.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.touchai.core.openai.OpenAIModel
import app.touchai.core.openai.LoggingChatClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.File

class TouchAiApplication : Application() {
    val settingsRepository by lazy { DataStoreSettingsRepository(settingsDataStore, ApiKeyCipher()) }
    val quickAccess by lazy { QuickAccessRuntime(this, settingsRepository) }
    val requestLogs by lazy { RequestLogStore(this) }
    val chatHistory by lazy { ChatHistoryStore(File(filesDir, "chats"), ApiKeyCipher()) }

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

    fun chatViewModelFactory(invoked: Boolean = false) = viewModelFactory {
        initializer {
            val client = HttpClient(OkHttp) {
                expectSuccess = false
                followRedirects = false
                install(HttpTimeout) { connectTimeoutMillis = 15_000; socketTimeoutMillis = 120_000 }
                engine { config { retryOnConnectionFailure(false) } }
            }
            OpenAIChatViewModel(chatHistory, settingsRepository, LoggingChatClient(OpenAIModel(client), requestLogs), client::close, invoked)
        }
    }
}
