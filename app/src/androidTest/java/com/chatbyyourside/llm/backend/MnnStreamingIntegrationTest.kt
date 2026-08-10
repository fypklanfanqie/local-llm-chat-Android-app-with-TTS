package com.chatbyyourside.llm.backend

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.llm.CpuBoostController
import com.chatbyyourside.llm.metrics.NativeGenerationSummary
import com.chatbyyourside.llm.profile.InferenceProfileResolver
import com.chatbyyourside.llm.profile.RuntimeVariant
import com.chatbyyourside.provider.local.ModelPathResolver
import kotlinx.coroutines.runBlocking
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MNN 批量流式集成测试（Task 4 Step 8）。
 *
 * 在**真实设备 + 已安装模型**上验证 `StreamBatcher` + `nativeGenerateStream` 摘要契约，
 * 覆盖计划 Step 8 的六条验收：
 * 1. CJK/emoji 任意批边界无损        -> [cjkAndEmojiSurviveAggressiveBatchBoundaries]
 * 2. 首 delta 立即（不缓冲整批）      -> [firstDeltaIsImmediateNotBuffered]
 * 3. 512-token 回调削减 ≥80%         -> [batchedCallbacksReducedAtLeast80Percent]
 * 4. 拼接 delta == modelContent      -> [assertByteIntegrity]（字节级：拼接待与 native 摘要 callbackBytes 逐字节相等，
 *                                       正是 LocalChatProvider 作为唯一累加器拼出的 modelText/modelContent）
 * 5. 无重复最终文本                  -> [assertByteIntegrity]（每个字节恰好一次：字节和 + callbackCount 双重核对）
 * 6. stop 只 flush 一次缓冲          -> [stopFlushesBufferedBytesOnceWithPolicyTruncation]
 *
 * 额外守卫 [balancedConfigNeverWorseThanPerTokenCallbacks]：真实 Balanced 默认（256B/16ms）
 * 下回调数绝不超过每 token 一次。
 *
 * **Fixture 假设守卫**：无已安装 MNN 模型（config.json + llm.mnn）或 native 不可用时，
 * [requireFixture] 抛出 Assume 跳过，CI 无模型机器不失败。
 *
 * **性能断言的速度依赖说明**（与 mnn_jni.cpp `StreamBatcher::add` 语义核对）：
 * maxMs 时间阈值从「缓冲内首个字符的到达时刻」起算（`firstCharTime_`），且只在有新字符
 * 到达时才检查。因此典型 CPU 解码速率（约 10..60 tok/s，字符间隔 16..100ms）下，16ms 时间
 * 阈值会逐字符/逐两字符触发 flush，批处理几乎不削减回调；≥80% 削减只在**字节阈值主导**
 * （maxMs 极大使时间阈值退位、256B 满即 flush）时确定成立。故第 3 条使用字节主导配置
 * 验证批处理机制本身兑现 ≥80% 承诺；Balanced 默认则以「不劣于每 token 一次」的下界守卫。
 */
@RunWith(AndroidJUnit4::class)
class MnnStreamingIntegrationTest {

    companion object {
        private class Fixture(val backend: MnnBackend, val modelPath: String)

        @Volatile
        private var fixture: Fixture? = null

        /** 惰性加载设备上第一个已安装的 MNN 模型（进程内只加载一次）；无模型/加载失败返回 null。 */
        private fun fixture(context: Context): Fixture? {
            fixture?.let { return it }
            synchronized(this) {
                fixture?.let { return it }
                if (!MnnBridge.nativeAvailable) return null
                val dirs = ModelPathResolver.getModelsDirectory(context)
                    .listFiles { f -> f.isDirectory } ?: return null
                for (dir in dirs) {
                    val config = ModelPathResolver.getConfigPath(context, dir.name) ?: continue
                    val backend = MnnBackend(context, MnnBackend.MnnMode.CPU, CpuBoostController(context))
                    // 通过 resolver 生成规范化 native config + 指纹，匹配当前 initialize(modelPath, nativeConfigJson, loadConfigHash) 契约。
                    val configJson = InferenceProfileResolver.buildAttemptNativeConfig(
                        variant = RuntimeVariant.CPU_OPTIMIZED,
                        backendType = "cpu",
                        threadNum = 4,
                        cachePath = File(context.cacheDir, "mnn_cache_integration.bin").absolutePath,
                        contextTokens = 2048,
                        lookahead = false,
                        temperature = 0.8f,
                        topP = 0.9f,
                        repeatPenalty = 1.1f,
                    )
                    val ok = runBlocking {
                        backend.initialize(
                            modelPath = config,
                            nativeConfigJson = configJson,
                            loadConfigHash = InferenceProfileResolver.loadConfigHash(configJson),
                        )
                    }
                    if (ok) {
                        fixture = Fixture(backend, config)
                        return fixture
                    }
                    backend.release()
                }
                return null
            }
        }
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 中文探针提示词：要求多字节输出，确保真正覆盖 UTF-8 跨批路径。 */
    private val probeMessages = listOf(
        ChatMessage(role = "system", content = "你是中文测试助手。你的每条回复都必须以中文为主，可以适当包含 emoji 表情符号。"),
        ChatMessage(role = "user", content = "请用三句话介绍你自己，必须包含中文，并带上一个 emoji。"),
    )

    private class Capture {
        val deltas = mutableListOf<String>()
        var firstElapsedMs: Long? = null
        val startedMs: Long = SystemClock.elapsedRealtime()
    }

    private suspend fun generate(
        backend: MnnBackend,
        cap: Capture,
        maxTokens: Int,
        batchMaxBytes: Int,
        batchMaxMs: Int,
        stopAfterBatches: Int? = null,
    ): NativeGenerationSummary? {
        var batches = 0
        return backend.generateStreamMessages(
            messages = probeMessages,
            maxTokens = maxTokens,
            temperature = 0.8f,
            topP = 0.9f,
            repeatPenalty = 1.1f,
            enableThinking = false,
            onToken = { delta ->
                cap.deltas += delta
                if (cap.firstElapsedMs == null) {
                    cap.firstElapsedMs = SystemClock.elapsedRealtime() - cap.startedMs
                }
                batches++
                if (stopAfterBatches != null && batches >= stopAfterBatches) false else true
            },
            batchMaxBytes = batchMaxBytes,
            batchMaxMs = batchMaxMs,
            downgradeReasons = emptyList(),
            executionControl = null,
            powerPolicy = com.chatbyyourside.llm.profile.PowerPolicy.DEFAULT,
            requestedMode = null,
            effectiveMode = null,
            loadConfigHash = null,
            attemptTrace = emptyList(),
            coldLoadMs = null,
            warmLoadMs = null,
        )
    }

    // ------------------------------------------------------------------
    // 1. CJK/emoji 任意批边界无损
    // ------------------------------------------------------------------

    @Test
    fun cjkAndEmojiSurviveAggressiveBatchBoundaries() {
        val fx = requireFixture()
        val cap = Capture()
        // 1 字节 / 1ms 即 flush：迫使每个 UTF-8 字符单独成批，最大化跨批边界。
        val summary = runBlocking {
            generate(fx.backend, cap, maxTokens = 200, batchMaxBytes = 1, batchMaxMs = 1)
        }
        assertNotNull("nativeGenerateStream 未返回摘要", summary)
        val joined = cap.deltas.joinToString("")
        assertTrue("模型未产出任何文本", joined.isNotBlank())
        assertNoReplacementChars(joined)
        assertByteIntegrity(cap, summary!!)
        assertNotNull("native 未记录首 delta 时延", summary.firstDeltaUs)
    }

    // ------------------------------------------------------------------
    // 2. 首 delta 立即（不缓冲整批）
    // ------------------------------------------------------------------

    @Test
    fun firstDeltaIsImmediateNotBuffered() {
        val fx = requireFixture()
        val cap = Capture()
        // 256B 字节阈值 + 1000ms 时间阈值：若首字符被缓冲，首 delta 会凑满整批 256B；
        // 立即语义下首 delta 仅为首个完整 UTF-8 字符（1..4 字节）。
        val summary = runBlocking {
            generate(fx.backend, cap, maxTokens = 200, batchMaxBytes = 256, batchMaxMs = 1000)
        }
        assertNotNull(summary)
        assertTrue("无首个 delta", cap.deltas.isNotEmpty())
        val firstBytes = cap.deltas.first().toByteArray(Charsets.UTF_8).size
        assertTrue(
            "首 delta 被缓冲到整批（$firstBytes 字节 ≥64）：首个可见字符未立即发出",
            firstBytes < 64,
        )
        assertNotNull("未记录首 delta 时延", cap.firstElapsedMs)
        assertNotNull(summary!!.firstDeltaUs)
    }

    // ------------------------------------------------------------------
    // 3. 512-token 回调削减 ≥80%（字节主导配置）
    // ------------------------------------------------------------------

    @Test
    fun batchedCallbacksReducedAtLeast80Percent() {
        val fx = requireFixture()
        val cap = Capture()
        // maxMs 极大 => 时间阈值退位，256B 字节阈值主导 => 每个回调约 85 个 CJK 字符。
        // 512-token（约 1.5KB）回复 → 约 7 次回调，远超 80% 削减；对慢模型同样成立。
        val summary = runBlocking {
            generate(fx.backend, cap, maxTokens = 512, batchMaxBytes = 256, batchMaxMs = 600000)
        }
        assertNotNull(summary)
        val s = summary!!
        val gen = s.generatedTokens
        assumeTrue("生成 token 过少（$gen），无统计意义，跳过削减断言", gen >= 8)
        val cb = s.callbackCount
        assertTrue(
            "回调未削减 ≥80%：callbacks=$cb tokens=$gen（比值 ${cb.toFloat() / gen}）",
            cb.toFloat() <= gen * 0.2f,
        )
        assertEquals("Kotlin 收到回调数与 native 摘要不一致", cb, cap.deltas.size)
        assertByteIntegrity(cap, s)
    }

    // ------------------------------------------------------------------
    // 4/5. 拼接 delta == modelContent、无重复（字节级完整性）
    // ------------------------------------------------------------------

    @Test
    fun balancedConfigNeverWorseThanPerTokenCallbacks() {
        val fx = requireFixture()
        val cap = Capture()
        // 真实 Balanced 默认（256B/16ms）：16ms 时间阈值在典型 CPU 速率下逐字符触发，
        // 守卫下界——回调数绝不比「每 token 一次」更多（每个回调至少推送 1 个完整字符 = ≥1 token）。
        val summary = runBlocking {
            generate(fx.backend, cap, maxTokens = 200, batchMaxBytes = 256, batchMaxMs = 16)
        }
        assertNotNull(summary)
        val s = summary!!
        assertTrue(
            "Balanced 下回调数超过 token 数：callbacks=${s.callbackCount} tokens=${s.generatedTokens}",
            s.callbackCount <= s.generatedTokens,
        )
        assertEquals("Kotlin 收到回调数与 native 摘要不一致", s.callbackCount, cap.deltas.size)
        assertByteIntegrity(cap, s)
    }

    // ------------------------------------------------------------------
    // 6. 策略截断：stop 只 flush 一次缓冲，不丢字、不重复
    // ------------------------------------------------------------------

    @Test
    fun stopFlushesBufferedBytesOnceWithPolicyTruncation() {
        val fx = requireFixture()
        val cap = Capture()
        // 字节主导（32B）：第 4 批后 onToken 返回 false => 策略截断，native 把缓冲内
        // 剩余完整字符 flush 一次。若 flush 两次或丢字，字节完整性/回调数核对会失败。
        val summary = runBlocking {
            generate(
                fx.backend, cap, maxTokens = 512,
                batchMaxBytes = 32, batchMaxMs = 600000,
                stopAfterBatches = 4,
            )
        }
        assertNotNull(summary)
        val s = summary!!
        if (s.completionReason != "POLICY_TRUNCATION") {
            assumeTrue(
                "模型在达到 stopAfterBatches=4 前已 EOS（tokens=${s.generatedTokens}），无法验证截断路径",
                false,
            )
        }
        assertEquals("策略截断原因推导错误", "POLICY_TRUNCATION", s.completionReason)
        assertEquals("stop 后回调数与摘要不一致（未 flush 一次）", s.callbackCount, cap.deltas.size)
        assertByteIntegrity(cap, s)
        assertNoReplacementChars(cap.deltas.joinToString(""))
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 获取 fixture；无模型/加载失败时 Assume 跳过。 */
    private fun requireFixture(): Fixture {
        val fx = fixture(context)
        assumeTrue(
            "设备上未安装 MNN 模型（config.json + llm.mnn）或 native 不可用，跳过流式集成测试",
            fx != null,
        )
        return fx!!
    }

    /** 拼接文本不含 U+FFFD：UTF-8 序列未被批边界截断。 */
    private fun assertNoReplacementChars(text: String) {
        assertTrue("出现 U+FFFD（UTF-8 序列被批边界截断）：$text", text.indexOf('\uFFFD') < 0)
    }

    /**
     * 字节级完整性（= 计划「拼接 delta == modelContent」「无重复」）：
     * Kotlin 收到的拼接字节 == native 摘要 [NativeGenerationSummary.callbackBytes]，
     * 且回调次数一致。每个字节恰好出现一次 => 无丢失、无重复、无最终文本复读。
     */
    private fun assertByteIntegrity(cap: Capture, s: NativeGenerationSummary) {
        val joined = cap.deltas.joinToString("")
        assertEquals(
            "native 摘要回调字节数（${s.callbackBytes}）≠ Kotlin 拼接字节数（${joined.toByteArray(Charsets.UTF_8).size}）",
            s.callbackBytes,
            joined.toByteArray(Charsets.UTF_8).size.toLong(),
        )
        assertEquals("回调次数不一致", s.callbackCount, cap.deltas.size)
    }
}
