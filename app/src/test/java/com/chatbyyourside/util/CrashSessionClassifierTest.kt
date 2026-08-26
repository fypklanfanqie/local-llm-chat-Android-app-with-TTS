package com.chatbyyourside.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CrashSessionClassifier] 纯函数单测（Task 5）：
 * 覆盖后台 Worker 会话不计崩溃、前台 active 计崩溃、正常 loaded 不计、unknown/pending 不计、
 * 旧版本 journal（无 kind）兼容判定、Worker 不覆盖前台活跃会话的门禁。
 */
class CrashSessionClassifierTest {

    private val classifier = CrashSessionClassifier

    // ===== classify：后台/未确认会话不计 =====

    @Test
    fun `background_worker 未 loaded 不计崩溃`() {
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = CrashSessionClassifier.KIND_BACKGROUND_WORKER,
                started = false,
                loaded = false,
                lastPhase = "application",
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CLEAN, verdict)
    }

    @Test
    fun `foreground_pending 未 loaded 不计崩溃`() {
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = CrashSessionClassifier.KIND_FOREGROUND_PENDING,
                started = false,
                loaded = false,
                lastPhase = "provider",
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CLEAN, verdict)
    }

    @Test
    fun `unknown kind 未 loaded 不计崩溃`() {
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = CrashSessionClassifier.KIND_UNKNOWN,
                started = false,
                loaded = false,
                lastPhase = "application",
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CLEAN, verdict)
    }

    @Test
    fun `未来新增的未知 kind 取值同样不计崩溃`() {
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = "some_future_kind",
                started = true,
                loaded = false,
                lastPhase = null,
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CLEAN, verdict)
    }

    // ===== classify：前台计崩溃 =====

    @Test
    fun `foreground_active 且未 loaded 计崩溃`() {
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = CrashSessionClassifier.KIND_FOREGROUND_ACTIVE,
                started = true,
                loaded = false,
                lastPhase = "activity",
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CRASH, verdict)
    }

    @Test
    fun `foreground_active 但已 loaded 不计崩溃`() {
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = CrashSessionClassifier.KIND_FOREGROUND_ACTIVE,
                started = true,
                loaded = true,
                lastPhase = "loaded",
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CLEAN, verdict)
    }

    @Test
    fun `任何 kind 已 loaded 都不计崩溃`() {
        for (kind in listOf(
            CrashSessionClassifier.KIND_FOREGROUND_ACTIVE,
            CrashSessionClassifier.KIND_FOREGROUND_PENDING,
            CrashSessionClassifier.KIND_BACKGROUND_WORKER,
            CrashSessionClassifier.KIND_UNKNOWN,
        )) {
            val verdict = classifier.classify(
                CrashSessionClassifier.ArchivedSession(
                    kind = kind,
                    started = true,
                    loaded = true,
                    lastPhase = "loaded",
                ),
            )
            assertEquals("kind=$kind 已 loaded 应 CLEAN", CrashSessionClassifier.Verdict.CLEAN, verdict)
        }
    }

    // ===== classify：旧版本 journal 兼容 =====

    @Test
    fun `旧 journal 无 kind 且 started 在 loaded 不在则兼容判定为崩溃`() {
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = null,
                started = true,
                loaded = false,
                lastPhase = "activity",
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CRASH, verdict)
    }

    @Test
    fun `旧 journal 无 kind 且无 started 则不计崩溃`() {
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = null,
                started = false,
                loaded = false,
                lastPhase = "application",
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CLEAN, verdict)
    }

    @Test
    fun `历史 application 阶段归档不直接算新崩溃（旧误判回归）`() {
        // 升级场景：上一轮是后台 Worker 冷启动（停在 application），journal 里只有旧版 phase
        // 归档、无 session_kind。旧实现按 last_phase=application 误判；新实现 kind=null 时只看
        // started&&!loaded，started=false（后台无 Activity）-> 不计。
        val verdict = classifier.classify(
            CrashSessionClassifier.ArchivedSession(
                kind = null,
                started = false,
                loaded = false,
                lastPhase = "application",
            ),
        )
        assertEquals(CrashSessionClassifier.Verdict.CLEAN, verdict)
    }

    // ===== shouldMarkBackgroundWorker：并发不覆盖 =====

    @Test
    fun `worker 不可覆盖前台活跃会话`() {
        assertFalse(
            classifier.shouldMarkBackgroundWorker(CrashSessionClassifier.KIND_FOREGROUND_ACTIVE),
        )
    }

    @Test
    fun `worker 可覆盖 pending 与 unknown`() {
        assertTrue(classifier.shouldMarkBackgroundWorker(CrashSessionClassifier.KIND_FOREGROUND_PENDING))
        assertTrue(classifier.shouldMarkBackgroundWorker(CrashSessionClassifier.KIND_UNKNOWN))
        assertTrue(classifier.shouldMarkBackgroundWorker(CrashSessionClassifier.KIND_BACKGROUND_WORKER))
    }

    @Test
    fun `worker 可覆盖缺失的 kind（null）`() {
        assertTrue(classifier.shouldMarkBackgroundWorker(null))
    }
}
