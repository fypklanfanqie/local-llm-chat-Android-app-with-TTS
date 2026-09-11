package com.chatbyyourside.data.repository

import com.chatbyyourside.data.local.LocalInferenceSettings
import com.chatbyyourside.data.local.SettingsStore
import com.chatbyyourside.data.model.ApiConfig
import com.chatbyyourside.data.model.CachedModelList
import com.chatbyyourside.data.model.Character
import com.chatbyyourside.data.model.ChatProviderType
import com.chatbyyourside.data.model.CloudProfile
import com.chatbyyourside.data.model.GroupChatConfig
import com.chatbyyourside.data.model.Lorebook
import com.chatbyyourside.data.model.LorebookGlobalConfig
import com.chatbyyourside.data.model.MomentAutoConfig
import com.chatbyyourside.data.model.MomentImageGenConfig
import com.chatbyyourside.data.model.TokenUsageEntry
import com.chatbyyourside.data.model.TokenUsageSnapshot
import com.chatbyyourside.data.model.SeedanceConfig
import com.chatbyyourside.data.model.UserProfileConfig
import com.chatbyyourside.data.model.WorldviewConfig
import com.chatbyyourside.data.model.SystemVoiceTemplate
import com.chatbyyourside.data.model.ThemeMode
import com.chatbyyourside.data.model.TtsConfig
import com.chatbyyourside.data.model.TtsEngine
import com.chatbyyourside.data.model.TtsLanguage
import com.chatbyyourside.data.model.VoicePair
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.config.Characters
import com.chatbyyourside.llm.backend.BackendPreference
import com.chatbyyourside.llm.profile.InferencePerformanceMode
import com.chatbyyourside.llm.thinking.LocalThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 设置仓库
 * 封装 SettingsStore，提供同步获取当前值的便捷方法
 */
class SettingsRepository(private val store: SettingsStore) {

    /** 主题模式（默认跟随系统）。 */
    val themeMode: Flow<ThemeMode> = store.themeMode

    val apiConfig: Flow<ApiConfig> = store.apiConfig
    /** Seedance 视频生成配置（聚合快照）。 */
    val seedanceConfig: Flow<SeedanceConfig> = store.seedanceConfig
    val ttsConfig: Flow<TtsConfig> = store.ttsConfig
    val ttsLanguage: Flow<TtsLanguage> = store.ttsLanguage
    val ttsVolume: Flow<Int> = store.ttsVolume
    val ttsVoiceMap: Flow<Map<String, VoicePair>> = store.ttsVoiceMap
    /** 朗读引擎（system=手机自带，默认；cloud=云端火山豆包）。 */
    val ttsEngine: Flow<TtsEngine> = store.ttsEngine
    /** 系统引擎声音模板。 */
    val ttsSystemTemplate: Flow<SystemVoiceTemplate> = store.ttsSystemTemplate
    /** 自动朗读新回复开关（默认关）。 */
    val ttsAutoRead: Flow<Boolean> = store.ttsAutoRead
    val activeCharacter: Flow<String> = store.activeCharacter
    val customCharacters: Flow<List<Character>> = store.customCharacters

    // ===== 世界观设定 =====
    val worldviews: Flow<List<WorldviewConfig>> = store.worldviews

    suspend fun updateWorldviews(transform: (List<WorldviewConfig>) -> List<WorldviewConfig>) =
        store.updateWorldviews(transform)

    // ===== 世界书（Lorebook）=====
    val lorebooks: Flow<List<Lorebook>> = store.lorebooks

    suspend fun updateLorebooks(transform: (List<Lorebook>) -> List<Lorebook>) =
        store.updateLorebooks(transform)

    val lorebookConfig: Flow<LorebookGlobalConfig> = store.lorebookConfig

    suspend fun updateLorebookConfig(transform: (LorebookGlobalConfig) -> LorebookGlobalConfig) =
        store.updateLorebookConfig(transform)

    val volume: Flow<Int> = store.volume
    val musicFavorites: Flow<Set<String>> = store.musicFavorites
    val musicRepeatMode: Flow<Int> = store.musicRepeatMode
    /** 随机播放开关（音乐页）。 */
    val musicShuffle: Flow<Boolean> = store.musicShuffle
    val activeProvider: Flow<ChatProviderType> = store.activeProvider
    val activeLocalModelId: Flow<String?> = store.activeLocalModelId
    val llmContextLen: Flow<Int> = store.llmContextLen
    val llmThreads: Flow<Int> = store.llmThreads
    val llmTemperature: Flow<Float> = store.llmTemperature
    val llmMaxTokens: Flow<Int> = store.llmMaxTokens
    val llmBackend: Flow<BackendPreference> = store.llmBackend
    /** 推理性能模式（默认 BALANCED）。 */
    val llmPerformanceMode: Flow<InferencePerformanceMode> = store.llmPerformanceMode
    /** 本地推理设置不可变快照（Task 6）：一次读取全部本地 LLM 参数。 */
    val localInferenceSettings: Flow<LocalInferenceSettings> = store.localInferenceSettings
    /** legacy：CPU 提频开关（Task 6 起不再权威，高级诊断视图仍可改）。 */
    val llmCpuBoost: Flow<Boolean> = store.llmCpuBoost
    /** legacy：CPU lookahead 投机解码开关（默认关，Task 6 起不再权威）。仅 MNN CPU 后端生效。 */
    val llmLookahead: Flow<Boolean> = store.llmLookahead
    /** 深度思考模式开关（本地 + 云端通用）。 */
    val deepThinking: Flow<Boolean> = store.deepThinking
    /** 云端单次回复上限（max_tokens）；null=未设置 → 请求不携带。仅云端聊天读取。 */
    val cloudMaxTokens: Flow<Int?> = store.cloudMaxTokens
    /** 云端聊天温度；null=未设置 → 请求不携带。仅云端聊天读取。 */
    val cloudTemperature: Flow<Float?> = store.cloudTemperature
    /** 滚动摘要折叠间隔（回合数）；null=未设置 → 读侧回落默认 50 并钳位到合法区间。 */
    val cloudFoldIntervalRounds: Flow<Int> = store.cloudFoldIntervalRounds.map { stored ->
        stored ?: AppConfig.ContextCompression.DEFAULT_FOLD_INTERVAL_ROUNDS
    }.map { it.coerceIn(AppConfig.ContextCompression.MIN_FOLD_INTERVAL_ROUNDS, AppConfig.ContextCompression.MAX_FOLD_INTERVAL_ROUNDS) }
    /** 本地思考档位（默认 AUTO，仅本地生效）；云端不读取。 */
    val localThinkingLevel: Flow<LocalThinkingLevel> = store.localThinkingLevel
    /** 性能浮窗液态玻璃开关（默认开）。 */
    val liquidGlass: Flow<Boolean> = store.liquidGlass

    // ===== 使用指南 =====
    /** 是否已完成首次阅读水平选择。 */
    val guideSetupDone: Flow<Boolean> = store.guideSetupDone
    /** 阅读水平原始串（"BEGINNER"/"EXPERIENCED"/"" 未选）。 */
    val guideLevel: Flow<String> = store.guideLevel
    /** 推理参数是否相对上次成功加载已变更（供设置页展示"将自动重载"横幅）*/
    val llmConfigChanged: Flow<Boolean> = store.llmConfigChanged

    // ===== 角色问候（角色主动消息，仅云端可用）=====
    /** 角色问候开关。 */
    val greetingEnabled: Flow<Boolean> = store.greetingEnabled
    /** 主动发消息的角色 id 集合（可多选）。 */
    val greetingCharacterIds: Flow<Set<String>> = store.greetingCharacterIds
    /** 每天主动消息条数。 */
    val greetingDailyCount: Flow<Int> = store.greetingDailyCount
    /** 当日配额（日期 -> 已发条数）。 */
    val greetingQuota: Flow<Pair<String, Int>> = store.greetingQuota
    /** 上次发问候的角色 id（跨天也连续轮询）。 */
    val greetingLastCharId: Flow<String?> = store.greetingLastCharId
    /** 下一次问候投递目标时间（epoch ms；0 = 尚未初始化）。 */
    val greetingNextFireAt: Flow<Long> = store.greetingNextFireAt

    // ===== 群聊（仅云端可用）=====
    /** 群聊配置聚合快照（开关/成员/自动聊天）。 */
    val groupChatConfig: Flow<GroupChatConfig> = store.groupChatConfig
    /** 每日自动聊天轮次上限。 */
    val groupDailyRounds: Flow<Int> = store.groupDailyRounds
    /** 当日轮次配额（日期 -> 已执行轮次）。 */
    val groupQuota: Flow<Pair<String, Int>> = store.groupQuota
    /** 上次发言的成员 id（跨天连续轮询）。 */
    val groupLastSpeakerId: Flow<String?> = store.groupLastSpeakerId
    /** 已执行轮次累计计数。 */
    val groupRoundCounter: Flow<Long> = store.groupRoundCounter
    /** 用户最近一次群聊发言时间（epoch ms）。 */
    val groupLastUserMessageAt: Flow<Long> = store.groupLastUserMessageAt
    /** 下一次自动聊天触发目标时间（epoch ms；0 = 尚未初始化）。 */
    val groupNextFireAt: Flow<Long> = store.groupNextFireAt

    // ===== 我的形象（我的形象）=====
    /** 我的形象聚合（昵称/头像路径/人设/关系）。 */
    val userProfile: Flow<UserProfileConfig> = store.userProfile

    // ===== 朋友圈 =====
    /** 朋友圈生图 API 配置（OpenAI 聊天格式兼容，与主 LLM 分离）。 */
    val momentImageGenConfig: Flow<MomentImageGenConfig> = store.momentImageGenConfig
    suspend fun getMomentImageGenConfigNow(): MomentImageGenConfig = store.getMomentImageGenConfigNow()
    suspend fun setMomentImageGenConfig(config: MomentImageGenConfig) = store.setMomentImageGenConfig(config)

    /** 朋友圈封面图路径（空=默认渐变）。 */
    val momentCoverPath: Flow<String> = store.momentCoverPath
    suspend fun setMomentCoverPath(path: String?) = store.setMomentCoverPath(path)

    /** 生图总开关（默认开）；关闭后发圈一律纯文字。 */
    val momentImageGenEnabled: Flow<Boolean> = store.momentImageGenEnabled
    suspend fun getMomentImageGenEnabledNow(): Boolean = store.getMomentImageGenEnabledNow()
    suspend fun setMomentImageGenEnabled(enabled: Boolean) = store.setMomentImageGenEnabled(enabled)

    /** 自动发圈配置（开关/间隔/角色集）。 */
    val momentAutoConfig: Flow<MomentAutoConfig> = store.momentAutoConfig
    suspend fun getMomentAutoConfigNow(): MomentAutoConfig = store.getMomentAutoConfigNow()
    suspend fun setMomentAutoConfig(config: MomentAutoConfig) = store.setMomentAutoConfig(config)

    /** 下一次自动发圈目标时间（epoch ms；0 = 尚未初始化）。 */
    val momentNextFireAt: Flow<Long> = store.momentNextFireAt
    suspend fun getMomentNextFireAtNow(): Long = store.getMomentNextFireAtNow()
    suspend fun setMomentNextFireAt(epochMs: Long) = store.setMomentNextFireAt(epochMs)

    /** 上次自动发圈的角色 id（轮换用）。 */
    suspend fun getMomentLastCharIdNow(): String? = store.getMomentLastCharIdNow()
    suspend fun setMomentLastCharId(id: String?) = store.setMomentLastCharId(id)

    /** 互动角色（用户发朋友圈后随机评论/点赞的候选集，可搜索多选）。 */
    val momentReplyCharacterIds: Flow<Set<String>> = store.momentReplyCharacterIds
    suspend fun getMomentReplyCharacterIdsNow(): Set<String> =
        withTimeoutOrNull(DATASTORE_TIMEOUT_MS) { momentReplyCharacterIds.first() } ?: emptySet()
    suspend fun setMomentReplyCharacterIds(ids: Set<String>) = store.setMomentReplyCharacterIds(ids)

    /**
     * 云端 API 是否就绪（配置过 key；内置免费代理端点无需 key）。
     * 朋友圈/问候/群聊等云端辅助功能据此判断，与聊天 Provider 切换（本地/云端）解耦——
     * 用户聊天用本地模型时，这些功能仍直接调用已配置的云端 LLM。
     */
    suspend fun isCloudApiReady(): Boolean {
        val cfg = getApiConfigNow()
        return cfg.apiKey.isNotBlank() || com.chatbyyourside.config.isFreeProxyBaseUrl(cfg.baseUrl)
    }

    // ===== Token 用量（按角色累计云端输入/输出 token）=====
    /** 全角色 Token 用量快照（设置页「Token 用量」图表与数字）。 */
    val tokenUsage: Flow<TokenUsageSnapshot> = store.tokenUsage
    suspend fun getTokenUsageNow(): TokenUsageSnapshot =
        withTimeoutOrNull(DATASTORE_TIMEOUT_MS) { tokenUsage.first() } ?: TokenUsageSnapshot()

    /**
     * 累计一次云端调用的 token 用量到 [characterId] 名下（原子读改写；空角色/零用量忽略）。
     * 归属口径：1:1 聊天、主动问候、群聊发言、朋友圈文案与评论回复。
     */
    suspend fun recordTokenUsage(
        characterId: String?,
        promptTokens: Int,
        completionTokens: Int,
        cachedTokens: Int = 0,
    ) {
        val id = characterId?.trim().takeUnless { it.isNullOrBlank() } ?: return
        if (promptTokens <= 0 && completionTokens <= 0) return
        store.updateTokenUsage { snapshot ->
            val entry = snapshot.chars[id] ?: TokenUsageEntry()
            snapshot.copy(
                chars = snapshot.chars + (
                    id to entry.copy(
                        promptTokens = entry.promptTokens + promptTokens,
                        completionTokens = entry.completionTokens + completionTokens,
                        calls = entry.calls + 1,
                        cachedTokens = entry.cachedTokens + cachedTokens,
                    )
                ),
            )
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.setThemeMode(mode)

    suspend fun setApiConfig(config: ApiConfig) = store.setApiConfig(config)

    /** 每供应商配置记忆 map（设置页切换/恢复用；请求仍读活跃 apiConfig）。 */
    val apiConfigMap: Flow<Map<String, ApiConfig>> = store.apiConfigMap

    /** 同步读取每供应商配置 map（5s 超时回退空 map，国产 ROM 文件 I/O 被拦截时设置页不卡死）。 */
    suspend fun getApiConfigMapNow(): Map<String, ApiConfig> = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        apiConfigMap.first()
    } ?: emptyMap()

    suspend fun setApiConfigFor(providerKey: String, config: ApiConfig) =
        store.setApiConfigFor(providerKey, config)
    suspend fun ensureApiConfigFor(providerKey: String, config: ApiConfig) =
        store.ensureApiConfigFor(providerKey, config)
    /** 原子双写：更新活跃 api_config 并写入该供应商记忆（设置页保存按钮调用）。 */
    suspend fun saveApiConfig(providerKey: String, config: ApiConfig) =
        store.saveApiConfig(providerKey, config)

    // ===== 自定义云端 LLM 配置档（多份保存 + 切换）=====

    val cloudProfiles: Flow<List<CloudProfile>> = store.cloudProfiles
    val activeCloudProfileId: Flow<String> = store.activeCloudProfileId

    suspend fun getCloudProfilesNow(): List<CloudProfile> = dataStoreFirst(cloudProfiles, emptyList())

    /** 当前生效配置档（未选 / 已被删除 → null）。 */
    suspend fun getActiveCloudProfileNow(): CloudProfile? {
        val id = dataStoreFirst(activeCloudProfileId, "")
        if (id.isBlank()) return null
        return getCloudProfilesNow().firstOrNull { it.id == id }
    }

    /**
     * 保存配置档并立即生效为当前档：id 为空则分配新 id（即「另存为新配置」），
     * 非空则覆盖同 id 的旧档（即「更新当前配置」）。
     *
     * 同时把该档写入活跃 [ApiConfig]：用户点保存的语义就是「以后用这份」，
     * 分两步（先落档再切换）会让 UI 出现「保存了但没生效」的错觉。
     */
    suspend fun saveCloudProfile(profile: CloudProfile): CloudProfile {
        val id = profile.id.ifBlank { "cp-" + java.util.UUID.randomUUID().toString().take(8) }
        val saved = profile.copy(id = id, baseUrl = profile.baseUrl.trim(), model = profile.model.trim())
        val list = getCloudProfilesNow().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = saved else list.add(saved)
        store.setCloudProfiles(list)
        store.setActiveCloudProfile(id)
        saveApiConfig(PROVIDER_KEY_CUSTOM, ApiConfig(saved.baseUrl, saved.apiKey, saved.model))
        return saved
    }

    /** 删除配置档；删的正好是当前档时把 active 清空（UI 自行决定是否回落到其它档）。 */
    suspend fun deleteCloudProfile(id: String) {
        store.setCloudProfiles(getCloudProfilesNow().filterNot { it.id == id })
        if (dataStoreFirst(activeCloudProfileId, "") == id) store.setActiveCloudProfile("")
    }

    /** 切换当前配置档并立即生效（聊天下一轮就用新端点）。不存在则返回 null、不改动任何状态。 */
    suspend fun activateCloudProfile(id: String): CloudProfile? {
        val profile = getCloudProfilesNow().firstOrNull { it.id == id } ?: return null
        store.setActiveCloudProfile(id)
        saveApiConfig(PROVIDER_KEY_CUSTOM, ApiConfig(profile.baseUrl, profile.apiKey, profile.model))
        return profile
    }

    // ===== 远程模型清单（清单不写死：从服务商 /models 拉取并缓存）=====

    val modelListCache: Flow<Map<String, CachedModelList>> = store.modelListCache

    suspend fun getModelListCacheNow(): Map<String, CachedModelList> =
        dataStoreFirst(modelListCache, emptyMap())

    /** 某服务商/配置档的上次拉取结果（从未拉取 → null）。 */
    suspend fun getCachedModelList(cacheKey: String): CachedModelList? = getModelListCacheNow()[cacheKey]

    /**
     * 写入某服务商的模型清单缓存。
     *
     * 网络调用按项目既有分层由 UI 层走 `container.directLlmClient.listModels(...)`（与「测试连接」
     * 同一惯例），仓储只负责落库与读取，避免数据层持有 HTTP 客户端。
     */
    suspend fun saveModelListCache(cacheKey: String, models: List<String>) {
        store.putModelListCache(cacheKey, CachedModelList(System.currentTimeMillis(), models))
    }
    suspend fun setSeedanceConfig(config: SeedanceConfig) = store.setSeedanceConfig(config)
    suspend fun setTtsConfig(config: TtsConfig) = store.setTtsConfig(config)
    suspend fun setTtsLanguage(lang: TtsLanguage) = store.setTtsLanguage(lang)
    suspend fun setTtsVolume(vol: Int) = store.setTtsVolume(vol)
    suspend fun setTtsVoiceMap(map: Map<String, VoicePair>) = store.setTtsVoiceMap(map)
    suspend fun setTtsEngine(engine: TtsEngine) = store.setTtsEngine(engine)
    suspend fun setTtsSystemTemplate(template: SystemVoiceTemplate) = store.setTtsSystemTemplate(template)
    suspend fun setTtsAutoRead(enabled: Boolean) = store.setTtsAutoRead(enabled)
    suspend fun setActiveCharacter(id: String) = store.setActiveCharacter(id)
    val activeConversations: Flow<Map<String, Long>> = store.activeConversations
    suspend fun setActiveConversation(characterId: String, conversationId: Long) =
        store.setActiveConversation(characterId, conversationId)
    suspend fun clearActiveConversation(characterId: String) = store.clearActiveConversation(characterId)
    suspend fun clearAllActiveConversations() = store.clearAllActiveConversations()
    suspend fun getActiveConversationNow(characterId: String): Long? =
        withTimeoutOrNull(DATASTORE_TIMEOUT_MS) { activeConversations.first() }?.get(characterId)
    suspend fun setCustomCharacters(list: List<Character>) = store.setCustomCharacters(list)
    suspend fun updateCustomCharacters(transform: (List<Character>) -> List<Character>) =
        store.updateCustomCharacters(transform)
    suspend fun setVolume(vol: Int) = store.setVolume(vol)
    suspend fun toggleMusicFavorite(key: String) = store.toggleMusicFavorite(key)
    suspend fun setMusicRepeatMode(mode: Int) = store.setMusicRepeatMode(mode)
    suspend fun setMusicShuffle(enabled: Boolean) = store.setMusicShuffle(enabled)
    suspend fun setActiveProvider(type: ChatProviderType) = store.setActiveProvider(type)
    suspend fun setActiveLocalModelId(id: String?) = store.setActiveLocalModelId(id)
    suspend fun setLlmParams(
        contextLen: Int? = null,
        threads: Int? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
    ) = store.setLlmParams(contextLen, threads, temperature, maxTokens)

    suspend fun setLlmBackend(preference: BackendPreference) = store.setLlmBackend(preference)

    suspend fun setLlmPerformanceMode(mode: InferencePerformanceMode) =
        store.setLlmPerformanceMode(mode)

    /** 同步读取本地推理设置快照；DataStore I/O 被拦截时超时回退不可变默认快照。 */
    suspend fun getLocalInferenceSettingsNow(timeoutMs: Long = DATASTORE_TIMEOUT_MS): LocalInferenceSettings =
        withTimeoutOrNull(timeoutMs) { localInferenceSettings.first() } ?: LocalInferenceSettings()

    suspend fun setLlmCpuBoost(enabled: Boolean) = store.setLlmCpuBoost(enabled)

    suspend fun setLlmLookahead(enabled: Boolean) = store.setLlmLookahead(enabled)

    suspend fun setDeepThinking(enabled: Boolean) = store.setDeepThinking(enabled)

    suspend fun setLocalThinkingLevel(level: LocalThinkingLevel) = store.setLocalThinkingLevel(level)

    suspend fun setLiquidGlass(enabled: Boolean) = store.setLiquidGlass(enabled)

    suspend fun setGuideSetupDone(done: Boolean) = store.setGuideSetupDone(done)
    suspend fun setGuideLevel(level: String) = store.setGuideLevel(level)

    suspend fun setGreetingEnabled(enabled: Boolean) = store.setGreetingEnabled(enabled)
    suspend fun setGreetingCharacterIds(ids: Set<String>) = store.setGreetingCharacterIds(ids)
    suspend fun setGreetingDailyCount(count: Int) = store.setGreetingDailyCount(count)
    suspend fun setGreetingQuota(date: String, count: Int) = store.setGreetingQuota(date, count)
    suspend fun setGreetingLastCharId(id: String?) = store.setGreetingLastCharId(id)
    suspend fun setGreetingNextFireAt(epochMs: Long) = store.setGreetingNextFireAt(epochMs)

    suspend fun setGroupChatConfig(config: GroupChatConfig) = store.setGroupChatConfig(config)
    suspend fun updateGroupChatConfig(transform: (GroupChatConfig) -> GroupChatConfig) =
        store.updateGroupChatConfig(transform)
    suspend fun setGroupDailyRounds(count: Int) = store.setGroupDailyRounds(count)
    suspend fun setGroupQuota(date: String, count: Int) = store.setGroupQuota(date, count)
    suspend fun setGroupLastSpeakerId(id: String?) = store.setGroupLastSpeakerId(id)
    suspend fun setGroupRoundCounter(counter: Long) = store.setGroupRoundCounter(counter)
    suspend fun setGroupLastUserMessageAt(epochMs: Long) = store.setGroupLastUserMessageAt(epochMs)
    suspend fun setGroupNextFireAt(epochMs: Long) = store.setGroupNextFireAt(epochMs)

    suspend fun setUserProfileConfig(config: UserProfileConfig) = store.setUserProfileConfig(config)

    /** 一次成功推理后写回本次生效的用户配置，使 [llmConfigChanged] 归 false */
    suspend fun acknowledgeLlmConfig(
        threads: Int, contextLen: Int, backend: BackendPreference, lookahead: Boolean, temperature: Float,
    ) = store.acknowledgeLlmConfig(threads, contextLen, backend, lookahead, temperature)

    /** 最近一次成功加载实际应用的 plan 配置哈希（Task 7）。 */
    val llmLastConfigHash: Flow<String?> = store.llmLastConfigHash

    suspend fun setLlmLastConfigHash(hash: String?) = store.setLlmLastConfigHash(hash)

    /** 同步获取当前 API 配置（阻塞读取 Flow 首值，5s 超时返回默认配置）。
     *  国产 ROM（MIUI/EMUI/ColorOS）的电池优化可能拦截 DataStore 文件 I/O 导致 .first() 永久挂起；
     *  withTimeoutOrNull 保证 UI 不卡死。 */
    suspend fun getApiConfigNow(): ApiConfig = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        apiConfig.first()
    } ?: ApiConfig(baseUrl = "", apiKey = "", model = "")

    suspend fun getTtsConfigNow(): TtsConfig = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        ttsConfig.first()
    } ?: TtsConfig(apiKey = "", appId = "", accessKey = "")

    /** 同步获取当前 Seedance 配置（5s 超时回退默认配置，保证国产 ROM 文件 I/O 被拦截时 UI 不卡死）。 */
    suspend fun getSeedanceConfigNow(): SeedanceConfig = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        seedanceConfig.first()
    } ?: SeedanceConfig()

    suspend fun getTtsLanguageNow(): TtsLanguage = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        ttsLanguage.first()
    } ?: TtsLanguage.ZH

    suspend fun getTtsEngineNow(): TtsEngine = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        ttsEngine.first()
    } ?: TtsEngine.DEFAULT

    suspend fun getTtsSystemTemplateNow(): SystemVoiceTemplate = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        ttsSystemTemplate.first()
    } ?: SystemVoiceTemplate.DEFAULT_TEMPLATE

    suspend fun getTtsAutoReadNow(): Boolean = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        ttsAutoRead.first()
    } ?: false

    suspend fun getTtsVoiceMapNow(): Map<String, VoicePair> = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        ttsVoiceMap.first()
    } ?: emptyMap()

    suspend fun getActiveProviderNow(): ChatProviderType = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        activeProvider.first()
    } ?: ChatProviderType.CLOUD

    suspend fun getActiveLocalModelIdNow(): String? = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        activeLocalModelId.first()
    }  // 超时返回 null（无模型），上游 LocalChatProvider 会抛出「未选择模型」

    suspend fun getDeepThinkingNow(): Boolean = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        deepThinking.first()
    } ?: false

    suspend fun getCloudMaxTokensNow(): Int? = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        cloudMaxTokens.first()
    }

    suspend fun getCloudTemperatureNow(): Float? = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        cloudTemperature.first()
    }

    suspend fun setCloudMaxTokens(value: Int?) = store.setCloudMaxTokens(value)

    suspend fun setCloudTemperature(value: Float?) = store.setCloudTemperature(value)

    // ===== 小说「发送后自动续写」开关（默认关 = 原逻辑：发送只追加该行，不触发生成）=====

    /** 开关流（默认 false）。开启后用户选角色发言会在落库后立刻让 AI 顺着这一行推进。 */
    val novelAutoContinue: Flow<Boolean> = store.novelAutoContinue

    suspend fun setNovelAutoContinue(enabled: Boolean) = store.setNovelAutoContinue(enabled)

    suspend fun getCloudFoldIntervalRoundsNow(): Int = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        cloudFoldIntervalRounds.first()
    } ?: AppConfig.ContextCompression.DEFAULT_FOLD_INTERVAL_ROUNDS

    suspend fun setCloudFoldIntervalRounds(rounds: Int) =
        store.setCloudFoldIntervalRounds(
            rounds.coerceIn(AppConfig.ContextCompression.MIN_FOLD_INTERVAL_ROUNDS, AppConfig.ContextCompression.MAX_FOLD_INTERVAL_ROUNDS),
        )

    /** 同步获取活跃角色（5s 超时回退默认角色），供 CharacterRepository 使用 */
    suspend fun getActiveCharacterNow(): String = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        activeCharacter.first()
    } ?: Characters.DEFAULT_CHARACTER_ID

    /** 同步获取自定义角色（5s 超时返回空列表，等同无自定义角色） */
    suspend fun getCustomCharactersNow(): List<Character> = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        customCharacters.first()
    } ?: emptyList()

    /** 同步获取世界观（5s 超时返回空列表，等同无世界观注入），供 Worker / 发送路径使用 */
    suspend fun getWorldviewsNow(): List<WorldviewConfig> = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        worldviews.first()
    } ?: emptyList()

    /** 同步获取全部世界书（5s 超时返回空列表，等同无世界书注入），供发送路径 / Worker 使用 */
    suspend fun getLorebooksNow(): List<Lorebook> = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        lorebooks.first()
    } ?: emptyList()

    /** 同步获取世界书全局参数（5s 超时回退默认值）。 */
    suspend fun getLorebookConfigNow(): LorebookGlobalConfig = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        lorebookConfig.first()
    } ?: LorebookGlobalConfig()

    // ===== 角色问候同步读取（供 GreetingWorker 用）=====
    /** 已开启?（超时返回 null 而非 false——Worker 据此区分「明确关闭」与「暂时读不到」）。 */
    suspend fun getGreetingEnabledOrNull(): Boolean? = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        greetingEnabled.first()
    }

    suspend fun getGreetingEnabledNow(): Boolean = getGreetingEnabledOrNull() ?: false

    /** 已选角色集合?（超时返回 null 而非空集，避免 Worker 误判「未选角色」）。 */
    suspend fun getGreetingCharacterIdsOrNull(): Set<String>? = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        greetingCharacterIds.first()
    }

    suspend fun getGreetingCharacterIdsNow(): Set<String> = getGreetingCharacterIdsOrNull() ?: emptySet()

    suspend fun getGreetingDailyCountNow(): Int = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        greetingDailyCount.first()
    } ?: AppConfig.Greeting.DEFAULT_DAILY_COUNT

    suspend fun getGreetingQuotaNow(): Pair<String, Int> = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        greetingQuota.first()
    } ?: "" to 0

    /** 上次发问候的角色 id（超时返回 null，Worker 退化为随机起点）。 */
    suspend fun getLastGreetingCharIdNow(): String? = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        greetingLastCharId.first()
    }

    /** 下一次问候投递目标时间（epoch ms；0 = 尚未初始化，超时回退 0）。 */
    suspend fun getGreetingNextFireAtNow(): Long = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        greetingNextFireAt.first()
    } ?: 0L

    // ===== 群聊同步读取（供 GroupChatWorker / GroupChatScheduler 用）=====
    /** 群聊配置?（超时返回 null 而非默认值，Worker 据此区分「关闭」与「暂时读不到」）。 */
    suspend fun getGroupChatConfigOrNull(): GroupChatConfig? = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        groupChatConfig.first()
    }

    suspend fun getGroupChatConfigNow(): GroupChatConfig = getGroupChatConfigOrNull() ?: GroupChatConfig()

    suspend fun getGroupDailyRoundsNow(): Int = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        groupDailyRounds.first()
    } ?: AppConfig.GroupChat.DEFAULT_DAILY_ROUNDS

    suspend fun getGroupQuotaNow(): Pair<String, Int> = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        groupQuota.first()
    } ?: "" to 0

    suspend fun getGroupLastSpeakerIdNow(): String? = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        groupLastSpeakerId.first()
    }

    suspend fun getGroupRoundCounterNow(): Long = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        groupRoundCounter.first()
    } ?: 0L

    suspend fun getGroupLastUserMessageAtNow(): Long = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        groupLastUserMessageAt.first()
    } ?: 0L

    suspend fun getGroupNextFireAtNow(): Long = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        groupNextFireAt.first()
    } ?: 0L

    /** 我的形象（超时回退默认空档案）。 */
    suspend fun getUserProfileNow(): UserProfileConfig = withTimeoutOrNull(DATASTORE_TIMEOUT_MS) {
        userProfile.first()
    } ?: UserProfileConfig()

    /**
     * DataStore 单值读取统一入口（超时 + 空值兜底）。
     * 国产 ROM 文件 I/O 被拦截时避免永久挂起；[fallback] 为超时/无值时的返回值。
     */
    private suspend fun <T> dataStoreFirst(flow: Flow<T>, fallback: T): T =
        withTimeoutOrNull(DATASTORE_TIMEOUT_MS) { flow.first() } ?: fallback

    companion object {
        /** DataStore .first() 超时阈值（ms）。国产 ROM 文件 I/O 被拦截时避免永久挂起。 */
        private const val DATASTORE_TIMEOUT_MS = 5000L

        /** 自定义服务商在每供应商配置 map 里的固定槽位键（切自定义配置档时同步写入）。 */
        const val PROVIDER_KEY_CUSTOM = "custom"

        /**
         * 模型清单缓存键：预设服务商按 id 缓存；自定义端点按**配置档 id** 分开缓存
         * （不同中转站的模型清单完全不同，共用 key 会互相覆盖）。
         */
        fun modelCacheKey(providerId: String?, profileId: String? = null): String =
            if (providerId.isNullOrBlank()) "custom:${profileId.orEmpty()}" else "preset:$providerId"
    }
}
