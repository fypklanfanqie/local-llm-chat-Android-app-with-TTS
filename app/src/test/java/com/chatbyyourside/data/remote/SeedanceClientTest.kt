package com.chatbyyourside.data.remote

import com.chatbyyourside.data.model.SeedanceConfig
import com.chatbyyourside.data.model.SeedanceModelVariant
import com.chatbyyourside.data.model.SeedanceRatio
import com.chatbyyourside.data.model.SeedanceResolution
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [SeedanceClient] 契约测试（Task 5，MockWebServer）。
 *
 * 覆盖：POST/GET/DELETE 路径与 Bearer 鉴权、content 顺序与 role=reference_image、
 * generate_audio=true、模型 ID、参数编码（分辨率/画幅/时长/水印）、无 fps/seed/camera、
 * 响应可空字段与未知字段容错、全部官方状态映射、错误分类（敏感/配额/鉴权/429/500/参数）、
 * 非 JSON 错误体、request-id 捕获、取消传播，以及 API Key / base64 不泄漏。
 */
class SeedanceClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SeedanceClient

    private val testJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val character = SeedanceImageContent("image/png", "aGVsbG8=")      // data:image/png;base64,aGVsbG8=
    private val background = SeedanceImageContent("image/jpeg", "d29ybGQ=")    // data:image/jpeg;base64,d29ybGQ=

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = SeedanceClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(apiKey: String = TEST_API_KEY): SeedanceConfig = SeedanceConfig(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = apiKey,
    )

    private fun request(
        variant: SeedanceModelVariant = SeedanceModelVariant.STANDARD,
        resolution: SeedanceResolution = SeedanceResolution.P720,
        ratio: SeedanceRatio = SeedanceRatio.PORTRAIT,
        durationSeconds: Int = 5,
        watermark: Boolean = false,
        background: SeedanceImageContent? = null,
    ): CreateSeedanceTask = CreateSeedanceTask(
        finalPrompt = "一位少女在夕阳下回眸",
        character = character,
        background = background,
        variant = variant,
        resolution = resolution,
        ratio = ratio,
        durationSeconds = durationSeconds,
        watermark = watermark,
    )

    /** 从最近一次请求体解析 JSON 对象。 */
    private fun lastRequestBody() = testJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    /** 执行一次 createTask，断言抛出 [SeedanceApiException] 并返回之。 */
    private suspend fun expectApiException(): SeedanceApiException {
        val ex = runCatching { client.createTask(config(), request()) }.exceptionOrNull()
        assertNotNull("期望抛出 SeedanceApiException", ex)
        assertTrue("期望 SeedanceApiException，实际 ${ex!!.javaClass.simpleName}", ex is SeedanceApiException)
        return ex as SeedanceApiException
    }

    // ---- 创建任务：路径 / 鉴权 / 请求体 ----

    @Test
    fun createTask_postsToContentsGenerationsTasks_withBearerHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        val resp = client.createTask(config(), request())
        assertEquals("cgt-abc", resp.id)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/contents/generations/tasks", recorded.path)
        assertEquals("Bearer $TEST_API_KEY", recorded.getHeader("Authorization"))
    }

    @Test
    fun createTask_encodesModelGenerateAudioAndParameters() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(
            config(),
            request(
                variant = SeedanceModelVariant.FAST,
                resolution = SeedanceResolution.P1080,
                ratio = SeedanceRatio.LANDSCAPE,
                durationSeconds = 12,
                watermark = true,
            ),
        )

        val body = lastRequestBody()
        assertEquals("doubao-seedance-2-0-fast-260128", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["generate_audio"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("1080p", body["resolution"]!!.jsonPrimitive.content)
        assertEquals("16:9", body["ratio"]!!.jsonPrimitive.content)
        assertEquals(12, body["duration"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["watermark"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun createTask_resolutionApiValueUses4kForP4K() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(resolution = SeedanceResolution.P4K))
        val body = lastRequestBody()
        assertEquals("4k", body["resolution"]!!.jsonPrimitive.content)
    }

    @Test
    fun createTask_contentOrderIsTextThenCharacterReferenceImage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request())

        val content = lastRequestBody()["content"]!!.jsonArray
        assertEquals(2, content.size)

        val textItem = content[0].jsonObject
        assertEquals("text", textItem["type"]!!.jsonPrimitive.content)
        assertEquals("一位少女在夕阳下回眸", textItem["text"]!!.jsonPrimitive.content)
        assertNull("text 项不应携带 role", textItem["role"])

        val imageItem = content[1].jsonObject
        assertEquals("image_url", imageItem["type"]!!.jsonPrimitive.content)
        assertEquals("reference_image", imageItem["role"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,aGVsbG8=",
            imageItem["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun createTask_backgroundAppendedAsSecondReferenceImage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(background = background))

        val content = lastRequestBody()["content"]!!.jsonArray
        assertEquals(3, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("reference_image", content[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("reference_image", content[2].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,d29ybGQ=",
            content[2].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun createTask_neverSerializesFpsSeedOrCamera() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(background = background))

        val body = lastRequestBody()
        val keys = body.keys
        assertFalse("请求体不得含 fps", keys.contains("fps"))
        assertFalse("请求体不得含 seed", keys.contains("seed"))
        assertFalse("请求体不得含 camera", keys.contains("camera"))

        val contentKeys = body["content"]!!.jsonArray.flatMap { it.jsonObject.keys }.toSet()
        assertFalse("content 项不得含 fps", contentKeys.contains("fps"))
        assertFalse("content 项不得含 seed", contentKeys.contains("seed"))
        assertFalse("content 项不得含 camera", contentKeys.contains("camera"))
    }

    // ---- 查询任务：GET 路径 / 鉴权 / 响应解析 ----

    @Test
    fun getTask_getsById_withBearerHeader_andParsesFullResponse() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("X-Request-Id", "req-123").setBody(
                """
                {
                  "id": "cgt-abc",
                  "status": "succeeded",
                  "output": {"video_url": "https://cdn.example/v.mp4?sign=SECRET", "last_frame_url": "https://cdn.example/f.jpg"},
                  "usage": {"total_tokens": 42},
                  "created_at": 1700000000,
                  "updated_at": 1700000100
                }
                """.trimIndent()
            )
        )
        val resp = client.getTask(config(), "cgt-abc")

        assertEquals("cgt-abc", resp.id)
        assertEquals(SeedanceRemoteStatus.SUCCEEDED, resp.remoteStatus)
        assertEquals("https://cdn.example/v.mp4?sign=SECRET", resp.output?.videoUrl)
        assertEquals("https://cdn.example/f.jpg", resp.output?.lastFrameUrl)
        assertNotNull(resp.usage)
        assertEquals(1700000000L, resp.createdAt)
        assertEquals(1700000100L, resp.updatedAt)
        assertEquals("req-123", resp.requestId)
        assertNull(resp.error)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/contents/generations/tasks/cgt-abc", recorded.path)
        assertEquals("Bearer $TEST_API_KEY", recorded.getHeader("Authorization"))
    }

    @Test
    fun response_toleratesMissingAndUnknownFields() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"cgt-abc","unknown_field":true,"output":{"extra":1}}"""
            )
        )
        val resp = client.getTask(config(), "cgt-abc")
        assertEquals("cgt-abc", resp.id)
        assertNull(resp.status)
        assertNull(resp.remoteStatus)
        assertNull(resp.output?.videoUrl)
        assertNull(resp.error)
        assertNull(resp.requestId)
    }

    @Test
    fun response_mapsEveryOfficialStatus() = runBlocking {
        val expected = mapOf(
            "queued" to SeedanceRemoteStatus.QUEUED,
            "running" to SeedanceRemoteStatus.RUNNING,
            "cancelled" to SeedanceRemoteStatus.CANCELLED,
            "succeeded" to SeedanceRemoteStatus.SUCCEEDED,
            "failed" to SeedanceRemoteStatus.FAILED,
            "expired" to SeedanceRemoteStatus.EXPIRED,
        )
        for ((wire, status) in expected) {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"x","status":"$wire"}"""))
            assertEquals("status=$wire 应映射为 $status", status, client.getTask(config(), "x").remoteStatus)
        }
    }

    @Test
    fun response_unknownStatusFallsBackToFailed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"x","status":"weird-status"}"""))
        assertEquals(SeedanceRemoteStatus.FAILED, client.getTask(config(), "x").remoteStatus)
    }

    @Test
    fun response_failedStatusCarriesErrorBody() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"x","status":"failed","error":{"code":"SomeError","message":"生成失败"}}"""
            )
        )
        val resp = client.getTask(config(), "x")
        assertEquals(SeedanceRemoteStatus.FAILED, resp.remoteStatus)
        assertEquals("SomeError", resp.error?.code)
        assertEquals("生成失败", resp.error?.message)
    }

    // ---- 错误分类 ----

    @Test
    fun error_sensitiveContent_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":"SensitiveContentError","message":"内容审核不通过"}}""")
        )
        assertEquals(SeedanceError.SENSITIVE_CONTENT, expectApiException().classification)
    }

    @Test
    fun error_quotaExceeded_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":"QuotaExceeded","message":"额度不足"}}""")
        )
        assertEquals(SeedanceError.QUOTA_EXCEEDED, expectApiException().classification)
    }

    @Test
    fun error_auth_isClassifiedOn401() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"InvalidApiKey","message":"bad key"}}""")
        )
        assertEquals(SeedanceError.AUTH, expectApiException().classification)
    }

    @Test
    fun error_429_isClassifiedTransient() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"code":"RateLimited"}}"""))
        assertEquals(SeedanceError.TRANSIENT_429_5XX, expectApiException().classification)
    }

    @Test
    fun error_500_isClassifiedTransient() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":{"code":"InternalError"}}"""))
        assertEquals(SeedanceError.TRANSIENT_429_5XX, expectApiException().classification)
    }

    @Test
    fun error_invalidParameter_isClassified() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":"InvalidParameter","message":"resolution 非法"}}""")
        )
        assertEquals(SeedanceError.INVALID_PARAMETER, expectApiException().classification)
    }

    @Test
    fun error_nonJsonBody_isTolerated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        assertEquals(SeedanceError.TRANSIENT_429_5XX, expectApiException().classification)
    }

    @Test
    fun error_requestIdCapturedFromHeaders() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setHeader("X-Request-Id", "req-err-1").setBody("""{"error":{}}""")
        )
        assertEquals("req-err-1", expectApiException().requestId)
    }

    // ---- 取消（已确认 DELETE） ----

    @Test
    fun cancelEndpointIsVerified() {
        assertTrue(CANCEL_ENDPOINT_VERIFIED)
    }

    @Test
    fun cancelQueuedTask_deletesById_andSynthesizesCancelled() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val resp = client.cancelQueuedTask(config(), "cgt-abc")

        assertEquals("cgt-abc", resp.id)
        assertEquals(SeedanceRemoteStatus.CANCELLED, resp.remoteStatus)

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/contents/generations/tasks/cgt-abc", recorded.path)
        assertEquals("Bearer $TEST_API_KEY", recorded.getHeader("Authorization"))
    }

    // ---- 取消传播与秘密脱敏 ----

    @Test
    fun cancellationPropagatesToCall() = runBlocking {
        server.enqueue(MockResponse().setBodyDelay(2, TimeUnit.SECONDS).setBody("""{"id":"x"}"""))
        var completed = false
        val job = launch {
            try {
                client.createTask(config(), request())
                completed = true
            } catch (e: CancellationException) {
                throw e
            }
        }
        delay(300)
        job.cancel()
        job.join()
        assertFalse("取消后 createTask 不得正常完成", completed)
    }

    @Test
    fun errorMessageNeverLeaksApiKeyOrBase64() = runBlocking {
        // 服务端在错误消息中恶意回显密钥与 base64，客户端消息必须剥离。
        val echoedSecret = "$TEST_API_KEY $characterBase64"
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":"InvalidParameter","message":"bad: $echoedSecret"}}"""
            )
        )
        val msg = expectApiException().message.orEmpty()
        assertFalse("异常消息不得泄漏 API Key", msg.contains(TEST_API_KEY))
        assertFalse("异常消息不得泄漏 base64", msg.contains(characterBase64))
        assertFalse("异常消息不得回显服务端原文", msg.contains(echoedSecret))
    }

    @Test
    fun requestBodyNeverContainsApiKey() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cgt-abc"}"""))
        client.createTask(config(), request(background = background))
        val body = server.takeRequest().body.readUtf8()
        assertFalse("请求体不得含 API Key", body.contains(TEST_API_KEY))
    }

    companion object {
        private const val TEST_API_KEY = "test-seedance-key-123"
        private const val characterBase64 = "aGVsbG8="
    }
}
