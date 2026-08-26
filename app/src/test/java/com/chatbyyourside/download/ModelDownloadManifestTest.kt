package com.chatbyyourside.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [ModelDownloadManifest] / [ModelDownloadIntegrity] 纯 JVM 单测：
 * 覆盖下载损坏的两种形态（大小不符、内容坏但同大小）都被加载前校验拦截；
 * 无清单旧目录兼容放行；清单本身损坏不阻断（按无清单处理）。
 */
class ModelDownloadManifestTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("model-manifest").toFile()
    }

    private fun file(rel: String, content: String): File {
        val f = File(dir, rel)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    @Test
    fun writeReadRoundTrip() {
        val f = file("llm.mnn.weight", "weight-bytes-123456")
        ModelDownloadManifest.write(
            dir,
            ModelDownloadManifest(
                modelId = "qwen",
                files = mapOf("llm.mnn.weight" to ModelDownloadManifest.entryFor(f)),
            ),
        )
        val read = ModelDownloadManifest.read(dir)
        assertTrue(read != null)
        assertEquals("qwen", read!!.modelId)
        assertEquals(f.length(), read.expectedSize("llm.mnn.weight"))
    }

    @Test
    fun noManifest_oldDirectoryIsNotBlocked() {
        file("llm.mnn", "graph")
        val result = ModelDownloadIntegrity.validate(dir)
        assertTrue(result is ModelDownloadIntegrity.Result.Ok)
        assertFalse((result as ModelDownloadIntegrity.Result.Ok).verified)
    }

    @Test
    fun missingFileFromManifestFails() {
        val f = file("llm.mnn.weight", "x")
        ModelDownloadManifest.write(
            dir,
            ModelDownloadManifest("m", mapOf("llm.mnn.weight" to ModelDownloadManifest.entryFor(f), "missing.bin" to "10:abcd12")),
        )
        val result = ModelDownloadIntegrity.validate(dir)
        assertTrue(result is ModelDownloadIntegrity.Result.Fail)
        assertTrue((result as ModelDownloadIntegrity.Result.Fail).reason.contains("missing.bin"))
    }

    @Test
    fun wrongSizeFails() {
        val f = file("llm.mnn.weight", "short")
        ModelDownloadManifest.write(
            dir,
            ModelDownloadManifest("m", mapOf("llm.mnn.weight" to "999:abcd12")),
        )
        val result = ModelDownloadIntegrity.validate(dir)
        assertTrue(result is ModelDownloadIntegrity.Result.Fail)
        assertTrue((result as ModelDownloadIntegrity.Result.Fail).reason.contains("大小不符"))
    }

    @Test
    fun sameSizeCorruptedContentFails() {
        val f = file("llm.mnn.weight", "good-content:1234567890")
        ModelDownloadManifest.write(
            dir,
            ModelDownloadManifest("m", mapOf("llm.mnn.weight" to ModelDownloadManifest.entryFor(f))),
        )
        // 篡改为等长不同内容（Range 拼接损坏的典型形态：大小对、字节错）。
        file("llm.mnn.weight", "bad--content:1234567890")
        assertEquals(f.length(), File(dir, "llm.mnn.weight").length())
        val result = ModelDownloadIntegrity.validate(dir)
        assertTrue("同大小内容损坏必须拦截", result is ModelDownloadIntegrity.Result.Fail)
        assertTrue((result as ModelDownloadIntegrity.Result.Fail).reason.contains("校验和"))
    }

    @Test
    fun matchingManifestPasses() {
        val a = file("llm.mnn", "graph-bytes")
        val b = file("llm.mnn.weight", "weight-bytes")
        ModelDownloadManifest.write(
            dir,
            ModelDownloadManifest(
                "m",
                mapOf(
                    "llm.mnn" to ModelDownloadManifest.entryFor(a),
                    "llm.mnn.weight" to ModelDownloadManifest.entryFor(b),
                ),
            ),
        )
        val result = ModelDownloadIntegrity.validate(dir)
        assertTrue(result is ModelDownloadIntegrity.Result.Ok)
        assertTrue((result as ModelDownloadIntegrity.Result.Ok).verified)
    }

    @Test
    fun corruptedManifestFileDoesNotBlock() {
        file("llm.mnn", "graph")
        file(ModelDownloadManifest.FILE_NAME, "{ corrupted json !!")
        assertNull(ModelDownloadManifest.read(dir))
        val result = ModelDownloadIntegrity.validate(dir)
        assertTrue(result is ModelDownloadIntegrity.Result.Ok)
    }
}
