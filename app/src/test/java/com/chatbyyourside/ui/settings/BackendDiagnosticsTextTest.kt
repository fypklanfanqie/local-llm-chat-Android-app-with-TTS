package com.chatbyyourside.ui.settings

import com.chatbyyourside.llm.backend.BackendType
import com.chatbyyourside.llm.benchmark.CertifiedInferenceOptions
import com.chatbyyourside.llm.metrics.InferenceTurnRecord
import com.chatbyyourside.llm.profile.InferencePerformanceMode
import com.chatbyyourside.llm.profile.DowngradeReason
import com.chatbyyourside.llm.template.ThinkingEffect
import com.chatbyyourside.llm.template.ThinkingTemplateCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断摘要纯逻辑测试（Task 7 Step 2/5）。
 *
 * 覆盖 [templateCapabilityText]（Step 5：UNKNOWN/UNSUPPORTED 不得声称「思考已关闭」）、
 * [thinkingStatusText]（请求/实际与模板能力合并展示口径）、[downgradeReasonText]、
 * [certificationStatusText] 与 [diagnosticRows]（记录 -> 摘要行的纯映射）。
 * 全部为纯函数，不触 Android 运行时。
 */
class BackendDiagnosticsTextTest {

    // ===== 模板能力文案（Step 5）=====

    @Test
    fun templateCapabilityUnsupportedStatesTheSwitchIsIneffectiveNotThatThinkingIsOff() {
        // 明确不支持：只说开关无效，不声称「思考已关闭」。
        assertTrue(templateCapabilityText(ThinkingTemplateCapability.UNSUPPORTED).contains("开关无效"))
        assertTrue(!templateCapabilityText(ThinkingTemplateCapability.UNSUPPORTED).contains("已关闭"))
    }

    @Test
    fun templateCapabilityUnknownDoesNotClaimSwitchDisabled() {
        // 信息不足：不得声称「思考已关闭」，文案提示开关可能无效。
        val text = templateCapabilityText(ThinkingTemplateCapability.UNKNOWN)
        assertTrue(text.contains("未知"))
        assertTrue(text.contains("可能无效"))
        assertTrue(!text.contains("已关闭"))
    }

    @Test
    fun templateCapabilitySupportedAndNullAreDistinct() {
        assertTrue(templateCapabilityText(ThinkingTemplateCapability.SUPPORTED).contains("可生效"))
        assertTrue(templateCapabilityText(null).contains("未选择模型"))
    }

    // ===== 思考状态合并文案（Step 2）=====

    @Test
    fun requestedWithUnsupportedTemplateSaysSwitchIneffective() {
        val text = thinkingStatusText(
            thinkingRequested = true,
            thinkingEffective = null,
            templateCapability = ThinkingTemplateCapability.UNSUPPORTED,
        )
        assertTrue(text.contains("模板不支持"))
        assertTrue(text.contains("开关无效"))
    }

    @Test
    fun requestedWithUnknownTemplateSaysPossiblyIneffective() {
        val text = thinkingStatusText(
            thinkingRequested = true,
            thinkingEffective = null,
            templateCapability = ThinkingTemplateCapability.UNKNOWN,
        )
        assertTrue(text.contains("模板能力未知"))
        assertTrue(text.contains("可能无效"))
    }

    @Test
    fun requestedWithSupportedTemplateAndEnabledEffectSaysEffective() {
        val text = thinkingStatusText(
            thinkingRequested = true,
            thinkingEffective = ThinkingEffect.ENABLED.name,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("已生效"))
    }

    @Test
    fun disableNotEffectiveIsSurfaced() {
        // 未请求但出现完整思考段：硬性要求口径「关闭未生效」。
        val text = thinkingStatusText(
            thinkingRequested = false,
            thinkingEffective = ThinkingEffect.THINKING_DISABLE_NOT_EFFECTIVE.name,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("关闭未生效"))
    }

    @Test
    fun disableRequestedAndEffectiveSaysClosed() {
        val text = thinkingStatusText(
            thinkingRequested = false,
            thinkingEffective = ThinkingEffect.DISABLED.name,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("已生效"))
    }

    @Test
    fun disableRequestedWithUnknownEffectDoesNotClaimEffective() {
        // Task 7 review M-3：请求关闭但效果 UNKNOWN（截断/失败/空响应生成）：不得声称「已生效」。
        val text = thinkingStatusText(
            thinkingRequested = false,
            thinkingEffective = ThinkingEffect.UNKNOWN.name,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("未能确认生效"))
        assertTrue(!text.contains("已生效"))
    }

    @Test
    fun requestedWithoutEvidenceDoesNotClaimEffective() {
        // 请求开启但无 ENABLED 证据（如生成被截断）：不得声称「已生效」。
        val text = thinkingStatusText(
            thinkingRequested = true,
            thinkingEffective = null,
            templateCapability = ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(text.contains("未确认生效"))
    }

    // ===== 降级原因文案 =====

    @Test
    fun knownDowngradeReasonsMapToChinese() {
        assertEquals("GPU 空输出回退 CPU", downgradeReasonText("EMPTY_GPU_OUTPUT_FALLBACK"))
        assertEquals("lookahead 未认证（未启用）", downgradeReasonText(DowngradeReason.LOOKAHEAD_UNCERTIFIED.name))
        assertEquals("OpenCL 健康异常（未入链）", downgradeReasonText(DowngradeReason.OPENCL_UNHEALTHY.name))
        assertEquals("标准构建不含 QNN（解析为 CPU）", downgradeReasonText(DowngradeReason.QNN_UNAVAILABLE_IN_STANDARD_BUILD.name))
    }

    @Test
    fun unknownDowngradeReasonIsKeptVerbatim() {
        // 未知原因原样保留，不猜测也不崩溃。
        assertEquals("SOME_FUTURE_REASON", downgradeReasonText("SOME_FUTURE_REASON"))
    }

    // ===== 认证状态文案 =====

    private fun cert(lookahead: Boolean = false, step: Int = 1) = CertifiedInferenceOptions(
        deviceFingerprint = "d",
        modelFingerprint = "m",
        variant = "CPU_OPTIMIZED",
        nativeBuildId = "b",
        mnnCommit = "c",
        lookahead = lookahead,
        decodeStepTokens = step,
    )

    @Test
    fun unCertifiedStatesBothGatedFeaturesAreOff() {
        val text = certificationStatusText(null)
        assertTrue(text.contains("未认证"))
        assertTrue(text.contains("lookahead"))
        assertTrue(text.contains("步进"))
    }

    @Test
    fun certifiedLookaheadOnlyMentionsLookahead() {
        val text = certificationStatusText(cert(lookahead = true, step = 1))
        assertTrue(text.contains("lookahead"))
        assertTrue(!text.contains("步进"))
    }

    @Test
    fun certifiedStepMentionsStep() {
        assertTrue(certificationStatusText(cert(lookahead = false, step = 2)).contains("步进 2"))
        assertTrue(certificationStatusText(cert(lookahead = true, step = 2)).contains("lookahead + 多 token 步进 2"))
    }

    // ===== 记录 -> 诊断行映射 =====

    private fun record(
        thinkingRequested: Boolean? = true,
        thinkingEffective: String? = ThinkingEffect.ENABLED.name,
        templateCapability: String? = ThinkingTemplateCapability.SUPPORTED.name,
        backend: BackendType? = BackendType.MNN_CPU,
        attemptTrace: List<String> = listOf("CPU_OPTIMIZED"),
        downgradeReasons: List<String> = emptyList(),
        prefillMs: Long? = 123L,
        decodeMs: Long? = 456L,
        ttftMs: Long? = 78L,
        decodeTps: Float? = 12.5f,
        kvReuse: Boolean? = true,
    ) = InferenceTurnRecord(
        generationId = "g1",
        requestedMode = InferencePerformanceMode.BALANCED,
        effectiveMode = InferencePerformanceMode.BALANCED,
        backend = backend,
        startedElapsedMs = 0L,
        endedElapsedMs = 1000L,
        prefillMs = prefillMs,
        decodeMs = decodeMs,
        ttftMs = ttftMs,
        decodeTps = decodeTps,
        kvReuse = kvReuse,
        attemptTrace = attemptTrace,
        downgradeReasons = downgradeReasons,
        thinkingRequested = thinkingRequested,
        templateCapability = templateCapability,
        thinkingEffective = thinkingEffective,
    )

    @Test
    fun nullRecordYieldsNoRows() {
        assertTrue(diagnosticRows(null, ThinkingTemplateCapability.SUPPORTED).isEmpty())
    }

    @Test
    fun recordYieldsThinkingBackendAndTimingRows() {
        val rows = diagnosticRows(record(), ThinkingTemplateCapability.SUPPORTED)
        val labels = rows.map { it.label }
        assertTrue(labels.contains("深度思考"))
        assertTrue(labels.contains("实际后端"))
        assertTrue(labels.contains("阶段计时"))
        val thinking = rows.first { it.label == "深度思考" }.value
        assertTrue(thinking.contains("已生效"))
        val backend = rows.first { it.label == "实际后端" }.value
        assertTrue(backend.contains("MNN CPU"))
        assertTrue(backend.contains("CPU_OPTIMIZED"))
        val timings = rows.first { it.label == "阶段计时" }.value
        assertTrue(timings.contains("prefill 123ms"))
        assertTrue(timings.contains("decode 456ms"))
        assertTrue(timings.contains("TTFT 78ms"))
        assertTrue(timings.contains("12.5 tok/s"))
        assertTrue(timings.contains("KV 复用"))
    }

    @Test
    fun fallbackReasonRowRenderedWhenDowngradesPresent() {
        val rows = diagnosticRows(
            record(downgradeReasons = listOf("EMPTY_GPU_OUTPUT_FALLBACK", DowngradeReason.LOOKAHEAD_UNCERTIFIED.name)),
            ThinkingTemplateCapability.SUPPORTED,
        )
        val fallback = rows.firstOrNull { it.label == "回退/降级" }
        assertTrue("应存在回退/降级行", fallback != null)
        assertTrue(fallback!!.value.contains("GPU 空输出回退 CPU"))
        assertTrue(fallback.value.contains("lookahead 未认证"))
    }

    @Test
    fun timingsRowOmittedWhenAllTimingsNull() {
        val rows = diagnosticRows(
            record(prefillMs = null, decodeMs = null, ttftMs = null, decodeTps = null, kvReuse = null),
            ThinkingTemplateCapability.SUPPORTED,
        )
        assertTrue(rows.none { it.label == "阶段计时" })
    }
}
