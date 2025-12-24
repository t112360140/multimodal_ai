package io.github.t112360140.multimodal_ai

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

class ModelDownloader(private val context: Context) {

    fun isModelDownloaded(path: String): Boolean {
        val destinationFile = File(context.getExternalFilesDir(null), path)
        return destinationFile.exists() && destinationFile.length() > 0
    }

    fun getModelPath(path: String): String {
        val destinationFile = File(context.getExternalFilesDir(null), path)
        return destinationFile.absolutePath
    }

    fun removeModel(path: String): Boolean {
        val destinationFile = File(context.getExternalFilesDir(null), path)
        return destinationFile.delete()
    }

    fun downloadModel(path:String, modelUrl: String, onComplete: () -> Unit) {
        if (isModelDownloaded(path)) {
            onComplete()
            return
        }
        val destinationFile = File(context.getExternalFilesDir(null), path)

        val request = DownloadManager.Request(Uri.parse(modelUrl))
            .setTitle("下載模型")
            .setDescription("模型下載中...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(destinationFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        // 註冊廣播接收器監聽下載完成
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val statusColumn = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusColumn != -1) {
                            when (cursor.getInt(statusColumn)) {
                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    onComplete()
                                }
                                DownloadManager.STATUS_FAILED -> {
                                    val reasonColumn = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    val reason = if (reasonColumn != -1) cursor.getInt(reasonColumn) else -1
                                }
                            }
                        }
                    }
                    cursor.close()

                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }
}