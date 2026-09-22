package com.dmcore.core.overlay

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import com.dmcore.core.R
import com.dmcore.core.actions.ActionExecutor
import com.dmcore.core.network.BackendClient
import com.dmcore.core.network.InteractResult
import java.io.File

/**
 * Dibuja la tarjeta flotante semi-transparente (orbe + transcripción) sobre cualquier app,
 * usando SYSTEM_ALERT_WINDOW. La dispara CoreForegroundService al detectar "hey jarvis"
 * (con el audio capturado), o MainActivity en modo de prueba (con texto libre).
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var ringPulse: Animator? = null
    private var iconBreathe: Animator? = null
    private var player: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
        val testText = intent?.getStringExtra(EXTRA_TEST_TEXT)
        val audioPath = intent?.getStringExtra(EXTRA_AUDIO_PATH)
        when {
            message != null -> setTranscript(message)
            testText != null -> runInteraction(testText)
            audioPath != null -> runAudioInteraction(File(audioPath))
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_core, null)
        view.setOnClickListener { stopSelf() }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.BOTTOM
        }

        windowManager.addView(view, params)
        overlayView = view
        ringPulse = startRingPulse(view.findViewById(R.id.micRing))
        iconBreathe = startIconBreathing(view.findViewById(R.id.micIcon))
        playEntrance(view.findViewById(R.id.overlayBar))

        // Se retira sola si nadie interactúa, para no quedar pegada en pantalla.
        mainHandler.postDelayed({ stopSelf() }, AUTO_DISMISS_MS)
    }

    /** Desliza la barra desde abajo con un leve rebote, en vez de aparecer de golpe. */
    private fun playEntrance(bar: View) {
        val density = resources.displayMetrics.density
        bar.translationY = 48f * density
        bar.alpha = 0f
        val translate = ObjectAnimator.ofFloat(bar, View.TRANSLATION_Y, bar.translationY, 0f).apply {
            interpolator = OvershootInterpolator(1.1f)
        }
        val fade = ObjectAnimator.ofFloat(bar, View.ALPHA, 0f, 1f).apply {
            interpolator = DecelerateInterpolator()
        }
        AnimatorSet().apply {
            playTogether(translate, fade)
            duration = 420
            start()
        }
    }

    /** Anillo del micrófono expandiéndose y desvaneciendo, con una curva de salida
     * (arranca rápido, frena suave) en vez de velocidad lineal — se siente orgánico. */
    private fun startRingPulse(ring: View): Animator {
        val easeOut = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        val scaleX = ObjectAnimator.ofFloat(ring, View.SCALE_X, 1f, 1.8f)
        val scaleY = ObjectAnimator.ofFloat(ring, View.SCALE_Y, 1f, 1.8f)
        val alpha = ObjectAnimator.ofFloat(ring, View.ALPHA, 0.7f, 0f)
        return AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 1600
            interpolator = easeOut
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    ring.scaleX = 1f
                    ring.scaleY = 1f
                    ring.alpha = 0.7f
                    animation.start()
                }
            })
            start()
        }
    }

    /** Pequeño "latido" del ícono central, desfasado del anillo, para que no se sienta
     * como una sola animación repetida en dos capas. */
    private fun startIconBreathing(icon: View): Animator {
        return ObjectAnimator.ofPropertyValuesHolder(
            icon,
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.14f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.14f),
        ).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            startDelay = 200
            start()
        }
    }

    private fun runInteraction(text: String) {
        setTranscript(text)
        BackendClient.interactWithText(text) { result -> handleInteractResult(result) }
    }

    private fun runAudioInteraction(audioFile: File) {
        BackendClient.interactWithAudio(audioFile) { result -> handleInteractResult(result) }
    }

    private fun handleInteractResult(result: Result<InteractResult>) {
        mainHandler.post {
            result.onSuccess {
                setTranscript(it.responseText)
                playResponseAudio(it.audioBase64)
                ActionExecutor.execute(applicationContext, it.action)
            }
            result.onFailure { setTranscript("Error hablando con el backend: ${it.message}") }
        }
    }

    private fun playResponseAudio(audioBase64: String) {
        try {
            val bytes = Base64.decode(audioBase64, Base64.DEFAULT)
            val file = File(cacheDir, "response_${System.currentTimeMillis()}.wav")
            file.writeBytes(bytes)
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { file.delete() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Si falla la reproducción, al menos el texto ya se mostró en el overlay.
        }
    }

    private fun setTranscript(text: String) {
        overlayView?.findViewById<TextView>(R.id.transcriptText)?.text = text
    }

    override fun onDestroy() {
        ringPulse?.cancel()
        ringPulse = null
        iconBreathe?.cancel()
        iconBreathe = null
        player?.release()
        player = null
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEST_TEXT = "test_text"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_AUDIO_PATH = "audio_path"
        private const val AUTO_DISMISS_MS = 25_000L
    }
}
