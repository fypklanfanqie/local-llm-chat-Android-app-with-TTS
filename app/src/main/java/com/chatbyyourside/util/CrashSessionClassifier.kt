package com.chatbyyourside.util

/**
 * 启动会话崩溃分类器（Task 5，纯 JVM、无 Android 依赖，可单测）。
 *
 * 背景：旧的 CrashWatchdog 判定基于 phase 归档（provider/application/activity/loaded）。
 * WorkManager 的问候/群聊 Worker 在主进程冷启动时同样经过 provider→application 阶段但永远没有
 * Activity，进程停在 application——下一次前台启动读到「上次停在 application」就误判为崩溃，
 * 连续两次后台运行即把用户误带入崩溃循环安全模式（跳过探测与后台调度的降级启动）。
 *
 * 方案：给每轮进程启动引入**会话类型**（session kind），由各组件在各自最早时机写入：
 * - [KIND_FOREGROUND_PENDING]：CrashInitProvider 初始化（尚未确认前台/后台）；
 * - [KIND_FOREGROUND_ACTIVE]：MainActivity.onCreate 最早升级（已进入前台 Activity；
 *   Loading 完成另有 loaded 标记）；
 * - [KIND_BACKGROUND_WORKER]：Worker doWork 最早标记（不覆盖已进入前台的活跃会话）；
 * - [KIND_UNKNOWN]：显式未知（预留；读不到/无法识别的取值同归此类处理）。
 *
 * 判定规则（[classify]，只依据**归档**的上一轮会话快照，与本轮标记完全隔离）：
 * - loaded -> 正常（无论类型）；
 * - 类型为 foreground_active -> 崩溃（已进前台且未 loaded，即 Activity/加载窗口内死亡）；
 * - 其余类型（pending/background/unknown/未来未知值）-> 不计（后台与未确认会话绝不误判）；
 * - 类型缺失（旧版本 journal）-> 保留旧语义 started&&!loaded；历史 application 归档不算新崩溃。
 *
 * phase 字段保留在快照里仅供诊断展示（崩溃日志头部），不参与分类。
 */
object CrashSessionClassifier {

    /** 尚未确认前台/后台（provider 阶段的初始态）。 */
    const val KIND_FOREGROUND_PENDING = "foreground_pending"

    /** 已进入前台 Activity（MainActivity.onCreate 最早写入）。 */
    const val KIND_FOREGROUND_ACTIVE = "foreground_active"

    /** 后台 Worker 会话（WorkManager 冷启动，无 Activity）。 */
    const val KIND_BACKGROUND_WORKER = "background_worker"

    /** 显式未知/无法识别。 */
    const val KIND_UNKNOWN = "unknown"

    /** 分类结论。 */
    enum class Verdict {
        /** 上一轮在启动窗口内异常退出（计入连续崩溃）。 */
        CRASH,

        /** 上一轮正常结束或不满足崩溃证据（计数归零）。 */
        CLEAN,
    }

    /**
     * 上一轮启动会话的归档快照。
     * @param kind 归档的会话类型；null = 旧版本 journal（无类型标记），走兼容分支。
     * @param started 归档的 MainActivity 存活标记（旧版本唯一证据）。
     * @param loaded 归档的加载完成标记。
     * @param lastPhase 归档的最后阶段（仅诊断展示用，不参与分类）。
     */
    data class ArchivedSession(
        val kind: String?,
        val started: Boolean,
        val loaded: Boolean,
        val lastPhase: String?,
    )

    /**
     * 分类上一轮会话是否为启动窗口内异常退出。
     * 规则见类注释；任何「不确定」的输入都归入 [Verdict.CLEAN]（宁漏判不误判——误判的代价是
     * 用户被错误降级启动，漏判仍有崩溃日志兜底）。
     */
    fun classify(session: ArchivedSession): Verdict = when {
        // 加载完成 = 本轮启动窗口安全通过，无论之后进程怎么退出都不算启动窗口内死亡。
        session.loaded -> Verdict.CLEAN
        // 旧版本 journal（无会话类型）：保留原 started/loaded 双标记判定。
        // 历史归档停在 application 阶段（可能是后台 Worker 会话）不算新崩溃。
        session.kind == null -> if (session.started) Verdict.CRASH else Verdict.CLEAN
        // 已进入前台 Activity 且未完成加载：Activity/加载窗口内的真实死亡。
        session.kind == KIND_FOREGROUND_ACTIVE -> Verdict.CRASH
        // pending / background_worker / unknown / 未来新增的未知取值：一律不计。
        else -> Verdict.CLEAN
    }

    /**
     * 后台 Worker 是否允许把本轮会话改写为 background_worker：
     * 已进入前台的活跃会话（foreground_active）不可被覆盖——前台先进入、周期 Worker 后触发的
     * 竞态下保住前台真相。其余状态（pending/background/unknown/null）均可写。
     */
    fun shouldMarkBackgroundWorker(currentKind: String?): Boolean =
        currentKind != KIND_FOREGROUND_ACTIVE
}
