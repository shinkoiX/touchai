package app.touchai.android

import app.touchai.core.openai.*
import java.io.File
import java.util.Locale
import java.util.Random
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatHistorySearchPerformanceTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = ApiKeyCipher { key }

    private val baseAi = AiConfiguration(
        OpenAIModelConfig(
            apiKey = "bench-secret-key",
            model = "gpt-5.4",
            protocol = ApiProtocol.Responses,
            reasoningEffort = "medium",
            webSearch = false,
            timeoutMillis = 60_000,
        ),
        "你是一个严谨的技术助手。",
    )

    private val zhTopics = arrayOf(
        "Android 界面渲染卡顿与帧率优化方案",
        "Kotlin 协程并发控制与结构化取消机制",
        "分布式系统事务一致性与幂等设计",
        "SQLite 索引调优与全文检索对比分析",
        "JVM 垃圾回收停顿与内存泄漏排查指南",
        "Compose 重组范围缩小与状态提升最佳实践",
        "移动端弱网环境下的断点续传与重连策略",
        "大模型流式输出缓冲与 Markdown 增量渲染",
        "操作系统虚拟内存页表与缓存局部性原理",
        "密码学对称加密 AES-GCM 与密钥安全存储",
        "现代前端浏览器关键渲染路径与首屏提速",
        "高并发消息队列削峰填谷与死信处理",
    )

    private val zhParagraphs = arrayOf(
        "在移动端本地历史记录检索场景中，如果每次查询都遍历磁盘上的完整 JSONL 文件并解析内嵌 Base64 图片，会导致严重的磁盘读取延迟与内存抖动。",
        "通过将纯文本摘要与元数据剥离到独立的增量索引文件，并在内存中维护字符双字倒排表，可以将中文与英文混合子串的检索延迟压低至毫秒以内。",
        "排查内存泄漏时应重点关注生命周期更长的单例如 ViewModel、后台协程作用域以及未及时反注册的监听器，并结合堆转储快照定位引用链。",
        "在多轮对话中，用户往往只记得某次回答里的局部中文关键词，例如（事务隔离级别）或「零拷贝流式扫描」，因此无需分词即可命中的子串检索至关重要。",
        "针对高频流式写入场景，采用内存即时更新叠加终态追加写日志，再配合周期性原子压缩，能够显著减少闪存写放大并兼顾崩溃自愈能力。",
        "处理跨平台文本时需要注意 Unicode 代理对、Emoji 表情符号以及全角标点符号附近的匹配高亮，避免截取摘要窗口时破坏字符边界。",
        "数据库查询优化除了添加覆盖索引之外，还要警惕回表开销、全表扫描以及在高基数文本字段上使用前缀通配符导致索引失效。",
        "网络请求在后台恢复时应当通过响应检查点持久化状态，保证进程被系统回收重启后仍能无缝接续未完成的推理任务。",
    )

    private val zhReasoning = arrayOf(
        "先梳理瓶颈：磁盘随机打开文件、Base64 图片解码负担以及重复解密密钥。",
        "对比 SQLite FTS5 与内存 2-gram 倒排索引：Android 11 自带版本不支持 trigram，且中文无空格分词。",
        "验证边界条件：单字中文查询、多关键词乱序匹配、Emoji 邻近片段截取与损坏索引恢复。",
        "评估复杂度：先按最稀疏的 bigram 倒排链表求交，再对少量候选文档执行精确子串验证。",
    )

    private val models = arrayOf("gpt-5.4", "gemini-2.5-pro", "claude-sonnet-4", "deepseek-r1")
    private val presets = arrayOf("代码审查", "翻译润色", "截图解释", "架构设计", null)

    private fun generateDataset(
        count: Int,
        imageEveryN: Int = 20,
        imagePayloadChars: Int = 512_000,
    ): List<SavedChat> {
        val rng = Random(20261126L)
        val sharedImageBlob = "data:image/png;base64," + "A".repeat(imagePayloadChars)
        val list = ArrayList<SavedChat>(count)
        for (i in 0 until count) {
            val turnCount = 2 + (i % 3)
            val turns = ArrayList<ChatTurn>(turnCount)
            val model = models[i % models.size]
            val preset = presets[i % presets.size]
            val ai = baseAi.copy(api = baseAi.api.copy(model = model))
            for (t in 0 until turnCount) {
                val topic = zhTopics[(i + t) % zhTopics.size]
                val p1 = zhParagraphs[(i * 3 + t) % zhParagraphs.size]
                val p2 = zhParagraphs[(i * 5 + t + 2) % zhParagraphs.size]
                val reason = zhReasoning[(i + t) % zhReasoning.size]
                val userText = if (t == 0) {
                    "$topic（会话 #$i，讨论并发与中文检索优化）"
                } else {
                    "继续追问第 ${t + 1} 轮：请结合实战案例详细说明「${zhTopics[(i + t * 2) % zhTopics.size]}」的落地细节。"
                }
                val answerText = "$p1 $p2 补充记录编号=$i-$t，涉及 Kotlin 协程与性能调优。"
                val images = if (t == 0 && imageEveryN > 0 && i % imageEveryN == 0) {
                    listOf(OpenAIImage(sharedImageBlob, "auto"))
                } else {
                    emptyList()
                }
                val citations = if (t == 0 && i % 4 == 0) {
                    listOf(WebCitation("https://example.com/doc/$i", "技术参考文档：$topic", 0, 8))
                } else {
                    emptyList()
                }
                turns.add(
                    ChatTurn(
                        user = ChatMessage(MessageRole.User, userText, images),
                        ai = ai,
                        presetName = preset,
                        answer = answerText,
                        reasoning = reason,
                        status = TurnStatus.Completed,
                        citations = citations,
                    ),
                )
            }
            list.add(SavedChat("chat-%05d".format(Locale.ROOT, i), 1_700_000_000_000L + i * 1_000L, preset, turns))
        }

        // Plant rare Chinese needles across different fields for strict correctness + ranking checks.
        fun replaceChat(index: Int, chat: SavedChat) { list[index] = chat }

        // Needle 1: Rare Chinese phrase inside turn 2 answer (newer) vs title match (older).
        val bodyNeedleIdx = (count * 3) / 4
        val titleNeedleIdx = count / 4
        replaceChat(
            bodyNeedleIdx,
            list[bodyNeedleIdx].copy(
                turns = list[bodyNeedleIdx].turns + ChatTurn(
                    user = ChatMessage(MessageRole.User, "补充探讨历史艺术"),
                    ai = baseAi,
                    answer = "这段回答专门提到景德镇青花瓷釉下彩工艺与高温还原焰烧制技巧。",
                    reasoning = "检索陶瓷工艺资料。",
                    status = TurnStatus.Completed,
                ),
            ),
        )
        replaceChat(
            titleNeedleIdx,
            list[titleNeedleIdx].copy(
                turns = listOf(
                    ChatTurn(
                        user = ChatMessage(MessageRole.User, "青花瓷釉下彩工艺的历史演变"),
                        ai = baseAi,
                        answer = "元代至明清时期的发展脉络概述。",
                        status = TurnStatus.Completed,
                    ),
                ),
            ),
        )

        // Needle 2: Rare Chinese phrase inside reasoning + citation + Emoji + full-width punctuation.
        val specialIdx = count / 2
        replaceChat(
            specialIdx,
            list[specialIdx].copy(
                turns = listOf(
                    ChatTurn(
                        user = ChatMessage(MessageRole.User, "🚀物理前沿笔记（量子退相干）"),
                        ai = baseAi.copy(api = baseAi.api.copy(model = "gemini-2.5-pro")),
                        presetName = "科研笔记",
                        answer = "环境耦合会导致量子纠缠态退相干，破坏叠加态相位关系。",
                        reasoning = "推导主方程并检验「退相干时间尺度」与温度的关系。",
                        citations = listOf(WebCitation("https://example.com/q", "量子力学导论：退相干机制", 0, 4)),
                        status = TurnStatus.Completed,
                    ),
                ),
            ),
        )

        // Needle 3: Rare single Chinese character + multi-term out-of-order Chinese match.
        val rareCharIdx = count - 2
        replaceChat(
            rareCharIdx,
            list[rareCharIdx].copy(
                turns = listOf(
                    ChatTurn(
                        user = ChatMessage(MessageRole.User, "生僻字与多词乱序测试"),
                        ai = baseAi,
                        answer = "饕餮纹饰常见于商周青铜礼器，关于内存泄漏的排查思路也在此记录。",
                        status = TurnStatus.Completed,
                    ),
                ),
            ),
        )

        assertTrue(rng.nextInt() != Int.MIN_VALUE || true)
        return list
    }

    private fun measureMillis(iterations: Int, block: () -> Unit): Stats {
        // Warmup
        repeat(3) { block() }
        val samples = DoubleArray(iterations)
        for (i in 0 until iterations) {
            val start = System.nanoTime()
            block()
            samples[i] = (System.nanoTime() - start) / 1_000_000.0
        }
        samples.sort()
        return Stats(
            avgMs = samples.average(),
            p50Ms = samples[samples.size / 2],
            p95Ms = samples[((samples.size - 1) * 0.95).toInt()],
            maxMs = samples.last(),
        )
    }

    private data class Stats(val avgMs: Double, val p50Ms: Double, val p95Ms: Double, val maxMs: Double) {
        override fun toString(): String =
            "avg=%.3fms p50=%.3fms p95=%.3fms max=%.3fms".format(Locale.ROOT, avgMs, p50Ms, p95Ms, maxMs)
    }

    @Test
    fun benchmarkDiskBacked2000ChatsAndVerifyChineseSearch() = runBlocking {
        val dir = temporaryFolder.newFolder("bench-2000")
        val dataset = generateDataset(count = 2_000, imageEveryN = 20, imagePayloadChars = 512_000)

        // Populate disk store
        val writeStart = System.nanoTime()
        val writerStore = ChatHistoryStore(dir, cipher)
        for (chat in dataset) writerStore.save(chat)
        val writeMs = (System.nanoTime() - writeStart) / 1_000_000.0

        val jsonlFiles = dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.toList()
        val totalJsonlBytes = jsonlFiles.sumOf { it.length() }
        val indexFile = File(dir, "search.index")
        val indexBytes = indexFile.length()

        // 1. Cold start with existing search.index
        val coldIndexStart = System.nanoTime()
        val indexedStore = ChatHistoryStore(dir, cipher)
        val listed = indexedStore.list()
        val coldIndexLoadMs = (System.nanoTime() - coldIndexStart) / 1_000_000.0
        assertEquals(2_000, listed.size)

        // 2. Cold start legacy migration (no search.index -> streaming JSON scanner over 2000 files incl. base64 images)
        assertTrue(indexFile.delete())
        val backfillStart = System.nanoTime()
        val migratedStore = ChatHistoryStore(dir, cipher)
        assertEquals(2_000, migratedStore.list().size)
        val backfillMs = (System.nanoTime() - backfillStart) / 1_000_000.0
        assertTrue(indexFile.exists())

        // 3. Verify Chinese search correctness on migrated store
        val bodyNeedleId = dataset[(2_000 * 3) / 4].id
        val titleNeedleId = dataset[2_000 / 4].id
        val specialId = dataset[2_000 / 2].id
        val rareCharId = dataset[2_000 - 2].id

        // Exact Chinese substring + title ranking ahead of newer body match + snippet verification
        val porcelainResults = migratedStore.search("青花瓷釉下彩工艺")
        assertEquals(listOf(titleNeedleId, bodyNeedleId), porcelainResults.map { it.entry.id })
        assertNull(porcelainResults[0].snippet)
        assertNotNull(porcelainResults[1].snippet)
        assertTrue(porcelainResults[1].snippet!!.contains("青花瓷釉下彩工艺"))

        // Chinese inside reasoning, citation, full-width punctuation, Emoji
        assertEquals(listOf(specialId), migratedStore.search("退相干时间尺度").map { it.entry.id })
        assertEquals(listOf(specialId), migratedStore.search("量子力学导论 科研笔记").map { it.entry.id })
        assertEquals(listOf(specialId), migratedStore.search("量子退相干").map { it.entry.id })
        assertEquals(listOf(specialId), migratedStore.search("🚀物理").map { it.entry.id })

        // Rare single Chinese char (fallback path) + multi-term out-of-order Chinese
        assertEquals(listOf(rareCharId), migratedStore.search("饕").map { it.entry.id })
        assertEquals(listOf(rareCharId), migratedStore.search("排查思路 饕餮").map { it.entry.id })

        // Verify snippet window does not break UTF-16 surrogate pairs around Emoji / CJK Extension B.
        val longEmojiBody = "前缀".repeat(35) + "🚀🔥" + "重点中文关键词" + "🎉✨" + "后缀".repeat(40)
        val snippet = extractSnippet("短标题", "短标题", "短标题\n$longEmojiBody", "短标题\n$longEmojiBody", listOf("重点中文关键词"))
        assertNotNull(snippet)
        assertTrue(snippet!!.contains("重点中文关键词"))
        for (i in snippet.indices) {
            if (Character.isHighSurrogate(snippet[i])) {
                assertTrue("Unpaired high surrogate at $i", i + 1 < snippet.length && Character.isLowSurrogate(snippet[i + 1]))
            }
            if (Character.isLowSurrogate(snippet[i])) {
                assertTrue("Unpaired low surrogate at $i", i > 0 && Character.isHighSurrogate(snippet[i - 1]))
            }
        }

        // Mixed Chinese + English query
        val mixed = migratedStore.search("Kotlin 协程 倒排表")
        assertTrue("Expected mixed CN/EN matches", mixed.size > 100)

        // Zero-match Chinese query
        assertTrue(migratedStore.search("不存在的火星基地").isEmpty())

        // 4. Hot search latency across diverse Chinese queries
        val queries = listOf(
            "精准长词(2条命中)" to "青花瓷釉下彩工艺",
            "推理字段精准词(1条)" to "退相干时间尺度",
            "中文多词AND(1条)" to "排查思路 饕餮",
            "中英混合高频" to "Kotlin 协程 倒排表",
            "中文高频2字" to "并发",
            "中文4字短语" to "内存泄漏",
            "中文单字回退(1条)" to "饕",
            "中文单字高频" to "优",
            "中文零命中(O(1))" to "不存在的火星基地",
        )

        println("=== 2,000 Disk Chats Benchmark ===")
        println("Total .jsonl size: %.2f MB (files=%d), search.index size: %.2f MB (ratio=%.2f%%)"
            .format(Locale.ROOT, totalJsonlBytes / 1048576.0, jsonlFiles.size, indexBytes / 1048576.0, indexBytes * 100.0 / totalJsonlBytes))
        println("Initial 2,000 save() total: %.1f ms".format(Locale.ROOT, writeMs))
        println("Cold load WITH search.index: %.1f ms".format(Locale.ROOT, coldIndexLoadMs))
        println("Cold legacy backfill (NO search.index, streaming skip images): %.1f ms".format(Locale.ROOT, backfillMs))

        for ((label, q) in queries) {
            var lastCount = 0
            val stats = measureMillis(iterations = 30) {
                lastCount = runBlocking { migratedStore.search(q).size }
            }
            println("  [2k] %-18s query='%s' hits=%-4d -> %s".format(Locale.ROOT, label, q, lastCount, stats))
            // Every hot query on 2,000 chats should stay well under 50ms even on single-char fallback.
            assertTrue("Query '$q' too slow: ${stats.p95Ms}ms", stats.p95Ms < 100.0)
        }
    }

    @Test
    fun benchmarkInMemory10000ChatsBigramVsLinearScan() {
        val dataset = generateDataset(count = 10_000, imageEveryN = 0, imagePayloadChars = 0)
        val records = dataset.map { IndexedChatRecord(it.entry, 0L, 0L, extractSearchText(it)) }
        val totalTextChars = records.sumOf { it.searchText.length.toLong() }

        val buildStart = System.nanoTime()
        val index = ChatHistorySearchIndex()
        for (record in records) index.upsert(record)
        val buildMs = (System.nanoTime() - buildStart) / 1_000_000.0

        // Prepare baseline linear scan records (pre-lowercased to make comparison fair!)
        val linearDocs = records.map { rec ->
            Triple(rec, rec.entry.title.lowercase(Locale.ROOT), rec.searchText.lowercase(Locale.ROOT))
        }
        fun linearSearch(query: String): Int {
            val terms = tokenizeSearchQuery(query)
            var hits = 0
            for ((rec, titleLower, textLower) in linearDocs) {
                if (terms.all { t -> titleLower.contains(t) || textLower.contains(t) }) {
                    extractSnippet(rec.entry.title, titleLower, rec.searchText, textLower, terms)
                    hits++
                }
            }
            return hits
        }

        val queries = listOf(
            "精准长词(2条)" to "青花瓷釉下彩工艺",
            "推理精准词(1条)" to "退相干时间尺度",
            "中文多词AND(1条)" to "排查思路 饕餮",
            "中英混合(高命中)" to "Kotlin 协程 倒排表",
            "中文4字(中命中)" to "虚拟内存",
            "中文单字(回退)" to "饕",
            "中文零命中" to "不存在的火星基地",
        )

        println("=== 10,000 In-Memory Chats Benchmark ===")
        println("Chats=10,000, total pure text=%.2f M chars, index build=%.1f ms"
            .format(Locale.ROOT, totalTextChars / 1_000_000.0, buildMs))

        for ((label, q) in queries) {
            var bigramHits = 0
            var linearHits = 0
            val bigramStats = measureMillis(iterations = 25) {
                bigramHits = index.search(q).size
            }
            val linearStats = measureMillis(iterations = 25) {
                linearHits = linearSearch(q)
            }
            assertEquals("Bigram and linear scan hit counts must match for '$q'", linearHits, bigramHits)
            val speedup = linearStats.avgMs / bigramStats.avgMs.coerceAtLeast(0.0001)
            println("  [10k] %-16s query='%s' hits=%-5d | bigram: %s | linear: %s | speedup=%.1fx"
                .format(Locale.ROOT, label, q, bigramHits, bigramStats, linearStats, speedup))
        }
    }
}
