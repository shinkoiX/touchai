package app.touchai.android

import app.touchai.core.openai.ChatClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun testChatViewModel(history: ChatHistoryRepository, repository: SettingsRepository, client: ChatClient): OpenAIChatViewModel {
    val requests = ChatRequestRunner(history, client, { _, _ -> buildJsonObject { put("status", "cancelled") } },
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), {}, {})
    return OpenAIChatViewModel(history, repository, client, requests)
}
