package com.chatbyyourside.manager

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.chatbyyourside.config.AppConfig
import com.chatbyyourside.data.model.SystemVoiceTemplate
import com.chatbyyourside.data.model.TtsConfig
import com.chatbyyourside.data.model.TtsEngine
import com.chatbyyourside.data.model.TtsLanguage
import com.chatbyyourside.data.model.speakerIdForLanguage
import com.chatbyyourside.data.repository.SettingsRepository
import com.chatbyyourside.tts.SystemTtsEngine
import com.chatbyyourside.tts.VolcTtsClient
import com.chatbyyourside.tts.chunkTtsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * TTS 管理器：按设置中的引擎分支朗读。
 *
 * - [TtsEngine.SYSTEM]（默认）：手机自带 TextToSpeech（离线、免凭据），声音模板见
 *   [SystemVoiceTemplate]；系统引擎不支持暂停，pause 退化为停止；
 * - [TtsEngine.CLOUD]：保持原小程序逻辑——CloudRun /tts 合成 base64 mp3 落临时文件后 MediaPlayer 播放，
 *   声音复刻音色按角色音色映射选择，支持中日双语。
 */
class TtsManager(
    private val context: Context,
    private val client: VolcTtsClient,
    private val settings: SettingsRepository,
) {

    companion object {
        private const val TAG = "TtsManager"
        /** 云端单段合成上限（字符）：火山 unidirectional 长文本保守值，超限按句界切分。 */
        private const val CLOUD_MAX_CHUNK_LENGTH = 500
    }

    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var isPlaying = false
    /** 当前播放的临时音频文件；中途 stopAll 时 onCompletion 不会触发，需主动删除避免泄漏 */
    private var currentFile: File? = null

    /** 系统引擎（懒初始化，见 SystemTtsEngine）。 */
    private val systemTts = SystemTtsEngine(context)

    /** 串行化 speak，避免并发调用互相打断导致状态错乱/资源泄漏 */
    private val mutex = Mutex()

    val playing: Boolean get() = isPlaying || systemTts.isPlaying

    /** 检查云端 TTS 凭据是否已配置（仅云端引擎需要）。 */
    suspend fun hasCredentials(): Boolean {
        val config = settings.getTtsConfigNow()
        return client.hasCredentials(config)
    }

    /**
     * 系统引擎试听：用未保存的模板立即朗读一段示例（设置页「试听」用）。
     * 语言跟随已保存设置；不触碰云端凭据。
     */
    suspend fun previewSystem(text: String, template: SystemVoiceTemplate) = mutex.withLock {
        if (text.isBlank()) throw Exception("没有可朗读的文本")
        if (playing) stopAll()
        val language = settings.getTtsLanguageNow()
        systemTts.speak(cleanTtsText(text), language, template)
    }

    /**
     * 合成并播放语音
     * @param text 待朗读文本
     * @param characterId 角色 ID（云端引擎用于选择音色；系统引擎忽略）
     */
    suspend fun speak(text: String, characterId: String) = mutex.withLock {
        if (text.isBlank()) throw Exception("没有可朗读的文本")
        if (playing) stopAll()

        val language = settings.getTtsLanguageNow()
        val engine = settings.getTtsEngineNow()
        when (engine) {
            TtsEngine.SYSTEM -> {
                val template = settings.getTtsSystemTemplateNow()
                val cleanText = cleanTtsText(text)
                systemTts.speak(cleanText, language, template)
            }
            TtsEngine.CLOUD -> speakCloud(text, characterId, language)
        }
    }

    /** 云端引擎合成并播放（原 MediaPlayer 路径；仅在 [speak] 持有 mutex 时调用，自身不加锁）。 */
    private suspend fun speakCloud(text: String, characterId: String, language: TtsLanguage) {
        val ttsConfig = settings.getTtsConfigNow()
        val volume = withTimeoutOrNull(5000) { settings.ttsVolume.first() }
            ?: AppConfig.TTS_DEFAULT_VOLUME

        if (!client.hasCredentials(ttsConfig)) {
            throw Exception("请先填写火山引擎 API Key")
        }

        val voiceMap = settings.getTtsVoiceMapNow()
        // 优先该角色当前语言的 speaker_id；未配置时回落 TtsConfig.defaultVoiceId（默认音色），
        // 让未逐一配置音色的角色也能直接朗读；两者都缺才给引导错误。
        val speakerId = speakerIdForLanguage(characterId, language, voiceMap)
            ?: ttsConfig.defaultVoiceId.takeIf { it.isNotBlank() }
            ?: throw Exception(
                "请先在设置 → 角色双语音色中填写该角色的${language.label} speaker_id",
            )

        // 清理括号内容
        val cleanText = cleanTtsText(text)

        // 长文本分段合成：火山单次合成有长度上限，按句界切 ≤500 字符/段，
        // MP3 帧流可直接字节拼接（与 VolcTtsClient 内部 Chunked 逐帧拼接同理）。
        // joinTo 显式空 separator——默认 ", " 会污染字节流。
        val audioBytes = chunkTtsText(cleanText, CLOUD_MAX_CHUNK_LENGTH)
            .joinTo(ByteArrayOutputStream(), separator = "") { chunk ->
                client.synthesize(chunk, characterId, ttsConfig, speakerId)
            }.toByteArray()

        // 写入临时文件（磁盘 IO，切到 IO 调度器）
        val tempFile = File(context.cacheDir, "tts_${System.currentTimeMillis()}.mp3")
        withContext(Dispatchers.IO) { tempFile.writeBytes(audioBytes) }

        // 播放必须在主线程：MediaPlayer 依赖创建线程的 Looper 投递回调，
        // 否则 onCompletion/onError 永不触发，导致 player 不释放、临时文件不删除
        withContext(Dispatchers.Main) { playAudio(tempFile, volume) }
    }

    private fun playAudio(file: File, volume: Int) {
        stopAll()

        val player = MediaPlayer()
        mediaPlayer = player
        currentFile = file

        val vol = (volume / 100f).coerceIn(0f, 1f)

        player.setDataSource(file.absolutePath)
        player.setVolume(vol, vol)
        player.setOnCompletionListener { mp ->
            isPlaying = false
            mediaPlayer = null
            try { mp.release() } catch (e: Exception) {}
            file.delete()
        }
        player.setOnErrorListener { mp, what, extra ->
            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
            isPlaying = false
            mediaPlayer = null
            try { mp.release() } catch (e: Exception) {}
            file.delete()
            true
        }
        player.prepare()
        player.start()
        isPlaying = true
    }

    fun stopAll() {
        mediaPlayer?.let {
            // release() 在任意状态均合法，直接释放即可；避免 isPlaying/stop 抛异常导致 release 被跳过
            try { it.release() } catch (e: Exception) {
                Log.w(TAG, "Release MediaPlayer: ${e.message}")
            }
        }
        mediaPlayer = null
        isPlaying = false
        // 中途停止时 onCompletion 不会触发，主动删除临时文件避免泄漏
        currentFile?.let { runCatching { it.delete() } }
        currentFile = null
        systemTts.stop()
    }

    /**
     * 视频等其它音频抢占焦点时暂停当前 TTS（云端保留临时文件与 MediaPlayer，可 [resume] 续播；
     * 系统引擎不支持暂停，语义退化为停止）。无播放中实例时为空操作；暂停期间 [playing] 仍为 true
     * （云端），UI 视为「正在播放」。云端路径与 [playAudio] 一样需在主线程调用。
     */
    fun pause() {
        if (systemTts.isPlaying) {
            systemTts.stop()
            return
        }
        mediaPlayer?.let { mp ->
            runCatching { if (mp.isPlaying) mp.pause() }
        }
    }

    /**
     * 抢占方释放音频后恢复被 [pause] 暂停的云端 TTS 播放。无暂停中的 MediaPlayer 时为空操作。
     * 与 [playAudio] 一样需在主线程调用。
     */
    fun resume() {
        mediaPlayer?.let { mp ->
            runCatching { if (!mp.isPlaying) mp.start() }
        }
    }

    /** 清理 TTS 文本：去除括号内容（保持原 cleanTtsText 逻辑） */
    fun cleanTtsText(text: String): String {
        return text
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")   // 剥掉 Qwen3 思考块，不朗读
            .replace(Regex("</?think>"), "")                    // 兜底：残留的未闭合标签
            .replace(Regex("[（(][^）)]*[）)]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
