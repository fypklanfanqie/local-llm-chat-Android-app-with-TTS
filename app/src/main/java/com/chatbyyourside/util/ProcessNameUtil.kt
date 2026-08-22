package com.chatbyyourside.util

import java.io.File

/**
 * 全版本兼容的当前进程名读取。
 *
 * 背景：`Process.myProcessName()` 为 API 33+ 方法，minSdk=24 下在 Android 7~12L 直接抛
 * NoSuchMethodError（v2.5 冷启动闪退根因，见 ChatApp.isMnnProbeProcess）。本工具改读
 * `/proc/self/cmdline`——zygote fork 后内核记录的进程名，NUL 结尾，API 24~36 单一路径通用，
 * 无需按版本分支。读取失败返回空串，调用方按「主进程」处理（探测进程走完整初始化，
 * 仅损失短路加速，功能无损）。
 */
object ProcessNameUtil {

    /** 当前进程名；任何读取失败返回空串（调用方按「主进程」处理）。 */
    fun currentProcessName(): String = runCatching {
        parseCmdline(File("/proc/self/cmdline").readBytes())
    }.getOrDefault("")

    /** 纯函数便于单测：取首个 NUL 前的字节按 UTF-8 解码。 */
    internal fun parseCmdline(bytes: ByteArray): String {
        val end = bytes.indexOf(0).let { if (it == -1) bytes.size else it }
        return String(bytes, 0, end, Charsets.UTF_8)
    }
}
