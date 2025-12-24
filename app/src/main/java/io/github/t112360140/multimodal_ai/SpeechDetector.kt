package io.github.t112360140.multimodal_ai

import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 負責管理 VAD 邏輯、串流音訊進行即時辨識。
 * 採用真正的串流模式，將音訊區塊即時送入 Whisper 引擎。
 */
class SpeechDetector(context: Context, model: String) : VoiceRecorder.AudioCallback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 建立一個單一線程的 dispatcher，確保 Whisper 任務循序執行
    private val whisperDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // --- VAD 設定 ---
    private val SAMPLE_RATE = SampleRate.SAMPLE_RATE_16K
    private val FRAME_SIZE = FrameSize.FRAME_SIZE_1024
    private val MODE = Mode.VERY_AGGRESSIVE
    private val SILENCE_DURATION_MS = 300
    private val SPEECH_DURATION_MS = 50

    // --- 語音結束邏輯參數 ---
    private val PAUSE_THRESHOLD_MS = 800
    private val FRAME_DURATION_MS = FRAME_SIZE.value * 1000 / SAMPLE_RATE.value
    private val MAX_SILENCE_FRAMES = PAUSE_THRESHOLD_MS / FRAME_DURATION_MS

    // --- Whisper 串流參數 ---
    private val audioBuffer = mutableListOf<Float>()
    // 根據 whisper.cpp stream 範例，設定一個處理步長
    private val AUDIO_STEP_MS = 3000
    private val AUDIO_STEP_SAMPLES = (AUDIO_STEP_MS / 1000f * SAMPLE_RATE.value).toInt()


    private var vad: VadSilero
    private val recorder = VoiceRecorder(this)
    private var whisperContext: WhisperContext? = null

    // --- 狀態管理 ---
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking = _isSpeaking.asStateFlow()
    private var silenceFrameCount = 0
    private var hasSpeechStarted = false

    // --- 回調介面 ---
    var onDone: (() -> Unit)? = null
    var onSpeechTranscribed: ((String) -> Unit)? = null
    var onSpeechStart: (() -> Unit)? = null
    var onSpeechCompleted: (() -> Unit)? = null


    init {
        vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SAMPLE_RATE)
            .setFrameSize(FRAME_SIZE)
            .setMode(MODE)
            .setSilenceDurationMs(SILENCE_DURATION_MS)
            .setSpeechDurationMs(SPEECH_DURATION_MS)
            .build()

        thread {
            val modelPath = File(context.getExternalFilesDir(null), model).absolutePath
            // 注意：這裡現在應該使用新的 init 方法，它會同時初始化 context 和 state
            whisperContext = WhisperContext.createContextFromFile(modelPath)
        }
    }

    fun startListening() {
        if (whisperContext == null) {
            Log.e("SpeechDetector", "Whisper is not initialized yet.")
            return
        }
        resetState()
        recorder.start(SAMPLE_RATE.value, FRAME_SIZE.value)
    }

    fun stopListening() {
        recorder.stop()
        resetState()
    }

    private fun resetState() {
        hasSpeechStarted = false
        silenceFrameCount = 0
        _isSpeaking.value = false
        audioBuffer.clear()
        // 如果您的 JNI 層有提供 reset_state 的函式，可以在此呼叫
        // 不過根據我們的設計，新的句子會自動處理，所以這裡通常不需要做什麼
    }

    fun release() {
        stopListening()
        vad.close()
        runBlocking { whisperContext?.release() }
        whisperContext = null
        whisperDispatcher.close()
    }

    // 由 VoiceRecorder 線程呼叫
    override fun onAudio(audioData: ShortArray) {
        val isCurrentSpeech = vad.isSpeech(audioData)

        if (isCurrentSpeech) {
            silenceFrameCount = 0
            if (!hasSpeechStarted) {
                hasSpeechStarted = true
                _isSpeaking.value = true
                onSpeechStart?.invoke()
            }
            // 將音訊數據加入緩衝區
            audioBuffer.addAll(convertPcm16bitToPcm32bitFloat(audioData).toList())

            // 當緩衝區中的數據足夠長時，進行分塊處理
            while (audioBuffer.size >= AUDIO_STEP_SAMPLES) {
                val chunkToProcess = audioBuffer.subList(0, AUDIO_STEP_SAMPLES).toFloatArray()
                streamAudioChunk(chunkToProcess, isSpeechEnding = false)
                audioBuffer.subList(0, AUDIO_STEP_SAMPLES).clear()
            }
        } else {
            if (hasSpeechStarted) {
                silenceFrameCount++
                if (silenceFrameCount >= MAX_SILENCE_FRAMES) {
                    // 語音結束，處理緩衝區中剩餘的音訊
                    if (audioBuffer.isNotEmpty()) {
                        val remainingAudio = audioBuffer.toFloatArray()
                        streamAudioChunk(remainingAudio, isSpeechEnding = true)
                        audioBuffer.clear()
                    } else {
                        // 即使沒有剩餘音訊，也發送一個結束信號以清空 Whisper 內部狀態
                        streamAudioChunk(floatArrayOf(), isSpeechEnding = true)
                    }
                    // 重設狀態
                    hasSpeechStarted = false
                    _isSpeaking.value = false
                    onSpeechCompleted?.invoke()
                }
            }
        }
    }

    // 運行在單一線程的 whisperDispatcher 上
    private fun streamAudioChunk(floatAudio: FloatArray, isSpeechEnding: Boolean) {
        if (whisperContext == null) return

        scope.launch(whisperDispatcher) {
            // 呼叫我們在 JNI 中新的串流函式
            val result = whisperContext?.streamTranscribeData(floatAudio)

            // 新的 JNI 函式會回傳辨識到的文字片段
            if (!result.isNullOrEmpty()) {
                onSpeechTranscribed?.invoke(result)
            }

            // 如果這是最後一個音訊塊，觸發 onDone
            if (isSpeechEnding) {
                onDone?.invoke()
            }
        }
    }

    private fun convertPcm16bitToPcm32bitFloat(pcmData: ShortArray): FloatArray {
        return FloatArray(pcmData.size) { i -> pcmData[i] / 32768.0f }
    }
}
