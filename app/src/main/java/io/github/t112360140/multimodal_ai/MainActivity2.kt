package io.github.t112360140.multimodal_ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

fun Bitmap.toByteArray(format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 85): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(format, quality, stream)
    return stream.toByteArray()
}

fun ImageProxy.toBitmap(): Bitmap {
    val buffer: ByteBuffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

class MainActivity2 : AppCompatActivity() {
    private lateinit var speechDetector: SpeechDetector
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var isLlmReady = false

    private var isMicEnabled = false
    private var isCameraEnabled = true

    private lateinit var imageCapture: ImageCapture

    private var config: Bundle? = null
    private var modelName: String = ""
    private var whisperModelPath: String = ""
    private var systmMessage:String = ""
    private var UseTts: Boolean = true
    private var sttType: Int = 1 // 1=GemmaAudio, 2=Whisper, 3=GoogleSTT

    // 累積 Type 2 的文字
    private var sttString: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main2)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val camera_view = findViewById<PreviewView>(R.id.camera_view)
        val close_btn = findViewById<FloatingActionButton>(R.id.close_btn)
        val mic_btn = findViewById<FloatingActionButton>(R.id.mic_btn)
        val camera_btn = findViewById<FloatingActionButton>(R.id.camera_btn)
        val model_output = findViewById<TextView>(R.id.model_output)

        // --- 讀取參數 ---
        if(intent!=null) config = intent.extras
        modelName = config?.getString("modelName") ?: ""
        whisperModelPath = config?.getString("whisperModelPath") ?: ""
        systmMessage = config?.getString("systmMessage") ?: getString(R.string.default_system_message)
        UseTts = config?.getBoolean("UseTts", true) ?: true
        sttType = config?.getInt("sttType", 1) ?: 1 // 讀取 sttType

        Log.i("MainActivity2", "STT Type: $sttType")

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        } else {
            startCamera()
        }

        // --- TTS 設定 ---
        if(UseTts){
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts.setPitch(1.0f)
                    tts.setSpeechRate(1.0f)
                    val result = tts.setLanguage(tts.getDefaultVoice().getLocale())
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTS", "The specified language is not supported!")
                    } else {
                        isTtsReady=true
                    }
                } else {
                    Log.e("TTS", "TTS Init failed!")
                }
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread { speechDetector.stopListening() }
                }
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { if (isMicEnabled) speechDetector.startListening() }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread { if (isMicEnabled) speechDetector.startListening() }
                }
            })
        }

        // --- 初始化 SpeechDetector ---
        // 根據 sttType 傳入
        speechDetector = SpeechDetector(this, sttType, whisperModelPath)

        // 設定回調 (部分回調只會在特定 Type 觸發，安全處理)

        // 1. 開始說話時
        speechDetector.onSpeechStart = {
            sttString = "" // 清空累積字串
            if (isMicEnabled && isLlmReady) {
                runOnUiThread { model_output.text = "Listening..." }
            }
        }

        // 2. Type 2 (Whisper) 的串流文字更新
        speechDetector.onPartialText = { text ->
            if(isMicEnabled && isLlmReady && sttType == 2){
                sttString += text
                runOnUiThread { model_output.text = sttString }
            }
        }

        // 3. Type 1 (Gemma Audio) 的音訊結果
        speechDetector.onAudioData = { audioBytes ->
            if (isMicEnabled && isLlmReady && sttType == 1) {
                runOnUiThread { model_output.text = "Thinking (Audio)..." }
                captureAndSend(null, audioBytes)
            }
        }

        // 4. Type 2 & 3 的完整文字結果
        speechDetector.onTextResult = { text ->
            if (isMicEnabled && isLlmReady) {
                // 如果是 Type 3 (Google), text 是完整句子
                // 如果是 Type 2 (Whisper), text 可能是最後的信號(空字串)，我們用 sttString
                val finalText = if(sttType == 3) text else sttString

                if (finalText.isNotBlank()) {
                    runOnUiThread { model_output.text = "$finalText\n\nThinking..." }
                    captureAndSend(finalText, null)
                } else {
                    // 沒收到文字 (例如 Type 3 錯誤或超時)，重啟聆聽
                    if(sttType == 3) speechDetector.startListening()
                }
            }
        }

        if(isMicEnabled) {
            speechDetector.startListening()
        }

        close_btn.setOnClickListener {
            Toast.makeText(this, "關閉中...", Toast.LENGTH_SHORT).show()
            exit(REQUEST_CODE_OK)
        }

        // 麥克風按鈕
        mic_btn.setOnClickListener {
            isMicEnabled = !isMicEnabled
            if (isMicEnabled) {
                mic_btn.setImageResource(R.drawable.pause)
                speechDetector.startListening()
                model_output.text = "Waiting..."
            } else {
                mic_btn.setImageResource(R.drawable.start)
                if (UseTts && ::tts.isInitialized) {
                    tts.stop()
                }
                speechDetector.stopListening()
                InferenceManager.stop()
                model_output.text = "Stop."
            }
        }

        // 鏡頭按鈕
        camera_btn.setOnClickListener {
            isCameraEnabled = !isCameraEnabled
            if (isCameraEnabled) {
                camera_btn.setImageResource(R.drawable.camera_off)
                camera_view.visibility = View.VISIBLE
                startCamera()
            } else {
                camera_btn.setImageResource(R.drawable.camera_on)
                camera_view.visibility = View.INVISIBLE
                stopCamera()
            }
        }

        if(InferenceManager.newConversation(systmMessage)){
            model_output.text = "Waiting..."
            isLlmReady = true
        } else {
            exit(REQUEST_CODE_INIT_ERROR)
        }
    }

    private fun captureAndSend(textData: String?, audioData: ByteArray?) {
        // 先停止聆聽，避免思考時收到雜音
        speechDetector.stopListening()

        if (isCameraEnabled && ::imageCapture.isInitialized) {
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = image.toBitmap()
                        image.close()
                        sendMessageToInference(textData, bitmap.toByteArray(), audioData)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraX", "Image capture failed.", exception)
                        sendMessageToInference(textData, null, audioData)
                    }
                }
            )
        } else {
            sendMessageToInference(textData, null, audioData)
        }
    }

    private fun sendMessageToInference(textData: String?, imageData: ByteArray?, audioData: ByteArray?) {
        val model_output = findViewById<TextView>(R.id.model_output)
        val model_output_scroll = findViewById<ScrollView>(R.id.model_output_scroll)
        var startOutput = false

        InferenceManager.sendMessage(textData, imageData, audioData, onMessage = { message ->
            runOnUiThread {
                if(!startOutput) {
                    startOutput = true
                    model_output.text = ""
                }
                model_output.append(message.toString())
                model_output_scroll.post {
                    model_output_scroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }, onDone = {
            runOnUiThread {
                if(UseTts) speakText(model_output.text.toString())
                if(isMicEnabled && isLlmReady) speechDetector.startListening()
            }
        }, onError = {
            runOnUiThread {
                model_output.append("\n\n模型輸出錯誤!")
                if(isMicEnabled && isLlmReady) speechDetector.startListening()
            }
        })
    }

    private fun exit(returnCode: Int = REQUEST_CODE_OK){
        val intent = Intent()
        intent.putExtra("RETURN", returnCode)
        setResult(RESULT_OK, intent)
        finish()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.camera_view).surfaceProvider)
            }
            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e("CameraX", "Binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProviderFuture.get().unbindAll()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun speakText(text: String) {
        if(isTtsReady && !isFinishing)
            tts.speak(text.replace('*', ' '), TextToSpeech.QUEUE_FLUSH, null, "utteranceId")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera() else exit(REQUEST_CODE_PERMISSIONS_ERROR)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        InferenceManager.stop()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        speechDetector.release()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val REQUEST_CODE_OK = 0
        private const val REQUEST_CODE_INIT_ERROR = -1
        private const val REQUEST_CODE_PERMISSIONS_ERROR = -2
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}