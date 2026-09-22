package com.dmcore.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dmcore.core.R
import com.dmcore.core.overlay.OverlayService
import com.dmcore.core.wakeword.WakeWordDetector
import com.dmcore.core.wakeword.WavUtil

/**
 * Servicio en primer plano que escucha "hey jarvis" (wake word preentrenado de
 * openWakeWord; ver docs/ENTRENAR_WAKE_WORD.md sobre por qué no es "core" todavía).
 * Al detectarla, captura unos segundos de audio y los manda a OverlayService para
 * que hable con el backend.
 */
class CoreForegroundService : Service() {

    private lateinit var detector: WakeWordDetector
    private var audioRecord: AudioRecord? = null
    private var listenerThread: Thread? = null

    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        detector = WakeWordDetector(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (!running) startListening()
        return START_STICKY
    }

    private fun startListening() {
        val minBuf = AudioRecord.getMinBufferSize(
            WakeWordDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf, WakeWordDetector.CHUNK_SAMPLES * 2 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            WakeWordDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord no se pudo inicializar")
            return
        }

        audioRecord = record
        running = true
        record.startRecording()

        listenerThread = Thread(this::listenLoop, "core-wakeword").apply { start() }
    }

    private fun listenLoop() {
        val record = audioRecord ?: return
        val chunk = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
        var capturing = false
        val captureBuffer = ArrayList<Short>(CAPTURE_MAX_SAMPLES)

        while (running) {
            val read = record.read(chunk, 0, chunk.size)
            if (read != chunk.size) continue

            if (capturing) {
                for (s in chunk) captureBuffer.add(s)
                if (captureBuffer.size >= CAPTURE_MAX_SAMPLES) {
                    finishCapture(captureBuffer)
                    captureBuffer.clear()
                    capturing = false
                }
                continue
            }

            val score = detector.processChunk(chunk) ?: continue
            if (score > WAKE_THRESHOLD) {
                Log.d(TAG, "Wake word detectada (score=$score)")
                startService(
                    Intent(this, OverlayService::class.java)
                        .putExtra(OverlayService.EXTRA_MESSAGE, "Te escucho…"),
                )
                capturing = true
                captureBuffer.clear()
            }
        }
    }

    private fun finishCapture(samples: List<Short>) {
        val wavFile = WavUtil.writeWav(cacheDir, samples.toShortArray(), WakeWordDetector.SAMPLE_RATE)
        startService(
            Intent(this, OverlayService::class.java)
                .putExtra(OverlayService.EXTRA_AUDIO_PATH, wavFile.absolutePath),
        )
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_listening))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        listenerThread?.join(500)
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        detector.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CoreForegroundService"
        private const val CHANNEL_ID = "core_listening"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_THRESHOLD = 0.5f
        private const val CAPTURE_SECONDS = 4
        private const val CAPTURE_MAX_SAMPLES = CAPTURE_SECONDS * WakeWordDetector.SAMPLE_RATE
    }
}
