package com.chatbyyourside.llm.backend

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * OpenCL 执行探测服务（Task 10）。
 *
 * 运行于独立进程 `:mnn_probe`：崩溃（驱动 SIGSEGV 等）不会波及主进程。onStartCommand 中调用
 * [nativeProbe]（backend_probe_jni.cpp，动态加载 libOpenCL.so + 极简 kernel 校验），把
 * [OpenClProbeResult] JSON 写入跨进程 SharedPreferences，随后 stopSelf 并结束自身进程。
 * 主进程 [OpenClProbeRunner] 负责 pending journal、绑定启动、轮询与超时/死亡判定。
 */
class OpenClProbeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val raw = try {
            nativeProbe()
        } catch (t: Throwable) {
            Log.e(TAG, "nativeProbe 异常: ${t.message}")
            "{\"success\":false,\"failureCode\":\"KERNEL_EXECUTION\"}"
        }
        val result = runCatching { json.decodeFromString<OpenClProbeResult>(raw) }
            .getOrElse { OpenClProbeResult(success = false, failureCode = OpenClProbeResult.FAILURE_KERNEL_EXECUTION) }
        try {
            val prefs = getSharedPreferences(OpenClProbeRunner.PREFS_NAME, Context.MODE_MULTI_PROCESS)
            prefs.edit().putString(OpenClProbeRunner.KEY_RESULT, json.encodeToString(result)).commit()
        } catch (t: Throwable) {
            Log.w(TAG, "写结果失败: ${t.message}")
        }
        Log.i(TAG, "probe finished success=${result.success} failure=${result.failureCode}")
        stopSelf(startId)
        // 探测进程自终止：避免残留空进程，也便于主进程按 Binder 死亡判定失败。
        Process.killProcess(Process.myPid())
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "OpenClProbeService"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        init {
            runCatching { System.loadLibrary("backend_probe") }
                .onFailure { Log.e(TAG, "System.loadLibrary(backend_probe) 失败: ${it.message}") }
        }

        /** backend_probe_jni.cpp 的入口：动态加载 OpenCL 并运行极简 kernel，返回结果 JSON。 */
        @JvmStatic
        external fun nativeProbe(): String
    }
}
