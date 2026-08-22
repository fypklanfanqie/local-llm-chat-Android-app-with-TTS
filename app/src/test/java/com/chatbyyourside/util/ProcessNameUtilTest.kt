package com.chatbyyourside.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProcessNameUtil.parseCmdline] 纯函数单测：/proc/self/cmdline 的 NUL 结尾格式解析。
 *
 * 真实读取（currentProcessName）依赖 /proc 文件系统，JVM 单测不覆盖；
 * 解析正确性即足以保证 isMnnProbeProcess 的后缀匹配语义。
 */
class ProcessNameUtilTest {

    private val nul = '\u0000'.toString()

    @Test
    fun `NUL 结尾的探测进程名解析后命中 mnn_probe 后缀`() {
        // /proc/self/cmdline 实际形态：进程名后跟若干 NUL 填充
        val raw = ("com.chatbyyourside:mnn_probe" + nul + nul + nul).toByteArray()
        val name = ProcessNameUtil.parseCmdline(raw)
        assertEquals("com.chatbyyourside:mnn_probe", name)
        assertTrue(name.endsWith(":mnn_probe"))
    }

    @Test
    fun `主进程名不含 mnn_probe 后缀`() {
        val raw = ("com.chatbyyourside" + nul).toByteArray()
        val name = ProcessNameUtil.parseCmdline(raw)
        assertEquals("com.chatbyyourside", name)
        assertFalse(name.endsWith(":mnn_probe"))
    }

    @Test
    fun `无 NUL 字节时整段解码`() {
        val raw = "com.chatbyyourside".toByteArray()
        assertEquals("com.chatbyyourside", ProcessNameUtil.parseCmdline(raw))
    }

    @Test
    fun `空数组返回空串`() {
        assertEquals("", ProcessNameUtil.parseCmdline(ByteArray(0)))
    }

    @Test
    fun `全 NUL 数组返回空串`() {
        assertEquals("", ProcessNameUtil.parseCmdline(byteArrayOf(0, 0, 0)))
    }
}
