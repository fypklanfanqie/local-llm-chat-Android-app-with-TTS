package com.chatbyyourside.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
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
 * 外部镜像：崩溃日志同步写一份到 `getExternalFilesDir/crash/`（即
 * `/sdcard/Android/data/<包名>/files/crash/`）。App 因闪退打不开时，设置页入口不可达，
 * 而部分厂商文件管理器也进不了 Android/data；镜像后用户用数据线连电脑（MTP）即可直接
 * 取出日志。安装时会把内部目录已有的历史日志迁移到镜像（上限 [MIRROR_MAX_FILES] 份，
 * 按文件名时间戳保留最新），使升级前落盘的旧崩溃也能被导出。所有镜像操作独立 try/catch，
 * 失败不影响内部落盘主流程。
 *
 * 原生崩溃（SIGSEGV 等，Java handler 拦不住）由 [CrashWatchdog] 的启动存活标记间接判定：
 * 启动窗口内进程死掉 -> 下次启动见 `started` 无 `loaded` -> 判定「上次启动异常退出」。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val DIR_NAME = "crash"

    /** 公共 Download 镜像子目录（MediaStore RELATIVE_PATH = Download/ChatLogs/）。 */
    private const val PUBLIC_DIR_NAME = "ChatLogs"

    /** 镜像目录保留的最新日志份数上限（防无限膨胀；文件名含时间戳，按名排序即按时间）。 */
    private const val MIRROR_MAX_FILES = 20

    private val installed = AtomicBoolean(false)
    private var crashDir: File? = null
    private var externalMirrorDir: File? = null
    /** 公共 Download 镜像用 applicationContext（install 时缓存；null = 未安装或 API<29）。 */
    @Volatile
    private var appContext: Context? = null

    /**
     * 安装全局崩溃 handler。应在 Application.onCreate 最开头调用（含 `:mnn_probe` 进程），
     * 确保任何 Java 未捕获异常都先落盘再交给原 handler（原 handler 仍负责终止进程）。
     * 幂等：重复调用直接返回。
     */
    @Synchronized
    fun install(context: Context) {
        if (installed.get()) return
        crashDir = File(context.filesDir, DIR_NAME)
        externalMirrorDir = try {
            context.getExternalFilesDir(null)?.let { File(it, DIR_NAME) }
        } catch (_: Throwable) {
            null
        }
        appContext = try {
            context.applicationContext
        } catch (_: Throwable) {
            null
        }
        migrateExistingLogsToMirror()
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
        val file: File? = try {
            val dir = crashDir ?: File(context.filesDir, DIR_NAME).also { crashDir = it }
            dir.mkdirs()
            val f = File(dir, "event_${timestamp()}.log")
            f.writeText(buildLogHeader() + "\n[$tag] $message\n")
            f
        } catch (_: Exception) {
            null  // 记录失败不影响主流程
        } ?: return
        mirrorToExternal(file)
        mirrorToPublicDownloads(file)
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
        mirrorToExternal(file)
        mirrorToPublicDownloads(file)
        Log.e(TAG, "崩溃日志已写入 ${file.absolutePath}")
    }

    /** 把刚落盘的崩溃/事件日志复制到外部镜像目录（独立 try/catch，失败不影响主流程）。 */
    private fun mirrorToExternal(file: File) {
        val mirror = externalMirrorDir ?: return
        try {
            mirror.mkdirs()
            file.copyTo(File(mirror, file.name), overwrite = false)
            trimMirror(mirror)
        } catch (_: Throwable) {
            // 镜像失败不追溯：内部日志已落盘，设置页仍可见
        }
    }

    /**
     * 公共 Download 镜像（API 29+ MediaStore，无需任何存储权限）：把崩溃/事件日志写入
     * `Download/ChatLogs/`，OPPO/vivo 用户用**自带文件管理器**即可直接看到并发给开发者——
     * 解决「App 打不开 → 设置页不可达；Android/data 被 MTP/厂商文件管理器屏蔽」的取证死角。
     *
     * - 仅 API 29+：MediaStore.Downloads 自 Q 起可用且 scoped storage 下 app 无需权限即可
     *   写入自己的贡献；API 24-28 走 legacy 直写公共目录需 WRITE_EXTERNAL_STORAGE 运行时
     *   权限（已随 da6ff6e 移除，不为其回加），低版本保持仅内部+外部私有镜像双通道。
     * - 同名文件跳过（IS_PENDING 发布后重复崩溃极少同名——名字带毫秒时间戳）；写入全程
     *   独立 try/catch，失败不影响内部落盘主流程。
     * - 超限清理 [trimPublicDownloads]：只删本应用自己创建的条目（owner 匹配，API 29+
     *   允许 app 删除自己的 MediaStore 贡献，无需用户确认）。
     */
    private fun mirrorToPublicDownloads(file: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val context = appContext ?: return
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "chatbyyourside_${file.name}")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/${PUBLIC_DIR_NAME}",
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return  // 插入被 ROM 拒绝：放弃公共镜像，内部日志仍在
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: run {
                // 打不开输出流：清掉半截 pending 条目再返回
                runCatching { resolver.delete(uri, null, null) }
                return
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            trimPublicDownloads(resolver)
        } catch (_: Throwable) {
            // 公共镜像失败不追溯：内部 + Android/data 双通道已尽力
        }
    }

    /** 公共 Download 目录保留最新 [MIRROR_MAX_FILES] 份，只删本应用创建的条目。 */
    private fun trimPublicDownloads(resolver: ContentResolver) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            )
            val pkg = appContext?.packageName
            resolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("Download/${PUBLIC_DIR_NAME}/"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { cursor ->
                // OWNER_PACKAGE_NAME 个别 ROM 可能缺列：缺列即放弃清理（宁多留不误删）。
                val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val ownerCol = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
                if (idCol < 0 || ownerCol < 0) return
                var seen = 0
                while (cursor.moveToNext()) {
                    seen++
                    val isOurs = cursor.getString(ownerCol) == pkg
                    if (seen > MIRROR_MAX_FILES && isOurs) {
                        val id = cursor.getLong(idCol)
                        runCatching {
                            resolver.delete(
                                android.content.ContentUris.withAppendedId(collection, id),
                                null, null,
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 清理失败不影响主流程：最坏情况是 Download/ChatLogs 多积几份日志
        }
    }

    /** 镜像目录超限时按文件名（含时间戳）删最旧的，保留最新 [MIRROR_MAX_FILES] 份。 */
    private fun trimMirror(mirror: File) {
        val files = mirror.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return
        if (files.size <= MIRROR_MAX_FILES) return
        files.sortedByDescending { it.name }
            .drop(MIRROR_MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }

    /** 安装时把内部目录已有的历史日志迁移到镜像（幂等：镜像已存在的跳过），让升级前
     *  落盘的崩溃（如 v2.5 的 myProcessName 闪退）也能经 USB 导出。 */
    private fun migrateExistingLogsToMirror() {
        val internal = crashDir ?: return
        val mirror = externalMirrorDir ?: return
        try {
            val existing = internal.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return
            for (f in existing) {
                val target = File(mirror, f.name)
                if (!target.exists()) {
                    mirror.mkdirs()
                    runCatching { f.copyTo(target, overwrite = false) }
                }
            }
            trimMirror(mirror)
        } catch (_: Throwable) {
            // 迁移失败不影响崩溃采集主流程
        }
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
        // Track A4 启动阶段：本次启动最后到达的阶段（provider 前的 old/unknown ->
        // application -> activity -> loaded）。「点图标即闪退」据此直接定位死亡阶段。
        appendLine("启动阶段: ${startupPhase()}")
    }

    /** 读 startup_journal/phase（CrashWatchdog.markPhase 写入）。crashDir 与 journal 同 filesDir，
     *  由 crashDir 父目录推导，避免额外依赖注入；读失败返回 unknown（旧版本无此标记）。 */
    private fun startupPhase(): String = try {
        val filesDir = crashDir?.parentFile ?: return "unknown"
        val phaseFile = File(File(filesDir, "startup_journal"), "phase")
        if (phaseFile.exists()) phaseFile.readText().trim() else "unknown"
    } catch (_: Throwable) {
        "unknown"
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
    private const val MARKER_STREAK = "crash_streak"
    private const val MARKER_PHASE = "phase"

    /** 启动阶段名（写 phase 标记）：provider（ContentProvider 阶段）-> application -> activity -> loaded。 */
    internal const val PHASE_PROVIDER = "provider"
    internal const val PHASE_APPLICATION = "application"
    internal const val PHASE_ACTIVITY = "activity"
    internal const val PHASE_LOADED = "loaded"

    /** 进入「崩溃循环安全模式」的连续崩溃次数阈值：连续 2 次启动窗口内崩溃 -> 降级启动。 */
    internal const val SAFE_MODE_THRESHOLD = 2

    /** 上次启动是否在加载窗口内异常退出（检查当下即代表上一次进程的状态）。 */
    fun hasCrashedLastLaunch(context: Context): Boolean = try {
        val dir = journalDir(context)
        File(dir, MARKER_STARTED).exists() && !File(dir, MARKER_LOADED).exists()
    } catch (_: Throwable) {
        false
    }

    /** 记录当前启动阶段（覆盖写；任何失败静默，绝不影响启动）。 */
    fun markPhase(context: Context, phase: String) {
        try {
            val dir = journalDir(context)
            dir.mkdirs()
            File(dir, MARKER_PHASE).writeText(phase)
        } catch (_: Throwable) {
            // 阶段标记失败不影响启动主流程
        }
    }

    /** 当前 phase 标记内容（崩溃日志头部用；读失败返回 unknown）。 */
    fun currentPhase(context: Context): String = try {
        val file = File(journalDir(context), MARKER_PHASE)
        if (file.exists()) file.readText().trim() else "unknown"
    } catch (_: Throwable) {
        "unknown"
    }

    /**
     * 更新连续崩溃计数（主进程 Application.onCreate 时调用；探测进程已短路，不参与）。
     * 上次启动在加载窗口内崩溃 -> 计数 +1；正常走完 -> 归零。计数持久化到 startup_journal。
     */
    fun updateCrashStreak(context: Context): Int = try {
        val dir = journalDir(context)
        dir.mkdirs()
        val file = File(dir, MARKER_STREAK)
        val prev = runCatching { file.readText().trim().toInt() }.getOrDefault(0)
        val next = if (hasCrashedLastLaunch(context)) prev + 1 else 0
        file.writeText(next.toString())
        next
    } catch (_: Throwable) {
        0
    }

    /** 是否已进入「崩溃循环安全模式」：连续 [SAFE_MODE_THRESHOLD] 次启动窗口内崩溃。 */
    fun isCrashLoopSafeMode(context: Context): Boolean = try {
        val file = File(journalDir(context), MARKER_STREAK)
        val streak = runCatching { file.readText().trim().toInt() }.getOrDefault(0)
        streak >= SAFE_MODE_THRESHOLD
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
