package io.github.t112360140.multimodal_ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 整合型 SpeechDetector
 * sttType: 1 -> Gemma Audio (File 1 Logic)
 * sttType: 2 -> Whisper (File 3 Logic)
 * sttType: 3 -> Google API (File 2 Logic)
 */
class SpeechDetector(context: Context, sttType: Int, whisperModelPath: String = "") {

    // --- 統一對外接口的回調 ---
    var onSpeechStart: (() -> Unit)? = null

    // Type 1 使用: 回傳 WAV Audio Bytes
    var onAudioData: ((ByteArray) -> Unit)? = null

    // Type 2 & 3 使用: 回傳完整文字
    var onTextResult: ((String) -> Unit)? = null

    // Type 2 使用: 串流文字
    var onPartialText: ((String) -> Unit)? = null

    // 內部實作
    private val implType1: ImplType1? = if (sttType == 1) ImplType1(context) else null
    private val implType2: ImplType2? = if (sttType == 2) ImplType2(context, whisperModelPath) else null
    private val implType3: ImplType3? = if (sttType == 3) ImplType3(context) else null

    private val currentType = sttType

    init {
        // --- 綁定 Type 1 (File 1) 回調 ---
        implType1?.onSpeechStart = { onSpeechStart?.invoke() }
        implType1?.onSpeechCompleted = { wavBytes -> onAudioData?.invoke(wavBytes) }

        // --- 綁定 Type 2 (File 3) 回調 ---
        implType2?.onSpeechStart = { onSpeechStart?.invoke() }
        implType2?.onSpeechTranscribed = { text -> onPartialText?.invoke(text) } // Partial
        // File 3 的 onDone 不帶參數，我們需要最後的完整文字嗎？
        // 原本 File 3 是透過 onSpeechTranscribed 累積字串，
        // 在 MainActivity 裡，我們會在 onDone 時處理。
        // 這裡為了讓 MainActivity 統一，我們讓 implType2 觸發 onTextResult
        implType2?.onDone = {
            // 這裡不做動作，邏輯由 MainActivity 處理，或由 Impl 內部處理
            // File 3 原本是在 onDone 時取用 sttString。
            // 為了保持 "完全按照原本程式"，邏輯保留在 Impl2，但外部透過 onTextResult 接收最終結果會比較好接
            onTextResult?.invoke("") // 觸發一個信號，讓 MainActivity 知道結束了
        }

        // --- 綁定 Type 3 (File 2) 回調 ---
        implType3?.onSpeechStart = { onSpeechStart?.invoke() }
        implType3?.onSpeechResult = { text -> onTextResult?.invoke(text) }
        implType3?.onSpeechError = { err ->
            // 可以選擇回傳錯誤訊息，或這裡簡單處理
            Log.e("SpeechDetector", "Type 3 Error: $err")
            onTextResult?.invoke("") // 回傳空字串代表錯誤或無結果
        }
    }

    fun startListening() {
        when(currentType) {
            1 -> implType1?.startListening()
            2 -> implType2?.startListening()
            3 -> implType3?.startListening()
        }
    }

    fun stopListening() {
        when(currentType) {
            1 -> implType1?.stopListening()
            2 -> implType2?.stopListening()
            3 -> implType3?.stopListening()
        }
    }

    fun release() {
        implType1?.release()
        implType2?.release()
        implType3?.release()
    }

    // =================================================================================
    // ⬇️ 以下完全複製貼上 File 1 的邏輯 (類別名稱修改為 ImplType1 以避免衝突)
    // =================================================================================
    private class ImplType1(context: Context) : VoiceRecorder.AudioCallback {
        private val SAMPLE_RATE = SampleRate.SAMPLE_RATE_16K
        private val FRAME_SIZE = FrameSize.FRAME_SIZE_1024
        private val MODE = Mode.VERY_AGGRESSIVE
        private val SILENCE_DURATION_MS = 300
        private val SPEECH_DURATION_MS = 50
        private val PAUSE_THRESHOLD_MS = 300
        private val FRAME_DURATION_MS = FRAME_SIZE.value * 1000 / SAMPLE_RATE.value
        private val MAX_SILENCE_FRAMES = PAUSE_THRESHOLD_MS / FRAME_DURATION_MS

        private var vad = Vad.builder()
            .setContext(context)
            .setSampleRate(SAMPLE_RATE)
            .setFrameSize(FRAME_SIZE)
            .setMode(MODE)
            .setSilenceDurationMs(SILENCE_DURATION_MS)
            .setSpeechDurationMs(SPEECH_DURATION_MS)
            .build()

        private val recorder = VoiceRecorder(this)
        private val _isSpeaking = MutableStateFlow(false)
        val isSpeaking = _isSpeaking.asStateFlow()

        private var silenceFrameCount = 0
        private var hasSpeechStarted = false
        private val audioBuffer = ByteArrayOutputStream()

        var onSpeechCompleted: ((ByteArray) -> Unit)? = null
        var onSpeechStart: (() -> Unit)? = null

        fun startListening() {
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
            audioBuffer.reset()
            _isSpeaking.value = false
        }

        fun release() {
            stopListening()
            vad.close()
        }

        override fun onAudio(audioData: ShortArray) {
            val isCurrentSpeech = vad.isSpeech(audioData)
            if (isCurrentSpeech) {
                silenceFrameCount = 0
                if (!hasSpeechStarted) {
                    hasSpeechStarted = true
                    _isSpeaking.value = true
                    audioBuffer.reset()
                    onSpeechStart?.invoke()
                }
                writeShortsToStream(audioData)
            } else {
                if (hasSpeechStarted) {
                    silenceFrameCount++
                    writeShortsToStream(audioData)
                    if (silenceFrameCount >= MAX_SILENCE_FRAMES) {
                        hasSpeechStarted = false
                        _isSpeaking.value = false
                        val finalAudio = audioBuffer.toByteArray()
                        audioBuffer.reset()
                        silenceFrameCount = 0
                        val wavAudio = createWavByteArray(finalAudio)
                        onSpeechCompleted?.invoke(wavAudio)
                    }
                }
            }
        }

        private fun createWavByteArray(pcmData: ByteArray): ByteArray {
            val dataSize = pcmData.size
            val sampleRate = SAMPLE_RATE.value
            val numChannels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * numChannels * bitsPerSample / 8
            val blockAlign = numChannels * bitsPerSample / 8
            val chunkSize = 36 + dataSize
            val header = ByteBuffer.allocate(44)
            header.order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(chunkSize)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)
            header.putShort(numChannels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort(blockAlign.toShort())
            header.putShort(bitsPerSample.toShort())
            header.put("data".toByteArray())
            header.putInt(dataSize)
            val wavStream = ByteArrayOutputStream()
            wavStream.write(header.array())
            wavStream.write(pcmData)
            return wavStream.toByteArray()
        }

        private fun writeShortsToStream(shorts: ShortArray) {
            val byteBuffer = ByteBuffer.allocate(shorts.size * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            shorts.forEach { byteBuffer.putShort(it) }
            audioBuffer.write(byteBuffer.array())
        }
    }

    // =================================================================================
    // ⬇️ 以下完全複製貼上 File 3 的邏輯 (類別名稱修改為 ImplType2)
    // =================================================================================
    private class ImplType2(context: Context, model: String) : VoiceRecorder.AudioCallback {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val whisperDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val SAMPLE_RATE = SampleRate.SAMPLE_RATE_16K
        private val FRAME_SIZE = FrameSize.FRAME_SIZE_1024
        private val MODE = Mode.VERY_AGGRESSIVE
        private val SILENCE_DURATION_MS = 300
        private val SPEECH_DURATION_MS = 50
        private val PAUSE_THRESHOLD_MS = 800
        private val FRAME_DURATION_MS = FRAME_SIZE.value * 1000 / SAMPLE_RATE.value
        private val MAX_SILENCE_FRAMES = PAUSE_THRESHOLD_MS / FRAME_DURATION_MS
        private val audioBuffer = mutableListOf<Float>()
        private val AUDIO_STEP_MS = 3000
        private val AUDIO_STEP_SAMPLES = (AUDIO_STEP_MS / 1000f * SAMPLE_RATE.value).toInt()

        private var vad: VadSilero
        private val recorder = VoiceRecorder(this)
        private var whisperContext: WhisperContext? = null
        private val _isSpeaking = MutableStateFlow(false)
        val isSpeaking = _isSpeaking.asStateFlow()
        private var silenceFrameCount = 0
        private var hasSpeechStarted = false

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
        }

        fun release() {
            stopListening()
            vad.close()
            runBlocking { whisperContext?.release() }
            whisperContext = null
            whisperDispatcher.close()
        }

        override fun onAudio(audioData: ShortArray) {
            val isCurrentSpeech = vad.isSpeech(audioData)

            if (isCurrentSpeech) {
                silenceFrameCount = 0
                if (!hasSpeechStarted) {
                    hasSpeechStarted = true
                    _isSpeaking.value = true
                    onSpeechStart?.invoke()
                }
                audioBuffer.addAll(convertPcm16bitToPcm32bitFloat(audioData).toList())
                while (audioBuffer.size >= AUDIO_STEP_SAMPLES) {
                    val chunkToProcess = audioBuffer.subList(0, AUDIO_STEP_SAMPLES).toFloatArray()
                    streamAudioChunk(chunkToProcess, isSpeechEnding = false)
                    audioBuffer.subList(0, AUDIO_STEP_SAMPLES).clear()
                }
            } else {
                if (hasSpeechStarted) {
                    silenceFrameCount++
                    if (silenceFrameCount >= MAX_SILENCE_FRAMES) {
                        if (audioBuffer.isNotEmpty()) {
                            val remainingAudio = audioBuffer.toFloatArray()
                            streamAudioChunk(remainingAudio, isSpeechEnding = true)
                            audioBuffer.clear()
                        } else {
                            streamAudioChunk(floatArrayOf(), isSpeechEnding = true)
                        }
                        hasSpeechStarted = false
                        _isSpeaking.value = false
                        onSpeechCompleted?.invoke()
                    }
                }
            }
        }

        private fun streamAudioChunk(floatAudio: FloatArray, isSpeechEnding: Boolean) {
            if (whisperContext == null) return
            scope.launch(whisperDispatcher) {
                val result = whisperContext?.streamTranscribeData(floatAudio)
                if (!result.isNullOrEmpty()) {
                    onSpeechTranscribed?.invoke(result)
                }
                if (isSpeechEnding) {
                    onDone?.invoke()
                }
            }
        }

        private fun convertPcm16bitToPcm32bitFloat(pcmData: ShortArray): FloatArray {
            return FloatArray(pcmData.size) { i -> pcmData[i] / 32768.0f }
        }
    }

    // =================================================================================
    // ⬇️ 以下完全複製貼上 File 2 的邏輯 (類別名稱修改為 ImplType3)
    // =================================================================================
    private class ImplType3(private val context: Context) {
        private var speechRecognizer: SpeechRecognizer? = null
        private val _isSpeaking = MutableStateFlow(false)
        val isSpeaking = _isSpeaking.asStateFlow()

        var onSpeechResult: ((String) -> Unit)? = null
        var onSpeechError: ((String) -> Unit)? = null
        var onSpeechStart: (() -> Unit)? = null

        private val recognitionListener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                _isSpeaking.value = true
                onSpeechStart?.invoke()
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { _isSpeaking.value = false }
            override fun onError(error: Int) {
                _isSpeaking.value = false
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音訊錯誤"
                    SpeechRecognizer.ERROR_CLIENT -> "客戶端錯誤"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "權限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "網路錯誤"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "網路超時"
                    SpeechRecognizer.ERROR_NO_MATCH -> "沒有辨識到相符的結果"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "辨識服務正忙"
                    SpeechRecognizer.ERROR_SERVER -> "伺服器錯誤"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "沒有偵測到語音輸入"
                    else -> "未知的辨識錯誤: $error"
                }
                onSpeechError?.invoke(errorMessage)
            }
            override fun onResults(results: Bundle?) {
                _isSpeaking.value = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    onSpeechResult?.invoke(text)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }

        fun startListening() {
            // 切回主線程以確保安全，雖然 File 2 原文沒強制，但 Android STT 建議在 UI Thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (speechRecognizer == null) {
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        onSpeechError?.invoke("此裝置不支援語音辨識")
                        return@post
                    }
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(recognitionListener)
                    }
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                }
                speechRecognizer?.startListening(intent)
            }
        }

        fun stopListening() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }

        fun release() {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }
    }
}