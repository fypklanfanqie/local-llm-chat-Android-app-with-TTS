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
        assertEquals(
            "聊天记录_阿橙_行动_报告_20240820_033640",
            suggestedExportBaseName("阿橙", "行动:报告?", 1_724_096_200_000L),
        )
    }
}
