package com.chatbyyourside.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.chatbyyourside.data.model.DisplayMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ChatMessageList] 滚动行为 instrumentation 测试（Task 3）。
 *
 * 覆盖「用户接管优先」验收：
 * 1. 长深度思考流式消息自动跟随其**底部**（无「回到底部」按钮）；
 * 2. 用户下滑（浏览上方历史、离开底部）后出现「回到底部」按钮；
 * 3. 后续流式内容增长不把用户拉回底部（按钮保持）；
 * 4. 点击按钮精确回到底部并恢复跟随（按钮消失）。
 *
 * 需在 API 34 设备/模拟器运行：`./gradlew connectedDebugAndroidTest`。
 */
@RunWith(AndroidJUnit4::class)
class ChatMessageListScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private fun streamingMsg(content: String) = DisplayMessage(
        id = "streaming",
        role = "streaming",
        content = content,
        segments = emptyList(),
        sender = "AI",
        isStreaming = true,
    )

    private fun uiState(content: String) = ChatUiState(
        characterName = "AI",
        messages = listOf(streamingMsg(content)),
        isStreaming = true,
        activeConversationId = 1L,
    )

    @Test
    fun deepThinkingStream_followsBottom_thenUserCanScrollAwayAndReturn() {
        var state by mutableStateOf(uiState("开始"))
        rule.setContent {
            ChatMessageList(state = state, onTts = {}, modifier = Modifier)
        }

        // 1. 长深度思考内容：自动跟随底部 -> 无回到底部按钮
        state = uiState("深度思考" + "，内容".repeat(300))
        rule.waitForIdle()
        rule.onNodeWithContentDescription("回到底部").assertDoesNotExist()

        // 2. 用户下滑浏览上方历史（离开底部）-> 按钮出现
        rule.onNodeWithTag(CHAT_LIST_TAG).performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("回到底部").assertExists()

        // 3. 后续流式内容增长，跟随保持暂停（按钮仍在，不把用户拉回底部）
        state = uiState("深度思考" + "，内容".repeat(300) + "，更多更多更多")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("回到底部").assertExists()

        // 4. 点击回到底部 -> 恢复跟随，按钮消失（已定位到真实底部）
        rule.onNodeWithContentDescription("回到底部").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("回到底部").assertDoesNotExist()
    }
}
