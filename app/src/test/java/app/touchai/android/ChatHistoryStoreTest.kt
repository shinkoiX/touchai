package app.touchai.android

import app.touchai.core.openai.*
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatHistoryStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = ApiKeyCipher { key }
    private val turn = ChatTurn(
        ChatMessage(MessageRole.User, "Explain this image", listOf(OpenAIImage("data:image/png;base64," + "A".repeat(3_000_000)))),
        AiConfiguration(OpenAIModelConfig(apiKey = "history-secret", model = "test-model", protocol = ApiProtocol.Responses,
            reasoningEffort = "high", webSearch = false, timeoutMillis = 90_000), "Be concise"),
        presetName = "Explain", answer = "The answer", reasoning = "First analyze the screenshot.", status = TurnStatus.Completed,
        citations = listOf(WebCitation("https://example.com/source", "Source", 0, 4)),
        generatedImages = listOf(GeneratedImage("generated", OpenAIImage("data:image/png;base64," + "B".repeat(3_000_000)))),
    )

    @Test fun recreationPreservesLargeAttachmentsAndConfigurationWithoutPlaintextKeys() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val chat = SavedChat("chat-one", 10, "explain", listOf(turn))
        ChatHistoryStore(directory, cipher).save(chat)
        val reopened = ChatHistoryStore(directory, cipher)
        assertEquals(chat, reopened.load(chat.id))
        assertEquals(listOf(chat.entry), reopened.list())
        assertFalse(directory.resolve("chat-one.jsonl").readText().contains("history-secret"))
    }

    @Test fun updatesKeepOneEntryAndDeletionRemovesMessagesAndImages() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val store = ChatHistoryStore(directory, cipher)
        val first = SavedChat("first", 10, null, listOf(turn))
        val second = first.copy(id = "second", updatedAt = 20)
        store.save(first); store.save(second)
        assertEquals(listOf("second", "first"), store.list().map { it.id })
        store.save(first.copy(updatedAt = 30, turns = listOf(turn, turn.copy(answer = "Follow-up"))))
        assertEquals(listOf("first", "second"), store.list().map { it.id })
        assertEquals(2, store.load("first").turns.size)
        store.delete("first")
        assertFalse(directory.resolve("first.jsonl").exists())
        assertEquals(listOf("second"), store.list().map { it.id })
    }

    @Test fun pendingRequestsRetainRecoveryStateAcrossReopening() = runBlocking {
        val store = ChatHistoryStore(temporaryFolder.newFolder(), cipher)
        store.save(SavedChat("interrupted", 10, null, listOf(turn.copy(status = TurnStatus.Streaming, answer = "Partial", responseId = "resp_test"))))
        assertTrue(store.list().single().pending)
        assertEquals(TurnStatus.Streaming, store.load("interrupted").turns.single().status)
        assertEquals("resp_test", store.load("interrupted").turns.single().responseId)
        assertEquals("Partial", store.load("interrupted").turns.single().answer)
    }

    @Test fun searchMatchesFullConversationContentAndExcludesSecretsAndImageBlobs() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val store = ChatHistoryStore(directory, cipher)
        val first = SavedChat("chat-image", 10, "explain", listOf(turn))
        val second = SavedChat(
            "chat-cjk",
            20,
            null,
            listOf(
                turn.copy(
                    user = ChatMessage(MessageRole.User, "如何优化 Android 搜索速度"),
                    answer = "使用倒排索引和文本缓存可以显著提升响应速度。",
                    reasoning = "先分析磁盘 I/O 与 Base64 瓶颈。",
                    presetName = null,
                    citations = listOf(WebCitation("https://example.com/fts", "SQLite 索引指南", 0, 4)),
                    generatedImages = emptyList(),
                ),
            ),
        )
        val third = SavedChat(
            "chat-title-match",
            5,
            null,
            listOf(
                turn.copy(
                    user = ChatMessage(MessageRole.User, "Explain the answer directly"),
                    answer = "Short reply",
                    reasoning = "",
                    citations = emptyList(),
                    generatedImages = emptyList(),
                ),
            ),
        )
        store.save(first)
        store.save(second)
        store.save(third)

        // Title match ranks ahead of newer body-only match, and body match includes a snippet.
        val answerResults = store.search("the answer")
        assertEquals(listOf("chat-title-match", "chat-image"), answerResults.map { it.entry.id })
        assertNull(answerResults.first().snippet)
        assertTrue(answerResults.last().snippet!!.contains("The answer"))

        // Multi-term, case-insensitive, CJK, single-char, reasoning, citation, and model searches work.
        assertEquals(listOf("chat-cjk"), store.search("倒排 瓶颈").map { it.entry.id })
        assertEquals(listOf("chat-cjk"), store.search("android 指南").map { it.entry.id })
        assertEquals(listOf("chat-cjk"), store.search("瓶").map { it.entry.id })
        assertEquals(listOf("chat-image"), store.search("screenshot source").map { it.entry.id })
        assertEquals(listOf("chat-cjk", "chat-image", "chat-title-match"), store.search("test-model").map { it.entry.id })
        assertTrue(store.search("nonexistent-token").isEmpty())

        // The derived index must never store plaintext API keys or base64 image payloads.
        val indexText = directory.resolve("search.index").readText()
        assertFalse(indexText.contains("history-secret"))
        assertFalse(indexText.contains("AAAA"))
        assertFalse(indexText.contains("BBBB"))
    }

    @Test fun searchLazilyIndexesLegacyJsonlFilesAndRecoversFromCorruptedIndex() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val original = ChatHistoryStore(directory, cipher)
        val chat = SavedChat(
            "legacy-chat",
            100,
            "explain",
            listOf(
                turn,
                turn.copy(
                    user = ChatMessage(MessageRole.User, "Second turn with \"quotes\" and \\ slash"),
                    answer = "Unicode \u4E2D\u6587 answer from legacy file",
                    reasoning = "Legacy reasoning note",
                    generatedImages = emptyList(),
                ),
            ),
        )
        original.save(chat)

        // Simulate upgrading from a version that had only .jsonl files and no search.index.
        val indexFile = directory.resolve("search.index")
        assertTrue(indexFile.delete())

        val upgraded = ChatHistoryStore(directory, cipher)
        val found = upgraded.search("中文 legacy")
        assertEquals(listOf("legacy-chat"), found.map { it.entry.id })
        assertTrue(found.single().snippet!!.contains("中文"))
        assertEquals(listOf("legacy-chat"), upgraded.search("quotes slash").map { it.entry.id })
        assertEquals(listOf("legacy-chat"), upgraded.search("reasoning note").map { it.entry.id })
        assertTrue(indexFile.exists())
        val rebuiltIndex = indexFile.readText()
        assertFalse(rebuiltIndex.contains("AAAA"))
        assertFalse(rebuiltIndex.contains("BBBB"))

        // Corrupt search.index and verify self-healing on next store instance.
        indexFile.writeText("{corrupted-json\n{\"v\":999,\"op\":\"u\"}\n")
        val recovered = ChatHistoryStore(directory, cipher)
        assertEquals(listOf("legacy-chat"), recovered.search("legacy reasoning").map { it.entry.id })
    }

    @Test fun searchReflectsUpdatesDeletionsAndCompactsJournal() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val store = ChatHistoryStore(directory, cipher)
        val lightTurn = turn.copy(
            user = ChatMessage(MessageRole.User, "Initial topic"),
            generatedImages = emptyList(),
        )
        repeat(70) { step ->
            store.save(
                SavedChat(
                    "evolving",
                    step.toLong(),
                    null,
                    listOf(lightTurn.copy(answer = "Iteration-$step")),
                ),
            )
        }
        // Trigger load and verify compaction keeps the journal small.
        assertEquals(listOf("evolving"), store.search("Iteration-69").map { it.entry.id })
        assertTrue(store.search("Iteration-0").isEmpty())
        assertEquals(1, directory.resolve("search.index").readLines().count { it.isNotBlank() })

        store.delete("evolving")
        assertTrue(store.search("Initial topic").isEmpty())
        assertTrue(ChatHistoryStore(directory, cipher).search("Initial topic").isEmpty())
    }

    @Test fun snippetKeepsTailChineseMatchNearTheStartSoTwoLinesNeverClipIt() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val store = ChatHistoryStore(directory, cipher)
        val longChineseBody = "针对高频流式写入场景采用内存即时更新叠加终态追加写日志再配合周期性原子压缩能够显著减少闪存写放大并兼顾崩溃自愈能力。".repeat(2) +
            "补充记录编号 42-1，涵盖协程调优。"
        store.save(
            SavedChat(
                "tail-match",
                10,
                null,
                listOf(
                    turn.copy(
                        user = ChatMessage(MessageRole.User, "高并发消息队列削峰填谷与死信处理"),
                        answer = longChineseBody,
                        reasoning = "",
                        citations = emptyList(),
                        generatedImages = emptyList(),
                    ),
                ),
            ),
        )
        val result = store.search("记录").single()
        val snippet = result.snippet!!
        assertTrue("Expected snippet to start with ellipsis: $snippet", snippet.startsWith("…"))
        val matchPos = snippet.indexOf("记录")
        assertTrue("Match must appear near the start of snippet (pos=$matchPos): $snippet", matchPos in 1..15)
    }

    @Test fun branchedTreePersistsAllVersionsWithoutDuplicatingLatestImagesAndSearchesOnlyLatestVersion() = runBlocking {
        val directory = temporaryFolder.newFolder()
        val store = ChatHistoryStore(directory, cipher)
        val v1Root = turn.copy(
            user = ChatMessage(MessageRole.User, "Compare versions"),
            answer = "Obsolete first-branch answer",
            reasoning = "Old branch reasoning",
            generatedImages = emptyList(),
        )
        val v1Child = v1Root.copy(
            user = ChatMessage(MessageRole.User, "Old branch follow-up"),
            answer = "Obsolete subtree reply",
            reasoning = "",
        )
        val v2Root = turn.copy(
            user = ChatMessage(MessageRole.User, "Compare versions"),
            answer = "Fresh latest-branch answer",
            reasoning = "Latest branch reasoning",
        )
        val v2Child = v2Root.copy(
            user = ChatMessage(MessageRole.User, "Latest branch follow-up"),
            answer = "Latest subtree reply",
            reasoning = "",
            generatedImages = emptyList(),
        )
        val tree = ChatTree()
            .appendTurn(v1Root, now = 100L)
            .appendTurn(v1Child, now = 200L)
            .let { t -> t.branchRetry(t.activePath().first().id, v2Root, now = 300L) }
            .appendTurn(v2Child, now = 400L)
        val chat = SavedChat("branched", 400L, "explain", tree.activeTurns(), tree)
        store.save(chat)

        // Ensure base64 image data on the latest path is stored once via turnIndex, not duplicated.
        val rawJsonl = directory.resolve("branched.jsonl").readText()
        assertFalse(rawJsonl.contains("history-secret"))
        val markerCount = Regex("data:image/png;base64,BBBB").findAll(rawJsonl).count()
        assertEquals("Generated image on latest path must not be duplicated in tree", 1, markerCount)

        // Warm search indexes ONLY the latest version.
        assertEquals(listOf("branched"), store.search("Fresh latest-branch").map { it.entry.id })
        assertEquals(listOf("branched"), store.search("Latest subtree").map { it.entry.id })
        assertTrue(store.search("Obsolete first-branch").isEmpty())
        assertTrue(store.search("Obsolete subtree").isEmpty())

        // Cold backfill (streaming JSON scanner with no search.index) also indexes ONLY the latest version.
        assertTrue(directory.resolve("search.index").delete())
        val reopened = ChatHistoryStore(directory, cipher)
        assertEquals(listOf("branched"), reopened.search("Fresh latest-branch").map { it.entry.id })
        assertTrue(reopened.search("Obsolete first-branch").isEmpty())

        // Full tree survives reload with both branches intact.
        val loaded = reopened.load("branched")
        assertNotNull(loaded.tree)
        assertEquals(4, loaded.tree!!.nodes.size)
        val rootSiblings = loaded.tree!!.siblingsOf(loaded.tree!!.activePath().first().id)
        assertEquals(2, rootSiblings.size)
        val switched = loaded.tree!!.selectBranch(rootSiblings.first().id)
        assertEquals(listOf("Obsolete first-branch answer", "Obsolete subtree reply"), switched.activeTurns().map { it.answer })

        // Export & import preserve the full tree and strip plaintext keys across all branches.
        val exported = java.io.ByteArrayOutputStream()
        ChatHistoryArchive(reopened).exportTo(exported)
        assertFalse(exported.toString(Charsets.UTF_8).contains("history-secret"))
        val importedStore = ChatHistoryStore(temporaryFolder.newFolder(), cipher)
        val importedCount = ChatHistoryArchive(importedStore).importFrom(exported.toByteArray().inputStream()) { }
        assertEquals(1, importedCount)
        val importedChat = importedStore.load(importedStore.list().single().id)
        assertEquals(4, importedChat.tree!!.nodes.size)
        assertEquals(listOf("Fresh latest-branch answer", "Latest subtree reply"), importedChat.latestTurns.map { it.answer })
    }
}
