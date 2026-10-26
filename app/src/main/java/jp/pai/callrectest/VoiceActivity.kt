package jp.pai.callrectest

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.TelephonyManager
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音声認識テスト。大きなボタンをタップすると SpeechRecognizer で認識し、結果を履歴に残す。
 * 通話中に Google の認識エンジンが動くかを確かめるためのもの。
 */
class VoiceActivity : Activity() {

    companion object {
        const val PREFS_NAME = "VoicePrefs"
        const val KEY_HISTORY = "history"
        const val KEY_OFFLINE = "prefer_offline"
    }

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private lateinit var button: Button
    private lateinit var status: TextView
    private lateinit var history: TextView
    private lateinit var offline: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        button = Button(this).apply {
            textSize = 28f
            text = "タップして話す"
            setOnClickListener { if (listening) stopListening() else startListening() }
        }
        status = TextView(this).apply {
            textSize = 20f
            setPadding(0, pad, 0, pad)
        }
        offline = CheckBox(this).apply {
            text = "オフライン認識を優先"
            isChecked = prefs.getBoolean(KEY_OFFLINE, false)
            setOnCheckedChangeListener { _, b -> prefs.edit().putBoolean(KEY_OFFLINE, b).apply() }
        }
        history = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            text = prefs.getString(KEY_HISTORY, "")
        }
        val clear = Button(this).apply {
            text = "履歴を消す"
            setOnClickListener {
                prefs.edit().remove(KEY_HISTORY).apply()
                history.text = ""
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (160 * resources.displayMetrics.density).toInt()))
            addView(status)
            addView(offline)
            addView(ScrollView(this@VoiceActivity).apply { addView(history) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(clear, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        }
        setContentView(root)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "音声認識エンジンが見つかりません（Google アプリが必要）"
            button.isEnabled = false
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        // 起動したらすぐ聞き始める（タップ1回で話せるように）
        startListening()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening()
        else status.text = "マイクの許可が必要です"
    }

    private fun startListening() {
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline.isChecked)
        recognizer = r
        listening = true
        button.text = "聞いています…（タップで中止）"
        status.text = if (inCall()) "通話中" else ""
        r.startListening(intent)
    }

    private fun stopListening() {
        recognizer?.cancel()
        listening = false
        button.text = "タップして話す"
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            status.text = (if (inCall()) "通話中：" else "") + "どうぞ"
            vibrate(60)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                status.text = it
            }
        }

        override fun onResults(results: Bundle?) {
            listening = false
            button.text = "タップして話す"
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrEmpty()) {
                status.text = "（認識結果なし）"
                return
            }
            status.text = text
            addHistory(text)
            vibrate(150)
        }

        override fun onError(error: Int) {
            listening = false
            button.text = "タップして話す"
            status.text = "エラー: " + errorName(error) + if (inCall()) "（通話中）" else ""
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun addHistory(text: String) {
        val time = SimpleDateFormat("MM/dd HH:mm", Locale.US).format(Date())
        val line = "$time ${if (inCall()) "[通話中] " else ""}$text\n"
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = line + prefs.getString(KEY_HISTORY, "")
        prefs.edit().putString(KEY_HISTORY, all).apply()
        history.text = all
    }

    private fun errorName(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_AUDIO -> "録音エラー (AUDIO)"
        SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "権限不足"
        SpeechRecognizer.ERROR_NETWORK -> "ネットワーク"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
        SpeechRecognizer.ERROR_NO_MATCH -> "聞き取れませんでした (NO_MATCH)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識エンジン使用中 (BUSY)"
        SpeechRecognizer.ERROR_SERVER -> "サーバー"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "発話なし (SPEECH_TIMEOUT)"
        else -> "不明 ($e)"
    }

    private fun inCall(): Boolean =
        (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState == TelephonyManager.CALL_STATE_OFFHOOK

    private fun vibrate(ms: Long) {
        (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onPause() {
        // 画面を離れたら認識を止める
        if (listening) stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }
}
