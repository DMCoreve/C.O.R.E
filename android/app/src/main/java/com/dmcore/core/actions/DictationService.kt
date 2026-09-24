package com.dmcore.core.actions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.dmcore.core.R
import com.dmcore.core.network.BackendClient
import com.dmcore.core.overlay.OverlayService
import java.io.File

/**
 * Graba audio de forma continua (a diferencia de OverlayService, que captura una sola
 * frase) hasta que el usuario la detiene desde la notificación, y sube la grabación
 * completa a POST /note para transcribirla y guardarla.
 */
class DictationService : Service() {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxDurationRunnable = Runnable { stopAndUpload() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAndUpload()
        } else {
            startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        startForeground(NOTIFICATION_ID, buildNotification())

        val file = File(cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
        outputFile = file

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        recorder = mediaRecorder
        isRecording = true

        showOverlayMessage("Grabando nota… toca \"Detener\" en la notificación cuando termines.")
        mainHandler.postDelayed(maxDurationRunnable, MAX_DURATION_MS)
    }

    private fun stopAndUpload() {
        mainHandler.removeCallbacks(maxDurationRunnable)

        val file = outputFile
        val activeRecorder = recorder
        recorder = null
        isRecording = false
        outputFile = null

        if (activeRecorder == null || file == null) {
            stopSelf()
            return
        }

        try {
            activeRecorder.stop()
        } catch (e: RuntimeException) {
            // Grabación demasiado corta para producir datos válidos; se descarta.
        }
        activeRecorder.release()

        BackendClient.uploadNote(file) { result ->
            mainHandler.post {
                result.onSuccess { showOverlayMessage("Nota guardada: ${it.transcript}") }
                result.onFailure { showOverlayMessage("No pude guardar la nota: ${it.message}") }
                file.delete()
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showOverlayMessage(message: String) {
        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_MESSAGE, message)
        }
        startService(intent)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.dictation_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, DictationService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.dictation_notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.dictation_stop_action),
                stopPendingIntent,
            )
            .build()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        recorder?.let {
            try {
                it.stop()
            } catch (e: RuntimeException) {
                // ya detenido o sin datos suficientes
            }
            it.release()
        }
        recorder = null
        isRecording = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dmcore.core.action.STOP_DICTATION"
        private const val CHANNEL_ID = "core_dictation"
        private const val NOTIFICATION_ID = 2
        private const val MAX_DURATION_MS = 5 * 60 * 1000L

        /** CoreForegroundService no escucha el wake word mientras se graba una nota. */
        @Volatile var isRecording = false
            private set
    }
}
