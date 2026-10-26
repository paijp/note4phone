package jp.pai.callrectest

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * アプリ一覧から起動する更新確認画面。
 * rel/version.txt の versionCode が自分より新しければ、確認のうえ rel/CallRecTest.apk を取得してインストールする。
 */
class UpdateActivity : Activity() {

    companion object {
        const val BASE_URL = "https://raw.githubusercontent.com/paijp/note4phone/main/rel/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "更新を確認中… (現在 ${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
        Thread {
            val latest = try {
                fetch(BASE_URL + "version.txt").toString(Charsets.UTF_8).trim().toInt()
            } catch (e: Exception) {
                null
            }
            runOnUiThread { onChecked(latest) }
        }.start()
    }

    private fun onChecked(latest: Int?) {
        if (latest == null) {
            done("更新の確認に失敗しました")
            return
        }
        if (latest <= BuildConfig.VERSION_CODE) {
            done("最新版です (${BuildConfig.VERSION_NAME})")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("更新があります")
            .setMessage("1.0.${BuildConfig.VERSION_CODE} → 1.0.$latest\nダウンロードしてインストールしますか？")
            .setPositiveButton("OK") { _, _ -> download() }
            .setNegativeButton("キャンセル") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun download() {
        // 提供元不明アプリのインストール許可（Android 8 以降はアプリごと）
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            done("このアプリからのインストールを許可して、もう一度起動してください")
            return
        }
        Toast.makeText(this, "ダウンロード中…", Toast.LENGTH_SHORT).show()
        Thread {
            val file = File(cacheDir, "update.apk")
            val ok = try {
                file.writeBytes(fetch(BASE_URL + "CallRecTest.apk"))
                true
            } catch (e: Exception) {
                false
            }
            runOnUiThread {
                if (ok) install(file) else done("ダウンロードに失敗しました")
            }
        }.start()
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        finish()
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.useCaches = false
        c.connectTimeout = 10000
        c.readTimeout = 30000
        try {
            if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun done(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
