package com.chatbyyourside.data.remote

import android.util.Log
import com.chatbyyourside.data.model.SeedanceConfig
import com.chatbyyourside.data.model.SeedanceModelVariant
import com.chatbyyourside.data.model.SeedanceRatio
import com.chatbyyourside.data.model.SeedanceResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * 取消端点是否已由官方文档复核。
 *
 * 已于实施时复核官方文档：取消排队任务为 `DELETE {baseUrl}/contents/generations/tasks/{id}`
 * （仅 queued 状态可取消，成功后无响应体；succeeded/failed/expired 为删除记录）。
 * 来源：火山方舟文档「取消或删除视频生成任务」(docs.volcengine.com/docs/82379/1521720) 及
 * volcengine Go/Python SDK 的 DeleteContentGenerationTask 操作。
 */
const val CANCEL_ENDPOINT_VERIFIED = true

/**
 * 预编码的参考图内容：调用方已读图并编码，客户端只负责拼接 data URL。
 *
 * @property mimeType 图片 MIME（如 "image/png"），不含 "data:" 前缀；
 * @property base64NoPrefix 无 "data:image/...;base64," 前缀的 base64 正文。
 */
data class SeedanceImageContent(
    val mimeType: String,
    val base64NoPrefix: String,
)

/**
 * 一次 Seedance 视频生成任务输入（客户端入参，非序列化 DTO）。
 *
 * 图片已预编码为 [SeedanceImageContent]；[character] 必填，[background] 可选。
 * [variant]/[resolution]/[ratio]/[durationSeconds]/[watermark] 为本次任务实际采用的生成参数。
 */
data class CreateSeedanceTask(
    val finalPrompt: String,
    val character: SeedanceImageContent,
    val background: SeedanceImageContent?,
    val variant: SeedanceModelVariant,
    val resolution: SeedanceResolution,
    val ratio: SeedanceRatio,
    val durationSeconds: Int,
    val watermark: Boolean,
)

/**
 * Seedance 2.0 视频生成任务客户端（火山方舟 contents/generations/tasks）。
 *
 * - [createTask]：`POST {baseUrl}/contents/generations/tasks`；
 * - [getTask]：`GET {baseUrl}/contents/generations/tasks/{id}`；
 * - [cancelQueuedTask]：`DELETE {baseUrl}/contents/generations/tasks/{id}`（仅 queued 可取消，见
 *   [CANCEL_ENDPOINT_VERIFIED]）。
 *
 * 与 [DirectLlmClient] 并行、独立：不复用其 buildEndpoint，也不经 ChatProvider 转发。
 * 使用构造注入的专用有限超时 OkHttp 客户端（不使用 RetrofitClient.streamingClient，后者无超时）。
 * 协程取消经 `invokeOnCompletion { call.cancel() }` 传播到底层 [okhttp3.Call]。
 */
@OptIn(ExperimentalSerializationApi::class) // explicitNulls=false：省略空字段，保持 content 项无 null 字段
class SeedanceClient(
    private val client: OkHttpClient,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    },
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 提交创建任务。请求体不含 fps/seed/camera；`generate_audio` 恒为 true。 */
    suspend fun createTask(config: SeedanceConfig, request: CreateSeedanceTask): SeedanceTaskResponse {
        val body = json.encodeToString(CreateSeedanceTaskRequest.serializer(), buildCreateRequest(config, request))
        val httpRequest = Request.Builder()
            .url(taskCollectionEndpoint(config.baseUrl))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return execute(httpRequest, taskId = null)
    }

    /** 查询任务状态/结果。 */
    suspend fun getTask(config: SeedanceConfig, taskId: String): SeedanceTaskResponse {
        val httpRequest = Request.Builder()
            .url(taskEndpoint(config.baseUrl, taskId))
            .header("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()
        return execute(httpRequest, taskId = taskId)
    }

    /**
     * 取消排队中的任务（官方 DELETE）。
     *
     * 仅远端状态为 queued 时才有取消语义；成功返回空体时合成 [SeedanceRemoteStatus.CANCELLED]。
     */
    suspend fun cancelQueuedTask(config: SeedanceConfig, taskId: String): SeedanceTaskResponse {
        val httpRequest = Request.Builder()
            .url(taskEndpoint(config.baseUrl, taskId))
            .header("Authorization", "Bearer ${config.apiKey}")
            .delete()
            .build()
        val parsed = execute(httpRequest, taskId = taskId)
        // 官方 DELETE 成功返回空体；合成取消结果（若服务端返回了任务对象则原样透传）。
        return if (parsed.id == null && parsed.status == null) {
            parsed.copy(id = taskId, status = SeedanceRemoteStatus.CANCELLED.storageKey)
        } else {
            parsed
        }
    }

    private suspend fun execute(request: Request, taskId: String?): SeedanceTaskResponse =
        withContext(Dispatchers.IO) {
            val call = client.newCall(request)
            // 复用 DirectLlmClient 的取消模式：在当前协程 Job 上注册 invokeOnCompletion，
            // 协程取消时关闭底层 Call；finally 兜底再 cancel，确保 Call 一定被释放。
            val handle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                call.execute().use { response ->
                    val requestId = extractRequestId(response)
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw buildApiError(response.code, raw, requestId, taskId, response.retryAfterMillis())
                    }
                    parseResponse(raw).copy(requestId = requestId)
                }
            } catch (e: IOException) {
                coroutineContext.ensureActive() // 被取消时抛 CancellationException，不误判为传输错误
                Log.w(TAG, "seedance network error taskId=$taskId")
                throw SeedanceApiException(
                    classification = SeedanceError.AMBIGUOUS_TRANSPORT,
                    message = "网络错误，无法确认任务状态：${e.message ?: "请求失败"}",
                    taskId = taskId,
                    cause = e,
                )
            } finally {
                handle?.dispose()
                call.cancel()
            }
        }

    private fun buildApiError(
        httpStatus: Int,
        raw: String,
        requestId: String?,
        taskId: String?,
        retryAfterMillis: Long?,
    ): SeedanceApiException {
        val (code, message) = parseErrorBody(raw)
        val classification = classifySeedanceError(httpStatus, code, message)
        // 仅记录非敏感元数据：任务 ID、HTTP 状态、request-id、分类后的错误码。
        Log.w(TAG, "seedance task error taskId=$taskId http=$httpStatus requestId=$requestId classification=$classification")
        return SeedanceApiException(
            classification = classification,
            message = humanReadableMessage(classification, httpStatus),
            httpStatus = httpStatus,
            remoteCode = code,
            requestId = requestId,
            taskId = taskId,
            retryAfterMillis = retryAfterMillis,
        )
    }

    /** 解析 Retry-After 头（秒 → 毫秒）；缺失或非数值时返回 null。 */
    private fun Response.retryAfterMillis(): Long? =
        header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1_000L }

    private fun buildCreateRequest(config: SeedanceConfig, request: CreateSeedanceTask): CreateSeedanceTaskRequest {
        val content = mutableListOf<SeedanceContentPart>(
            SeedanceContentPart(type = "text", text = request.finalPrompt),
            SeedanceContentPart(
                type = "image_url",
                role = "reference_image",
                imageUrl = SeedanceImageUrl(url = request.character.toDataUrl()),
            ),
        )
        request.background?.let {
            content += SeedanceContentPart(
                type = "image_url",
                role = "reference_image",
                imageUrl = SeedanceImageUrl(url = it.toDataUrl()),
            )
        }
        return CreateSeedanceTaskRequest(
            model = request.variant.modelId,
            content = content,
            resolution = request.resolution.apiValue(),
            ratio = request.ratio.apiValue,
            duration = request.durationSeconds,
            generateAudio = config.generateAudio,
            watermark = request.watermark,
        )
    }

    private fun parseResponse(raw: String): SeedanceTaskResponse {
        if (raw.isBlank()) return SeedanceTaskResponse()
        return try {
            json.decodeFromString(SeedanceTaskResponse.serializer(), raw)
        } catch (e: Exception) {
            // 非 JSON / 未知形状：保守返回空响应，不因字段缺失崩溃。
            SeedanceTaskResponse()
        }
    }

    /** 从错误体提取 (code, message)，非 JSON 或缺失时回落 null。 */
    private fun parseErrorBody(raw: String): Pair<String?, String?> {
        if (raw.isBlank()) return null to null
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val err = obj["error"]
            val code = when (err) {
                is JsonObject -> err["code"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
            val message = when (err) {
                is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
            (code ?: obj["code"]?.jsonPrimitive?.contentOrNull) to
                (message ?: obj["message"]?.jsonPrimitive?.contentOrNull)
        } catch (e: Exception) {
            null to null
        }
    }

    private fun extractRequestId(response: Response): String? =
        REQUEST_ID_HEADERS.firstNotNullOfOrNull { response.header(it) }

    private fun taskCollectionEndpoint(baseUrl: String): String =
        baseUrl.trimEnd('/') + "/contents/generations/tasks"

    private fun taskEndpoint(baseUrl: String, taskId: String): String =
        baseUrl.trimEnd('/') + "/contents/generations/tasks/" + taskId

    private fun SeedanceImageContent.toDataUrl(): String = "data:$mimeType;base64,$base64NoPrefix"

    private fun SeedanceResolution.apiValue(): String = when (this) {
        SeedanceResolution.P480 -> "480p"
        SeedanceResolution.P720 -> "720p"
        SeedanceResolution.P1080 -> "1080p"
        SeedanceResolution.P4K -> "4k"
    }

    /** 面向用户的中文可读文案，绝不携带 API Key / base64 / 签名 URL / 服务端原始消息。 */
    private fun humanReadableMessage(classification: SeedanceError, httpStatus: Int): String =
        when (classification) {
            SeedanceError.SENSITIVE_CONTENT -> "视频生成内容未通过审核，请修改角色或场景描述后重试"
            SeedanceError.QUOTA_EXCEEDED -> "额度不足或已达上限，请稍后重试"
            SeedanceError.AUTH -> "Seedance API Key 无效或未授权"
            SeedanceError.INVALID_PARAMETER -> "请求参数不合法，请调整生成设置"
            SeedanceError.TRANSIENT_429_5XX -> "视频服务暂时繁忙（HTTP $httpStatus），请稍后重试"
            SeedanceError.AMBIGUOUS_TRANSPORT -> "网络错误，无法确认任务状态"
            SeedanceError.OTHER -> "视频生成失败（HTTP $httpStatus）"
        }

    companion object {
        private const val TAG = "SeedanceClient"

        /** request-id 候选响应头（OkHttp header 匹配大小写不敏感）。 */
        private val REQUEST_ID_HEADERS = listOf("X-Request-Id", "Request-Id", "X-Tt-Logid", "x-trace-id")
    }
}
