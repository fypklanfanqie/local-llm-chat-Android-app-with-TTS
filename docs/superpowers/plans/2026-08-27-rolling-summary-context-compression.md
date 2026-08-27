# 滚动摘要上下文压缩 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 单聊云端路径实现「角色卡稳定前缀 + 每 40 轮折叠一次的滚动摘要」上下文压缩，保前缀缓存命中率并提升长程记忆。

**Architecture:** 会话表新增摘要两列（Room v9 非破坏迁移）；发送路径按水位过滤历史并在 system 后插入一条仅随折叠变化的【前情提要】system 消息；回复落库后由 Mutex 串行的后台协程检查水位、调云端模型生成新摘要并事务写回。纯判定/拼装逻辑独立成 JVM 可测的 `RollingSummaryPlanner`。

**Tech Stack:** Kotlin / Room / DataStore / OkHttp 直连（DirectLlmClient）/ JUnit4。

## Global Constraints

- 设计规格：`docs/superpowers/specs/2026-08-27-rolling-summary-context-compression-design.md`
- 高水位触发 `>160 行`，单批折叠最旧 `80 行`；摘要 ≤300 字；超时 30s
- 仅单聊云端路径；本地推理 / 群聊 / GreetingWorker / 礼物道谢零改动
- 本机可运行 Gradle：每任务以 `gradlew :app:compileDebugKotlin` 或定向单测验证后才提交
- 折叠绝不阻塞聊天：只在回复落库完成后执行，任何失败静默放弃等下轮重试
- 不要运行 `gradlew assembleDebug` 全量构建，编译验证用 compileDebugKotlin 足够

---

### Task 1: 配置常量与纯逻辑规划器（TDD）

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/config/AppConfig.kt`
- Create: `app/src/main/java/com/chatbyyourside/llm/RollingSummaryPlanner.kt`
- Test: `app/src/test/java/com/chatbyyourside/llm/RollingSummaryPlannerTest.kt`

**Interfaces:**
- Produces（后续任务原样引用）:
```kotlin
// AppConfig 内：
object ContextCompression {
    const val HIGH_WATER_ROWS = 160      // 未摘要行数超过此值触发折叠
    const val FOLD_BATCH_ROWS = 80       // 单次折叠的最旧行数
    const val SUMMARY_MAX_CHARS = 300    // 摘要正文长度上限（字符）
    const val SUMMARY_TIMEOUT_MS = 30_000L
}
// llm 包内：
data class SummaryRow(val id: Long, val role: String, val text: String)
fun shouldFold(rowsBeyondWatermark: Int): Boolean          // 严格大于 HIGH_WATER_ROWS 才 true
fun selectBatch(rows: List<SummaryRow>): List<SummaryRow>? // null=不该折；非空=最旧 FOLD_BATCH 行(升序)
fun renderFoldedTranscript(batch: List<SummaryRow>): String
fun buildSummaryMessages(oldSummary: String, transcript: String): List<ChatMessageDto>
fun clampSummary(raw: String, maxChars: Int = AppConfig.ContextCompression.SUMMARY_MAX_CHARS): String
```

- [ ] **Step 1: 在 AppConfig 中追加常量对象**

在 `AppConfig.kt` 的 `object GroupChat {...}` 之后、类结尾 `}` 前插入：

```kotlin
    // ===== 云端滚动摘要（上下文压缩）=====
    object ContextCompression {
        // 高水位：未摘要原文行数超过该值即触发折叠。160 行 ≈ 80 个对话回合。
        const val HIGH_WATER_ROWS = 160
        // 单批折叠的最旧行数：折完保证仍留 ~80 行原文衔接上文。约每 40 回合发生一次前缀变化。
        const val FOLD_BATCH_ROWS = 80
        // 摘要正文字符上限。
        const val SUMMARY_MAX_CHARS = 300
        // 摘要生成调用超时；失败静默放弃，下个阈值自然重试。
        const val SUMMARY_TIMEOUT_MS = 30_000L
    }
```

- [ ] **Step 2: 写失败的测试**

新建 `app/src/test/java/com/chatbyyourside/llm/RollingSummaryPlannerTest.kt`：

```kotlin
package com.chatbyyourside.llm

import com.chatbyyourside.config.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RollingSummaryPlanner] 纯函数测试：折叠阈值边界、批次选取保序、转写渲染、提示词拼装、摘要截断。
 */
class RollingSummaryPlannerTest {

    private fun row(id: Long, role: String = "user", text: String = "消息$id") =
        RollingSummaryPlanner.SummaryRow(id, role, text)

    @Test
    fun foldOnlyWhenStrictlyAboveHighWater() {
        assertFalse(RollingSummaryPlanner.shouldFold(AppConfig.ContextCompression.HIGH_WATER_ROWS))
        assertTrue(RollingSummaryPlanner.shouldFold(AppConfig.ContextCompression.HIGH_WATER_ROWS + 1))
        assertFalse(RollingSummaryPlanner.shouldFold(0))
    }

    @Test
    fun selectBatchRequiresOverHighWaterAndKeepsOrder() {
        assertNull(RollingSummaryPlanner.selectBatch(List(160) { row(it.toLong()) })) // 未超高水位不折
        val rows = List(181) { row(it.toLong()) }
        val batch = RollingSummaryPlanner.selectBatch(rows)!!
        assertEquals(80, batch.size)
        assertEquals(0L, batch.first().id)   // 最旧 80 行
        assertEquals(79L, batch.last().id)
        assertEquals((0..79).map { it.toLong() }, batch.map { it.id }) // 升序不乱
    }

    @Test
    fun transcriptRendersRoleTagsInOrder() {
        val t = RollingSummaryPlanner.renderFoldedTranscript(
            listOf(row(1, "user", "你好呀"), row(2, "assistant", "你好！")),
        )
        assertTrue(t.contains("[用户] 你好呀"))
        assertTrue(t.contains("[角色] 你好！"))
        assertTrue(t.indexOf("[用户]") < t.indexOf("[角色]"))
    }

    @Test
    fun summaryPromptCarriesOldSummaryAndTranscriptAndWordBudget() {
        val msgs = RollingSummaryPlanner.buildSummaryMessages(
            oldSummary = "旧摘要：主角与旅伴结盟。",
            transcript = "[用户] 出发\n[角色] 好。",
        )
        assertEquals(1, msgs.size)
        assertEquals("system", msgs[0].role)
        val body = msgs[0].content.toString()
        assertTrue(body.contains("旧摘要：主角与旅伴结盟。"))
        assertTrue(body.contains("[用户] 出发"))
        assertTrue(body.contains("300")) // 字数预算出现在指令里
    }

    @Test
    fun clampSummaryTrimsWhitespaceThenHardCaps() {
        val marker = "，……（后略）"
        assertEquals("abc", RollingSummaryPlanner.clampSummary("  abc \n"))
        // 截断保留预算含省略标记：总长恒等于 SUMMARY_MAX_CHARS
        val clamped = RollingSummaryPlanner.clampSummary("字".repeat(500))
        assertEquals(AppConfig.ContextCompression.SUMMARY_MAX_CHARS, clamped.length)
        assertTrue(clamped.endsWith(marker))
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `gradlew :app:testDebugUnitTest --tests "com.chatbyyourside.llm.RollingSummaryPlannerTest"`
Expected: 编译失败（`RollingSummaryPlanner` 不存在）

- [ ] **Step 4: 实现 RollingSummaryPlanner**

新建 `app/src/main/java/com/chatbyyourside/llm/RollingSummaryPlanner.kt`：

```kotlin
package com.chatbyyourside.llm

import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.remote.ChatMessageDto

/**
 * 滚动摘要规划器（纯函数，JVM 可测）。
 *
 * 缓存契约：摘要块只在高水位越过时整批更新一次，两次更新之间请求历史纯追加，
 * 【前情提要】与其后的原文窗口逐字节一致 → 云端前缀缓存全程命中；
 * 折叠那一刻仅重算一次，摊薄命中率 ≈ (40-1)/40。
 */
object RollingSummaryPlanner {

    /** 待折叠源行：id 为 chat_history 主键，role 仅 user/assistant。 */
    data class SummaryRow(val id: Long, val role: String, val text: String)

    fun shouldFold(rowsBeyondWatermark: Int): Boolean =
        rowsBeyondWatermark > AppConfig.ContextCompression.HIGH_WATER_ROWS

    /** 取最旧一批（升序传入时天然保序）；未超高水位返回 null 表示无需折叠。 */
    fun selectBatch(rows: List<SummaryRow>): List<SummaryRow>? {
        if (!shouldFold(rows.size)) return null
        return rows.take(AppConfig.ContextCompression.FOLD_BATCH_ROWS)
    }

    /** 批次转写成带角色标注的时间线文本，交给摘要模型阅读。 */
    fun renderFoldedTranscript(batch: List<SummaryRow>): String = buildString {
        batch.forEach { r ->
            append(if (r.role == "user") "\n[用户] " else "\n[角色] ")
            append(r.text.replace('\n', ' '))
        }
    }.trim()

    /**
     * 摘要生成请求体：单条 system 消息，要求保留关系/承诺/伏笔/情绪基调与时空连续性。
     * 不走流式，不用深度思考——一次性轻量调用。
     */
    fun buildSummaryMessages(oldSummary: String, transcript: String): List<ChatMessageDto> {
        val budget = AppConfig.ContextCompression.SUMMARY_MAX_CHARS
        val oldPart = if (oldSummary.isBlank()) "" else "【此前摘要】\n$oldSummary\n\n"
        return listOf(
            ChatMessageDto(
                role = "system",
                content = kotlinx.serialization.json.JsonPrimitive(
                    "$oldPart【新增对话】\n$transcript\n\n" +
                        "请把以上内容合并为一份连贯的前情提要，用于延续接下来的对话。" +
                        "必须保留：人物关系与称呼、已发生的承诺或约定、未解决的冲突与伏笔、" +
                        "当前情绪基调、时间地点连续性。不要评论、不要分点标题。" +
                        "中文输出，不超过 $budget 字，直接输出摘要正文。",
                ),
            ),
        )
    }

    /** 清洗模型输出并硬截断：总长（含省略标记）恒等于预算。 */
    fun clampSummary(raw: String, maxChars: Int = AppConfig.ContextCompression.SUMMARY_MAX_CHARS): String {
        val cleaned = raw.trim()
        if (cleaned.length <= maxChars) return cleaned
        val marker = "，……（后略）"
        return cleaned.take(maxChars - marker.length) + marker
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `gradlew :app:testDebugUnitTest --tests "com.chatbyyourside.llm.RollingSummaryPlannerTest"`
Expected: PASS（5 个用例全绿）

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/chatbyyourside/config/AppConfig.kt app/src/main/java/com/chatbyyourside/llm/RollingSummaryPlanner.kt app/src/test/java/com/chatbyyourside/llm/RollingSummaryPlannerTest.kt
git commit -m "feat(summary): 折叠水位/批次/提示词纯逻辑规划器 + 单测"
```

---

### Task 2: Room v9 迁移与会话摘要持久化

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/data/local/AppDatabase.kt`（version、ConversationEntity、ConversationDao、ChatDao、MIGRATION_8_9、addMigrations）
- Modify: `app/src/main/java/com/chatbyyourside/data/repository/ConversationRepository.kt`

**Interfaces:**
- Consumes: Task 1 无依赖
- Produces（后续任务引用）:
```kotlin
// ConversationEntity 新增字段（默认值兜底旧行）
val summaryText: String = ""
val summarizedUpToMessageId: Long = 0L
// ConversationDao 新增
suspend fun updateSummary(id: Long, summaryText: String, summarizedUpToMessageId: Long)
// ChatDao 新增（watermark 之后现存全部行，按 id 升序）
suspend fun listAfterWatermark(conversationId: Long, afterId: Long): List<ChatHistoryEntity>
// ConversationRepository 新增
suspend fun updateSummary(conversationId: Long, summaryText: String, upToMessageId: Long)
```

- [ ] **Step 1: ConversationEntity 追加两列**

在 `coverImagePath: String? = null,` 之后追加：

```kotlin
    /**
     * 滚动摘要正文（云端上下文压缩）：已被总结覆盖的最旧对话的脉络概要，注入在
     * 人设 system 之后作为第二级缓存锚。v8->v9 迁移新增列，旧行默认空串。
     */
    val summaryText: String = "",
    /**
     * 已被 [summaryText] 覆盖的最大 chat_history.id；发送侧仅取 id 大于该值的原文进 payload。
     * v8->v9 迁移新增列，旧行默认 0（= 尚无摘要）。
     */
    val summarizedUpToMessageId: Long = 0L,
```

- [ ] **Step 2: ConversationDao 追加写回方法**

在 `updateGroupCover(...)` 方法之后追加：

```kotlin
    /** 写回滚动摘要及其水位（Task：滚动摘要上下文压缩）。 */
    @Query(
        "UPDATE conversation SET summaryText = :summaryText, " +
            "summarizedUpToMessageId = :summarizedUpToMessageId WHERE id = :id"
    )
    suspend fun updateSummary(id: Long, summaryText: String, summarizedUpToMessageId: Long)
```

- [ ] **Step 3: ChatDao 追加水位读取**

在 `getAllHistoryList(...)` 之后追加：

```kotlin
    /**
     * 水位之后的现存原文（滚动摘要用）：仅取 id 大于 [afterId] 的行，按 id 升序，
     * 天然排除 DB 裁剪掉的历史；量级受 MAX_HISTORY_PER_CONVERSATION 兜底约束。
     */
    @Query(
        "SELECT * FROM chat_history WHERE conversationId = :conversationId AND id > :afterId " +
            "ORDER BY id ASC"
    )
    suspend fun listAfterWatermark(conversationId: Long, afterId: Long): List<ChatHistoryEntity>
```

- [ ] **Step 4: version 8→9 + MIGRATION_8_9**

把 `version = 8,` 改为 `version = 9,`；在 `MIGRATION_7_8` 定义之后追加：

```kotlin
        /**
         * v8->v9：会话表新增滚动摘要两列。纯加列非破坏迁移，旧行取默认值
         * （summaryText=''、summarizedUpToMessageId=0 → 功能从零开始累积）。
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `conversation` ADD COLUMN `summaryText` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `conversation` ADD COLUMN `summarizedUpToMessageId` INTEGER NOT NULL DEFAULT 0")
            }
        }
```

并把 `.addMigrations(MIGRATION_2_3, ..., MIGRATION_7_8)` 尾部改为 `..., MIGRATION_7_8, MIGRATION_8_9)`。

⚠️ 同时全局 grep `"conversation"` 的 `@Update`/`@Insert(onConflict=REPLACE)`：若有 REPLACE 整行写入会话表的代码路径，缺省新字段会把摘要清掉——现有代码库 conversation 只经 `@Insert`(create 时，字段默认值生效) 与定点 UPDATE，确认无整行 REPLACE 即可（发现则改定点更新）。

- [ ] **Step 5: ConversationRepository 透传**

在该仓库类体内追加（构造器已持有 `conversationDao`，按现有字段名对齐）：

```kotlin
    /** 写回滚动摘要及水位；失败抛出由调用方静默吞（摘要非关键路径）。 */
    suspend fun updateSummary(conversationId: Long, summaryText: String, upToMessageId: Long) {
        conversationDao.updateSummary(conversationId, summaryText, upToMessageId)
    }
```

（若仓库内 dao 字段命名不同，以实际名为准；确认 `getById` 同文件存在）

- [ ] **Step 6: 编译验证**

Run: `gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 提交**

```bash
git add -A app/src/main/java/com/chatbyyourside/data/
git commit -m "feat(db): v9 会话表滚动摘要两列 + 水位读取/写回 DAO"
```

---

### Task 3: 发送路径接入（水位过滤 + 摘要注入）

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt`（sendMessage 内 ：863 附近与 apiMessages 构建段）

**Interfaces:**
- Consumes: Task 1 `buildSummaryMessages` 无关；本任务只用实体新列 + 现有 API
- Produces: `apiMessages` 头部多出一条可选 `role="system"` 的【前情提要】；`history` 变量已含水过滤

- [ ] **Step 1: 读会话行并过滤历史窗口**

把

```kotlin
                val history = container.chatRepository.getHistory(convId).takeLast(AppConfig.MAX_CONTEXT_MESSAGES)
```

替换为：

```kotlin
                // 滚动摘要：会话行携带水位与旧摘要（幂等快照，供下方组装与后续折叠复用）。
                val conversationRow = container.conversationRepository.getById(convId)
                val summaryText = conversationRow?.summaryText.orEmpty()
                val summaryWatermark = conversationRow?.summarizedUpToMessageId ?: 0L
                val history = container.chatRepository.getHistory(convId)
                    .filter { it.databaseId != null && it.databaseId > summaryWatermark }
                    .takeLast(AppConfig.MAX_CONTEXT_MESSAGES)
```

（lorebook 扫描窗 :897 `scanMessages = history.takeLast(50)` 自动继承过滤语义，摘要外的原文才是本轮可见上下文）

- [ ] **Step 2: 组装处注入摘要 system 消息**

把 `val isCloudProvider = ...` 行下移说明不变；在 `buildList {` 里首条 system `add(...)` 之后紧接着加入第二条（完整段落对照：第一条是 `lorebookStaticHead + char.systemPrompt + ...`）：

```kotlin
                    // 滚动摘要锚：【前情提要】紧随静态 system，只在高水位折叠时变一次——
                    // 变化前历史纯追加、缓存全程命中；Anthropic 端点合并 system 时自然纳入缓存区。
                    // 特殊邂逅可能已在本段上方把 Provider 切到云端，此处读最终生效值。
                    if (summaryText.isNotBlank() &&
                        container.chatProviderManager.getActiveProvider() !is LocalChatProvider
                    ) {
                        add(
                            ChatMessage(
                                role = "system",
                                content = "【前情提要】以下是此前对话的脉络概要，请在回应中保持连贯：\n$summaryText",
                            )
                        )
                    }
```

- [ ] **Step 3: 编译验证**

Run: `gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 手工冒烟清单（真机，可延后到 Task 5 一并验）**

长对话开启云端 AI 发送一轮：logcat 无异常、回复正常；用 adb shell sqlite3 查询 conversation 表确认两列存在且初值为默认。（仅 Task 2+3 都完成后才有意义）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt
git commit -m "feat(chat): 发送路径注入前情提要锚 + 按 summarize 水位过滤历史"
```

---

### Task 4: 后台折叠编排器（Mutex + 摘要调用 + 写回）

**Files:**
- Modify: `app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt`

**Interfaces:**
- Consumes: Task 1 planner 全部函数；Task 2 `chatDao.listAfterWatermark` / `conversationRepository.updateSummary`；现有 `MarkdownParser.stripThink`、`container.directLlmClient.chatOnce(baseUrl, apiKey, model, messages): String`
- Produces: `private val contextFoldMutex: Mutex`、`private fun scheduleContextFold(convId: Long)`；`finalizeAssistant(...)` 尾部一处调用（两个上游调用点 success/stopped 因此都被覆盖）

- [ ] **Step 1: 成员与导入**

文件头部补齐（若无）：

```kotlin
import com.chatbyyourside.data.remote.DirectLlmClient
import com.chatbyyourside.llm.RollingSummaryPlanner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
```

类成员区（与 `generationCounter` 相邻）追加：

```kotlin
    /** 滚动摘要折叠互斥：跨会话全局串行——折叠本身低频（约每 40 轮一次），简单串行足够。 */
    private val contextFoldMutex = Mutex()
```

- [ ] **Step 2: 实现调度入口与执行体**

在 `finalizeAssistant` 函数之后新增：

```kotlin
    /**
     * 回复落库后调用：水位达标则后台折叠最旧一批进摘要。
     * 绝不在发送关键路径上——本函数内部整体 viewModelScope.launch 异步；
     * 任何失败静默放弃（Log.w），下一阈值自然重试，不影响当轮已完成的对话。
     */
    private fun scheduleContextFold(convId: Long) {
        viewModelScope.launch {
            try {
                contextFoldMutex.withLock { performContextFoldLocked(convId) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "滚动摘要折叠放弃（下轮阈值重试）: ${e.message}")
            }
        }
    }

    /** 调用方须已持有 [contextFoldMutex]。 */
    private suspend fun performContextFoldLocked(convId: Long) {
        // 本地推理回合不折：摘要是云端缓存产物，本地路径不消费它。
        if (container.chatProviderManager.getActiveProvider() is LocalChatProvider) return
        val conv = container.conversationRepository.getById(convId) ?: return
        val watermark = conv.summarizedUpToMessageId
        val rows = container.database.chatDao()
            .listAfterWatermark(convId, watermark)
            .map { RollingSummaryPlanner.SummaryRow(it.id, it.role, MarkdownParser.stripThink(it.content)) }
        if (!RollingSummaryPlanner.shouldFold(rows.size)) return
        val batch = RollingSummaryPlanner.selectBatch(rows) ?: return
        val apiConfig = container.settingsRepository.getApiConfigNow()

        Log.i(TAG, "滚动摘要折叠触发：watermark=$watermark batch=${batch.size} rows")
        val summary = withTimeoutOrNull(AppConfig.ContextCompression.SUMMARY_TIMEOUT_MS) {
            container.directLlmClient.chatOnce(
                baseUrl = apiConfig.baseUrl,
                apiKey = apiConfig.apiKey,
                model = apiConfig.model,
                messages = RollingSummaryPlanner.buildSummaryMessages(
                    oldSummary = conv.summaryText,
                    transcript = RollingSummaryPlanner.renderFoldedTranscript(batch),
                ),
            )
        }?.let { RollingSummaryPlanner.clampSummary(MarkdownParser.stripThink(it)) }
        if (summary.isNullOrBlank()) {
            Log.w(TAG, "滚动摘要生成失败/超时，跳过本次折叠")
            return
        }
        container.conversationRepository.updateSummary(convId, summary, batch.last().id)
        Log.i(TAG, "滚动摘要已写回：upTo=${batch.last().id} chars=${summary.length}")
    }
```

（若 `container.database` 暴露的是各具名 Dao 属性而非 `chatDao()` 函数，以实际形态对齐——参考同文件 ：906 `container.database.affinityDao()` 用法。）

- [ ] **Step 3: 接入落库完成点**

在 `finalizeAssistant(...)` 函数体末尾（`touch(convId)` 与乐观替换逻辑之后、函数收尾花括号之前）追加一行：

```kotlin
        // 滚动摘要：成功落库与用户停止保留两种终态都会经过这里；统一在此检查水位。
        scheduleContextFold(convId)
```

- [ ] **Step 4: 编译 + 冒烟验证**

Run: `gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

真机冒烟（需能连 ADB）：临时把 `HIGH_WATER_ROWS` 改成 `6`、`FOLD_BATCH_ROWS` 用默认跑一轮长聊，logcat 过滤 `ChatViewModel` 观察「折叠触发/已写回」日志；随后发新消息看回复是否延续摘要语境；最后恢复 160/80。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/chatbyyourside/ui/chat/ChatViewModel.kt
git commit -m "feat(summary): 回复落库后水位触发的后台折叠编排器（Mutex 串行、失败静默重试）"
```

---

### Task 5: 回归护栏与收尾

**Files:**
- Test: `app/src/test/java/com/chatbyyourside/llm/RollingSummaryPlannerTest.kt`（补 1 例缓存契约守卫）

- [ ] **Step 1: 补缓存契约测试（相邻轮 prefix 稳定断言）**

在测试类中追加：

```kotlin
    @Test
    fun betweenFoldsWindowIsAppendOnly() {
        // 缓存契约模拟：水位固定时，逐轮增长的消息序列里，仅尾部追加、头部永不回退。
        val watermark = 100L
        var tail = (101L..140L).map(::row)
        repeat(20) { round ->
            tail = tail + row(tail.last().id + 1)
            val visible = tail.filter { it.id > watermark } // 发送侧过滤语义
            assertEquals(140L + round, visible.first().id)  // 第一条不随轮次滑动
            assertEquals(watermark, visible.first().id - 1) // 与前缀严格相接（无空洞跳跃）
        }
    }
```

- [ ] **Step 2: 定向测试 + 全量单测回归**

Run: `gradlew :app:testDebugUnitTest --tests "com.chatbyyourside.llm.RollingSummaryPlannerTest" --tests "com.chatbyyourside.llm.LorebookEngineTest" --tests "com.chatbyyourside.data.remote.DirectLlmClientUsageTest"`
Expected: 三组全 PASS（新增功能不触碰既有 Lorebook/Usage 契约）

- [ ] **Step 3: 收尾提交**

```bash
git add app/src/test/java/com/chatbyyourside/llm/RollingSummaryPlannerTest.kt
git commit -m "test(summary): 相邻轮 append-only 缓存契约回归用例"
```

- [ ] **Step 4: 真机验收清单（交还用户）**

1. 云端 AI 连续聊 >45 轮：观察 logcat「折叠触发/已写回」，每约 40 轮出现一次
2. 折叠后继续问一句早期细节（人物承诺类），模型应能凭摘要接上
3. `adb logcat -s DirectLlm` 命中率：折叠后第一轮下降一次，随后回升至高位
4. 切本地 MNN 推理完全不受影响；群聊照常
