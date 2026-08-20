package com.chatbyyourside.data.model

/**
 * 我的形象（「设置 → 我的形象」）。
 *
 * - [avatarPath]：我的头像内部存储路径（经 [com.chatbyyourside.util.UserProfileImageStore] 落盘；空=未设置）。
 * - [persona]：用户人设（一段文本）。
 * - [relationship]：与角色之间的关系（全局一段文本）。
 *
 * [toDirectiveText] 把非空字段拼成注入 system prompt 的「用户信息」指令块：
 * 群聊（[com.chatbyyourside.ui.groupchat.GroupChatPromptBuilder]）、云端/本地 1:1
 * （[com.chatbyyourside.ui.chat.ChatViewModel]）、主动问候
 * （[com.chatbyyourside.work.GreetingWorker]）统一使用，保持身份描述口径一致。
 */
data class UserProfileConfig(
    val avatarPath: String = "",
    val persona: String = "",
    val relationship: String = "",
) {
    /**
     * 生成注入用指令块；全部字段为空时返回空串（调用方跳过注入）。
     * 纯函数，JVM 可测。
     */
    fun toDirectiveText(): String {
        if (persona.isBlank() && relationship.isBlank()) return ""
        return buildString {
            append("\n[用户信息] 用户是使用本应用与我聊天的人。")
            if (persona.isNotBlank()) append("人设：", persona.trim(), "。")
            if (relationship.isNotBlank()) append("用户与你的关系：", relationship.trim(), "。")
            append("请在对话中自然体现以上设定。")
        }
    }
}