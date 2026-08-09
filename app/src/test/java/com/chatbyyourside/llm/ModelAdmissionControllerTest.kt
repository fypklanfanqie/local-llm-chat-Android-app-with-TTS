package com.chatbyyourside.llm

import com.chatbyyourside.llm.ModelAdmissionController.AdmissionDecision
import com.chatbyyourside.llm.ModelAdmissionController.MemoryInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 存储/RAM 准入决策测试（Task 13）。 */
class ModelAdmissionControllerTest {

    private val gb = 1024L * 1024 * 1024

    private fun kvFor(context: Int, bytesPerToken: Long = 2L * 1024 * 1024): Long = context * bytesPerToken

    private fun mem(
        workingSet: Long,
        context: Int,
        availMem: Long,
        lowMemory: Boolean = false,
        peakPss: Long? = null,
    ) = MemoryInputs(
        modelWorkingSetBytes = workingSet,
        configuredContext = context,
        kvBytesForContext = { kvFor(it) },
        activationReserveBytes = 128L * 1024 * 1024,
        backendOverheadBytes = 64L * 1024 * 1024,
        measuredPeakPssBytes = peakPss,
        availMemBytes = availMem,
        thresholdBytes = 256L * 1024 * 1024,
        lowMemory = lowMemory,
    )

    @Test
    fun storageSufficientIsAllowed() {
        val d = ModelAdmissionController.assessStorage(
            bundleBytes = 2L * gb, availableBytes = 4L * gb,
        )
        assertTrue(d is AdmissionDecision.Allowed)
    }

    @Test
    fun storageInsufficientRejectsWithRequiredAndAvailable() {
        val d = ModelAdmissionController.assessStorage(
            bundleBytes = 3L * gb, availableBytes = 2L * gb,
        ) as AdmissionDecision.Rejected

        assertEquals(
            ModelAdmissionController.storageRequiredBytes(3L * gb),
            d.details["requiredBytes"],
        )
        assertEquals(2L * gb, d.details["availableBytes"])
    }

    @Test
    fun largeRamAllowsFullConfiguredContext() {
        // 8 GB 设备：working set 3GB + 4096 context KV(~8MB) 放得进。
        val d = ModelAdmissionController.decideMemory(mem(workingSet = 3L * gb, context = 4096, availMem = 8L * gb))
        assertEquals(AdmissionDecision.Allowed(contextTokens = 4096), d)
    }

    @Test
    fun lowRamDowngradesContextStep() {
        // 4 GB 设备：working set 3GB 挤占后，4096 放不下 -> 降档到 2048。
        val d = ModelAdmissionController.decideMemory(mem(workingSet = 3L * gb, context = 4096, availMem = 4L * gb))
        val downgraded = d as AdmissionDecision.Downgraded
        assertEquals(2048, downgraded.actualContext)
        assertTrue(downgraded.reasons.contains(com.chatbyyourside.llm.profile.DowngradeReason.MEMORY))
    }

    @Test
    fun modelTooLargeIsRejected() {
        val d = ModelAdmissionController.decideMemory(mem(workingSet = 6L * gb, context = 4096, availMem = 8L * gb))
        assertTrue(d is AdmissionDecision.Rejected)
    }

    @Test
    fun lowMemoryGuardRejectsOrDowngradesMoreAggressively() {
        // lowMemory 时额外扣 availMem/4：5GB 设备 working set 3GB 也放不下 -> 拒绝或最低档。
        val d = ModelAdmissionController.decideMemory(
            mem(workingSet = 3L * gb, context = 4096, availMem = 5L * gb, lowMemory = true),
        )
        assertTrue("lowMemory 下应拒绝或降档", d is AdmissionDecision.Rejected || d is AdmissionDecision.Downgraded)
    }

    @Test
    fun contextStepsHalveDownToMinimum() {
        assertEquals(listOf(4096, 2048, 1024, 512), ModelAdmissionController.contextSteps(4096))
    }
}
