package com.chatbyyourside.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelPromptBuilder] 契约测试：续写提示词的关键约束。
 */
class NovelPromptBuilderTest {

    @Test
    fun system_containsStyleAndFormatContract() {
        val system = NovelPromptBuilder.buildSystem(
            background = "荒岛求生",
            characterSheets = listOf("苏晚：工作室主理"),
            protagonistName = "小满",
            protagonistPersona = "冷静",
        )
        assertTrue(system.contains("荒岛求生"))
        assertTrue(system.contains("苏晚：工作室主理"))
        assertTrue(system.contains("小满"))
        // 输出格式约束：名字：对白 / 旁白
        assertTrue(system.contains("旁白"))
        // 语言约束
        assertTrue(system.contains("简体中文"))
    }

    @Test
    fun user_containsChapterContext_andContinueInstruction() {
        val script = listOf(
            NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_NARRATION, "旁白", null, "开场"),
            NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_CHARACTER, "苏晚", "amiya", "你好"),
        )
        val user = NovelPromptBuilder.buildUser(
            chapterTitle = "第 1 话",
            summary = "坠机后的第一晚",
            opening = "旁白：夜幕降临",
            requirements = "以苏晚发现物资为发展",
            script = script,
        )
        assertTrue(user.contains("第 1 话"))
        assertTrue(user.contains("坠机后的第一晚"))
        assertTrue(user.contains("夜幕降临"))
        assertTrue(user.contains("以苏晚发现物资为发展"))
        assertTrue(user.contains("旁白：开场"))
        assertTrue(user.contains("苏晚：你好"))
        // 续写指令
        assertTrue(user.contains("续写"))
    }

    @Test
    fun user_trimsScriptWindow() {
        val many = (1..100).map { i ->
            NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_NARRATION, "旁白", null, "行$i")
        }
        val user = NovelPromptBuilder.buildUser("t", "", "", "", many)
        // 最近 80 行窗口：首行（行1）被裁掉，行21..100 保留
        assertFalse(user.contains("行1\n"))
        assertFalse(user.contains("行20"))
        assertTrue(user.contains("行100"))
    }

    // ===== 用户亲自发言后的「按这个角色推进剧情」约束 =====

    private fun directedLine(
        speakerType: String,
        speakerName: String = "苏晚",
        content: String = "我们分头找线索。",
        speakerRole: String? = null,
    ) = NovelPromptBuilder.DirectedLine(speakerType, speakerName, content, speakerRole)

    @Test
    fun user_withoutDirectedLine_hasNoAdvancementSection() {
        // 未传用户发言时：不出现「用户刚写下的一行」段，续写指令从新内容开始（老行为逐字节不变）
        val user = NovelPromptBuilder.buildUser("第 1 话", "", "", "", emptyList())
        assertFalse(user.contains("用户刚写下的一行"))
        assertTrue(user.contains("从新内容开始"))
    }

    @Test
    fun directedCharacterLine_requiresInCharacterAdvancement() {
        val user = NovelPromptBuilder.buildUser(
            chapterTitle = "第 1 话", summary = "", opening = "", requirements = "",
            script = listOf(NovelScriptParser.ScriptLine(NovelScriptParser.TYPE_CHARACTER, "苏晚", "senpai", "开场")),
            directed = directedLine(NovelScriptParser.TYPE_CHARACTER, speakerRole = "protagonist"),
        )
        assertTrue(user.contains("用户刚写下的一行"))
        assertTrue(user.contains("苏晚：我们分头找线索。"))
        // 贴人设推进：不得 OOC / 不得自相矛盾
        assertTrue(user.contains("严格贴合其人设"))
        assertTrue(user.contains("不得 OOC"))
        // 剧情要真的往前走，而不是原地重复
        assertTrue(user.contains("引出新的行动、信息或冲突"))
        // 主角定位：重心与视角围绕他
        assertTrue(user.contains("他是本作主角"))
        // 明确从哪一行之后接着写，避免复述用户那行
        assertTrue(user.contains("之后接着写"))
        assertTrue(user.contains("不要复述用户这一行"))
    }

    @Test
    fun directedSupportingLine_keepsSceneRestrained() {
        val user = NovelPromptBuilder.buildUser(
            "t", "", "", "", emptyList(),
            directed = directedLine(NovelScriptParser.TYPE_CHARACTER, speakerName = "阿橙", speakerRole = "supporting"),
        )
        assertTrue(user.contains("他是本作配角"))
        assertTrue(user.contains("戏份保持克制"))
        assertTrue(user.contains("让主线确实向前一步"))
    }

    @Test
    fun directedCharacterLine_withoutCastRole_omitsRoleHints() {
        val user = NovelPromptBuilder.buildUser(
            "t", "", "", "", emptyList(),
            directed = directedLine(NovelScriptParser.TYPE_CHARACTER, speakerRole = null),
        )
        assertFalse(user.contains("他是本作主角"))
        assertFalse(user.contains("他是本作配角"))
    }

    @Test
    fun directedNarration_andUserLine_haveDistinctSemantics() {
        val narration = NovelPromptBuilder.buildUser(
            "t", "", "", "", emptyList(),
            directed = directedLine(NovelScriptParser.TYPE_NARRATION, speakerName = "旁白", content = "灯突然灭了。"),
        )
        assertTrue(narration.contains("已经发生的事实"))

        val lead = NovelPromptBuilder.buildUser(
            "t", "", "", "", emptyList(),
            directed = directedLine(NovelScriptParser.TYPE_USER, speakerName = "小满", content = "我推开门。"),
        )
        assertTrue(lead.contains("主控（用户本人）"))
        assertTrue(lead.contains("各角色按各自人设回应"))
    }

    @Test
    fun directedBlankContentAppendsNothing() {
        val user = NovelPromptBuilder.buildUser(
            "t", "", "", "", emptyList(),
            directed = directedLine(NovelScriptParser.TYPE_CHARACTER, content = "   "),
        )
        assertFalse(user.contains("用户刚写下的一行"))
    }
}
