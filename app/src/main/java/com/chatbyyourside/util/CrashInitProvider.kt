package com.chatbyyourside.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * 最早崩溃采集 Provider（Track A0，本夜新加）。
 *
 * 背景：Android 进程启动时 **ContentProvider 在 Application.onCreate 之前、按 initOrder 降序
 * 实例化**。本项目依赖 WorkManager 默认初始化器——它经 androidx.startup InitializationProvider
 * 在任何自定义 Application 代码之前跑纯 Java 初始化。若 OPPO/vivo ColorOS/OriginOS 在
 * ContentProvider 阶段发生 ROM 文件 I/O / native 栏截，崩溃发生在 CrashReporter（原在
 * ChatApp.onCreate 安装）装上之前，日志零覆盖，用户「一点图标就闪退」无从查证。
 *
 * 本 Provider 以 manifest 最高 initOrder 排在 androidx.startup 之前，onCreate 里立即：
 *  1. 安装 CrashReporter（幂等；Application.onCreate 的重复安装直接 no-op）。任何异常都吞掉，
 *     绝不能让自己成为新的崩溃点——返回 true 但静默降级。
 *  2. 写一条 event 日志 `content_provider_attached`（含 SDK/ABI），让既睡设备上至少有一条
 *     可追踪的启动时序证据（内部 + 外部镜像双写，万一后续 Application 阶段崩溃也有参照）。
 *
 * 注意：event 日志特意不含任何私有数据（无 key、无模型、无对话）——只含设备指纹与时刻，
 * ContentProvider 阶段没有业务上下文，也不该访问业务层。
 */
class CrashInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val appContext = context
        if (appContext == null) return true
        // 安装崩溃采集：失败绝不能拖垮启动。
        try {
            CrashReporter.install(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "CrashReporter.install 失败（不影响启动）: ${e.message}")
        }
        // 留下「ContentProvider 已附着」的时序痕迹（幂等；同 file 名带毫秒时间戳）。
        try {
            CrashReporter.logEvent(
                appContext,
                TAG,
                "content_provider_attached sdk=${Build.VERSION.SDK_INT} " +
                    "abi=${Build.SUPPORTED_ABIS.joinToString(",")}",
            )
        } catch (e: Throwable) {
            Log.e(TAG, "logEvent 失败（不影响启动）: ${e.message}")
        }
        return true
    }

    // ContentProvider 抽象方法空实现：本类不对外暴露数据通路。
    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<String>?): Int = 0

    private companion object {
        const val TAG = "CrashInitProvider"
    }
}