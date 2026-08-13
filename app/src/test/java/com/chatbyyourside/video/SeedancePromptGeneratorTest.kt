package com.chatbyyourside.video

import com.chatbyyourside.data.model.ApiConfig
import com.chatbyyourside.data.model.SeedanceModelVariant
import com.chatbyyourside.data.model.SeedanceRatio
import com.chatbyyourside.data.model.SeedanceResolution
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Seedance 结构化视频提示词生成器测试（Task 4）。
 *
 * 用假 [SeedancePromptLlm]（零 HTTP）验证生成器自身契约：
 *  - 出站消息绝不含图片/base64；
 *  - `<think>` 被剥离后再解析；
 *  - 单一可见角色（系统指令强制，且不额外引入第二人）；
 *  - 可选场景：空白时省略、有值时透传；
 *  - technical / finalPrompt 均含模型型号/分辨率/画幅/时长/音频；
 *  - 围栏 JSON 与裸 JSON 均可解析；
 *  - 畸形 JSON 抛类型化异常且不重试（只调用一次）。
 */
class SeedancePromptGeneratorTest {

    /** 假 LLM：记录每次请求并返回脚本内容，便于断言调用次数与出站消息。 */
    private class FakeLlm(private val script: String) : SeedancePromptLlm {
        val calls = mutableListOf<SeedancePromptRequest>()

        override suspend fun complete(request: SeedancePromptRequest): String {
            calls += request
            return script
        }
    }

    private fun apiConfig() = ApiConfig(
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "test-key",
        model = "deepseek-chat",
    )

    private fun input(
        variant: SeedanceModelVariant = SeedanceModelVariant.STANDARD,
        resolution: SeedanceResolution = SeedanceResolution.P720,
        ratio: SeedanceRatio = SeedanceRatio.PORTRAIT,
        durationSeconds: Int = 5,
        sceneDescription: String = "海边日落",
    ) = SeedancePromptInput(
        characterName = "小明",
        characterRole = "邻家少年",
        characterSystemPrompt = "你是小明，一个温和的邻家少年。",
        userText = "你好呀，今天天气不错。",
        assistantText = "（小明抬头看向远方，微笑着说）是啊，适合散步。",
        sceneDescription = sceneDescription,
        variant = variant,
        resolution = resolution,
        ratio = ratio,
        durationSeconds = durationSeconds,
    )

    private fun fullDocumentJson(): String = """
        {
          "subject": "小明，一个身穿白衬衫的邻家少年",
          "action": "缓步走向海边，转头微笑",
          "environment": "黄昏海边，浪花轻拍",
          "camera": "中景跟拍，缓慢推近",
          "lighting": "暖金色夕阳",
          "audio": "海浪声与轻柔脚步声",
          "continuity": "白衬衫与发型全程一致",
          "technical": "占位",
          "finalPrompt": "占位"
        }
    """.trimIndent()

    private fun singleUserMessage(fake: FakeLlm): String =
        (fake.calls.single().messages[1].content as JsonPrimitive).content

    // ---- 出站消息不含图片/base64 ----

    @Test
    fun outgoingMessages_containNoImageOrBase64() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input())

        val all = fake.calls.single().messages.joinToString("\n") { msg ->
            when (val c = msg.content) {
                is JsonPrimitive -> c.content
                else -> c.toString()
            }
        }
        assertFalse("出站消息不应出现图片标记", all.contains("image_url", ignoreCase = true))
        assertFalse("出站消息不应出现 base64", all.contains("base64", ignoreCase = true))
        assertFalse("出站消息不应出现 data:image", all.contains("data:image", ignoreCase = true))
        assertFalse("出站消息不应出现 base64 数据头", all.contains("iVBOR", ignoreCase = true))
    }

    // ---- think 剥离 ----

    @Test
    fun thinkTagIsStrippedBeforeParsing() = runBlocking {
        val fake = FakeLlm("<think>我先分析一下人物与环境</think>\n" + fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertEquals("小明", doc.subject.substringBefore("，"))
    }

    // ---- 单一可见角色 ----

    @Test
    fun onlySingleCharacterIsReferenced() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input())

        val messages = fake.calls.single().messages
        val system = (messages[0].content as JsonPrimitive).content
        val user = (messages[1].content as JsonPrimitive).content
        assertTrue("系统指令应强制单一可见角色", system.contains("只有一个可见角色"))
        assertTrue("系统指令应禁止第二人", system.contains("第二人"))
        assertTrue("用户消息应提及当前角色", user.contains("小明"))
        assertFalse("用户消息不得额外引入其他角色名", user.contains("小红"))
    }

    // ---- 可选场景 ----

    @Test
    fun blankSceneIsOmittedFromUserMessage() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(sceneDescription = ""))
        assertFalse("空白场景不应出现「场景补充」", singleUserMessage(fake).contains("场景补充"))
    }

    @Test
    fun sceneDescriptionIsPassedThrough() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        SeedancePromptGenerator(fake).generate(apiConfig(), input(sceneDescription = "雨夜街头"))
        assertTrue("非空场景应透传", singleUserMessage(fake).contains("雨夜街头"))
    }

    // ---- technical / finalPrompt 技术参数 ----

    @Test
    fun technicalAndFinalPromptEncodeTechnicalConstraints() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(
            apiConfig(),
            input(
                variant = SeedanceModelVariant.FAST,
                resolution = SeedanceResolution.P720,
                ratio = SeedanceRatio.LANDSCAPE,
                durationSeconds = 8,
            ),
        )
        val bits = listOf(
            SeedanceModelVariant.FAST.modelId,
            "P720",
            "16:9",
            "8秒",
            "音频：开启",
        )
        for (bit in bits) {
            assertTrue("technical 应包含「$bit」", doc.technical.contains(bit))
            assertTrue("finalPrompt 应包含「$bit」", doc.finalPrompt.contains(bit))
        }
    }

    // ---- JSON 解析形态 ----

    @Test
    fun fencedJsonIsParsed() = runBlocking {
        val fake = FakeLlm("```json\n" + fullDocumentJson() + "\n```")
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertEquals("小明", doc.subject.substringBefore("，"))
    }

    @Test
    fun bareJsonIsParsed() = runBlocking {
        val fake = FakeLlm(fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertTrue("裸 JSON 应解析出主体", doc.subject.contains("小明"))
    }

    @Test
    fun leadingProseBeforeJsonIsTrimmed() = runBlocking {
        val fake = FakeLlm("好的，以下是你的视频提示词：\n" + fullDocumentJson())
        val doc = SeedancePromptGenerator(fake).generate(apiConfig(), input())
        assertTrue(doc.subject.contains("小明"))
    }

    // ---- 畸形 JSON：类型化失败且不重试 ----

    @Test
    fun malformedJsonThrowsTypedFailureWithoutRetry() = runBlocking {
        val fake = FakeLlm("好的，这是你的视频提示词：这不是 JSON")
        val generator = SeedancePromptGenerator(fake)
        try {
            generator.generate(apiConfig(), input())
            fail("解析失败应抛出 SeedancePromptParseException")
        } catch (e: SeedancePromptParseException) {
            // 预期
        }
        assertEquals("解析失败不应重试（只调用一次）", 1, fake.calls.size)
    }

    @Test
    fun emptyJsonObjectThrowsInsteadOfSucceeding() = runBlocking {
        val fake = FakeLlm("{}")
        val generator = SeedancePromptGenerator(fake)
        try {
            generator.generate(apiConfig(), input())
            fail("空 JSON 不应被当作成功提示词")
        } catch (e: SeedancePromptParseException) {
            // 预期
        }
        assertEquals(1, fake.calls.size)
    }
}
