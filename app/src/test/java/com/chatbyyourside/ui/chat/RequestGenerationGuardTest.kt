package com.chatbyyourside.ui.chat

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RequestGenerationGuard] 纯 JVM 单测（Task 5）：
 * 覆盖「旧回调在新请求/切会话时被丢弃」「旧 finally 不清新状态」「正常取消仍传播」语义。
 */
class RequestGenerationGuardTest {

    @Test
    fun `新请求使旧请求序号失效`() {
        val guard = RequestGenerationGuard()
        val g1 = guard.next()
        assertTrue(guard.isCurrent(g1))
        val g2 = guard.next()
        assertFalse("旧回调必须被丢弃", guard.isCurrent(g1))
        assertTrue(guard.isCurrent(g2))
    }

    @Test
    fun `invalidateAll 后任何序号都不再是当前`() {
        val guard = RequestGenerationGuard()
        val g1 = guard.next()
        guard.invalidateAll()
        assertFalse(guard.isCurrent(g1))
    }

    @Test
    fun `初始态无当前请求`() {
        val guard = RequestGenerationGuard()
        assertFalse("current=0 时 isCurrent(0) 也为 false", guard.isCurrent(0L))
    }

    @Test
    fun `旧 finally 场景 - 新请求接管后旧收尾被拒`() {
        val guard = RequestGenerationGuard()
        // 第 1 轮发送。
        val g1 = guard.next()
        // 用户立刻发第 2 条 -> 新代际。
        val g2 = guard.next()
        // 旧 job 的 catch/finally 迟到执行：凭守卫识别自己已被取代。
        if (guard.isCurrent(g1)) {
            throw AssertionError("迟到的旧 finally 不应通过校验")
        }
        // 新请求状态完好（模拟：finally 只在 isCurrent 通过时清理）。
        assertEquals(g2, guard.current)
    }

    @Test
    fun `正常取消仍向上传播 - CancellationException 不被守卫吞掉`() {
        // 守卫只做布尔校验、不捕获异常；这里固化调用方约定：
        // CancellationException 必须先于守卫判定之外原样 rethrow。
        fun callback(guard: RequestGenerationGuard, generation: Long) {
            try {
                throw CancellationException("宿主取消")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
            if (!guard.isCurrent(generation)) return
        }
        val guard = RequestGenerationGuard()
        val g = guard.next()
        var propagated = false
        try {
            callback(guard, g)
        } catch (e: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        // 守卫本身不受影响。
        assertTrue(guard.isCurrent(g))
    }
}
