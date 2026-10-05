package app.touchai.android

internal class MemoryChatHistoryRepository : ChatHistoryRepository {
    val chats = linkedMapOf<String, SavedChat>()
    override suspend fun save(chat: SavedChat) { chats[chat.id] = chat }
    override suspend fun list() = chats.values.map { it.entry }.sortedByDescending { it.updatedAt }
    override suspend fun load(id: String) = chats.getValue(id)
    override suspend fun delete(id: String) { chats.remove(id) }
}
