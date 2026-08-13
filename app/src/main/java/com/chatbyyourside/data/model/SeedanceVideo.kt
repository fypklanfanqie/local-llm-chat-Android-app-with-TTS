package com.chatbyyourside.data.model

/**
 * Seedance 视频任务的持久化状态机（Room `seedance_video.state` 列取值）。
 *
 * 每个状态以 [storageKey] 持久化；[fromStorageKey] 还原时对未知/空值保守回落 [DEFAULT]，
 * 避免历史脏值导致崩溃或误触发自动提交。
 *
 * 合法转换集中定义于 [com.chatbyyourside.video.canTransition]，此处只声明状态集合。
 * （领域数据类 `SeedanceVideo` 由 Task 2 引入，与 Room 映射一起落地。）
 */
enum class SeedanceVideoState(val storageKey: String) {
    /** 待复制角色图/背景图快照（outbox 落库初始态）。 */
    SNAPSHOT_PENDING("snapshot_pending"),
    /** 待生成视频提示词。 */
    PROMPT_PENDING("prompt_pending"),
    /** 提示词生成中。 */
    PROMPTING("prompting"),
    /** 待提交远端创建任务。 */
    SUBMISSION_PENDING("submission_pending"),
    /** 远端创建任务提交中。 */
    SUBMITTING("submitting"),
    /** 远端任务排队中。 */
    QUEUED("queued"),
    /** 远端任务生成中。 */
    RUNNING("running"),
    /** 已请求取消（仅 QUEUED 可发起），结果以服务端状态为准。 */
    CANCEL_REQUESTED("cancel_requested"),
    /** 待下载成品视频。 */
    DOWNLOAD_PENDING("download_pending"),
    /** 成品视频下载中。 */
    DOWNLOADING("downloading"),
    /** 终态：视频已下载校验并归档，可播放。 */
    READY("ready"),
    /** 终态：远端任务已取消。 */
    CANCELLED("cancelled"),
    /** 远端任务过期（需用户确认后重新提交）。 */
    EXPIRED("expired"),
    /** 快照复制失败（修复角色图后可手动重试）。 */
    FAILED_SNAPSHOT("failed_snapshot"),
    /** 提示词生成失败。 */
    FAILED_PROMPT("failed_prompt"),
    /** 当前模型/基地址配置与任务快照不一致，拒绝静默换模型。 */
    FAILED_PROMPT_CONFIG_CHANGED("failed_prompt_config_changed"),
    /** 提交失败或结果不确定（AMBIGUOUS_POST），绝不自动重发。 */
    FAILED_SUBMISSION("failed_submission"),
    /** 远端模型生成失败。 */
    FAILED_REMOTE("failed_remote"),
    /** 查询远端状态失败。 */
    FAILED_QUERY("failed_query"),
    /** 下载失败。 */
    FAILED_DOWNLOAD("failed_download");

    companion object {
        /**
         * 未知状态值保守回落 [FAILED_SUBMISSION]：既不冒充 [READY] 播放未校验文件，
         * 也不会被 Worker 自动认领而产生重复提交，等待用户确认后再行动。
         */
        val DEFAULT: SeedanceVideoState = FAILED_SUBMISSION

        /** 从存储键还原；未知/空值保守回落 [DEFAULT]。 */
        fun fromStorageKey(value: String?): SeedanceVideoState =
            entries.firstOrNull { it.storageKey == value } ?: DEFAULT
    }
}
