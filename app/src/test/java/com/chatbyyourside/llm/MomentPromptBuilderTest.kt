package com.chatbyyourside.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MomentPromptBuilder] 契约测试：发圈/评论回复提示词的关键约束。
 */
class MomentPromptBuilderTest {

    @Test
    fun postUserMessage_containsJsonContract_andCaptionRules() {
        val msg = MomentPromptBuilder.buildPostUserMessage("苏晚", "用户：早上好", imageCount = 2)
        assertTrue(msg.contains("苏晚"))
        assertTrue(msg.contains("{\"caption\""))
        assertTrue(msg.contains("imagePrompt"))
        assertTrue(msg.contains("早上好"))
        // 带图要求 + 日系动画插画画风约束
        assertTrue(msg.contains("英文生图提示词"))
        assertTrue(msg.contains("日系动画插画"))
        assertTrue(msg.contains("不要写实照片"))
    }

    @Test
    fun imageGenMessage_animeIllustrationStyle() {
        val msg = MomentPromptBuilder.buildImageGenUserMessage("a sunny park", 1)
        assertTrue(msg.contains("日系动画插画"))
        assertTrue(msg.contains("赛璐璐上色"))
        assertTrue(msg.contains("不要写实照片"))
        assertTrue(msg.contains("a sunny park"))
    }

    @Test
    fun userPostCommentPrompt_directsSingleComment() {
        val msg = MomentPromptBuilder.buildUserPostCommentPrompt("苏晚", "今天好累", hasImages = true)
        assertTrue(msg.contains("苏晚发了一条朋友圈"))
        assertTrue(msg.contains("今天好累"))
        assertTrue(msg.contains("附了几张图片"))
        assertTrue(msg.contains("只输出评论正文"))
        assertFalse(msg.contains("附了几张照片"))
        val noImg = MomentPromptBuilder.buildUserPostCommentPrompt("苏晚", "今天好累", hasImages = false)
        assertFalse(noImg.contains("附了几张图片"))
    }

    @Test
    fun postUserMessage_zeroImages_requiresEmptyImagePrompt() {
        val msg = MomentPromptBuilder.buildPostUserMessage("阿橙", "", imageCount = 0)
        assertTrue(msg.contains("imagePrompt 填空字符串"))
        assertFalse(msg.contains("英文生图提示词"))
    }

    @Test
    fun postUserMessage_noMention_forbidsMentionAndUserContent() {
        val msg = MomentPromptBuilder.buildPostUserMessage("阿橙", "", imageCount = 0, mentionTarget = null)
        // 默认（未掷中 @）：不许 @ 任何人，且正文不得提到用户
        assertTrue(msg.contains("不要 @ 任何人"))
        assertTrue(msg.contains("不要提到用户"))
        assertTrue(msg.contains("日常分享"))
    }

    @Test
    fun postUserMessage_withMention_directsSingleMention() {
        val msg = MomentPromptBuilder.buildPostUserMessage("阿橙", "", imageCount = 0, mentionTarget = "凛")
        assertTrue(msg.contains("@凛"))
        assertTrue(msg.contains("只 @ 这一个"))
        assertFalse(msg.contains("不要 @ 任何人"))
    }

    @Test
    fun postSystemDirective_noMention_dailyToneOnly() {
        val directive = MomentPromptBuilder.buildPostSystemDirective(null)
        assertTrue(directive.contains("与用户无关"))
        assertTrue(directive.contains("不要提到用户"))
        assertFalse(directive.contains("[@ 好友]"))
    }

    @Test
    fun postSystemDirective_withMention_namesTarget() {
        val directive = MomentPromptBuilder.buildPostSystemDirective("小鹿")
        assertTrue(directive.contains("[@ 好友]"))
        assertTrue(directive.contains("@小鹿"))
        assertTrue(directive.contains("与用户无关"))
    }

    @Test
    fun imageGenMessage_mentionsCountAndReference() {
        val msg = MomentPromptBuilder.buildImageGenUserMessage("a sunny park", 2)
        assertTrue(msg.contains("2 张"))
        assertTrue(msg.contains("参考图"))
        assertTrue(msg.contains("a sunny park"))
    }

    @Test
    fun replyPrompt_directsFirstPersonReply() {
        val msg = MomentPromptBuilder.buildReplyPrompt("今天好累", "辛苦了")
        assertTrue(msg.contains("今天好累"))
        assertTrue(msg.contains("辛苦了"))
        assertTrue(msg.contains("只输出回复正文"))
    }

    @Test
    fun replyPromptForUserPost_notesImages() {
        val withImg = MomentPromptBuilder.buildReplyPromptForUserPost("晒猫", hasImages = true, commentContent = "好可爱")
        assertTrue(withImg.contains("附了几张照片"))
        val noImg = MomentPromptBuilder.buildReplyPromptForUserPost("晒猫", hasImages = false, commentContent = "好可爱")
        assertFalse(noImg.contains("附了几张照片"))
    }
}
