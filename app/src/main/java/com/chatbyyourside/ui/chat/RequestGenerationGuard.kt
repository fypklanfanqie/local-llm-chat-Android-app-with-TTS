package com.chatbyyourside.ui.chat

/**
 * 请求代际守卫（Task 5）：纯 JVM、无 Android 依赖，可单测。
 *
 * 背景：本地同步 JNI 取消后 provider 可能继续回调旧请求的 onChunk；旧 job 的 catch/finally
 * 也可能晚于新请求启动才执行。若不校验「自己还是当前活跃请求」，迟到回调会覆盖新会话的
 * streaming 气泡、迟到的 finally 会清掉新请求的状态（丢消息/按钮卡死）。
 *
 * 用法：每次发请求 [next] 取自增序号；回调/收尾入口用 [isCurrent] 校验，
 * 不匹配直接丢弃。序号只在发起方线程递增（ViewModel 主线程），读取可为任意线程。
 */
class RequestGenerationGuard {

    /** 单调递增的请求序号；0 表示尚无请求。仅发起方调用。 */
    @get:Synchronized
    var current: Long = 0L
        private set

    /** 分配新请求序号并返回（使所有旧序号失效）。 */
    @Synchronized
    fun next(): Long = ++current

    /** 该序号是否仍是当前活跃请求（旧回调/旧 finally 返回 false -> 调用方必须丢弃）。 */
    @Synchronized
    fun isCurrent(generation: Long): Boolean = generation == current && current != 0L

    /** 显式失效全部请求（切会话/清状态时可选调用；此后任何 isCurrent 都为 false）。 */
    @Synchronized
    fun invalidateAll() {
        current = -1
    }
}
