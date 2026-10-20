package com.dad.tablet

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater: downloads the latest tablet APK from the GitHub "latest"
 * release and launches the system installer. Because every build is signed with
 * the same key, the new version installs over the old one without uninstalling.
 */
object Updater {

    private const val APK_URL =
        "https://github.com/ahmad-ksa/dad/releases/download/latest/dad-tablet.apk"

    fun checkAndInstall(context: Context) {
        val app = context.applicationContext
        val ui = Handler(Looper.getMainLooper())
        ui.post { Toast.makeText(app, R.string.update_downloading, Toast.LENGTH_SHORT).show() }
        Thread {
            val apk = runCatching { download(app) }.getOrNull()
            ui.post {
                if (apk == null) {
                    Toast.makeText(app, R.string.update_failed, Toast.LENGTH_LONG).show()
                } else {
                    install(context, apk)
                }
            }
        }.start()
    }

    private fun download(context: Context): File {
        val out = File(context.externalCacheDir ?: context.cacheDir, "dad-update.apk")
        val conn = (URL(APK_URL).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
        }
        conn.connect()
        conn.inputStream.use { input -> out.outputStream.use { input.copyTo(it) } }
        conn.disconnect()
        return out
    }

    private fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
