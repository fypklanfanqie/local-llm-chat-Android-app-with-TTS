package com.chatbyyourside.provider.local

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalStreamRenderPump] 契约测试（Task 4）。
 *
 * 验证：首块立即渲染；高频 append 被 conflated 合并并按节流渲染；finish 取消渲染协程后
 * 同步渲染最终帧且不再触发任何 UI 回调；策略截断正确收缩累加器。
 */
class LocalStreamRenderPumpTest {

    private fun pump(
        scope: kotlinx.coroutines.CoroutineScope,
        minIntervalMs: Long = 30L,
        clock: () -> Long,
    ) = LocalStreamRenderPump(scope = scope, minIntervalMs = minIntervalMs, clock = clock)

    @Test
    fun firstDeltaRendersImmediately() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()
        p.append("你好")
        runCurrent()
        assertEquals(listOf("你好"), rendered)
        p.finish()
    }

    @Test
    fun rapidAppendsCoalesceToCadence() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, minIntervalMs = 30, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()

        p.append("a")
        runCurrent()
        assertEquals(listOf("a"), rendered)

        // 第二个信号在节流窗口内被合并：b、c 两个 append 合并为一次渲染
        p.append("b")
        advanceTimeBy(10)
        p.append("c")
        advanceTimeBy(30)
        runCurrent()
        assertEquals("b/c 未合并到一次渲染", listOf("a", "abc"), rendered)
        p.finish()
    }

    @Test
    fun finishRendersFinalFrameAndStopsFurtherCallbacks() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()

        p.append("第一段")
        // 不推进时间：渲染协程尚在节流/挂起，finish 取消它并同步渲染最终帧
        p.finish()
        assertEquals(listOf("第一段"), rendered)

        // finish 后任何 append 都不得再触发渲染（避免完成路径追加幽灵 streaming 气泡）
        p.append("第二段")
        runCurrent()
        assertEquals(listOf("第一段"), rendered)
    }

    @Test
    fun truncateToShrinksAccumulator() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()

        p.append("ABCDE")
        p.truncateTo(3)
        assertEquals("ABC", p.snapshot())
        p.finish()
        assertEquals(listOf("ABC"), rendered)
    }

    @Test
    fun emptySnapshotsDoNotRender() = runTest {
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { rendered += it }
        p.start()
        p.finish()   // 未追加任何内容：不触发 onChunk
        assertTrue(rendered.isEmpty())
    }

    // ===== Task 5：回调异常不逃逸 =====

    @Test
    fun throwingOnChunkDoesNotEscapeAndPumpKeepsRendering() = runTest {
        val rendered = mutableListOf<String>()
        var failFirst = true
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { it }
        p.onChunk = { text ->
            if (failFirst) {
                failFirst = false
                throw IllegalStateException("宿主 UI 回调炸了")
            }
            rendered += text
        }
        p.start()
        // 第一帧 onChunk 抛异常：不得沿渲染协程逃逸（否则 backgroundScope 子协程失败、测试报错）。
        p.append("第一帧")
        runCurrent()
        assertTrue(rendered.isEmpty())
        // 推进虚拟时间越过节流窗口后下一帧仍正常渲染：泵未被一次回调异常打断。
        // 注意：pump 回调的是「全文快照」（第一帧+第二帧），异常首帧的文本不丢——
        // 权威累积在 pump 内，UI 层按快照覆盖渲染。
        advanceTimeBy(31)
        p.append("第二帧")
        runCurrent()
        assertEquals(listOf("第一帧第二帧"), rendered)
        p.finish()
    }

    @Test
    fun throwingDecorateSkipsFrameWithoutKillingRenderLoop() = runTest {
        var failNext = true
        val rendered = mutableListOf<String>()
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        p.decorate = { raw ->
            if (failNext) {
                failNext = false
                throw IllegalArgumentException("装饰解析失败")
            }
            "[$raw]"
        }
        p.onChunk = { rendered += it }
        p.start()
        // 首帧装饰抛异常：本帧放弃（onChunk 不被调用），渲染协程存活。
        p.append("a")
        runCurrent()
        assertTrue(rendered.isEmpty())
        // 下一帧装饰恢复后继续渲染。
        p.append("b")
        runCurrent()
        assertEquals(1, rendered.size)
        assertTrue(rendered[0].startsWith("["))
        p.finish()
    }

    @Test
    fun normalCancellationStillPropagatesFromCallback() = runTest {
        val p = pump(backgroundScope, clock = { testScheduler.currentTime })
        var sawCancel = false
        p.decorate = { it }
        p.onChunk = { throw kotlinx.coroutines.CancellationException("宿主取消") }
        p.start()
        try {
            p.append("x")
            // finish 内部 doRender 同步调用 onChunk -> CancellationException 上抛。
            p.finish()
        } catch (e: kotlinx.coroutines.CancellationException) {
            sawCancel = true
        }
        assertTrue("正常取消必须传播", sawCancel)
    }
}
