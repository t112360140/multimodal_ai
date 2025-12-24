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
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    private lateinit var modelDownloader: ModelDownloader

    private lateinit var model_name_select: Spinner
    private lateinit var download_btn: ImageButton
    private lateinit var model_name_select_stt: Spinner
    private lateinit var download_btn_stt: ImageButton
    private lateinit var use_gpu: RadioButton
    private lateinit var use_tts: Switch
    private lateinit var system_message: EditText

    private lateinit var info_tv: TextView
    private lateinit var start_btn: Button

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


        modelDownloader = ModelDownloader(this)

        model_name_select.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.models_name_llm,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        model_name_select.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateDownloadStatus()
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {

            }
        }

        download_btn.setOnClickListener { view ->
            val position = model_name_select.selectedItemPosition
            val model_name = resources.getStringArray(R.array.models_name_llm)[position]
            val model_path = resources.getStringArray(R.array.models_path_llm)[position]
            val model_url = resources.getStringArray(R.array.models_url_llm)[position]

            if(!modelDownloader.isModelDownloaded(model_path)){
                val builder = AlertDialog.Builder(this)
                builder.setTitle("模型下載")
                builder.setMessage("確定下載模型: $model_name ?")

                builder.setPositiveButton("下載") { dialog: DialogInterface, which: Int ->
                    showInfo(this, "開始下載模型!", 1, Toast.LENGTH_SHORT)
                    modelDownloader.downloadModel(model_path, model_url, {
                        showInfo(this, "模型下載完成!", 1, Toast.LENGTH_SHORT)
                        updateDownloadStatus()
                    })
                    dialog.dismiss() // Dismiss the dialog after the button is clicked
                }
                builder.setNegativeButton("取消") { dialog: DialogInterface, which: Int ->
                    dialog.dismiss()
                }
                val alertDialog: AlertDialog = builder.create()
                alertDialog.show()
            }else{
                val builder = AlertDialog.Builder(this)
                builder.setTitle("刪除模型")
                builder.setMessage("確定刪除模型: $model_name ?")

                builder.setPositiveButton("刪除") { dialog: DialogInterface, which: Int ->
                    if(modelDownloader.removeModel(model_path)) {
                        showInfo(this, "模型刪除成功!", 1, Toast.LENGTH_SHORT)
                    }else{
                        showInfo(this, "模型刪除失敗!", 3, Toast.LENGTH_SHORT)
                    }
                    updateDownloadStatus()

                    dialog.dismiss() // Dismiss the dialog after the button is clicked
                }
                builder.setNegativeButton("取消") { dialog: DialogInterface, which: Int ->
                    dialog.dismiss()
                }
                val alertDialog: AlertDialog = builder.create()
                alertDialog.show()
            }
        }

        model_name_select_stt.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.models_name_whisper,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        model_name_select_stt.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                updateDownloadStatus()
            }

            override fun onNothingSelected(p0: AdapterView<*>?) {

            }
        }

        download_btn_stt.setOnClickListener { view ->
            val position = model_name_select_stt.selectedItemPosition
            val model_name = resources.getStringArray(R.array.models_name_whisper)[position]
            val model_path = resources.getStringArray(R.array.models_path_whisper)[position]
            val model_url = resources.getStringArray(R.array.models_url_whisper)[position]

            if(!modelDownloader.isModelDownloaded(model_path)){
                val builder = AlertDialog.Builder(this)
                builder.setTitle("模型下載")
                builder.setMessage("確定下載模型: $model_name ?")

                builder.setPositiveButton("下載") { dialog: DialogInterface, which: Int ->
                    showInfo(this, "開始下載模型!", 1, Toast.LENGTH_SHORT)
                    modelDownloader.downloadModel(model_path, model_url, {
                        showInfo(this, "模型下載完成!", 1, Toast.LENGTH_SHORT)
                        updateDownloadStatus()
                    })
                    dialog.dismiss() // Dismiss the dialog after the button is clicked
                }
                builder.setNegativeButton("取消") { dialog: DialogInterface, which: Int ->
                    dialog.dismiss()
                }
                val alertDialog: AlertDialog = builder.create()
                alertDialog.show()
            }else{
                val builder = AlertDialog.Builder(this)
                builder.setTitle("刪除模型")
                builder.setMessage("確定刪除模型: $model_name ?")

                builder.setPositiveButton("刪除") { dialog: DialogInterface, which: Int ->
                    if(modelDownloader.removeModel(model_path)) {
                        showInfo(this, "模型刪除成功!", 1, Toast.LENGTH_SHORT)
                    }else{
                        showInfo(this, "模型刪除失敗!", 3, Toast.LENGTH_SHORT)
                    }
                    updateDownloadStatus()

                    dialog.dismiss() // Dismiss the dialog after the button is clicked
                }
                builder.setNegativeButton("取消") { dialog: DialogInterface, which: Int ->
                    dialog.dismiss()
                }
                val alertDialog: AlertDialog = builder.create()
                alertDialog.show()
            }
        }

        start_btn.setOnClickListener { view ->
            val position = model_name_select.selectedItemPosition
            val model_path = resources.getStringArray(R.array.models_path_llm)[position]
            val position_stt = model_name_select_stt.selectedItemPosition
            val model_path_stt = resources.getStringArray(R.array.models_path_whisper)[position_stt]

            start_btn.setEnabled(false)
            if(modelDownloader.isModelDownloaded(model_path)) {
                showInfo(this, "模型載入中...", 1, Toast.LENGTH_SHORT)
                lifecycleScope.launch {
                    val path = modelDownloader.getModelPath(model_path)
                    val success = InferenceManager.loadModel(this@MainActivity, path, use_gpu.isChecked)

                    if (success) {
                        runOnUiThread { showInfo(this@MainActivity, "模型載入成功!", 1, Toast.LENGTH_SHORT) }
                        val intent = Intent(this@MainActivity, MainActivity2::class.java)
                        val bundle = Bundle()
                        bundle.putString("modelName", model_name_select.selectedItem.toString())
                        bundle.putString("whisperModelPath", model_path_stt)
                        bundle.putBoolean("UseTts", use_tts.isChecked)
                        bundle.putString("systmMessage",
                            system_message.text.toString().ifEmpty { getString(R.string.default_system_message) })

                        intent.putExtras(bundle)

                        startActivityForResult(intent, 9)
                    } else {
                        runOnUiThread { showInfo(this@MainActivity, "模型載入失敗!", 3, Toast.LENGTH_SHORT) }
                        runOnUiThread { updateDownloadStatus() }
                    }
                }
            }else{
                showInfo(this, "模型不存在!", 3, Toast.LENGTH_SHORT)
                updateDownloadStatus()
            }
        }
    }

    fun updateDownloadStatus(){
        val position = model_name_select.selectedItemPosition
        val model_path = resources.getStringArray(R.array.models_path_llm)[position]

        val position_stt = model_name_select_stt.selectedItemPosition
        val model_path_stt = resources.getStringArray(R.array.models_path_whisper)[position_stt]

        var model_ready = true
        if(modelDownloader.isModelDownloaded(model_path)){
            download_btn.setImageResource(R.drawable.delect)
        }else{
            download_btn.setImageResource(R.drawable.download)
            model_ready = false
        }
        if(modelDownloader.isModelDownloaded(model_path_stt)){
            download_btn_stt.setImageResource(R.drawable.delect)
        }else{
            download_btn_stt.setImageResource(R.drawable.download)
            model_ready = false
        }
        start_btn.setEnabled(model_ready)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        logInfo("")
        if(resultCode == RESULT_OK) {
            if(data!=null){
                val return_code = data.getIntExtra("RETURN", REQUEST_CODE_UNKNOW_ERROR)
                when(return_code){
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
        if(str.isEmpty()) {
            info_tv.setText("")
            return
        }
        var fullText = ""
        var foregroundSpan: ForegroundColorSpan
        when (level) {
            0 -> {
                fullText = "[ D ] $str"
                foregroundSpan = ForegroundColorSpan(Color.BLUE);
            }
            1 -> {
                fullText = "[ I ] $str"
                foregroundSpan = ForegroundColorSpan(Color.GREEN);
            }
            2 -> {
                fullText = "[ W ] $str"
                foregroundSpan = ForegroundColorSpan(Color.YELLOW);
            }
            else -> {
                fullText = "[ E ] $str"
                foregroundSpan = ForegroundColorSpan(Color.RED);
            }
        }
        val spannableString = SpannableString(fullText)
        spannableString.setSpan(foregroundSpan, 0, 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        info_tv.setText(spannableString)
    }
}