package com.chatbyyourside.config

/**
 * 应用全局配置
 */
object AppConfig {
    // ===== TTS 代理 =====
    // 火山引擎 TTS 透明代理（CloudBase Web 函数），对齐网页版 workers/cloudbase-tts-fn。
    // 代理直接透传 header + body 到火山引擎 V3 API，不做请求格式转换。
    const val TTS_PROXY_URL = "https://lanfanqie-d8go1l51d56f44d20.service.tcloudbase.com/tts"

    // ===== 云端 AI 默认配置 =====
    const val DEFAULT_API_BASE = "https://api.deepseek.com/v1"
    const val DEFAULT_MODEL = "deepseek-chat"

    // ===== 资源 CDN（立绘/语音/BGM 公网地址）=====
    // 由于 Android 无微信云存储，需将 assets 上传至公网 CDN
    // 留空则使用本地 assets 回退
    const val ASSET_CDN_BASE = ""

    // ===== TTS =====
    const val TTS_DEFAULT_VOLUME = 85

    // ===== 本地 LLM 默认参数 =====
    object LLM {
        // 4096（非 2048）：现代小模型（Qwen2.5 / Gemma2 / Llama3.2 / SmolLM2 等）均支持 ≥8192，
        // 4096 通用安全且让长对话少丢上下文。设置页可调到 32768（受模型实际支持限制）。
        const val DEFAULT_CONTEXT_LEN = 4096
        const val DEFAULT_THREADS = 4
        // 0.8（非 0.9）：小模型角色扮演在高温下易「上头」，从单角色回复滑向编造多角色剧本并无限生成
        // （配合 LocalChatProvider 的 system prompt 输出规范 + onToken 剧本标记截断兜底）。0.8 保留角色
        // 语气多样性的同时显著降低跑偏概率。
        const val DEFAULT_TEMPERATURE = 0.8f
        // 输出不设硬性上限：默认生成到模型自然结束（EOS），不再截断在 1024。
        // MAX_TOKENS_UNLIMITED 是传给 native 的「实际上限」，远大于正常回复，仅作循环边界保护；
        // 真正的终止靠 EOS + onToken 多角色剧本截断兜底（防小模型「上头」无限生成，正常聊天不触发）。
        const val MAX_TOKENS_UNLIMITED = 65536
        const val DEFAULT_MAX_TOKENS = MAX_TOKENS_UNLIMITED
        const val DEFAULT_TOP_P = 0.9f
        // 1.2（非 1.1）：小模型无重复惩罚时会逐字复读角色卡循环；mixed_samplers 现已含 "penalty"
        // 生效（见 mnn_jni.cpp set_config）。1.1 偏弱压不住结构性复读，1.2 在 max_penalty=10 内安全。
        const val DEFAULT_REPEAT_PENALTY = 1.2f
    }

    // ===== 聊天历史 =====
    // 每个会话（conversation）最多保留的消息条数；超出按时间修剪最旧消息。
    const val MAX_HISTORY_PER_CONVERSATION = 100
    // 单次请求喂给模型上下文的最大消息条数（取该会话最近 N 条）。与历史上限一致，即发送全部历史，
    // 不再人为截断在 20 条；实际进入 KV cache 的内容由 contextLen 自然裁剪（超出部分由模型左截断）。
    const val MAX_CONTEXT_MESSAGES = 100

    // ===== 角色问候（角色主动消息）=====
    // 开启后，所选角色会在白天随机时间主动给用户发消息（早安/晚安/关心/开话题）。
    // 仅云端 AI 模式可用：消息由 DirectLlmClient.chatOnce 生成，符合角色人设。
    object Greeting {
        // 用户可设置的每天主动消息条数范围与默认值
        const val DEFAULT_DAILY_COUNT = 3
        const val MIN_DAILY_COUNT = 1
        const val MAX_DAILY_COUNT = 10
        // 仅在此时段内触发（避免深夜打扰）：08:00–23:00
        const val HOUR_START = 8
        const val HOUR_END = 23
        // 生成主动消息时带入的最近历史条数（让消息能衔接正在聊的话题）
        const val CONTEXT_MESSAGES = 6
        // 单次生成超时（秒）
        const val GENERATE_TIMEOUT_MS = 60_000L
        // 云 API 失败后重排的间隔（毫秒），避免 WorkManager retry 风暴
        const val RETRY_DELAY_MS = 45 * 60 * 1000L
    }
}
