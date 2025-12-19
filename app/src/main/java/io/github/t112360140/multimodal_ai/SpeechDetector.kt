package io.github.t112360140.multimodal_ai

import android.content.Context
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 負責管理 VAD 邏輯、累積音訊，並在使用者說完話後回傳完整數據。
 */
class SpeechDetector(context: Context) : VoiceRecorder.AudioCallback {

    // --- 設定參數 ---
    // Gemma 模型與多數 ASR 模型皆使用 16kHz
    private val SAMPLE_RATE = SampleRate.SAMPLE_RATE_16K
    // Frame size: 16kHz 下，1024 samples = 64ms
    private val FRAME_SIZE = FrameSize.FRAME_SIZE_1024
    private val MODE = Mode.VERY_AGGRESSIVE

    // 這些參數給 Builder 使用，雖然我們自己也實作了計數邏輯，但設定給 VAD 內部參考也無妨
    private val SILENCE_DURATION_MS = 300
    private val SPEECH_DURATION_MS = 50

    // --- 自定義邏輯參數 ---
    // 設定多少毫秒的靜音視為一句話真正結束 (例如 800ms)
    private val PAUSE_THRESHOLD_MS = 300
    // 計算一 Frame 有多少毫秒 (1024 / 16000 * 1000 = 64ms)
    private val FRAME_DURATION_MS = FRAME_SIZE.value * 1000 / SAMPLE_RATE.value
    // 計算需要多少個連續靜音 Frame (300 / 64 ~= 4 frames)
    private val MAX_SILENCE_FRAMES = PAUSE_THRESHOLD_MS / FRAME_DURATION_MS

    // --- VAD 初始化 ---
    // 直接使用 Builder，不需要 Context，Library 會自動載入 .so 檔
    private var vad = Vad.builder()
        .setContext(context)
        .setSampleRate(SAMPLE_RATE)
        .setFrameSize(FRAME_SIZE)
        .setMode(MODE)
        .setSilenceDurationMs(SILENCE_DURATION_MS)
        .setSpeechDurationMs(SPEECH_DURATION_MS)
        .build()

    private val recorder = VoiceRecorder(this)

    // --- 狀態管理 ---
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking = _isSpeaking.asStateFlow()

    private var silenceFrameCount = 0
    private var hasSpeechStarted = false

    // 用來累積一句話的完整音訊
    private val audioBuffer = ByteArrayOutputStream()

    // 回調介面：當一句話完整結束時觸發
    var onSpeechCompleted: ((ByteArray) -> Unit)? = null

    var onSpeechStart: (() -> Unit)? = null

    fun startListening() {
        resetState()
        // 傳入 int value 給 recorder (注意：這裡要傳 SAMPLE_RATE.value 而不是 enum 本身)
        recorder.start(SAMPLE_RATE.value, FRAME_SIZE.value)
    }

    fun stopListening() {
        recorder.stop()
        resetState()
    }

    private fun resetState() {
        hasSpeechStarted = false
        silenceFrameCount = 0
        audioBuffer.reset()
        _isSpeaking.value = false
    }

    // 釋放資源
    fun release() {
        stopListening()
        vad.close()
    }

    override fun onAudio(audioData: ShortArray) {
        // 1. VAD 判斷當前 Frame 是否為人聲
        val isCurrentSpeech = vad.isSpeech(audioData)

        if (isCurrentSpeech) {
            // --- 偵測到人聲 ---
            silenceFrameCount = 0 // 重置靜音計數

            if (!hasSpeechStarted) {
                hasSpeechStarted = true
                _isSpeaking.value = true
                audioBuffer.reset()

                onSpeechStart?.invoke()
            }

            // 寫入數據
            writeShortsToStream(audioData)

        } else {
            // --- 偵測到靜音 ---
            if (hasSpeechStarted) {
                silenceFrameCount++

                // 靜音期間也寫入數據，避免語句尾端被切斷
                writeShortsToStream(audioData)

                // 檢查是否達到結束閾值
                if (silenceFrameCount >= MAX_SILENCE_FRAMES) {
                    // --- 對話結束 ---
                    hasSpeechStarted = false
                    _isSpeaking.value = false

                    val finalAudio = audioBuffer.toByteArray()
                    audioBuffer.reset()

                    // 重置 silenceFrameCount 以防連續觸發
                    silenceFrameCount = 0

                    // 將 PCM 轉為 WAV 格式
                    val wavAudio = createWavByteArray(finalAudio)

                    // 觸發回調 (傳出完整的 WAV ByteArray)
                    onSpeechCompleted?.invoke(wavAudio)
                }
            }
        }
    }

    private fun createWavByteArray(pcmData: ByteArray): ByteArray {
        val dataSize = pcmData.size
        val sampleRate = SAMPLE_RATE.value
        val numChannels = 1 // Mono
        val bitsPerSample = 16 // PCM 16-bit
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        val blockAlign = numChannels * bitsPerSample / 8
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44)
        header.order(ByteOrder.LITTLE_ENDIAN)

        // RIFF chunk
        header.put("RIFF".toByteArray())
        header.putInt(chunkSize)
        header.put("WAVE".toByteArray())

        // "fmt " sub-chunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // Subchunk1Size for PCM
        header.putShort(1) // AudioFormat, 1 for PCM
        header.putShort(numChannels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())

        // "data" sub-chunk
        header.put("data".toByteArray())
        header.putInt(dataSize)

        val wavStream = ByteArrayOutputStream()
        wavStream.write(header.array())
        wavStream.write(pcmData)

        return wavStream.toByteArray()
    }

    private fun writeShortsToStream(shorts: ShortArray) {
        // 將 ShortArray (PCM 16bit) 轉為 ByteArray (Little Endian)
        val byteBuffer = ByteBuffer.allocate(shorts.size * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { byteBuffer.putShort(it) }
        audioBuffer.write(byteBuffer.array())
    }
}
