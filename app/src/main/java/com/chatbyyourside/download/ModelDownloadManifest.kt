package com.chatbyyourside.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 模型下载完成清单（Task：MNN load SIGSEGV 防线）。
 *
 * 背景：Range 续传出错的后果是「大小正确、内容已坏」的权重文件——MNN
 * `Llm::load()` 反序列化时深栈解引用空指针（tombstone 见 ConvolutionCommon::load
 * SIGSEGV），Java try/catch 拦不住。单靠 config.json+llm.mnn 存在性判完整不够。
 *
 * 本清单在下载逐文件成功、分片合并完成后原子落盘，记录每个文件的最终 size
 * （及可用时的 SHA-256）。加载前对照清单校验；无清单的旧/手工目录走旧判定
 * （见 [ModelDownloadManifest.validate]），不误伤存量用户。
 */
@Serializable
data class ModelDownloadManifest(
    val modelId: String,
    val files: Map<String, String> = emptyMap(), // 相对路径 -> "size:sha256 前缀"（sha 可为空串）
) {
    /** 文件中记录的字节数；缺失返回 null。 */
    fun expectedSize(relPath: String): Long? = files[relPath]
        ?.substringBefore(':')
        ?.toLongOrNull()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        const val FILE_NAME = ".download-manifest.json"

        /** 原子写入（tmp + rename）；失败抛异常由调用方吞（不影响下载主流程）。 */
        fun write(dir: File, manifest: ModelDownloadManifest) {
            val tmp = File(dir, FILE_NAME + ".tmp")
            tmp.writeText(json.encodeToString(serializer(), manifest))
            if (!tmp.renameTo(File(dir, FILE_NAME))) {
                tmp.copyTo(File(dir, FILE_NAME), overwrite = true)
                tmp.delete()
            }
        }

        /** 读取清单；缺失/损坏返回 null（调用方按「无需校验」处理）。 */
        fun read(dir: File): ModelDownloadManifest? {
            val f = File(dir, FILE_NAME)
            if (!f.exists()) return null
            return try {
                json.decodeFromString(serializer(), f.readText())
            } catch (_: Exception) {
                null
            }
        }

        /** 给一个现有文件计算清单项：size + sha256 前 16 位（大权重读 sha 成本可控：本地文件读一遍）。 */
        fun entryFor(file: File): String {
            val size = file.length()
            val sha = try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { ins ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }.take(16)
            } catch (_: Exception) {
                ""
            }
            return "$size:$sha"
        }
    }
}

/**
 * 加载前完整性校验（纯函数，JVM 可测）。
 * @return null = 无需校验（无清单的旧目录）；否则为校验通过/失败的判定结果。
 */
object ModelDownloadIntegrity {

    sealed interface Result {
        /** 通过（或清单缺失——旧目录兼容，不阻断但记日志）。 */
        data class Ok(val verified: Boolean) : Result

        /** 失败：清单存在但文件缺失/大小不符/校验和不符，绝不进入 native 加载。 */
        data class Fail(val reason: String) : Result
    }

    /**
     * 校验模型目录与下载清单的一致性。
     * - 无清单（旧版本/手工放置）-> Ok(verified=false)，沿用既有存在性判定；
     * - 有清单 -> 逐文件存在 + size 精确匹配（有 sha 前缀则再比对），任一不符即 Fail。
     */
    fun validate(dir: File): Result {
        val manifest = ModelDownloadManifest.read(dir) ?: return Result.Ok(verified = false)
        for ((rel, item) in manifest.files) {
            val f = File(dir, rel)
            if (!f.exists()) return Result.Fail("缺少文件: $rel")
            val size = item.substringBefore(':').toLongOrNull() ?: continue
            if (f.length() != size) {
                return Result.Fail("文件大小不符（下载损坏）: $rel 期望 $size 实际 ${f.length()}")
            }
            val sha = item.substringAfter(':', "")
            if (sha.isNotEmpty() && sha.length == 16) {
                val actual = ModelDownloadManifest.entryFor(f).substringAfter(':', "")
                if (actual != sha) return Result.Fail("文件校验和不符（下载损坏）: $rel")
            }
        }
        return Result.Ok(verified = true)
    }
}
