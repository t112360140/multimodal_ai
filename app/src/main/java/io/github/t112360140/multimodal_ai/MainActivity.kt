package io.github.t112360140.multimodal_ai

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class Model(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("url") val url: String
)

data class Models(
    @SerializedName("llm") val llm: List<Model>,
    @SerializedName("whisper") val whisper: List<Model>
)

class MainActivity : AppCompatActivity() {
    private lateinit var modelDownloader: ModelDownloader

    private lateinit var model_name_select: Spinner
    private lateinit var download_btn: ImageButton
    private lateinit var model_name_select_stt: Spinner
    private lateinit var download_btn_stt: ImageButton
    private lateinit var use_gpu: RadioButton
    private lateinit var use_tts: Switch
    private lateinit var system_message: EditText

    private lateinit var gemma_stt: RadioButton
    private lateinit var whisper_stt: RadioButton
    private lateinit var api_stt: RadioButton

    private lateinit var info_tv: TextView
    private lateinit var start_btn: Button

    private var llmModels: List<Model> = emptyList()
    private var whisperModels: List<Model> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        model_name_select = findViewById<Spinner>(R.id.model_name)
        download_btn = findViewById<ImageButton>(R.id.download_btn)
        model_name_select_stt = findViewById<Spinner>(R.id.model_name_stt)
        download_btn_stt = findViewById<ImageButton>(R.id.download_btn_stt)
        use_gpu = findViewById<RadioButton>(R.id.backend_gpu)
        use_tts = findViewById<Switch>(R.id.use_tts)
        system_message = findViewById<EditText>(R.id.system_message)
        info_tv = findViewById<TextView>(R.id.info)
        start_btn = findViewById<Button>(R.id.start_btn)

        gemma_stt = findViewById<RadioButton>(R.id.use_gemma_as_stt)
        whisper_stt = findViewById<RadioButton>(R.id.use_whisper_as_stt)
        api_stt = findViewById<RadioButton>(R.id.use_api_as_stt)

        modelDownloader = ModelDownloader(this)

        fetchModels()

        model_name_select.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateDownloadStatus()
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        download_btn.setOnClickListener {
            val position = model_name_select.selectedItemPosition
            if (position >= 0 && position < llmModels.size) {
                val model = llmModels[position]
                handleModelDownload(model)
            }
        }

        model_name_select_stt.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateDownloadStatus()
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        download_btn_stt.setOnClickListener {
            val position = model_name_select_stt.selectedItemPosition
            if (position >= 0 && position < whisperModels.size) {
                val model = whisperModels[position]
                handleModelDownload(model)
            }
        }

        start_btn.setOnClickListener {
            val llmPosition = model_name_select.selectedItemPosition
            val sttPosition = model_name_select_stt.selectedItemPosition

            if (llmPosition >= 0 && llmPosition < llmModels.size && sttPosition >=0 && sttPosition < whisperModels.size) {
                val llmModel = llmModels[llmPosition]
                val sttModel = whisperModels[sttPosition]

                start_btn.isEnabled = false
                if (modelDownloader.isModelDownloaded(llmModel.path)) {
                    showInfo(this, "模型載入中...", 1, Toast.LENGTH_SHORT)
                    lifecycleScope.launch {
                        val path = modelDownloader.getModelPath(llmModel.path)
                        val success = InferenceManager.loadModel(
                            this@MainActivity,
                            path,
                            use_gpu.isChecked,
                            gemma_stt.isChecked
                        )

                        if (success) {
                            runOnUiThread { showInfo(this@MainActivity, "模型載入成功!", 1, Toast.LENGTH_SHORT) }
                            val intent = Intent(this@MainActivity, MainActivity2::class.java)
                            val bundle = Bundle()
                            bundle.putString("modelName", llmModel.name)
                            bundle.putInt(
                                "sttType",
                                if (gemma_stt.isChecked) 1 else if (whisper_stt.isChecked) 2 else 3
                            )
                            bundle.putString("whisperModelPath", sttModel.path)
                            bundle.putBoolean("UseTts", use_tts.isChecked)
                            bundle.putString(
                                "systmMessage",
                                system_message.text.toString()
                                    .ifEmpty { getString(R.string.default_system_message) })

                            intent.putExtras(bundle)

                            startActivityForResult(intent, 9)
                        } else {
                            runOnUiThread { showInfo(this@MainActivity, "模型載入失敗!", 3, Toast.LENGTH_SHORT) }
                            runOnUiThread { updateDownloadStatus() }
                        }
                    }
                } else {
                    showInfo(this, "模型不存在!", 3, Toast.LENGTH_SHORT)
                    updateDownloadStatus()
                }
            }
        }
    }

    private fun fetchModels() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(getString(R.string.models_list_url))
                val connection = url.openConnection() as HttpURLConnection
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val models = Gson().fromJson(reader, Models::class.java)
                reader.close()
                connection.disconnect()

                withContext(Dispatchers.Main) {
                    llmModels = models.llm
                    whisperModels = models.whisper
                    updateSpinners()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "無法抓取模型列表，將使用預設列表", Toast.LENGTH_LONG).show()
                    loadDefaultModels()
                }
            }
        }
    }

    private fun loadDefaultModels() {
        val llmNames = resources.getStringArray(R.array.models_name_llm)
        val llmPaths = resources.getStringArray(R.array.models_path_llm)
        val llmUrls = resources.getStringArray(R.array.models_url_llm)
        llmModels = llmNames.mapIndexed { index, name ->
            Model(name, llmPaths[index], llmUrls[index])
        }

        val whisperNames = resources.getStringArray(R.array.models_name_whisper)
        val whisperPaths = resources.getStringArray(R.array.models_path_whisper)
        val whisperUrls = resources.getStringArray(R.array.models_url_whisper)
        whisperModels = whisperNames.mapIndexed { index, name ->
            Model(name, whisperPaths[index], whisperUrls[index])
        }
        updateSpinners()
    }

    private fun updateSpinners() {
        val llmAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, llmModels.map { it.name })
        llmAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        model_name_select.adapter = llmAdapter

        val whisperAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, whisperModels.map { it.name })
        whisperAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        model_name_select_stt.adapter = whisperAdapter
        updateDownloadStatus()
    }

    fun updateDownloadStatus() {
        if (llmModels.isEmpty() || whisperModels.isEmpty()) return

        val llmPosition = model_name_select.selectedItemPosition
        val sttPosition = model_name_select_stt.selectedItemPosition

        var modelReady = true
        if (modelDownloader.isModelDownloaded(llmModels[llmPosition].path)) {
            download_btn.setImageResource(R.drawable.delect)
        } else {
            download_btn.setImageResource(R.drawable.download)
            modelReady = false
        }
        if (modelDownloader.isModelDownloaded(whisperModels[sttPosition].path)) {
            download_btn_stt.setImageResource(R.drawable.delect)
        } else {
            download_btn_stt.setImageResource(R.drawable.download)
            if (whisper_stt.isChecked) modelReady = false
        }
        start_btn.isEnabled = modelReady
    }
    
    private fun handleModelDownload(model: Model) {
        if (!modelDownloader.isModelDownloaded(model.path)) {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("模型下載")
            builder.setMessage("確定下載模型: ${model.name} ?")

            builder.setPositiveButton("下載") { dialog: DialogInterface, _: Int ->
                showInfo(this, "開始下載模型!", 1, Toast.LENGTH_SHORT)
                modelDownloader.downloadModel(model.path, model.url) {
                    showInfo(this, "模型下載完成!", 1, Toast.LENGTH_SHORT)
                    updateDownloadStatus()
                }
                dialog.dismiss()
            }
            builder.setNegativeButton("取消") { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.show()
        } else {
            val builder = AlertDialog.Builder(this)
            builder.setTitle("刪除模型")
            builder.setMessage("確定刪除模型: ${model.name} ?")

            builder.setPositiveButton("刪除") { dialog: DialogInterface, _: Int ->
                if (modelDownloader.removeModel(model.path)) {
                    showInfo(this, "模型刪除成功!", 1, Toast.LENGTH_SHORT)
                } else {
                    showInfo(this, "模型刪除失敗!", 3, Toast.LENGTH_SHORT)
                }
                updateDownloadStatus()
                dialog.dismiss()
            }
            builder.setNegativeButton("取消") { dialog: DialogInterface, _: Int ->
                dialog.dismiss()
            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        logInfo("")
        if (resultCode == RESULT_OK) {
            if (data != null) {
                val returnCode = data.getIntExtra("RETURN", REQUEST_CODE_UNKNOW_ERROR)
                when (returnCode) {
                    REQUEST_CODE_OK -> {
                        updateDownloadStatus()
                    }
                    REQUEST_CODE_INIT_ERROR -> showInfo(this, "模型初始化失敗!", 3, Toast.LENGTH_LONG)
                    REQUEST_CODE_PERMISSIONS_ERROR -> showInfo(this, "權限不足!", 3, Toast.LENGTH_LONG)
                    REQUEST_CODE_UNKNOW_ERROR -> showInfo(this, "未知錯誤!", 3, Toast.LENGTH_LONG)
                }
            }
        }

        InferenceManager.close()
    }


    fun showInfo(context: Context, str: String, level: Int = 1, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(context, str, Toast.LENGTH_SHORT).show()
        logInfo(str, level)
    }

    fun logInfo(str: String = "", level: Int = 1) {
        if (str.isEmpty()) {
            info_tv.text = ""
            return
        }
        val fullText: String
        val foregroundSpan: ForegroundColorSpan
        when (level) {
            0 -> {
                fullText = "[ D ] $str"
                foregroundSpan = ForegroundColorSpan(Color.BLUE)
            }

            1 -> {
                fullText = "[ I ] $str"
                foregroundSpan = ForegroundColorSpan(Color.GREEN)
            }

            2 -> {
                fullText = "[ W ] $str"
                foregroundSpan = ForegroundColorSpan(Color.YELLOW)
            }

            else -> {
                fullText = "[ E ] $str"
                foregroundSpan = ForegroundColorSpan(Color.RED)
            }
        }
        val spannableString = SpannableString(fullText)
        spannableString.setSpan(foregroundSpan, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        info_tv.text = spannableString
    }
    
    companion object {
        const val REQUEST_CODE_OK = 0
        const val REQUEST_CODE_INIT_ERROR = 1
        const val REQUEST_CODE_PERMISSIONS_ERROR = 2
        const val REQUEST_CODE_UNKNOW_ERROR = 3
    }
}