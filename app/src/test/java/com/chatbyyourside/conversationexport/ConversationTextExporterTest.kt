package com.chatbyyourside.conversationexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTextExporterTest {

    @Test
    fun renderIncludesEveryMessageInChronologicalOrder() {
        val document = ConversationExportDocument(
            title = "雨夜闲聊",
            ownerName = "阿橙",
            createdAt = 1_720_000_000_000L,
            exportedAt = 1_720_000_120_000L,
            messages = listOf(
                ConversationExportMessage(1_720_000_001_000L, "我", "今晚辛苦了。"),
                ConversationExportMessage(
                    1_720_000_002_000L,
                    "阿橙",
                    "你也请早点休息。",
                    listOf("附件：行动记录.pdf"),
                ),
            ),
        )

        val text = ConversationTextExporter.render(document)

        assertTrue(text.indexOf("我\n今晚辛苦了。") < text.indexOf("阿橙\n你也请早点休息。"))
        assertTrue(text.contains("附件：行动记录.pdf"))
        assertTrue(text.contains("会话：雨夜闲聊"))
    }

    @Test
    fun suggestedNameSanitizesProviderUnsafeCharacters() {
        // 期望值按运行时默认时区动态构造：suggestedExportBaseName 内部用
        // SimpleDateFormat（无显式 TimeZone），CI runner 为 UTC 而开发机为 +8，
        // 硬编码时刻字符串会随执行环境时区漂移导致 ComparisonFailure。
        val expected = "聊天记录_阿橙_行动_报告_" +
            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date(1_724_096_200_000L))
        assertEquals(
            expected,
            suggestedExportBaseName("阿橙", "行动:报告?", 1_724_096_200_000L),
        )
    }
}
