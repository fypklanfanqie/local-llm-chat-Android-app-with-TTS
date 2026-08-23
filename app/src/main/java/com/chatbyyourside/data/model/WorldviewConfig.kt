package com.chatbyyourside.data.model

import kotlinx.serialization.Serializable

/**
 * 世界观注入的目标类型：一一对应绑定单个目标。
 */
@Serializable
enum class WorldviewTargetType {
    /** 绑定某角色的个人聊天（targetId = 角色 id） */
    CHARACTER,

    /** 绑定某个群聊（targetId = 群会话 id 的字符串形式） */
    GROUP,
}

/**
 * 用户自定义世界观：一段注入 system prompt 的叙事设定，与单个目标（角色或群聊）一一对应。
 * 持久化于 SettingsStore 键 `worldviews`（JSON 数组，仿 custom_characters 模式）。
 *
 * 同一目标允许多条并存，注入时按数组顺序拼接。
 */
@Serializable
data class WorldviewConfig(
    val id: String,                     // "wv-" + 时间戳生成
    val name: String,                   // 条目名（如「末世框架」）
    val content: String,                // 世界观正文
    val targetType: WorldviewTargetType,
    val targetId: String,               // CHARACTER: 角色 id；GROUP: 群会话 id 字符串
)

/**
 * 把命中目标的世界观拼成注入 system prompt 的指令块；列表为空返回空串（调用方跳过注入）。
 *
 * 三处注入口径统一（仿 [UserProfileConfig.toDirectiveText] 先例）：
 * - 个人聊天：ChatViewModel.sendMessage 的 system 拼接处
 * - 群聊：GroupChatPromptBuilder.buildSystemPrompt
 * - 主动问候：GreetingWorker.generateGreeting
 *
 * 格式对齐项目既有【特殊邂逅背景】的中文标记风格。
 */
fun buildWorldviewDirective(worldviews: List<WorldviewConfig>): String {
    if (worldviews.isEmpty()) return ""
    return buildString {
        worldviews.forEach { wv ->
            append("\n【世界观】").append(wv.name.trim()).append("\n")
            append(wv.content.trim()).append("\n")
        }
        append("请始终遵循以上世界观设定进行对话。")
    }
}
