package com.chatbyyourside.data.remote

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型清单远程刷新（`GET /models`）的纯逻辑契约：
 * 端点归一化与响应解析。网络本身由设置页「从服务商获取」触发，这里只锁可测部分。
 */
class DirectLlmModelListTest {

    private val client = DirectLlmClient(OkHttpClient())

    @Test
    fun modelsEndpoint_openAiCompatible_appendsModels() {
        assertEquals("https://api.deepseek.com/v1/models", client.buildModelsEndpoint("https://api.deepseek.com/v1"))
        // 尾斜杠不该产生双斜杠
        assertEquals("https://api.deepseek.com/v1/models", client.buildModelsEndpoint("https://api.deepseek.com/v1/"))
    }

    @Test
    fun modelsEndpoint_stripsPastedChatCompletionsSuffix() {
        // 中转站常把完整端点粘进来：必须先剥掉再拼 /models，否则 404
        assertEquals(
            "https://relay.example.com/v1/models",
            client.buildModelsEndpoint("https://relay.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun modelsEndpoint_anthropicUsesV1Models() {
        assertEquals(
            "https://api.anthropic.com/v1/models",
            client.buildModelsEndpoint("https://api.anthropic.com/v1/messages"),
        )
        assertEquals(
            "https://api.anthropic.com/v1/models",
            client.buildModelsEndpoint("https://api.anthropic.com/v1"),
        )
    }

    @Test
    fun parseModelIds_standardOpenAiDataArray() {
        val body = """{"object":"list","data":[{"id":"deepseek-chat"},{"id":"deepseek-reasoner"}]}"""
        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), client.parseModelIds(body))
    }

    @Test
    fun parseModelIds_toleratesModelsKeyBareArrayAndPrefix() {
        // 少数网关用 models 键 / 裸数组 / models/ 前缀，都要能吃下并剥前缀
        assertEquals(listOf("gpt-4o"), client.parseModelIds("""{"models":[{"id":"models/gpt-4o"}]}"""))
        assertEquals(listOf("plain-model"), client.parseModelIds("""["plain-model"]"""))
    }

    @Test
    fun parseModelIds_dedupesAndDropsBlanks() {
        val body = """{"data":[{"id":"a"},{"id":"a"},{"id":"  "},{"name":"b"}]}"""
        assertEquals(listOf("a", "b"), client.parseModelIds(body))
    }

    @Test
    fun parseModelIds_garbageReturnsEmpty() {
        assertTrue(client.parseModelIds("not json").isEmpty())
        assertTrue(client.parseModelIds("""{"unexpected":1}""").isEmpty())
    }
}
