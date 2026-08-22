package com.chatbyyourside

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.chatbyyourside.data.local.AppDatabase
import com.chatbyyourside.notification.AppLifecycleObserver
import com.chatbyyourside.notification.GreetingNotificationManager
import com.chatbyyourside.notification.GroupChatNotificationManager
import com.chatbyyourside.service.InferenceForegroundService
import com.chatbyyourside.ui.affinity.DailyCheckinBus
import com.chatbyyourside.util.CrashReporter
import com.chatbyyourside.util.ProcessNameUtil
import com.chatbyyourside.work.GreetingScheduler
import com.chatbyyourside.work.GroupChatScheduler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 全局 Application 入口
 * 初始化 AppContainer（手动 DI 容器）
 */
class ChatApp : Application() {
    lateinit var container: AppContainer
        private set

    /** 应用级协程作用域：用于启动时触发角色问候后台调度（不阻塞 onCreate）。
     *  挂 CoroutineExceptionHandler：后台启动任务的任何未捕获异常只记录、不杀进程，
     *  避免鸿蒙/MIUI/EMUI 等 ROM 上 DataStore/Room 文件 I/O 异常导致启动闪退。 */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "后台启动任务未捕获异常（已忽略以避免进程崩溃）", throwable)
            }
    )

    // Task 15/16：内存压力释放安全网——系统 trim 到「明确内存紧张」档时释放已加载模型
    // （BackendManager.release 为 deferred-safe：生成中延迟到 JNI 返回后释放）。**不调整后台驻留
    // 时序**：UI_HIDDEN/BACKGROUND 仅表示退到后台、非内存压力，模型仍按现状常驻，避免「过早回收」观感；
    // 仅当系统明确内存紧张（RUNNING_LOW/CRITICAL、后台 MODERATE/COMPLETE、低内存）时让出大模型，缓解 LMK 压力。
    private val memoryPressureCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                level == ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            if (critical) {
                Log.i(TAG, "系统内存紧张（trim level=$level），释放已加载模型")
                runCatching { container.backendManager.release() }
            }
        }

        override fun onLowMemory() {
            Log.i(TAG, "系统低内存，释放已加载模型")
            runCatching { container.backendManager.release() }
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        // 崩溃采集：安装全局 uncaught handler（须在一切初始化前，覆盖主进程与 :mnn_probe）。
        // 任何 Java 未捕获异常先落盘 filesDir/crash/，供设置页「崩溃日志」查看/分享；
        // 原生崩溃（SIGSEGV 等）由 CrashWatchdog 启动存活标记间接判定。
        CrashReporter.install(this)
        // :mnn_probe 隔离进程只运行 OpenCL 探测 service，不执行主应用初始化（AppContainer、
        // 通知渠道、问候调度等均与探测无关）。短路可显著加快探测进程启动——否则完整
        // onCreate（含通知/前台观察/后台调度）在冷启动 + 驱动初始化之上叠加延迟，
        // 容易超过 OpenClProbeRunner 的探测超时，导致 probe 失败 -> COOLDOWN -> OpenCL
        // 不入链 -> 用户显式选 GPU 仍回退 CPU。探测所需 libbackend_probe.so 由
        // OpenClProbeService 的 companion init 独立加载，不依赖本 onCreate。
        if (isMnnProbeProcess()) return

        container = AppContainer(this)

        // 启动同步初始化整体兜底（Track B3）：通知渠道创建 / 生命周期观察 / 空闲 OpenCL 探测
        // 任一失败只记录日志（含崩溃日志文件），绝不因 ROM 差异（鸿蒙/ColorOS 通知或 I/O 拦截）
        // 导致启动闪退。AppContainer 构造本身全 lazy、不在此兜底范围内。
        runCatching {
            // 角色问候：通知 channel + 前后台观察 + 确保后台调度链存活
            GreetingNotificationManager.createChannel(this)
            // 群聊（多人角色同群）：通知 channel（含进度保活频道）
            GroupChatNotificationManager.createChannel(this)
            // 本地推理前台服务保活通知 channel
            InferenceForegroundService.createChannel(this)
            AppLifecycleObserver.register(this)
            // Task 15/16：前台空闲时只做轻量 OpenCL 探测（绝不自动加载模型/预热）。
            container.startIdleOpenClProbe(appScope)
        }.onFailure { e ->
            Log.w(TAG, "启动同步初始化部分失败（非致命）：${e.message}")
            CrashReporter.logEvent(this, "startup", "启动同步初始化失败: ${e.message}")
        }
        // Task 15/16：内存压力安全网（关键 trim/低内存时释放模型；生成中延迟释放）。
        registerComponentCallbacks(memoryPressureCallbacks)
        appScope.launch {
            try {
                GreetingScheduler.ensureScheduled(this@ChatApp, container.settingsRepository)
            } catch (e: Exception) {
                Log.w(TAG, "角色问候后台调度初始化失败（非致命）：${e.message}")
            }
        }
        // 群聊自动聊天：后台调度链（仅云端可用，按 next_fire_at + 精确闹钟触发）
        appScope.launch {
            try {
                GroupChatScheduler.ensureScheduled(this@ChatApp, container.settingsRepository)
            } catch (e: Exception) {
                Log.w(TAG, "群聊后台调度初始化失败（非致命）：${e.message}")
            }
        }
        // Task 6：恢复 Seedance 视频流水线（复位进程中断残留的进行中状态 + 重入队可自动认领任务）。幂等，异步。
        // 失败不致命：数据库打不开时仅记录，避免启动闪退。
        appScope.launch {
            try {
                container.seedanceVideoScheduler.recoverPending()
            } catch (e: Exception) {
                Log.w(TAG, "Seedance 流水线恢复失败（非致命）：${e.message}")
            }
        }
        // 好感度：首次打开当天尚未签到则触发签到提示（导航层消费 DailyCheckinBus）。
        appScope.launch {
            try {
                if (container.affinityRepository.shouldShowDailyCheckinPrompt()) {
                    DailyCheckinBus.request()
                }
            } catch (e: Exception) {
                Log.w(TAG, "每日签到提示初始化失败（非致命）：${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ChatApp"
    }

    /** 当前是否运行于 :mnn_probe 隔离进程（OpenCL 探测专用，见 OpenClProbeService）。
     *  经 ProcessNameUtil 读 /proc/self/cmdline：Process.myProcessName() 为 API 33+ 方法，
     *  minSdk=24 下 Android 7~12L 会抛 NoSuchMethodError（v2.5 冷启动闪退根因）。 */
    private fun isMnnProbeProcess(): Boolean =
        ProcessNameUtil.currentProcessName().endsWith(":mnn_probe")
}
