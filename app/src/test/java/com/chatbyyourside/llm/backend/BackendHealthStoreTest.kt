package com.chatbyyourside.llm.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 健康策略决策测试（Task 9 Step 3，注入 clock 纯函数）。 */
class BackendHealthStoreTest {

    private val hourMs = 60L * 60 * 1000

    @Test
    fun unknownRecordAllowsAttempt() {
        assertTrue(BackendHealthPolicy.shouldAttempt(record = null, nowElapsedMs = 1_000))
    }

    @Test
    fun cooldownSkipsUntilExpiry() {
        val failing = BackendHealthPolicy.afterFailure(
            record = null,
            failureClass = HealthFailureClass.PROBE,
            nowElapsedMs = 1_000,
        )
        assertEquals(HealthState.COOLDOWN, failing.state)
        assertFalse("冷却期内应跳过", BackendHealthPolicy.shouldAttempt(failing, nowElapsedMs = 1_000))
        assertTrue("冷却期后应放行", BackendHealthPolicy.shouldAttempt(failing, nowElapsedMs = 1_000 + 24 * hourMs + 1))
    }

    @Test
    fun blacklistedIsSkippedUntilResetOrFingerprintChange() {
        val blacklisted = BackendHealthPolicy.afterCrashMarker(nowElapsedMs = 5_000)

        assertFalse(BackendHealthPolicy.shouldAttempt(blacklisted, nowElapsedMs = Long.MAX_VALUE))
        // 显式 reset 语义：更新为 UNKNOWN 后重新放行。
        val reset = HealthRecord(state = HealthState.UNKNOWN)
        assertTrue(BackendHealthPolicy.shouldAttempt(reset, nowElapsedMs = 5_001))
    }

    @Test
    fun probeFailureGives24hCooldown() {
        val record = BackendHealthPolicy.afterFailure(
            record = null, failureClass = HealthFailureClass.PROBE, nowElapsedMs = 1_000,
        )
        assertEquals(1_000 + 24 * hourMs, record.cooldownUntilElapsedMs)
    }

    @Test
    fun repeatedGenerationFailureGives7dCooldown() {
        val first = BackendHealthPolicy.afterFailure(
            record = null, failureClass = HealthFailureClass.GENERATION, nowElapsedMs = 1_000,
        )
        val second = BackendHealthPolicy.afterFailure(
            record = first, failureClass = HealthFailureClass.GENERATION, nowElapsedMs = 2_000,
        )
        assertEquals(2, second.failureCount)
        assertEquals(2_000 + 7L * 24 * hourMs, second.cooldownUntilElapsedMs)
    }

    @Test
    fun cancellationOrThermalLeavesNoPenalty() {
        // 取消/热停止不写失败记录 -> 键无记录 -> 恒可尝试。
        assertTrue(BackendHealthPolicy.shouldAttempt(null, nowElapsedMs = 1_000))
    }
}
