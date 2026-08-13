package com.chatbyyourside.data.model

/**
 * Seedance 2.0 视频生成模型型号。
 *
 * 仅支持中国火山方舟 Seedance 2.0；`modelId` 直接作为创建任务请求的 `model` 字段值：
 *  - [STANDARD]：标准模型，支持全部分辨率；
 *  - [FAST]：快速模型，仅支持 480p/720p（见 [com.chatbyyourside.video.validateSeedanceRequest]）。
 */
enum class SeedanceModelVariant(val modelId: String) {
    STANDARD("doubao-seedance-2-0-260128"),
    FAST("doubao-seedance-2-0-fast-260128"),
}

/**
 * 视频分辨率。标准模型支持全部档位，Fast 模型仅支持 [P480]/[P720]。
 */
enum class SeedanceResolution { P480, P720, P1080, P4K }

/**
 * 视频画幅比例，`apiValue` 为创建任务请求中使用的字符串。
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
    ADAPTIVE("adaptive"),
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
