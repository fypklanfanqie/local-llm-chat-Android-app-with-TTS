package com.chatbyyourside

import android.content.Context
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.local.AppDatabase
import com.chatbyyourside.data.local.SettingsStore
import com.chatbyyourside.data.remote.DirectLlmClient
import com.chatbyyourside.data.remote.RetrofitClient
import com.chatbyyourside.data.repository.AssetRepository
import com.chatbyyourside.data.repository.CharacterRepository
import com.chatbyyourside.data.repository.ChatBackgroundRepository
import com.chatbyyourside.data.repository.ChatRepository
import com.chatbyyourside.data.repository.ConversationRepository
import com.chatbyyourside.data.repository.DocumentRepository
import com.chatbyyourside.data.repository.MusicLibraryRepository
import com.chatbyyourside.data.repository.SettingsRepository
import com.chatbyyourside.download.DownloadManager
import com.chatbyyourside.llm.CpuBoostController
import com.chatbyyourside.llm.backend.BackendHealthCoordinator
import com.chatbyyourside.llm.backend.BackendHealthStore
import com.chatbyyourside.llm.backend.BackendManager
import com.chatbyyourside.llm.backend.OpenClProbeRunner
import com.chatbyyourside.llm.benchmark.DefaultLocalInferenceBenchmarkRunner
import com.chatbyyourside.llm.benchmark.InferenceCertificationStore
import com.chatbyyourside.manager.AudioManager
import com.chatbyyourside.manager.ModelManager
import com.chatbyyourside.manager.TtsManager
import com.chatbyyourside.perfmon.PerformanceCollector
import com.chatbyyourside.provider.ChatProviderManager
import com.chatbyyourside.provider.cloud.CloudChatProvider
import com.chatbyyourside.provider.local.LocalChatProvider
import com.chatbyyourside.tts.VolcTtsClient

/**
 * 手动 DI 容器
 * 集中管理所有单例依赖
 */
class AppContainer(private val context: Context) {

    // ===== 本地存储 =====
    val settingsStore: SettingsStore by lazy { SettingsStore(context) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(context) }

    // ===== 仓库 =====
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(settingsStore) }
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(database.conversationDao()) }
    val assetRepository: AssetRepository by lazy { AssetRepository(context) }
    val documentRepository: DocumentRepository by lazy { DocumentRepository(directLlmClient) }
    val characterRepository: CharacterRepository by lazy { CharacterRepository(settingsRepository) }

    // 通讯界面背景：内置 PRTS 轮播 + 用户自定义图片（最多 20 张，复制到内部存储）。
    val chatBackgroundRepository: ChatBackgroundRepository by lazy {
        ChatBackgroundRepository(context, assetRepository, settingsStore)
    }

    // 音乐库：本地导入 + 在线添加曲目的持久化播放列表（文件拷贝到内部存储）。
    val musicLibrary: MusicLibraryRepository by lazy {
        MusicLibraryRepository(context, settingsStore)
    }

    // ===== 网络 API =====
    /** 直连对话商 OpenAI 兼容 API 客户端（云端对话/翻译/文档提取，不经代理） */
    val directLlmClient: DirectLlmClient by lazy { DirectLlmClient(RetrofitClient.streamingClient) }

    // ===== TTS =====
    val ttsClient: VolcTtsClient by lazy { VolcTtsClient(AppConfig.TTS_PROXY_URL, RetrofitClient.okHttpClient) }
    val ttsManager: TtsManager by lazy { TtsManager(context, ttsClient, settingsRepository) }

    // ===== 音频 =====
    val audioManager: AudioManager by lazy { AudioManager(context, settingsRepository) }

    // ===== 本地 LLM =====
    // CPU 提频控制器（非 root：PerformanceHintManager hint session + 推理线程高优先级；
    // SustainedPerformanceMode 在 MainActivity 窗口级开启）。enabled 由 LocalChatProvider 同步设置开关，
    // 在 MnnBackend.generateStreamMessages 内包住 nativeGenerateStream 生效。
    val cpuBoostController: CpuBoostController by lazy { CpuBoostController(context) }

    // Task 7：后端健康记录存储（与协调器共享同一实例；设置页「清除后端健康记录」整体重置）。
    val backendHealthStore: BackendHealthStore by lazy { BackendHealthStore(context) }

    // Task 7：推理选项认证存储（lookahead/步进基准证据）。LocalChatProvider 每轮按
    // device+model+variant+native 组合查证；设置页「运行基准并认证」落盘 /「清除实验认证」重置。
    val inferenceCertificationStore: InferenceCertificationStore by lazy {
        InferenceCertificationStore(context)
    }

    // Task 7：本地推理基准运行器（认证闭环用）。热检查由自建 ThermalMonitor 采样驱动
    // （API 29+/PowerManager 缺席时为 no-op，热守卫不拒绝——见 runner KDoc）。
    val benchmarkRunner: DefaultLocalInferenceBenchmarkRunner by lazy {
        DefaultLocalInferenceBenchmarkRunner(context, backendManager, settingsRepository)
    }

    // Task 3：后端健康协调器（OpenCL 探测/健康记录单点）。BackendManager 与 LocalChatProvider 共享
    // 同一实例，避免两套状态。健康键设备指纹 = healthDeviceFingerprintOf（Build/OS/SoC/ABI + 策略，
    // 不含 native 身份——native 重建不改变健康键，旧构建的失败教训仍适用于新构建，final review I2）。
    // 认证键设备指纹用 deviceFingerprintOf（含 native 身份，另经 certKey 显式 native 分量绑定）。
    // modelFingerprint 由调用方按当前模型逐轮传入（模型切换即新键）。
    val backendHealthCoordinator: BackendHealthCoordinator by lazy {
        BackendHealthCoordinator(
            store = backendHealthStore,
            deviceFingerprint = BackendHealthCoordinator.healthDeviceFingerprintOf(),
            probeRunner = OpenClProbeRunner.real(context),
        )
    }

    // 推理后端管理器：MNN CPU / OpenCL GPU / QNN NPU，按偏好选择并支持回退链
    val backendManager: BackendManager by lazy {
        BackendManager(context, cpuBoostController, backendHealthCoordinator)
    }

    // ===== Chat Provider =====
    val cloudChatProvider: CloudChatProvider by lazy {
        CloudChatProvider(directLlmClient, settingsRepository)
    }
    val localChatProvider: LocalChatProvider by lazy {
        LocalChatProvider(
            context,
            backendManager,
            settingsRepository,
            cpuBoostController,
            backendHealthCoordinator,
            inferenceCertificationStore,
        )
    }
    val chatProviderManager: ChatProviderManager by lazy {
        ChatProviderManager(cloudChatProvider, localChatProvider, settingsRepository)
    }

    // ===== 性能监控浮窗（仅本地聊天界面显示；应用内液态玻璃，见 PerformanceGlassOverlay）=====
    // collector 复用 localChatProvider 已封装的 native 频率/温度读取。浮窗在 ChatScreen 内组合，
    // 直接读 settingsRepository.liquidGlass 获取玻璃开关，无需此处缓存/推送。
    val performanceCollector: PerformanceCollector by lazy {
        PerformanceCollector(context, localChatProvider)
    }

    // ===== 模型管理 =====
    val downloadManager: DownloadManager by lazy { DownloadManager(context) }
    val modelManager: ModelManager by lazy {
        ModelManager(context, downloadManager, settingsRepository, backendManager)
    }
}
