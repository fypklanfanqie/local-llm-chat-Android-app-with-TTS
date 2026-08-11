package com.chatbyyourside

import android.app.Application
import android.os.Process
import com.chatbyyourside.data.local.AppDatabase
import com.chatbyyourside.notification.AppLifecycleObserver
import com.chatbyyourside.notification.GreetingNotificationManager
import com.chatbyyourside.work.GreetingScheduler
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

    /** 应用级协程作用域：用于启动时触发角色问候后台调度（不阻塞 onCreate）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // :mnn_probe 隔离进程只运行 OpenCL 探测 service，不执行主应用初始化（AppContainer、
        // 通知渠道、问候调度等均与探测无关）。短路可显著加快探测进程启动——否则完整
        // onCreate（含通知/前台观察/后台调度）在冷启动 + 驱动初始化之上叠加延迟，
        // 容易超过 OpenClProbeRunner 的探测超时，导致 probe 失败 -> COOLDOWN -> OpenCL
        // 不入链 -> 用户显式选 GPU 仍回退 CPU。探测所需 libbackend_probe.so 由
        // OpenClProbeService 的 companion init 独立加载，不依赖本 onCreate。
        if (isMnnProbeProcess()) return

        container = AppContainer(this)

        // 角色问候：通知 channel + 前后台观察 + 确保后台调度链存活
        GreetingNotificationManager.createChannel(this)
        AppLifecycleObserver.register(this)
        appScope.launch {
            GreetingScheduler.ensureScheduled(this@ChatApp, container.settingsRepository)
        }
    }

    /** 当前是否运行于 :mnn_probe 隔离进程（OpenCL 探测专用，见 OpenClProbeService）。 */
    private fun isMnnProbeProcess(): Boolean =
        (Process.myProcessName() ?: "").endsWith(":mnn_probe")
}
