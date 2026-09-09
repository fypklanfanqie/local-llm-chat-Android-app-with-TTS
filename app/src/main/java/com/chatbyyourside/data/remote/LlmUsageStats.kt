package com.chatbyyourside.data.remote

/**
 * 单次云端请求 token 用量（按角色记账与缓存命中观测的统一载体）。
 *
 * [cachedTokens] 是命中率观测的核心字段：OpenAI 兼容端点取
 * `usage.prompt_tokens_details.cached_tokens`，DeepSeek 取 `prompt_cache_hit_tokens`，
 * Anthropic 取 `cache_read_input_tokens`——各家字段的解析统一在
 * [DirectLlmClient.parseCloudUsageJson] 完成，本类型只作为回调的稳定契约。
 *
 * 说明：另写一份环形统计 + 独立 usage 解析器会与 [CloudUsageStats] / `lastCloudUsage`
 * 重复，故此处只保留记账契约，避免仓库里出现两套用量口径。
 */
data class LlmTokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

/** 既有各家 usage 解析结果 -> 记账契约；无计数的一侧按 0 计（记账侧另有零值红线）。 */
internal fun CloudUsageStats.toLlmTokenUsage(): LlmTokenUsage = LlmTokenUsage(
    promptTokens = promptTokens ?: 0,
    completionTokens = completionTokens ?: 0,
    cachedTokens = cacheHitTokens ?: 0,
    cacheWriteTokens = 0,
)
