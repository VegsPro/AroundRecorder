package com.audioshow.ms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileInputStream
import java.util.Calendar
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.audioshow.ms.START"
        const val ACTION_STOP = "com.audioshow.ms.STOP"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "recording_channel"
        const val SEGMENT_MS = 15 * 60 * 1000L

        @Volatile
        var isRunning = false
            private set
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var segmentStartedAt: Long = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val isRecording = AtomicBoolean(false)
    private var segmentRunnable: Runnable? = null

    private val monthsRu = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioShow::WakeLock").apply {
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
        try {
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
        } catch (_: Exception) {
        }
        startForeground(NOTIFICATION_ID, buildNotification("Запись началась…"))
        val ok = startNewSegment()
        if (ok) {
            scheduleNextSegment()
        } else {
            // failed to start — stop cleanly
            isRecording.set(false)
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        isRecording.set(false)
        isRunning = false
        segmentRunnable?.let { handler.removeCallbacks(it) }
        // Finalize current segment — file will contain everything recorded so far
        finalizeCurrentSegment()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Safe filename without ':' or other illegal chars.
     * Example: "14-33 - 1 ноября 2026г.m4a"
     */
    private fun makeFileName(start: Date = Date()): String {
        val cal = Calendar.getInstance().apply { time = start }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val month = monthsRu[cal.get(Calendar.MONTH)]
        val year = cal.get(Calendar.YEAR)
        // Use hyphen instead of colon — colon breaks MediaRecorder / filesystem
        return "%02d-%02d - %d %s %dг.m4a".format(h, m, day, month, year)
    }

    /** @return true if recording started successfully */
    private fun startNewSegment(): Boolean {
        // Finalize previous segment first (so it is not empty)
        finalizeCurrentSegment()

        val dir = getRecordingsDir()
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val fileName = makeFileName()
        val file = File(dir, fileName)
        currentFile = file
        segmentStartedAt = System.currentTimeMillis()

        return try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(128000)
            recorder.setAudioSamplingRate(44100)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            updateNotification("Идёт запись: ${file.name}")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            mediaRecorder = null
            // remove empty broken file
            try { if (file.exists() && file.length() == 0L) file.delete() } catch (_: Exception) {}
            currentFile = null
            updateNotification("Ошибка записи: ${e.message}")
            false
        }
    }

    /**
     * Stop MediaRecorder so the file is finalized with all audio recorded so far.
     * Then copy a public copy into Download/AudioShow.
     */
    private fun finalizeCurrentSegment() {
        val file = currentFile
        val recorder = mediaRecorder
        mediaRecorder = null
        currentFile = null

        if (recorder == null) return

        try {
            recorder.stop()
        } catch (_: Exception) {
            // stop can throw if nothing was recorded yet
        }
        try {
            recorder.reset()
        } catch (_: Exception) {
        }
        try {
            recorder.release()
        } catch (_: Exception) {
        }

        if (file == null) return

        // Delete empty / tiny broken files (< 1 KB)
        if (!file.exists() || file.length() < 1024) {
            try { file.delete() } catch (_: Exception) {}
            return
        }

        // Copy to public Download/AudioShow so user can see files in Downloads
        copyToPublicDownloads(file)
    }

    private fun copyToPublicDownloads(source: File) {
        try {
            val name = source.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AudioShow")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(source).use { inp -> inp.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "AudioShow"
                )
                if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, name)
                source.copyTo(dest, overwrite = true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        if (isRecording.get()) {
            stopRecording()
        }
        super.onDestroy()
    }
}
