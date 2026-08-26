package com.chatbyyourside.provider.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地流式渲染泵（Task 4）：把全文装饰与 UI 回调移出**同步 JNI decode 回调**。
 *
 * 背景：MNN `generate(1)` 步进循环中，每次回调都在解码线程同步执行
 * `accumulated.toString()`（O(n) 拷贝）+ `renderLocalThink` + 完整 Markdown 解析 + StateFlow 更新；
 * 长回复累计为 O(n²)，且这些工作直接推迟下一次 `generate(1)`，降低有效解码速率。
 *
 * 本类把上述工作挪到独立协程：
 * - 解码线程只做增量 append（锁内）+ 增量剧本检测 + 策略截断 + conflated 渲染信号，立即返回；
 * - 渲染协程用 [Channel.CONFLATED] 合并高频信号，按 [minIntervalMs] 节流快照全文、装饰并回调 UI；
 * - 首个完整可见 delta 立即渲染（无间隔等待）；
 * - [finish] 在 native 返回后取消渲染协程并同步渲染最终帧，此后不再触发任何 UI 回调，
 *   避免晚到的渲染信号在完成消息替换后追加幽灵 `streaming` 气泡。
 *
 * 线程模型：`accumulated` 由解码线程写入、渲染线程读取，全部经 [textLock] 保护；
 * [onChunk] 只由渲染协程与 [finish]（解码线程收尾）调用，二者经 [renderLock] 串行。
 *
 * 纯 JVM 可测：无 Android 依赖（回调异常经 [onError] 通知，缺省忽略）；[clock] 可注入
 * （测试用虚拟时钟）。
 */
class LocalStreamRenderPump(
    private val scope: CoroutineScope,
    /** 相邻两次 UI 渲染的最小间隔（ms）；首块立即渲染。 */
    private val minIntervalMs: Long = 30L,
    /** 单调时钟（生产用 `System.nanoTime`；测试注入虚拟时钟）。 */
    private val clock: () -> Long = { System.nanoTime() },
) {
    /** conflated 信号：解码线程高频 trySend，渲染线程只处理最新一个。 */
    private val signal = Channel<Unit>(Channel.CONFLATED)

    /** 全文累加器（唯一权威原文）。 */
    private val textLock = Any()
    private val accumulated = StringBuilder()

    /** 串行化 onChunk：渲染协程与 finish 不并发调用。 */
    private val renderLock = Mutex()

    private var renderJob: Job? = null
    private var lastRenderMs: Long? = null

    /** 装饰回调（如 `renderLocalThink`）：把原文包成 `<think>` 折叠展示。 */
    @Volatile
    var decorate: ((String) -> String)? = null

    /** UI 回调（如 ChatViewModel.onChunk）：仅在渲染节流放行时调用。 */
    @Volatile
    var onChunk: ((String) -> Unit)? = null

    /**
     * 回调异常通知（Task 5）：decorate/onChunk 抛出普通异常时调用（CancellationException 不算），
     * 生产侧接日志/遥测；测试据此断言异常被收口。本类自身不依赖 android.util.Log。
     */
    @Volatile
    var onError: ((Throwable) -> Unit)? = null

    /** 启动渲染协程（幂等）。 */
    fun start() {
        if (renderJob != null) return
        renderJob = scope.launch {
            for (u in signal) {
                val now = clock()
                val since = lastRenderMs?.let { now - it } ?: 0L
                if (lastRenderMs != null && since < minIntervalMs) {
                    delay(minIntervalMs - since)
                }
                doRender()
            }
        }
    }

    /** 解码线程调用：追加增量并置位渲染信号（非挂起、永不阻塞）。 */
    fun append(text: String) {
        synchronized(textLock) { accumulated.append(text) }
        signal.trySend(Unit)
    }

    /** 快照当前全文（锁定读取，供基准/结果构造）。 */
    fun snapshot(): String = synchronized(textLock) { accumulated.toString() }

    /**
     * 解码线程调用：策略截断到指定长度（只缩不扩）并立即触发一帧重渲染。
     *
     * 截断让累加器变短——若不立即重渲染，UI 上的 streaming 气泡仍显示截断前的较长文本，
     * 完成路径用 snapshot 构造最终消息时会「瞬间缩短」，用户感知为回复说完后内容消失/回缩。
     * 置位渲染信号走既有节流协程（首帧语义与正常流式一致）。
     */
    fun truncateTo(length: Int) {
        synchronized(textLock) {
            if (accumulated.length > length) accumulated.setLength(length)
        }
        signal.trySend(Unit)
    }

    /**
     * native 返回后收尾：取消渲染协程（丢弃 pending 信号）、等待其退出，再同步渲染最终帧。
     * 此后 [onChunk] 不会再被调用，避免完成路径被晚到渲染追加幽灵气泡。
     */
    suspend fun finish() {
        renderJob?.cancel()
        renderJob?.join()
        doRender()
    }

    private suspend fun doRender() {
        renderLock.withLock {
            val raw = snapshot()
            if (raw.isEmpty()) return
            // Task 5：装饰/UI 回调异常独立收口——宿主回调（Markdown 解析、StateFlow 更新）抛出时
            // 绝不让异常沿渲染协程逃逸（否则 SupervisorJob 子协程失败会中断后续渲染帧）；
            // CancellationException 原样 rethrow 保持取消传播。经 onError 通知后本帧放弃，下一信号继续。
            val decorated = try {
                decorate?.invoke(raw) ?: raw
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                runCatching { onError?.invoke(e) }
                return
            }
            try {
                onChunk?.invoke(decorated)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                runCatching { onError?.invoke(e) }
            }
            lastRenderMs = clock()
        }
    }
}
