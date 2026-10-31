package com.vegspro.aroundrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.vegspro.aroundrecorder.START"
        const val ACTION_STOP = "com.vegspro.aroundrecorder.STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "recording_channel"
        const val SEGMENT_MS = 15 * 60 * 1000L // 15 minutes

        @Volatile
        var isRunning = false
            private set
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val isRecording = AtomicBoolean(false)
    private var segmentRunnable: Runnable? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AroundRecorder::WakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun startRecording() {
        if (isRecording.get()) return
        isRecording.set(true)
        isRunning = true
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // up to 24h, will re-acquire if needed
        startForeground(NOTIFICATION_ID, buildNotification("Запись началась…"))
        startNewSegment()
        scheduleNextSegment()
    }

    private fun stopRecording() {
        isRecording.set(false)
        isRunning = false
        segmentRunnable?.let { handler.removeCallbacks(it) }
        stopCurrentRecorder()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startNewSegment() {
        stopCurrentRecorder()
        val dir = getRecordingsDir()
        if (!dir.exists()) dir.mkdirs()

        val fileName = "rec_${dateFormat.format(Date())}.m4a"
        currentFile = File(dir, fileName)

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentFile!!.absolutePath)
                prepare()
                start()
            }
            updateNotification("Идёт запись: ${currentFile!!.name}")
        } catch (e: Exception) {
            e.printStackTrace()
            updateNotification("Ошибка записи: ${e.message}")
            isRecording.set(false)
            isRunning = false
        }
    }

    private fun stopCurrentRecorder() {
        try {
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
        } catch (_: Exception) {
        }
        mediaRecorder = null
    }

    private fun scheduleNextSegment() {
        segmentRunnable = Runnable {
            if (isRecording.get()) {
                startNewSegment()
                scheduleNextSegment()
            }
        }
        handler.postDelayed(segmentRunnable!!, SEGMENT_MS)
    }

    private fun getRecordingsDir(): File {
        // App-specific external storage — no special permission needed on modern Android
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "AroundRecordings")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Постоянная запись окружения"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
