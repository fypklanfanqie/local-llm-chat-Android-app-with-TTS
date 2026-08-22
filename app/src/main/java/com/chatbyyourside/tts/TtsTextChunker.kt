package com.chatbyyourside.tts

/**
 * TTS 长文本切分（纯函数，JVM 可测）。
 *
 * 系统 TTS 引擎有 [android.speech.tts.TextToSpeech.getMaxSpeechInputLength] 上限（通常 4000 字符，
 * 超限静默失败）；火山云端单次合成也有保守长度上限。长回复按句末标点贪心打包成 ≤ maxLen 的段：
 * 优先在句号/问号等处断开，整段无句界时硬切，保证任何输入都能产出合法分段。
 */
private val SENTENCE_END_CHARS = charArrayOf('。', '！', '？', '!', '?', '；', ';', '\n')

internal fun chunkTtsText(text: String, maxLen: Int): List<String> {
    if (maxLen <= 0 || text.length <= maxLen) return listOf(text)

    val chunks = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        val remaining = text.length - start
        if (remaining <= maxLen) {
            chunks.add(text.substring(start))
            break
        }
        val window = text.substring(start, start + maxLen)
        // 贪心：在窗口内找最后一个句末标点，连同标点一起断句（朗读停顿更自然）
        val lastSentenceEnd = window.lastIndexOfAny(SENTENCE_END_CHARS)
        val splitAt = if (lastSentenceEnd >= 0) start + lastSentenceEnd + 1 else start + maxLen
        chunks.add(text.substring(start, splitAt).trim())
        start = splitAt
    }
    return chunks.filter { it.isNotBlank() }.ifEmpty { listOf(text.take(maxLen)) }
}
