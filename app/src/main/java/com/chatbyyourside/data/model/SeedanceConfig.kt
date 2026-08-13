package com.chatbyyourside.data.model

/**
 * Seedance 视频生成模型型号。
 *
 * 支持中国火山方舟 Seedance 2.0 系列与 1.5 Pro；`modelId` 直接作为创建任务请求的 `model` 字段值：
 *  - [STANDARD]：Seedance 2.0 标准模型，支持全部分辨率；
 *  - [FAST]：Seedance 2.0 快速模型，仅支持 480p/720p；
 *  - [SEEDANCE_1_5_PRO]：Seedance 1.5 Pro，时长 4–12 秒，仅支持首帧（first_frame）参考图。
 *
 * 持久化用 [storageKey]（= modelId）；[fromStorageKey] 还原时对未知/空值保守回落 [DEFAULT]。
 */
enum class SeedanceModelVariant(val modelId: String) {
    STANDARD("doubao-seedance-2-0-260128"),
    FAST("doubao-seedance-2-0-fast-260128"),
    SEEDANCE_1_5_PRO("doubao-seedance-1-5-pro-251215");

    /** 该模型支持的最短视频时长（秒）；各模型均从 4 秒起。 */
    val minDurationSeconds: Int get() = 4

    /** 该模型支持的最长视频时长（秒）：2.0 系列 15 秒，1.5 Pro 12 秒（官方 [4,12]）。 */
    val maxDurationSeconds: Int get() = when (this) {
        SEEDANCE_1_5_PRO -> 12
        STANDARD, FAST -> 15
    }

    /** 该模型支持的分辨率档位：Fast 仅 480p/720p；标准与 1.5 Pro 支持全部。 */
    val supportedResolutions: Set<SeedanceResolution> get() = when (this) {
        FAST -> setOf(SeedanceResolution.P480, SeedanceResolution.P720)
        STANDARD, SEEDANCE_1_5_PRO -> SeedanceResolution.entries.toSet()
    }

    /** 参考图在 content 中的 role：1.5 Pro 仅支持 `first_frame`（首帧单图）；2.0 系列用 `reference_image`。 */
    val referenceImageRole: String get() = when (this) {
        SEEDANCE_1_5_PRO -> "first_frame"
        STANDARD, FAST -> "reference_image"
    }

    /** 是否支持第二张背景参考图：1.5 Pro 首帧模式仅单图，背景不发送（场景描述仍写入提示词）。 */
    val supportsBackgroundReference: Boolean get() = when (this) {
        SEEDANCE_1_5_PRO -> false
        STANDARD, FAST -> true
    }

    /** Room 持久化键 = modelId（请求原值，稳定且唯一）。 */
    val storageKey: String get() = modelId

    companion object {
        val DEFAULT: SeedanceModelVariant = STANDARD

        /** 从存储键还原；未知/空值保守回落 [DEFAULT]（SeedanceConfig 默认档位）。 */
        fun fromStorageKey(value: String?): SeedanceModelVariant =
            entries.firstOrNull { it.modelId == value } ?: DEFAULT
    }
}

/**
 * 视频分辨率。标准模型支持全部档位，Fast 模型仅支持 [P480]/[P720]。
 *
 * 持久化用 [storageKey]（= 枚举名，如 "P720"）；[fromStorageKey] 对未知/空值保守回落 [DEFAULT]。
 */
enum class SeedanceResolution {
    P480, P720, P1080, P4K;

    /** Room 持久化键 = 枚举名（P480/P720/P1080/P4K）。 */
    val storageKey: String get() = name

    companion object {
        val DEFAULT: SeedanceResolution = P720

        /** 从存储键还原；未知/空值保守回落 [DEFAULT]（SeedanceConfig 默认档位）。 */
        fun fromStorageKey(value: String?): SeedanceResolution =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * 视频画幅比例，`apiValue` 为创建任务请求中使用的字符串。
 *
 * 持久化用 [storageKey]（= apiValue）；[fromStorageKey] 对未知/空值保守回落 [DEFAULT]。
 */
enum class SeedanceRatio(val apiValue: String) {
    /** 竖屏 9:16（默认）。 */
    PORTRAIT("9:16"),
    /** 横屏 16:9。 */
    LANDSCAPE("16:9"),
    /** 方形 1:1。 */
    SQUARE("1:1"),
    /** 经典竖屏 3:4。 */
    PORTRAIT_CLASSIC("3:4"),
    /** 经典横屏 4:3。 */
    LANDSCAPE_CLASSIC("4:3"),
    /** 超宽屏 21:9。 */
    ULTRAWIDE("21:9"),
    /** 由模型自适应画幅。 */
    ADAPTIVE("adaptive");

    /** Room 持久化键 = apiValue（"9:16" 等请求原值）。 */
    val storageKey: String get() = apiValue

    companion object {
        val DEFAULT: SeedanceRatio = PORTRAIT

        /** 从存储键还原；未知/空值保守回落 [DEFAULT]（SeedanceConfig 默认档位）。 */
        fun fromStorageKey(value: String?): SeedanceRatio =
            entries.firstOrNull { it.apiValue == value } ?: DEFAULT
    }
}

/**
 * Seedance 视频生成配置（DataStore 聚合持久化，Task 3 接入设置页）。
 *
 * - [generateAudio] 固定为 true，不可配置；
 * - 时长为 4–15 秒固定整数，默认 5 秒；
 * - 方舟基地址来自用户配置，默认中国官方地址；API Key 不得硬编码，也不进入 Room/日志；
 * - 人物图由角色立绘提供（调用方单独传入），背景图与场景描述可选。
 */
data class SeedanceConfig(
    val baseUrl: String = "https://ark.cn-beijing.volces.com/api/v3",
    val apiKey: String = "",
    val variant: SeedanceModelVariant = SeedanceModelVariant.STANDARD,
    val resolution: SeedanceResolution = SeedanceResolution.P720,
    val ratio: SeedanceRatio = SeedanceRatio.PORTRAIT,
    val durationSeconds: Int = 5,
    val watermark: Boolean = false,
    val backgroundImagePath: String? = null,
    val sceneDescription: String = "",
) {
    /** 固定开启视频语音（Seedance 2.0 生成音频不可关闭）。 */
    val generateAudio: Boolean get() = true
}
