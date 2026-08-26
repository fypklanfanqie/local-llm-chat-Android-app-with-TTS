package com.chatbyyourside.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [CrashWatchdog] 会话化 journal 的真实文件 I/O 测试（Task 5）。
 *
 * CrashWatchdog 全部公开 API 只依赖 `Context.filesDir` 一个文件路径；测试用 TemporaryFolder
 * 构造同构目录走**真实实现**（非桩），覆盖：
 * - 后台 Worker 会话（background_worker）不计崩溃、不误入安全模式；
 * - 前台启动未完成加载计崩溃；
 * - 正常 loaded 不计且 streak 归零；
 * - 旧版本 journal（无 session_kind/last_kind）兼容判定，历史 application 归档不算新崩溃；
 * - Worker 不覆盖前台活跃会话的并发门禁；
 * - provider 初始化时序（archiveArchivedKindIfNeeded -> markSessionKind(pending)）下
 *   updateCrashStreak 读到的是归档侧的上轮真相。
 */
class CrashWatchdogJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 与生产 journalDir(context) 同构：filesDir/startup_journal。 */
    private fun journal(): File {
        val filesDir = tmp.newFolder("files")
        return File(filesDir, "startup_journal")
    }

    // ===== 后台 Worker 冷启动场景（本任务修复的核心误判）=====

    @Test
    fun `后台 Worker 冷启动后前台启动不误判崩溃`() {
        val dir = journal()
        // 第 N 轮：后台 Worker 冷启动 —— provider 标 pending、Worker 改 background_worker、
        // 停在 application 阶段死亡。
        CrashWatchdog.archiveArchivedKindIfNeeded(dir)
        CrashWatchdog.markPhase(dir, CrashWatchdog.PHASE_PROVIDER)
        CrashWatchdog.markSessionKind(dir, CrashSessionClassifier.KIND_FOREGROUND_PENDING)
        CrashWatchdog.markSessionKind(dir, CrashSessionClassifier.KIND_BACKGROUND_WORKER)
        CrashWatchdog.markPhase(dir, CrashWatchdog.PHASE_APPLICATION)
        // 进程死亡，无任何收尾。

        // 第 N+1 轮前台启动：provider 归档 + pending 初始化后，ChatApp.updateCrashStreak 判定。
        CrashWatchdog.archiveArchivedKindIfNeeded(dir)
        CrashWatchdog.archiveLastPhase(dir)
        CrashWatchdog.markSessionKind(dir, CrashSessionClassifier.KIND_FOREGROUND_PENDING)

        assertFalse(
            "后台 Worker 会话不得被判为崩溃",
            CrashWatchdog.hasCrashedLastLaunch(dir),
        )
        assertEquals(0, CrashWatchdog.updateCrashStreak(dir))
        assertFalse(CrashWatchdog.isCrashLoopSafeMode(dir))
    }

    @Test
    fun `连续两次后台 Worker 冷启动也不进入安全模式`() {
        val dir = journal()
        repeat(3) {
            CrashWatchdog.archiveArchivedKindIfNeeded(dir)
            CrashWatchdog.archiveLastPhase(dir)
            CrashWatchdog.markSessionKind(dir, CrashSessionClassifier.KIND_BACKGROUND_WORKER)
            CrashWatchdog.markPhase(dir, CrashWatchdog.PHASE_APPLICATION)
            assertEquals(0, CrashWatchdog.updateCrashStreak(dir))
        }
        assertFalse(CrashWatchdog.isCrashLoopSafeMode(dir))
    }

    // ===== 前台崩溃仍能检出 =====

    @Test
    fun `前台 active 未 loaded 计崩溃并累加 streak`() {
        val dir = journal()
        // 上轮：前台启动进入 MainActivity 但加载窗口内死亡。
        CrashWatchdog.markStarted(dir)   // 写 started + foreground_active
        // 未 markLoaded，进程死亡。

        // 下轮 provider 阶段归档。
        CrashWatchdog.archiveArchivedKindIfNeeded(dir)

        assertTrue(CrashWatchdog.hasCrashedLastLaunch(dir))
        assertEquals(1, CrashWatchdog.updateCrashStreak(dir))
        assertFalse(CrashWatchdog.isCrashLoopSafeMode(dir))

        // 再崩一次 -> 达到安全模式阈值。
        CrashWatchdog.markStarted(dir)
        CrashWatchdog.archiveArchivedKindIfNeeded(dir)
        assertEquals(2, CrashWatchdog.updateCrashStreak(dir))
        assertTrue(CrashWatchdog.isCrashLoopSafeMode(dir))
    }

    @Test
    fun `正常 loaded 启动把 streak 归零`() {
        val dir = journal()
        CrashWatchdog.markStarted(dir)
        CrashWatchdog.markLoaded(dir)
        CrashWatchdog.archiveArchivedKindIfNeeded(dir)
        assertFalse(CrashWatchdog.hasCrashedLastLaunch(dir))
        assertEquals(0, CrashWatchdog.updateCrashStreak(dir))
        assertFalse(CrashWatchdog.isCrashLoopSafeMode(dir))
    }

    // ===== 旧版本 journal 兼容 =====

    @Test
    fun `旧 journal 无 kind 时按 started+loaded 兼容判定`() {
        val dir = journal()
        // 手工构造旧版本盘面：只有 started、loaded 缺失（升级前的崩溃现场）。
        dir.mkdirs()
        File(dir, "started").writeText("1")

        // 新代码首次运行：provider 归档（无 session_kind 可归档 -> last_kind 不存在）。
        CrashWatchdog.archiveArchivedKindIfNeeded(dir)

        assertTrue(
            "旧版本崩溃现场应保留检出能力",
            CrashWatchdog.hasCrashedLastLaunch(dir),
        )
        assertEquals(1, CrashWatchdog.updateCrashStreak(dir))
    }

    @Test
    fun `历史遗留 application 归档不算新崩溃`() {
        val dir = journal()
        // 升级前：后台 Worker 冷启动只留下 last_phase=application，无任何 kind/started。
        CrashWatchdog.markPhase(dir, CrashWatchdog.PHASE_APPLICATION)
        CrashWatchdog.archiveLastPhase(dir)

        assertFalse(
            "历史 application 归档不得算新崩溃",
            CrashWatchdog.hasCrashedLastLaunch(dir),
        )
        assertEquals(0, CrashWatchdog.updateCrashStreak(dir))
    }

    // ===== 并发不覆盖：Worker 不覆盖前台活跃会话 =====

    @Test
    fun `worker 在前台已进入后不可覆盖会话类型`() {
        val dir = journal()
        // 前台先进入（MainActivity.markStarted）。
        CrashWatchdog.markStarted(dir)
        // 周期 Worker 随后触发：标记被门禁拒绝。
        CrashWatchdog.markSessionKind(dir, CrashSessionClassifier.KIND_BACKGROUND_WORKER)

        assertEquals(
            CrashSessionClassifier.KIND_FOREGROUND_ACTIVE,
            CrashWatchdog.currentSessionKind(dir),
        )
    }

    @Test
    fun `worker 在 pending 阶段可覆盖为 background_worker`() {
        val dir = journal()
        CrashWatchdog.archiveArchivedKindIfNeeded(dir)
        CrashWatchdog.markSessionKind(dir, CrashSessionClassifier.KIND_FOREGROUND_PENDING)
        CrashWatchdog.markSessionKind(dir, CrashSessionClassifier.KIND_BACKGROUND_WORKER)

        assertEquals(
            CrashSessionClassifier.KIND_BACKGROUND_WORKER,
            CrashWatchdog.currentSessionKind(dir),
        )
    }

    // ===== 归档隔离：本轮标记不污染上轮判定 =====

    @Test
    fun `provider 覆盖本轮 kind 后判定仍读归档侧上轮真相`() {
        val dir = journal()
        // 上轮：前台启动窗口内死亡。
        CrashWatchdog.markStarted(dir)
        CrashWatchdog.markPhase(dir, CrashWatchdog.PHASE_ACTIVITY)

        // 本轮 provider：先归档，再把本轮标成 pending/application——判定必须不受影响。
        CrashWatchdog.archiveLastPhase(dir)
        CrashWatchdog.archiveArchivedKindIfNeeded(dir)
        CrashWatchdog.markPhase(dir, CrashWatchdog.PHASE_PROVIDER)
        CrashWatchdog.markSessionKind(dir, CrashSessionClassifier.KIND_FOREGROUND_PENDING)

        assertTrue(CrashWatchdog.hasCrashedLastLaunch(dir))
        // 归档的 phase 保留诊断真相（activity），不被本轮 provider 覆盖。
        assertEquals(CrashWatchdog.PHASE_ACTIVITY, CrashWatchdog.lastArchivedPhase(dir))
    }

    @Test
    fun `空 journal 全部读操作返回安全默认值`() {
        val dir = journal()
        assertNull(CrashWatchdog.lastArchivedPhase(dir))
        assertNull(CrashWatchdog.currentSessionKind(dir))
        assertFalse(CrashWatchdog.hasCrashedLastLaunch(dir))
        assertEquals(0, CrashWatchdog.updateCrashStreak(dir))
        assertFalse(CrashWatchdog.isCrashLoopSafeMode(dir))
    }
}
