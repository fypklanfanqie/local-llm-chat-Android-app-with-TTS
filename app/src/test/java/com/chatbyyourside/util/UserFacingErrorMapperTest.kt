package com.chatbyyourside.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [UserFacingErrorMapper] 纯 JVM 单测：异常类型、文本模式、HTTP 码映射，
 * 并断言原始英文消息/URL/LogID 绝不出现在结果里。
 */
class UserFacingErrorMapperTest {

    @Test
    fun `null 异常返回 fallback`() {
        assertEquals("下载失败", UserFacingErrorMapper.userFacingError(null, "下载失败"))
    }

    @Test
    fun `未知异常回落 fallback 且不透传原始 message`() {
        val t = RuntimeException("Secret internal path /data/user/0/x failed")
        val msg = UserFacingErrorMapper.userFacingError(t, "请求失败")
        assertEquals("请求失败", msg)
        assertFalse(msg.contains("Secret"))
    }

    @Test
    fun `SocketTimeout 与超时字样映射为网络超时`() {
        assertEquals(
            UserFacingErrorMapper.MSG_NETWORK_TIMEOUT,
            UserFacingErrorMapper.userFacingError(SocketTimeoutException("read timed out")),
        )
        assertEquals(
            UserFacingErrorMapper.MSG_NETWORK_TIMEOUT,
            UserFacingErrorMapper.userFacingError(RuntimeException("HTTP timeout")),
        )
    }

    @Test
    fun `UnknownHost 与连接失败映射为无法连接服务器`() {
        assertEquals(
            UserFacingErrorMapper.MSG_NETWORK_UNREACHABLE,
            UserFacingErrorMapper.userFacingError(UnknownHostException("api.example.com")),
        )
        assertEquals(
            UserFacingErrorMapper.MSG_NETWORK_UNREACHABLE,
            UserFacingErrorMapper.userFacingError(IOException("Connection reset by peer")),
        )
    }

    @Test
    fun `OOM 映射为内存不足`() {
        val msg = UserFacingErrorMapper.userFacingError(OutOfMemoryError("Failed to allocate"))
        assertEquals(UserFacingErrorMapper.MSG_OUT_OF_MEMORY, msg)
    }

    @Test
    fun `UnsatisfiedLinkError 与 JNI 契约异常映射为引擎版本不匹配`() {
        assertEquals(
            UserFacingErrorMapper.MSG_ENGINE_MISMATCH,
            UserFacingErrorMapper.userFacingError(UnsatisfiedLinkError("No implementation found for x")),
        )
        // 文本模式兜底（跨包类型不可直接引用时）。
        assertTrue(
            UserFacingErrorMapper.userFacingError(RuntimeException("UnsatisfiedLinkError: no impl"))
                .contains("本地 AI 引擎"),
        )
    }

    @Test
    fun `HTTP 状态码文本映射为对应中文`() {
        assertEquals(
            "API Key 无效或没有访问权限，请在设置页检查密钥",
            UserFacingErrorMapper.userFacingError(RuntimeException("HTTP 401: Unauthorized")),
        )
        assertEquals(
            "服务暂时不可用（500），请稍后再试",
            UserFacingErrorMapper.userFacingError(RuntimeException("HTTP 500 @ https://secret.example.com/v1")),
        )
    }

    @Test
    fun `HTTP 结果不泄露 URL 或供应商原文`() {
        val raw = "HTTP 429: rate limited by provider (LogID: abc123) at https://internal.example.com/path"
        val msg = UserFacingErrorMapper.userFacingError(RuntimeException(raw))
        assertFalse(msg.contains("https://"))
        assertFalse(msg.contains("LogID"))
        assertFalse(msg.contains("rate limited"))
    }

    @Test
    fun `JSON 与空响应映射为服务返回内容异常`() {
        assertEquals(
            UserFacingErrorMapper.MSG_BAD_RESPONSE,
            UserFacingErrorMapper.userFacingError(RuntimeException("failed to parse JSON response")),
        )
        assertEquals(
            UserFacingErrorMapper.MSG_BAD_RESPONSE,
            UserFacingErrorMapper.userFacingError(RuntimeException("响应体为空")),
        )
    }

    @Test
    fun `httpMessage 覆盖常用状态码`() {
        assertTrue(UserFacingErrorMapper.httpMessage(404).contains("地址"))
        assertTrue(UserFacingErrorMapper.httpMessage(429).contains("频繁") || UserFacingErrorMapper.httpMessage(429).contains("额度"))
        assertTrue(UserFacingErrorMapper.httpMessage(503).contains("服务"))
    }
}
