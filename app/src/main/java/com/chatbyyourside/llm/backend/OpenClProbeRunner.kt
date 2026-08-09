package com.chatbyyourside.llm.backend

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 主进程 OpenCL 探测协调器（Task 10 Step 3）。
 *
 * 流程：写 pending journal -> 启动 `:mnn_probe` 进程的 [OpenClProbeService] -> 轮询结果
 * （跨进程 SharedPreferences）-> 超时/进程死亡/畸形结果均视为失败并清 journal。
 * 探测可经 [launchProbe]/[readResult]/[clock] 注入（测试用 fake probe 覆盖成功/普通失败/超时/死亡）。
 */
class OpenClProbeRunner(
    private val launchProbe: () -> Unit,
    private val readResult: () -> OpenClProbeResult?,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行一次探测，返回终态结果。超时返回 [OpenClProbeResult.FAILURE_TIMEOUT]；
     * 结果畸形（无法解析）返回 [OpenClProbeResult.FAILURE_PROCESS_DEATH]（服务死亡未写结果）。
     */
    suspend fun runProbe(): OpenClProbeResult {
        launchProbe()
        val start = clock()
        while (clock() - start < PROBE_TIMEOUT_MS) {
            delay(POLL_MS)
            val raw = readResult() ?: continue
            return raw
        }
        return OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_TIMEOUT)
    }

    companion object {
        const val PREFS_NAME = "opencl_probe"
        const val KEY_PENDING = "pending"
        const val KEY_RESULT = "result"
        const val PROBE_TIMEOUT_MS = 5000L
        private const val POLL_MS = 100L

        /** 真实实现：写 journal -> startService(:mnn_probe)。 */
        fun real(context: Context): OpenClProbeRunner {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS)
            val json = Json { ignoreUnknownKeys = true }
            return OpenClProbeRunner(
                launchProbe = {
                    prefs.edit()
                        .putBoolean(KEY_PENDING, true)
                        .putString(KEY_RESULT, null)
                        .commit()
                    context.startService(Intent(context, OpenClProbeService::class.java))
                },
                readResult = {
                    val raw = prefs.getString(KEY_RESULT, null) ?: return@OpenClProbeRunner null
                    runCatching { json.decodeFromString<OpenClProbeResult>(raw) }
                        .getOrElse { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_PROCESS_DEATH) }
                },
            )
        }
    }
}
