package com.chatbyyourside.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 崩溃日志采集（OPPO/鸿蒙启动闪退排查 Track A1）。
 *
 * 安装全局 [Thread.setDefaultUncaughtExceptionHandler]：任何进程（主进程 / `:mnn_probe`）的
 * Java 未捕获异常都会把「堆栈 + 设备指纹」写入 `filesDir/crash/crash_<时间戳>.log`，供设置页
 * 「崩溃日志」入口查看 / 复制 / 分享。这是解决「拿不到崩溃日志」的核心手段——用户无需 adb，
 * 闪退后从 设置 → 崩溃日志 复制发给开发者即可定位真因。
 *
 * 原生崩溃（SIGSEGV 等，Java handler 拦不住）由 [CrashWatchdog] 的启动存活标记间接判定：
 * 启动窗口内进程死掉 -> 下次启动见 `started` 无 `loaded` -> 判定「上次启动异常退出」。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crash"

    private val installed = AtomicBoolean(false)
    private var crashDir: File? = null

    /**
     * 安装全局崩溃 handler。应在 Application.onCreate 最开头调用（含 `:mnn_probe` 进程），
     * 确保任何 Java 未捕获异常都先落盘再交给原 handler（原 handler 仍负责终止进程）。
     * 幂等：重复调用直接返回。
     */
    @Synchronized
    fun install(context: Context) {
        if (installed.get()) return
        crashDir = File(context.filesDir, DIR_NAME)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(thread, throwable)
            } catch (e: Exception) {
                Log.e(TAG, "写崩溃日志失败", e)
            }
            prev?.uncaughtException(thread, throwable)
        }
        installed.set(true)
        Log.i(TAG, "CrashReporter installed (dir=${crashDir?.absolutePath})")
    }

    /** 崩溃日志目录（不存在时返回目录对象，读取方自行判空）。 */
    fun crashLogDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 手动记录一条事件日志（非崩溃，如启动初始化异常兜底），与崩溃日志同目录。 */
    fun logEvent(context: Context, tag: String, message: String) {
        try {
            val dir = crashDir ?: File(context.filesDir, DIR_NAME).also { crashDir = it }
            dir.mkdirs()
            val file = File(dir, "event_${timestamp()}.log")
            file.writeText(buildLogHeader() + "\n[$tag] $message\n")
        } catch (_: Exception) {
            // 记录失败不影响主流程
        }
    }

    private fun writeCrash(thread: Thread, throwable: Throwable) {
        val dir = crashDir ?: return
        dir.mkdirs()
        val file = File(dir, "crash_${timestamp()}.log")
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        pw.println(buildLogHeader())
        pw.println("崩溃线程: ${thread.name} (id=${thread.id})")
        pw.println("进程: ${processName()}")
        pw.println("---- 堆栈 ----")
        throwable.printStackTrace(pw)
        pw.flush()
        file.writeText(sw.toString())
        Log.e(TAG, "崩溃日志已写入 ${file.absolutePath}")
    }

    private fun buildLogHeader(): String = buildString {
        appendLine("===== 崩溃日志 =====")
        appendLine("时间: ${timestamp()}")
        appendLine("厂商: ${Build.MANUFACTURER}")
        appendLine("品牌: ${Build.BRAND}")
        appendLine("型号: ${Build.MODEL}")
        appendLine("设备: ${Build.DEVICE}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("SDK: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        appendLine("系统指纹: ${Build.FINGERPRINT}")
        appendLine("版本增量: ${Build.VERSION.INCREMENTAL}")
        appendLine("进程: ${processName()}")
    }

    /** 当前进程名（经 ProcessNameUtil 读 /proc/self/cmdline，全 API 级别可用；
     *  原 Process.myProcessName() 为 API 33+，低版本上崩溃日志进程名恒为 "unknown"）。 */
    private fun processName(): String = ProcessNameUtil.currentProcessName().ifEmpty { "unknown" }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}

/**
 * 启动存活日志（Track A2）：捕获 Java handler 拦不住的启动窗口原生崩溃。
 *
 * 机制：MainActivity.onCreate 写 `started` 标记、LoadingScreen 完成后写 `loaded` 标记。
 * 下次启动检查：`started` 在且 `loaded` 不在 -> 上次启动在加载窗口内进程死掉（极可能原生崩溃），
 * 首页据此显示「上次启动异常退出」提示，引导用户去设置查看崩溃日志。
 * 检查后由 [markStarted] 重置（删 loaded、重写 started），标记天然一次性。
 */
object CrashWatchdog {

    private const val TAG = "CrashWatchdog"
    private const val DIR_NAME = "startup_journal"
    private const val MARKER_STARTED = "started"
    private const val MARKER_LOADED = "loaded"

    /** 上次启动是否在加载窗口内异常退出（检查当下即代表上一次进程的状态）。 */
    fun hasCrashedLastLaunch(context: Context): Boolean = try {
        val dir = journalDir(context)
        File(dir, MARKER_STARTED).exists() && !File(dir, MARKER_LOADED).exists()
    } catch (_: Throwable) {
        false
    }

    /** 开始新一轮启动：写 started 标记，清空上一次的 loaded 标记。 */
    fun markStarted(context: Context) {
        try {
            val dir = journalDir(context)
            dir.mkdirs()
            File(dir, MARKER_STARTED).writeText("1")
            File(dir, MARKER_LOADED).delete()
        } catch (_: Throwable) {
            Log.w(TAG, "写 started 标记失败")
        }
    }

    /** 加载画面正常走完：写 loaded 标记，表示启动窗口安全通过。 */
    fun markLoaded(context: Context) {
        try {
            val dir = journalDir(context)
            dir.mkdirs()
            File(dir, MARKER_LOADED).writeText("1")
        } catch (_: Throwable) {
            Log.w(TAG, "写 loaded 标记失败")
        }
    }

    private fun journalDir(context: Context): File = File(context.filesDir, DIR_NAME)
}
