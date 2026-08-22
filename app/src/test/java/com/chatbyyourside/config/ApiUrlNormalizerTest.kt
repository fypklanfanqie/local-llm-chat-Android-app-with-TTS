package com.chatbyyourside.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [normalizeBaseUrl] 与 [isFreeProxyBaseUrl] 纯函数测试。
 *
 * 覆盖：剥离粘贴/输入法带进 URL 尾部的 scheme 残渣、trim 空白与尾斜杠、
 * 缺 scheme 补 https://、空串/纯 scheme 判无效、幂等、免费代理地址污染豁免。
 */
class ApiUrlNormalizerTest {

    private fun assertNormalized(input: String, expected: String) {
        assertEquals("normalizeBaseUrl($input)", expected, normalizeBaseUrl(input))
    }

    @Test
    fun stripsTrailingSchemeJunk() {
        assertNormalized(
            "https://note3-prev-api.askdiandian.com/v1/chat/completionshttps",
            "https://note3-prev-api.askdiandian.com/v1/chat/completions",
        )
        assertNormalized("https://api.deepseek.com/v1/messageshttps", "https://api.deepseek.com/v1/messages")
        assertNormalized("https://api.deepseek.com/v1/chat/completionshttps/", "https://api.deepseek.com/v1/chat/completions")
        assertNormalized("https://x.com/v1/chat/completionshttps://", "https://x.com/v1/chat/completions")
        // 大写残渣同样剥除（removeSuffix 大小写敏感，须用 dropLast）
        assertNormalized("https://x.com/v1/chat/completionsHTTPS", "https://x.com/v1/chat/completions")
    }

    @Test
    fun trimsWhitespaceAndTrailingSlash() {
        assertNormalized("  https://api.deepseek.com/  ", "https://api.deepseek.com")
        assertNormalized("https://api.deepseek.com/", "https://api.deepseek.com")
    }

    @Test
    fun prependsSchemeWhenMissing() {
        assertNormalized("api.deepseek.com", "https://api.deepseek.com")
        assertNormalized("api.deepseek.com:8080/v1", "https://api.deepseek.com:8080/v1")
        assertNormalized("api.deepseek.com/v1/chat/completions", "https://api.deepseek.com/v1/chat/completions")
    }

    @Test
    fun keepsCleanUrlsUnchanged() {
        assertNormalized("https://api.deepseek.com/v1/chat/completions", "https://api.deepseek.com/v1/chat/completions")
        assertNormalized("https://api.openai.com/v1", "https://api.openai.com/v1")
        assertNormalized("HTTP://API.EXAMPLE.COM", "HTTP://API.EXAMPLE.COM")
    }

    @Test
    fun blankAndSchemeOnlyAreInvalid() {
        assertNormalized("", "")
        assertNormalized("   ", "")
        assertNormalized("https", "")
        assertNormalized("http", "")
    }

    @Test
    fun isIdempotent() {
        val inputs = listOf(
            "https://note3-prev-api.askdiandian.com/v1/chat/completionshttps",
            "api.deepseek.com",
            "https://api.deepseek.com/v1/messageshttps/",
            "HTTP://API.EXAMPLE.COM",
            "https://x.com/v1/chat/completionshttps://",
            "",
        )
        inputs.forEach { input ->
            val once = normalizeBaseUrl(input)
            assertEquals("normalizeBaseUrl 非幂等：$input", once, normalizeBaseUrl(once))
        }
    }

    @Test
    fun freeProxyBaseUrl_recognizesPollutedStoredValue() {
        assertTrue(isFreeProxyBaseUrl("https://siliconflow-free-proxy.lanfanqie.workers.dev/v1https"))
        assertTrue(isFreeProxyBaseUrl("siliconflow-free-proxy.lanfanqie.workers.dev/v1"))
        assertTrue(isFreeProxyBaseUrl(FREE_PROVIDER_BASE_URL))
        assertFalse(isFreeProxyBaseUrl("https://api.deepseek.com"))
    }
}
