package com.chatbyyourside.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DirectLlmClient.parseCloudUsageJson] 纯解析测试：覆盖 DeepSeek 自动缓存字段、
 * OpenAI 兼容系 prompt_tokens_details.cached_tokens（流式 include_usage 最终块）、
 * Anthropic message_start 事件形态，以及无 usage 负载的拒绝路径。
 */
class DirectLlmClientUsageTest {

    private val client = DirectLlmClient(OkHttpClient())

    @Test
    fun deepSeekUsageFields() {
        val raw = """
            {"id":"x","object":"chat.completion",
             "usage":{"prompt_tokens":1000,"completion_tokens":50,
                      "prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":200,
                      "total_tokens":1050}}
        """.trimIndent()
        val s = client.parseCloudUsageJson(raw)!!
        assertEquals(1000, s.promptTokens)
        assertEquals(50, s.completionTokens)
        assertEquals(800, s.cacheHitTokens)
        assertEquals(200, s.cacheMissTokens)
        assertEquals(0.8f, s.hitRatio()!!, 1e-4f)
    }

    @Test
    fun openAiStyleCachedTokensInFinalChunk() {
        // stream_options.include_usage 的最终空 choices 块
        val raw = """
            {"id":"x","object":"chat.completion.chunk","choices":[],
             "usage":{"prompt_tokens":2048,"completion_tokens":64,
                      "prompt_tokens_details":{"cached_tokens":1536}}}
        """.trimIndent()
        val s = client.parseCloudUsageJson(raw)!!
        assertEquals(2048, s.promptTokens)
        assertEquals(64, s.completionTokens)
        assertEquals(1536, s.cacheHitTokens)
        assertNull(s.cacheMissTokens)
    }

    @Test
    fun anthropicMessageStartEvent() {
        val raw = """
            {"type":"message_start",
             "message":{"id":"msg_1","role":"assistant",
                        "usage":{"input_tokens":2096,"output_tokens":1,
                                 "cache_creation_input_tokens":2095,
                                 "cache_read_input_tokens":0}}}
        """.trimIndent()
        val s = client.parseCloudUsageJson(raw)!!
        assertEquals(2096, s.promptTokens)
        assertEquals(1, s.completionTokens)
        assertEquals(0, s.cacheHitTokens)
        assertTrue(s.hasCacheInfo)
    }

    @Test
    fun payloadWithoutUsageReturnsNull() {
        val delta = """{"choices":[{"delta":{"content":"hi"}}]}"""
        assertNull(client.parseCloudUsageJson(delta))
    }

    @Test
    fun hitRatioGuardsZeroAndMissing() {
        val noPrompt = CloudUsageStats(promptTokens = null, completionTokens = null, cacheHitTokens = 10, cacheMissTokens = null)
        assertNull(noPrompt.hitRatio())
        val zero = CloudUsageStats(promptTokens = 0, completionTokens = null, cacheHitTokens = null, cacheMissTokens = null)
        assertNull(zero.hitRatio())
        assertNull(zero.takeIf { it.hasCacheInfo }) // hit=null 视为无缓存信息
    }
}
