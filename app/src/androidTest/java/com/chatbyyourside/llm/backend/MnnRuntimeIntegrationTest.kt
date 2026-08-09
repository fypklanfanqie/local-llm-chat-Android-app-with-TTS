package com.chatbyyourside.llm.backend

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chatbyyourside.data.model.ChatMessage
import com.chatbyyourside.llm.CpuBoostController
import com.chatbyyourside.provider.local.ModelPathResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MNN 运行时权威集成测试（Task 16 Step 3）。
 *
 * 覆盖：JNI handshake、类加载（API 24+ 无高版本类型崩）、短 CPU 生成、取消、两轮 KV 复用、
 * 生命周期 release。无真实模型 fixture 时以明确原因跳过（不静默通过）。
 */
@RunWith(AndroidJUnit4::class)
class MnnRuntimeIntegrationTest {

    companion object {
        private var loaded: BackendHandle? = null

        private class BackendHandle(val backend: MnnBackend, val configPath: String)

        @BeforeClass
        @JvmStatic
        fun loadFixture() {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            if (!MnnBridge.nativeAvailable) return
            val dirs = ModelPathResolver.getModelsDirectory(context)
                .listFiles { f -> f.isDirectory } ?: return
            for (dir in dirs) {
                val config = ModelPathResolver.getConfigPath(context, dir.name) ?: continue
                val backend = MnnBackend(context, MnnBackend.MnnMode.CPU, CpuBoostController(context))
                val ok = runBlocking {
                    backend.initialize(config, nativeConfigOf(), loadConfigHashOf(config))
                }
                if (ok) { loaded = BackendHandle(backend, config); return }
            }
        }

        private fun nativeConfigOf(): String =
            """{"schemaVersion":1,"backend_type":"cpu","thread_num":4,"cache_path":"/data/local/tmp/mnn_test_cache.bin","precision":"low","memory":"low","use_mmap":true,"reuse_kv":true,"attention_mode":8,"dynamic_option":0,"temperature":0.8,"topP":0.9,"repetition_penalty":1.2,"mixed_samplers":["penalty","topK","tfs","typical","topP","min_p","temperature"],"power":"high","kv_max_length":2048}"""

        private fun loadConfigHashOf(config: String): String = config.hashCode().toString(16)

        private fun messages(secondTurn: Boolean = false): List<ChatMessage> {
            val sys = ChatMessage(role = "system", content = "你是中文测试助手。")
            return if (secondTurn) {
                listOf(sys, ChatMessage(role = "user", content = "你好"), ChatMessage(role = "assistant", content = "你好！"), ChatMessage(role = "user", content = "请再说一句话"))
            } else {
                listOf(sys, ChatMessage(role = "user", content = "请用一句话介绍你自己。"))
            }
        }
    }

    private fun requireHandle(): BackendHandle {
        assumeTrue("设备上无 MNN 模型 fixture，跳过运行时集成测试（明确原因，非静默通过）", loaded != null)
        return loaded!!
    }

    @Test
    fun nativeRuntimeInfoHandshakeIsValid() {
        val info = MnnBridge.nativeGetRuntimeInfo()
        assertNotNull("nativeGetRuntimeInfo 应返回 JSON", info)
        assertTrue("应含 abiVersion", info!!.contains("abiVersion"))
        assertTrue("应含 mnnCommit", info.contains("mnnCommit"))
    }

    @Test
    fun shortCpuGenerationProducesText() {
        val fx = requireHandle()
        val sb = StringBuilder()
        val summary = runBlocking {
            fx.backend.generateStreamMessages(
                messages = messages(),
                maxTokens = 64, temperature = 0.8f, topP = 0.9f, repeatPenalty = 1.2f,
                enableThinking = false,
                onToken = { sb.append(it); true },
                batchMaxBytes = 256, batchMaxMs = 16, downgradeReasons = emptyList(),
                executionControl = null, powerPolicy = com.chatbyyourside.llm.profile.PowerPolicy.DEFAULT,
            )
        }
        assertNotNull(summary)
        assertTrue("应产出可见文本", sb.isNotBlank())
        assertTrue("gen_len>0", summary!!.generatedTokens > 0)
    }

    @Test
    fun cancellationStopsGeneration() {
        val fx = requireHandle()
        val sb = StringBuilder()
        val summary = runBlocking {
            fx.backend.generateStreamMessages(
                messages = messages(), maxTokens = 256, temperature = 0.8f, topP = 0.9f, repeatPenalty = 1.2f,
                enableThinking = false,
                onToken = { sb.append(it); sb.length < 8 },  // 立即截断
                batchMaxBytes = 256, batchMaxMs = 16, downgradeReasons = emptyList(),
                executionControl = null, powerPolicy = com.chatbyyourside.llm.profile.PowerPolicy.DEFAULT,
            )
        }
        assertNotNull(summary)
        // 策略截断（onToken false）应记 POLICY_TRUNCATION。
        assertTrue(
            "截断原因应为 POLICY_TRUNCATION 或提前结束",
            summary!!.completionReason == "POLICY_TRUNCATION" || summary.generatedTokens <= 16,
        )
    }

    @Test
    fun secondTurnReusesKvCache() {
        val fx = requireHandle()
        // 第一轮生成（预热 + 前缀）。
        runBlocking { fx.backend.generateStreamMessages(messages(false), 32, 0.8f, 0.9f, 1.2f, false, { true }, 256, 16, emptyList(), null, com.chatbyyourside.llm.profile.PowerPolicy.DEFAULT) }
        // 第二轮：新增 user，历史前缀应命中 KV。
        val summary = runBlocking {
            fx.backend.generateStreamMessages(messages(true), 32, 0.8f, 0.9f, 1.2f, false, { true }, 256, 16, emptyList(), null, com.chatbyyourside.llm.profile.PowerPolicy.DEFAULT)
        }
        assertNotNull(summary)
        assertEquals("第二轮应复用 KV 前缀", 1, summary!!.reuseKv)
    }

    @Test
    fun releaseDoesNotCrashAndAllowsReload() {
        val fx = requireHandle()
        fx.backend.release()
        val ok = runBlocking {
            fx.backend.initialize(fx.configPath, nativeConfigOf(), loadConfigHashOf(fx.configPath))
        }
        assertTrue("release 后应能重新加载", ok)
    }
}
