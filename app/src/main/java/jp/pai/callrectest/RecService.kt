package jp.pai.callrectest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.TelephonyManager
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * キーを押すたびに 待機 → 録音 → 再生 → 待機 と切り替える。
 * 録音中は最大振幅を記録し、停止時に表示する（0 のままなら通話中にマイクが取れていない）。
 * 再生は通話中ならスピーカーホンに切り替えて鳴らし、相手側（留守電）に届くかを確認する。
 */
class RecService : Service() {

    companion object {
        const val ACTION_TOGGLE = "jp.pai.callrectest.TOGGLE"
        const val CHANNEL_ID = "rec"
        const val NOTIFICATION_ID = 1
    }

    private enum class State { IDLE, RECORDING, PLAYING }

    private var state = State.IDLE
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var peak = 0
    private var savedSpeaker: Boolean? = null
    private val handler = Handler(Looper.getMainLooper())

    // 録音中の最大振幅を 100ms ごとに拾う
    private val peakPoller = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            try {
                peak = maxOf(peak, r.maxAmplitude)
            } catch (e: Exception) {
            }
            handler.postDelayed(this, 100)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "録音テスト", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService の後は必ず startForeground を呼ぶ
        startForeground(NOTIFICATION_ID, notification(stateLabel()))
        if (intent?.action == ACTION_TOGGLE) {
            when (state) {
                State.IDLE -> startRecording()
                State.RECORDING -> {
                    stopRecording()
                    startPlaying()
                }
                State.PLAYING -> stopPlaying()
            }
        }
        if (state == State.IDLE) {
            stopForeground(true)
            stopSelf()
        } else {
            updateNotification()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(getExternalFilesDir(null), "rec_$name.m4a")
        peak = 0
        try {
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            state = State.RECORDING
            handler.post(peakPoller)
            vibrate(longArrayOf(0, 80))
            toast(if (inCall()) "録音開始（通話中）" else "録音開始")
        } catch (e: Exception) {
            recorder?.release()
            recorder = null
            toast("録音開始に失敗: ${e.message}")
            vibrate(longArrayOf(0, 300))
        }
    }

    private fun stopRecording() {
        handler.removeCallbacks(peakPoller)
        val r = recorder ?: return
        try {
            peak = maxOf(peak, r.maxAmplitude)
            r.stop()
        } catch (e: Exception) {
            // 短すぎる録音などで stop が失敗することがある
        }
        r.release()
        recorder = null
        state = State.IDLE
        toast("録音停止 最大振幅=$peak" + if (peak == 0) "（無音：マイクが取れていない可能性）" else "")
    }

    private fun startPlaying() {
        val f = file ?: return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // 通話中はスピーカーホンにして、相手側マイクへ音を回り込ませる
            if (inCall()) {
                savedSpeaker = am.isSpeakerphoneOn
                am.isSpeakerphoneOn = true
            }
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { stopPlaying(); finishIfIdle() }
            p.prepare()
            p.start()
            player = p
            state = State.PLAYING
            vibrate(longArrayOf(0, 80, 100, 80))
        } catch (e: Exception) {
            toast("再生に失敗: ${e.message}")
            restoreSpeaker()
            state = State.IDLE
        }
    }

    private fun stopPlaying() {
        player?.let {
            try {
                it.stop()
            } catch (e: Exception) {
            }
            it.release()
        }
        player = null
        restoreSpeaker()
        state = State.IDLE
        vibrate(longArrayOf(0, 40))
    }

    private fun finishIfIdle() {
        if (state == State.IDLE) {
            stopForeground(true)
            stopSelf()
        }
    }

    private fun restoreSpeaker() {
        val s = savedSpeaker ?: return
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = s
        savedSpeaker = null
    }

    private fun inCall(): Boolean =
        (getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager).callState == TelephonyManager.CALL_STATE_OFFHOOK

    private fun stateLabel(): String = when (state) {
        State.IDLE -> "待機中"
        State.RECORDING -> "録音中（キーで停止→再生）"
        State.PLAYING -> "再生中（キーで停止）"
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("通話録音テスト")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(stateLabel()))
    }

    private fun vibrate(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(peakPoller)
        recorder?.release()
        player?.release()
        restoreSpeaker()
        super.onDestroy()
    }
}
