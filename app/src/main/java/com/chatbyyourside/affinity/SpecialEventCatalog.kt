package com.chatbyyourside.affinity

import android.content.Context
import com.chatbyyourside.config.Characters
import com.chatbyyourside.data.model.Character
import com.chatbyyourside.data.model.SpecialEventScript
import kotlinx.serialization.json.Json

/**
 * 内置特殊邂逅目录。正式资源由 assets/content/special_events.json 提供（[SpecialEventCatalog] 泛化加载，
 * 大众版内置角色文案见该资产）；缺少条目时使用基于角色既有人设的离线保底场景，
 * 保证用户不会因内容文件损坏而无法进入已解锁事件。
 */
class SpecialEventCatalog(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    private val scripts: Map<String, SpecialEventScript> by lazy {
        runCatching {
            context.assets.open("content/special_events.json").bufferedReader().use { reader ->
                json.decodeFromString<List<SpecialEventScript>>(reader.readText())
                    .associateBy { keyOf(it.characterId, it.threshold) }
            }
        }.getOrDefault(emptyMap())
    }

    fun eventFor(characterId: String, threshold: Int): SpecialEventScript {
        return scripts[keyOf(characterId, threshold)] ?: fallbackFor(
            Characters.ALL[characterId] ?: Character(
                id = characterId,
                name = "角色",
                code = characterId,
                role = "聊天伙伴",
                race = "",
                systemPrompt = "以聊天伙伴的身份自然交谈。",
            ),
            threshold,
        )
    }

    fun hasCompleteOfficialCoverage(): Boolean =
        Characters.ALL.keys.all { id -> AFFINITY_EVENT_THRESHOLDS.all { threshold -> scripts.containsKey(keyOf(id, threshold)) } }

    fun missingOfficialKeys(): List<String> = Characters.ALL.keys.flatMap { id ->
        AFFINITY_EVENT_THRESHOLDS.filter { threshold -> !scripts.containsKey(keyOf(id, threshold)) }
            .map { threshold -> keyOf(id, threshold) }
    }

    private fun fallbackFor(character: Character, threshold: Int): SpecialEventScript {
        val stage = when (threshold) {
            50 -> "第一次把忙碌之外的时间留给彼此"
            100 -> "在平常日子里共同处理一件只属于你们的难题"
            150 -> "面对对方不愿轻易说出的旧事与选择"
            else -> "在日常相处中确认彼此会同行的约定"
        }
        val scene = "${character.role.ifBlank { "聊天伙伴" }}常待的地方，${character.name} 熟悉的一角。$stage。"
        return SpecialEventScript(
            characterId = character.id,
            threshold = threshold,
            title = "${character.name} · ${threshold}好感邂逅",
            scene = scene,
            opening = "能占用你一点时间吗？这件事……我想只和你说。",
            systemPrompt = character.systemPrompt + "\n\n【好感邂逅场景】\n$scene\n请由你主动开启对话，围绕这一场景与对方自然交流。保持角色人设，避免提及系统、好感度或游戏机制。",
            memorySummary = stage,
            toneTags = listOf("关系进展", "独处", character.role.ifBlank { "伙伴" }),
        )
    }

    companion object {
        fun keyOf(characterId: String, threshold: Int): String = "$characterId#$threshold"
    }
}
