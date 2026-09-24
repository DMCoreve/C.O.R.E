package com.dmcore.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dmcore.core.MainActivity
import com.dmcore.core.R
import com.dmcore.core.actions.DictationService
import com.dmcore.core.network.BackendClient
import com.dmcore.core.overlay.OverlayService
import com.dmcore.core.wakeword.WakeWordDetector

/**
 * Servicio en primer plano que escucha "Jupiter" (wake word de la comunidad de
 * openWakeWord, pronunciado en inglés; ver docs/ARQUITECTURA.md).
 *
 * Al detectarla suelta el micrófono y abre OverlayService en modo escucha: ahí el
 * SpeechRecognizer del teléfono transcribe la orden (rápido y con fin de frase
 * automático). Cuando el overlay se cierra, manda ACTION_RESUME y se vuelve a escuchar.
 */
class CoreForegroundService : Service() {

    private lateinit var detector: WakeWordDetector
    private var audioRecord: AudioRecord? = null
    private var listenerThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var pausedAt = 0L
    @Volatile private var pauseRequested = false

    private val keepAwake = object : Runnable {
        override fun run() {
            BackendClient.warmUp()
            mainHandler.postDelayed(this, KEEP_AWAKE_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        detector = WakeWordDetector(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: RuntimeException) {
            // Android 14+ no deja abrir el micrófono en primer plano desde segundo plano
            // (p. ej. cuando el sistema reinicia el servicio solo por START_STICKY).
            // Mejor apagarse que entrar en un loop de crashes; se reactiva desde la app.
            Log.e(TAG, "No se pudo pasar a primer plano", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESUME) {
            paused = false
        } else if (intent?.action == ACTION_PAUSE) {
            pauseRequested = true
        } else if (!running) {
            startListening()
            mainHandler.removeCallbacks(keepAwake)
            mainHandler.post(keepAwake)
        }
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
            record.release()
            return
        }

        audioRecord = record
        running = true
        isRunning = true
        record.startRecording()
        Log.i(TAG, "Escuchando (recordingState=${record.recordingState}, buffer=$bufferSize)")

        listenerThread = Thread(this::listenLoop, "core-wakeword").apply { start() }
    }

    private fun listenLoop() {
        try {
            listenLoopInner()
        } catch (t: Throwable) {
            Log.e(TAG, "El hilo de escucha murió", t)
        }
    }

    /** Todas las operaciones sobre AudioRecord ocurren en este hilo. */
    private fun listenLoopInner() {
        val record = audioRecord ?: return
        val chunk = ShortArray(WakeWordDetector.CHUNK_SAMPLES)
        // Diagnóstico: cada ~2s loguea el volumen del mic y el score máximo del
        // wake word, para distinguir "no llega audio" de "el modelo no dispara".
        var diagChunks = 0
        var diagMaxScore = 0f
        var diagMaxRms = 0.0

        while (running) {
            if (pauseRequested) {
                pauseRequested = false
                pauseMic(record)
            }
            // Una nota de dictado usa el micrófono con MediaRecorder: no competir.
            if (DictationService.isRecording) {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                Thread.sleep(200)
                continue
            }
            if (paused) {
                // Red de seguridad: si el overlay nunca avisa (se cayó), no quedarse
                // sordo para siempre.
                if (SystemClock.elapsedRealtime() - pausedAt > MAX_PAUSE_MS) paused = false
                if (paused) {
                    Thread.sleep(100)
                    continue
                }
            }
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                detector.reset()
                record.startRecording()
                Log.i(TAG, "Escucha reanudada")
            }

            val read = record.read(chunk, 0, chunk.size)
            if (read != chunk.size) {
                if (read < 0) Log.w(TAG, "AudioRecord.read devolvió error $read")
                continue
            }

            val score = detector.processChunk(chunk) ?: continue

            var sumSq = 0.0
            for (s in chunk) sumSq += s * s.toDouble()
            diagMaxRms = maxOf(diagMaxRms, Math.sqrt(sumSq / chunk.size))
            diagMaxScore = maxOf(diagMaxScore, score)
            if (++diagChunks >= DIAG_EVERY_CHUNKS) {
                Log.d(TAG, "diag: rmsMax=${diagMaxRms.toInt()} scoreMax=${"%.3f".format(diagMaxScore)}")
                diagChunks = 0
                diagMaxScore = 0f
                diagMaxRms = 0.0
            }

            if (score > WAKE_THRESHOLD) {
                Log.i(TAG, "Wake word detectada (score=$score)")
                pauseMic(record)
                startService(
                    Intent(this, OverlayService::class.java).putExtra(OverlayService.EXTRA_LISTEN, true),
                )
            }
        }
    }

    /** Suelta el micrófono para que SpeechRecognizer lo pueda usar. Solo desde el hilo de escucha. */
    private fun pauseMic(record: AudioRecord) {
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        pausedAt = SystemClock.elapsedRealtime()
        paused = true
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

        // Atajo por si el wake word no dispara: tocar "Hablar" abre la escucha directo.
        val talkIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, OverlayService::class.java).putExtra(OverlayService.EXTRA_LISTEN, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_listening))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, getString(R.string.notification_talk_action), talkIntent)
            .build()
    }

    override fun onDestroy() {
        running = false
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        listenerThread?.join(500)
        audioRecord?.let {
            if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
            it.release()
        }
        audioRecord = null
        detector.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CoreForegroundService"
        private const val CHANNEL_ID = "core_listening"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_THRESHOLD = 0.5f
        private const val DIAG_EVERY_CHUNKS = 25 // ~2s
        private const val MAX_PAUSE_MS = 90_000L

        // Render Free duerme el servicio a los 15 min sin tráfico; 10 min lo mantiene
        // despierto mientras C.O.R.E. escucha (cabe en las 750 h/mes gratis).
        private const val KEEP_AWAKE_INTERVAL_MS = 10 * 60 * 1000L

        const val ACTION_RESUME = "com.dmcore.core.action.RESUME_LISTENING"
        const val ACTION_PAUSE = "com.dmcore.core.action.PAUSE_LISTENING"

        /** Para que OverlayService solo pida reanudar si este servicio está corriendo. */
        @Volatile var isRunning = false
            private set

        /** Suelta el micrófono mientras otro componente escucha (no-op si no está activo). */
        fun pause(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, CoreForegroundService::class.java).setAction(ACTION_PAUSE),
            )
        }

        /** Vuelve a escuchar el wake word tras una interacción (no-op si no está activo). */
        fun resume(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, CoreForegroundService::class.java).setAction(ACTION_RESUME),
            )
        }
    }
}
