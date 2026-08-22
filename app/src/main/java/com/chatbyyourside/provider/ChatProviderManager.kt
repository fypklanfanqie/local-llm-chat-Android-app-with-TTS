package com.chatbyyourside.provider

import com.chatbyyourside.data.model.ChatProviderType
import com.chatbyyourside.data.repository.SettingsRepository
import com.chatbyyourside.provider.cloud.CloudChatProvider
import com.chatbyyourside.provider.local.LocalChatProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Chat Provider 管理器
 *
 * 根据设置选择当前活跃的 Provider（云端 / 本地）。
 * 聊天页面通过此管理器获取 Provider，不直接接触具体实现。
 */
class ChatProviderManager(
    private val cloudProvider: CloudChatProvider,
    private val localProvider: LocalChatProvider,
    private val settings: SettingsRepository,
    /**
     * Provider 切换回调（审计 llm-backend-6 接线）：参数 = 切换后是否仍为本地。
     * false（本地→云端）：经 [ModelResidencyController] 宽限后释放已加载模型；
     * true（云端→本地）：恢复驻留资格，取消任何在途宽限释放。
     */
    private val onSwitchAwayFromLocal: (staysLocal: Boolean) -> Unit = {},
) {

    val activeProviderType: Flow<ChatProviderType> = settings.activeProvider

    /** 获取当前活跃 Provider */
    suspend fun getActiveProvider(): ChatProvider {
        return when (settings.getActiveProviderNow()) {
            ChatProviderType.LOCAL -> localProvider
            ChatProviderType.CLOUD -> cloudProvider
        }
    }

    /** 切换 Provider 类型 */
    suspend fun switchProvider(type: ChatProviderType) {
        val previous = settings.getActiveProviderNow()
        settings.setActiveProvider(type)
        // 切离本地（切到云端）后本地模型不应继续驻留（数 GB 权重/KV 常驻浪费内存）。
        // 经驻留控制器走宽限释放（审计 llm-backend-6 接线）：宽限内切回本地/回前台自动取消，
        // 宽限期满或生成中由 BackendManager 的 deferred-safe 机制安全释放。
        // 同时同步「切回本地」方向，恢复驻留资格（否则切回后 canReside 恒 false、后台即释放）。
        if (previous != type) {
            onSwitchAwayFromLocal(type == ChatProviderType.LOCAL)
        }
    }

    /** 取消所有 Provider 的当前推理 */
    fun cancelAll() {
        cloudProvider.cancel()
        localProvider.cancel()
    }
}
