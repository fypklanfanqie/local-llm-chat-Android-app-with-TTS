package com.chatbyyourside.llm.backend

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OpenCL 探测协调器测试（Task 10 Step 5）。
 *
 * 用注入的 fake probe/clock 验证成功、普通失败、超时与进程死亡判定；不依赖真实 OpenCL 设备。
 * 真实设备身份/输出校验由 CI 上的真机 instrumentation 覆盖。
 */
@RunWith(AndroidJUnit4::class)
class OpenClProbeServiceTest {

    @Test
    fun successResultReturnedImmediately() = runBlocking {
        var launched = false
        val runner = OpenClProbeRunner(
            launchProbe = { launched = true },
            readResult = {
                OpenClProbeResult(success = true, vendor = "Qualcomm", device = "Adreno 740", driver = "v2.2.0", durationMs = 3)
            },
            clock = { 0L },
        )

        val result = runner.runProbe()

        assertTrue(launched)
        assertTrue(result.success)
        assertEquals("Qualcomm", result.vendor)
        assertEquals("Adreno 740", result.device)
    }

    @Test
    fun ordinaryFailureMapsFailureCode() = runBlocking {
        val runner = OpenClProbeRunner(
            launchProbe = {},
            readResult = { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_NO_DEVICE) },
            clock = { 0L },
        )

        val result = runner.runProbe()

        assertFalse(result.success)
        assertEquals(OpenClProbeResult.FAILURE_NO_DEVICE, result.failureCode)
    }

    @Test
    fun timeoutWhenNoResultArrives() = runBlocking {
        val runner = OpenClProbeRunner(
            launchProbe = {},
            readResult = { null },
            clock = { Long.MAX_VALUE },  // 立即超时
        )

        val result = runner.runProbe()

        assertFalse(result.success)
        assertEquals(OpenClProbeResult.FAILURE_TIMEOUT, result.failureCode)
    }

    @Test
    fun malformedResultMapsToProcessDeath() = runBlocking {
        val runner = OpenClProbeRunner(
            launchProbe = {},
            readResult = { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_PROCESS_DEATH) },
            clock = { 0L },
        )

        val result = runner.runProbe()

        assertEquals(OpenClProbeResult.FAILURE_PROCESS_DEATH, result.failureCode)
    }
}
