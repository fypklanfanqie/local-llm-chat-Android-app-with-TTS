package com.chatbyyourside.llm

import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.remote.ChatMessageDto
import kotlinx.serialization.json.JsonPrimitive

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

    fun shouldFold(
        rowsBeyondWatermark: Int,
        highWaterRows: Int = AppConfig.ContextCompression.HIGH_WATER_ROWS,
    ): Boolean = rowsBeyondWatermark > highWaterRows

    /** 取最旧一批（升序传入时天然保序）；未超高水位返回 null 表示无需折叠。 */
    fun selectBatch(
        rows: List<SummaryRow>,
        batchSize: Int = AppConfig.ContextCompression.FOLD_BATCH_ROWS,
    ): List<SummaryRow>? {
        if (rows.size <= batchSize * 2) return null
        return rows.take(batchSize)
    }

    // ===== 折叠间隔 → 水位/批量 派生（用户可调间隔时使用）=====

    /** 把用户设定的折叠间隔（回合数）钳位到合法区间，返回单批折叠行数。 */
    fun batchRowsFor(intervalRounds: Int): Int =
        intervalRounds.coerceIn(AppConfig.ContextCompression.MIN_FOLD_INTERVAL_ROUNDS, AppConfig.ContextCompression.MAX_FOLD_INTERVAL_ROUNDS) * 2

    /** 由批量行数派生触发水位（批量的两倍：折完留足一整批原文衔接）。 */
    fun highWaterRowsFor(batchRows: Int): Int = batchRows * 2

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
                content = JsonPrimitive(
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
