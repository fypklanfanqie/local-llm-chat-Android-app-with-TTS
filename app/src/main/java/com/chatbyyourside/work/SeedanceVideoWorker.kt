package com.chatbyyourside.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chatbyyourside.ChatApp
import com.chatbyyourside.video.PipelineOutcome

/**
 * Seedance 视频流水线 Worker。
 *
 * WorkData 仅携带 `localTaskId`（[SeedanceVideoScheduler.KEY_LOCAL_TASK_ID]），不落任何密钥/提示词/图片。
 * 所有路径都以 success 返回并自行按 [PipelineOutcome] 重排（有界退避/轮询），避免 WorkManager
 * 指数退避风暴；真正等待用户的失败态返回 success 后不再调度。
 *
 * 不创建通知渠道、不起前台服务；唯一工作名 `seedance-video-{localTaskId}` 由调度器统一管理。
 */
class SeedanceVideoWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(SeedanceVideoScheduler.KEY_LOCAL_TASK_ID, -1L)
        if (taskId <= 0) return Result.success()

        // Task 5 会话化：本 Worker 冷启动主进程时把会话标记为 background_worker（不覆盖已进入
        // 前台的 active 会话），防止「停在 application 阶段」被下一次前台启动误判为崩溃。
        try {
            com.chatbyyourside.util.CrashWatchdog.markSessionKind(
                applicationContext,
                com.chatbyyourside.util.CrashSessionClassifier.KIND_BACKGROUND_WORKER,
            )
        } catch (_: Throwable) {
        }

        val container = (applicationContext as ChatApp).container
        val outcome = container.seedancePipelineCoordinator.advance(taskId)
        return when (outcome) {
            is PipelineOutcome.Complete, is PipelineOutcome.WaitingForUser -> Result.success()
            is PipelineOutcome.Reschedule -> {
                container.seedanceVideoScheduler.enqueueDelayed(taskId, outcome.delayMillis)
                Result.success()
            }
        }
    }
}
