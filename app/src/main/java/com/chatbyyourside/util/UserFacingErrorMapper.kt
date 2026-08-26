package com.chatbyyourside.util

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException

/**
 * 用户可见错误映射（纯 JVM、无 Android 依赖）。
 *
 * 目标：普通用户看到的错误永远是通俗中文——不显示原始异常消息、HTTP 状态码原文、
 * URL、LogID 或 native 黑话。原始异常仍由调用方 Log.e/CrashReporter 记录，本函数只
 * 负责「异常 -> 人话」这一层展示转换。
 *
 * 规则：
 * - 已知异常类型按类映射；
 * - 已知文本模式（各客户端抛出的 "HTTP xxx" / timeout 字样）按内容映射；
 * - 其余一律回落 [fallback]，绝不透传 `t.message`。
 */
object UserFacingErrorMapper {

    /** 本地 AI 引擎版本不匹配。 */
    const val MSG_ENGINE_MISMATCH = "本地 AI 引擎版本不匹配，请更新或重新安装应用"

    /** 内存不足。 */
    const val MSG_OUT_OF_MEMORY = "内存不足，请关闭其他应用或降低模型/上下文长度后重试"

    /** 存储问题。 */
    const val MSG_STORAGE = "存储空间不足或暂时无法读写，请清理空间后重试"

    /** 网络超时。 */
    const val MSG_NETWORK_TIMEOUT = "网络连接超时，请检查网络后重试"

    /** 无法连接服务器。 */
    const val MSG_NETWORK_UNREACHABLE = "无法连接服务器，请检查网络或服务地址后重试"

    /** 服务返回内容异常。 */
    const val MSG_BAD_RESPONSE = "服务返回内容异常，请稍后重试"

    /**
     * 把任意 Throwable 映射为用户可读的中文提示。
     * @param fallback 未知异常时的兜底文案（调用点可给场景化默认值，如「下载失败」）。
     */
    fun userFacingError(t: Throwable?, fallback: String = "请求失败"): String {
        if (t == null) return fallback
        // 按类型精确匹配（含子类）。
        val typeName = t.javaClass.name
        when (t) {
            is UnsatisfiedLinkError -> return MSG_ENGINE_MISMATCH
            is LinkageError -> return MSG_ENGINE_MISMATCH
            is OutOfMemoryError -> return MSG_OUT_OF_MEMORY
            is SocketTimeoutException -> return MSG_NETWORK_TIMEOUT
            is UnknownHostException -> return MSG_NETWORK_UNREACHABLE
            is java.net.ConnectException -> return MSG_NETWORK_UNREACHABLE
            is javax.net.ssl.SSLException -> return MSG_NETWORK_UNREACHABLE
        }
        // JNI 契约异常按类名判定（internal 类，避免跨包编译依赖）。
        if (typeName.contains("JniContractMismatch")) return MSG_ENGINE_MISMATCH
        // 原因链上溯一层：很多网络异常被包在 RuntimeException/IOException 里。
        t.cause?.let { cause ->
            when (cause) {
                is SocketTimeoutException -> return MSG_NETWORK_TIMEOUT
                is UnknownHostException -> return MSG_NETWORK_UNREACHABLE
                is java.net.ConnectException -> return MSG_NETWORK_UNREACHABLE
                is OutOfMemoryError -> return MSG_OUT_OF_MEMORY
            }
        }
        // 存储类：android.database.sqlite.* 在 JVM 不可引用，按类名判定。
        if (typeName.contains("sqlite", ignoreCase = true) ||
            typeName.contains("SQLiteFull", ignoreCase = true)
        ) {
            return MSG_STORAGE
        }
        if (t is IOException && typeName.contains("Disk")) return MSG_STORAGE

        // 文本模式匹配：各客户端抛出的 Exception("...")。
        val message = t.message ?: return fallback
        val httpCode = Regex("""HTTP[^\d]{0,4}(\d{3})""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        if (httpCode != null) return httpMessage(httpCode)
        val lower = message.lowercase()
        return when {
            lower.contains("timeout") || lower.contains("timed out") || lower.contains("超时") ->
                MSG_NETWORK_TIMEOUT
            lower.contains("unknownhost") || lower.contains("unable to resolve") ||
                lower.contains("failed to connect") || lower.contains("connection reset") ||
                lower.contains("econnrefused") || lower.contains("network") ->
                MSG_NETWORK_UNREACHABLE
            lower.contains("outofmemory") || lower.contains("oom") ->
                MSG_OUT_OF_MEMORY
            lower.contains("unsatisfiedlink") || lower.contains("no implementation found") ->
                MSG_ENGINE_MISMATCH
            lower.contains("json") || lower.contains("解析") || lower.contains("格式") ||
                lower.contains("空内容") || lower.contains("响应体为空") || lower.contains("empty") ->
                MSG_BAD_RESPONSE
            else -> fallback
        }
    }

    /** HTTP 状态码 -> 中文；不含码值本身与任何供应商原文。 */
    fun httpMessage(code: Int): String = when (code) {
        401, 403 -> "API Key 无效或没有访问权限，请在设置页检查密钥"
        402 -> "账户余额或额度不足，请前往服务商充值"
        404 -> "服务地址不存在或接口路径有误，请检查设置页的服务地址"
        408 -> MSG_NETWORK_TIMEOUT
        413 -> "发送的内容过长，请缩短后重试"
        429 -> "请求过于频繁或额度已用尽，请稍后再试"
        in 500..599 -> "服务暂时不可用（$code），请稍后再试"
        else -> "请求失败（$code），请检查网络或稍后再试"
    }
}
