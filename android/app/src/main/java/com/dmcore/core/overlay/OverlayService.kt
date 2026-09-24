package com.dmcore.core.overlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.dmcore.core.R
import com.dmcore.core.actions.ActionExecutor
import com.dmcore.core.network.BackendClient
import com.dmcore.core.network.BackendHttpException
import com.dmcore.core.network.InteractResult
import com.dmcore.core.service.CoreForegroundService
import java.util.Locale

/**
 * Isla flotante arriba de la pantalla (variante B del tablero de diseño), sobre
 * cualquier app vía SYSTEM_ALERT_WINDOW.
 *
 * Flujo de voz, todo lo pesado on-device para que responda rápido:
 *   EXTRA_LISTEN -> SpeechRecognizer del teléfono (transcripción en vivo y fin de
 *   frase automático) -> backend solo con el texto (Gemini) -> TextToSpeech del
 *   teléfono. Antes el audio viajaba a Render para Whisper + Piper y tardaba 30-40s.
 *
 * También acepta EXTRA_TEST_TEXT (botón de prueba) y EXTRA_MESSAGE (avisos sueltos,
 * p. ej. de DictationService).
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var orb: VoiceOrbView? = null
    private var waveBars: WaveBarsView? = null
    private var dotAnimators: List<ObjectAnimator> = emptyList()

    private var recognizer: SpeechRecognizer? = null
    private var recognitionHandled = false
    private var languageAttempt = 0

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var dismissing = false
    private var requestSeq = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable { dismiss() }

    // Si el reconocedor nunca contesta (ni resultado ni error), avisar en vez de cerrarse mudo.
    private val listenTimeout = Runnable {
        if (!recognitionHandled) {
            recognitionHandled = true
            showError(getString(R.string.overlay_error_no_speech))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        initTts()
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        revive()
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
        val testText = intent?.getStringExtra(EXTRA_TEST_TEXT)
        when {
            intent?.getBooleanExtra(EXTRA_LISTEN, false) == true -> startListening()
            testText != null -> runInteraction(testText)
            message != null -> showMessage(message)
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- ventana

    private fun showOverlay() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_core, null)
        view.findViewById<View>(R.id.overlayBar).setOnClickListener { dismiss() }

        // Debajo de la barra de estado, no encima de la hora ni de la cámara.
        val statusBarId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBar = if (statusBarId > 0) resources.getDimensionPixelSize(statusBarId) else 0
        view.setPadding(view.paddingLeft, statusBar + dp(6), view.paddingRight, view.paddingBottom)

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }

        try {
            windowManager.addView(view, params)
        } catch (e: RuntimeException) {
            // Sin permiso de superposición (lo revocaron): no hay dónde mostrar nada.
            Log.e(TAG, "No se pudo mostrar el overlay", e)
            stopSelf()
            return
        }
        overlayView = view
        orb = view.findViewById(R.id.voiceOrb)
        waveBars = view.findViewById(R.id.waveBars)
        dotAnimators = startDotAnimations(view.findViewById(R.id.thinkingDots))
        setState(State.LISTENING)
        playEntrance(view.findViewById(R.id.overlayBar))
    }

    private enum class State(val labelRes: Int, val colorRes: Int, val orbMode: VoiceOrbView.Mode) {
        LISTENING(R.string.overlay_state_listening, R.color.core_red, VoiceOrbView.Mode.LISTENING),
        THINKING(R.string.overlay_state_thinking, R.color.core_orange, VoiceOrbView.Mode.THINKING),
        SPEAKING(R.string.overlay_state_speaking, R.color.core_amber, VoiceOrbView.Mode.SPEAKING),
        ERROR(R.string.overlay_state_error, R.color.core_text_dim, VoiceOrbView.Mode.ERROR),
    }

    private fun setState(state: State) {
        val view = overlayView ?: return
        val color = ContextCompat.getColor(this, state.colorRes)

        orb?.setMode(state.orbMode, color)
        waveBars?.setMode(
            when (state) {
                State.LISTENING -> WaveBarsView.Mode.LEVEL
                State.SPEAKING -> WaveBarsView.Mode.SPEAKING
                else -> WaveBarsView.Mode.IDLE
            },
            color,
        )
        view.findViewById<TextView>(R.id.stateLabel).apply {
            setText(state.labelRes)
            setTextColor(color)
        }
        view.findViewById<View>(R.id.stateDot).backgroundTintList = ColorStateList.valueOf(color)

        // Pensando: tres puntos en lugar de texto.
        val thinking = state == State.THINKING
        view.findViewById<View>(R.id.thinkingDots).visibility = if (thinking) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.transcriptText).visibility = if (thinking) View.GONE else View.VISIBLE
    }

    private fun startDotAnimations(container: ViewGroup): List<ObjectAnimator> {
        val text = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.core_text))
        return (0 until container.childCount).map { i ->
            val dot = container.getChildAt(i)
            dot.backgroundTintList = text
            ObjectAnimator.ofFloat(dot, View.ALPHA, 0.25f, 1f).apply {
                duration = 480
                startDelay = i * 160L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }
    }

    /** Baja desde arriba con desaceleración suave y un leve zoom, estilo sistema. */
    private fun playEntrance(bar: View) {
        bar.translationY = -dp(40).toFloat()
        bar.alpha = 0f
        bar.scaleX = 0.96f
        bar.scaleY = 0.96f
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(bar, View.TRANSLATION_Y, 0f),
                ObjectAnimator.ofFloat(bar, View.ALPHA, 1f),
                ObjectAnimator.ofFloat(bar, View.SCALE_X, 1f),
                ObjectAnimator.ofFloat(bar, View.SCALE_Y, 1f),
            )
            duration = 460
            interpolator = PathInterpolator(0.2f, 0.9f, 0.1f, 1f)
            start()
        }
    }

    /** Si llega un comando nuevo mientras la isla se estaba yendo, la trae de vuelta. */
    private fun revive() {
        mainHandler.removeCallbacks(dismissRunnable)
        if (!dismissing) return
        dismissing = false
        overlayView?.findViewById<View>(R.id.overlayBar)?.apply {
            animate().cancel()
            translationY = 0f
            alpha = 1f
        }
    }

    private fun dismiss() {
        if (dismissing) return
        dismissing = true
        mainHandler.removeCallbacks(dismissRunnable)
        requestSeq++ // descarta una respuesta que llegue tarde
        stopRecognizer()
        tts?.stop()
        val bar = overlayView?.findViewById<View>(R.id.overlayBar)
        if (bar == null) {
            stopSelf()
            return
        }
        bar.animate()
            .translationY(-dp(30).toFloat())
            .alpha(0f)
            .setDuration(240)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction { if (dismissing) stopSelf() }
            .start()
    }

    private fun scheduleDismiss(delayMs: Long) {
        mainHandler.removeCallbacks(dismissRunnable)
        mainHandler.postDelayed(dismissRunnable, delayMs)
    }

    // ---------------------------------------------------------------- escuchar

    private fun startListening() {
        if (overlayView == null) return
        CoreForegroundService.pause(this) // el micrófono es de SpeechRecognizer ahora
        stopRecognizer()
        tts?.stop()
        requestSeq++
        setState(State.LISTENING)
        setQuery(null)
        setTranscript(getString(R.string.overlay_listening_hint))
        BackendClient.warmUp() // mientras hablas, Render se va despertando

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError(getString(R.string.overlay_error_recognizer))
            return
        }
        languageAttempt = 0
        launchRecognizer()
        mainHandler.removeCallbacks(dismissRunnable)
        mainHandler.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS)
    }

    private fun launchRecognizer() {
        recognitionHandled = false
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        rec.setRecognitionListener(listener)
        val language = recognitionLanguages()[languageAttempt]
        Log.i(TAG, "Reconociendo en $language")
        rec.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            },
        )
    }

    /** Español del teléfono primero (p. ej. es-VE); si el reconocedor no lo tiene, variantes comunes. */
    private fun recognitionLanguages(): List<String> {
        val device = Locale.getDefault()
        val first = if (device.language == "es") device.toLanguageTag() else "es-US"
        return listOf(first, "es-US", "es-ES").distinct()
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            overlayView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB va de ~-2 (silencio) a ~10 (voz fuerte).
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            orb?.setLevel(level)
            waveBars?.setLevel(level)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = firstResult(partialResults) ?: return
            if (text.isNotBlank()) setQuery(text, live = true)
        }

        override fun onResults(results: Bundle?) {
            if (recognitionHandled) return
            recognitionHandled = true
            orb?.setLevel(0f)
            waveBars?.setLevel(0f)
            val text = stripWakeWord(firstResult(results).orEmpty())
            Log.i(TAG, "Reconocido: \"$text\"")
            if (text.isBlank()) {
                showError(getString(R.string.overlay_error_no_speech))
            } else {
                runInteraction(text)
            }
        }

        override fun onError(error: Int) {
            if (recognitionHandled) return
            Log.w(TAG, "SpeechRecognizer error $error")
            val languages = recognitionLanguages()
            val languageProblem = error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
            if (languageProblem && languageAttempt + 1 < languages.size) {
                languageAttempt++
                stopRecognizer()
                launchRecognizer()
                mainHandler.postDelayed(listenTimeout, LISTEN_TIMEOUT_MS)
                return
            }
            recognitionHandled = true
            showError(
                getString(
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.overlay_error_no_speech
                        SpeechRecognizer.ERROR_AUDIO,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.overlay_error_mic
                        SpeechRecognizer.ERROR_NETWORK,
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.overlay_error_network
                        else -> R.string.overlay_error_recognizer
                    },
                ),
            )
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun stopRecognizer() {
        mainHandler.removeCallbacks(listenTimeout)
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    // ---------------------------------------------------------------- responder

    private fun runInteraction(text: String) {
        if (overlayView == null) return
        stopRecognizer()
        val seq = ++requestSeq
        setQuery(text)
        setState(State.THINKING)
        scheduleDismiss(SAFETY_DISMISS_MS)

        // Si Render estaba dormido tarda 30-50s: avisar en vez de parecer colgado.
        mainHandler.postDelayed({
            if (seq == requestSeq && !dismissing) {
                overlayView?.findViewById<View>(R.id.thinkingDots)?.visibility = View.GONE
                setTranscript(getString(R.string.overlay_waking_server))
            }
        }, SLOW_HINT_MS)

        val startedAt = SystemClock.elapsedRealtime()
        BackendClient.interactWithText(text) { result ->
            mainHandler.post {
                if (seq != requestSeq || dismissing) return@post
                requestSeq++ // invalida el aviso de "está tardando" (éxito o error)
                Log.i(TAG, "Respuesta en ${SystemClock.elapsedRealtime() - startedAt}ms")
                result.onSuccess { handleResponse(it) }
                result.onFailure { error ->
                    val quota = (error as? BackendHttpException)?.code == 429
                    showError(getString(if (quota) R.string.overlay_error_quota else R.string.overlay_error_network))
                }
            }
        }
    }

    private fun handleResponse(response: InteractResult) {
        setState(State.SPEAKING)
        setTranscript(response.responseText)
        try {
            ActionExecutor.execute(applicationContext, response.action)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo ejecutar la acción ${response.action}", e)
        }
        speak(response.responseText)
    }

    private fun showMessage(message: String) {
        setState(State.SPEAKING)
        waveBars?.setMode(WaveBarsView.Mode.IDLE, ContextCompat.getColor(this, R.color.core_amber))
        setQuery(null)
        setTranscript(message)
        scheduleDismiss(READ_DISMISS_MS)
    }

    private fun showError(message: String) {
        stopRecognizer()
        setState(State.ERROR)
        setTranscript(message)
        scheduleDismiss(ERROR_DISMISS_MS)
    }

    // ---------------------------------------------------------------- voz

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TextToSpeech no inicializó ($status)")
                return@TextToSpeech
            }
            val device = Locale.getDefault()
            val candidates = listOfNotNull(
                device.takeIf { it.language == "es" },
                Locale.forLanguageTag("es-US"),
                Locale.forLanguageTag("es-ES"),
            )
            val locale = candidates.firstOrNull {
                engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
            }
            if (locale == null) {
                Log.w(TAG, "El TTS del teléfono no tiene voz en español")
                return@TextToSpeech
            }
            engine.language = locale
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    mainHandler.post { if (!dismissing) scheduleDismiss(AFTER_SPEECH_DISMISS_MS) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { if (!dismissing) scheduleDismiss(READ_DISMISS_MS) }
                }
            })
            ttsReady = true
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (!ttsReady || engine == null || text.isBlank()) {
            scheduleDismiss(READ_DISMISS_MS)
            return
        }
        // Red de seguridad por si onDone nunca llega: tiempo aproximado de lectura.
        scheduleDismiss(READ_DISMISS_MS + text.length * 90L)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "core-${SystemClock.elapsedRealtime()}")
    }

    // ---------------------------------------------------------------- texto

    private fun setQuery(text: String?, live: Boolean = false) {
        overlayView?.findViewById<TextView>(R.id.queryText)?.apply {
            if (text.isNullOrBlank()) {
                visibility = View.GONE
            } else {
                this.text = if (live) text else "“$text”"
                visibility = View.VISIBLE
            }
        }
    }

    private fun setTranscript(text: String) {
        overlayView?.findViewById<TextView>(R.id.transcriptText)?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        stopRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        dotAnimators.forEach { it.cancel() }
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        orb = null
        waveBars = null
        mainHandler.removeCallbacksAndMessages(null)
        CoreForegroundService.resume(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayService"

        const val EXTRA_LISTEN = "listen"
        const val EXTRA_TEST_TEXT = "test_text"
        const val EXTRA_MESSAGE = "message"

        private const val LISTEN_TIMEOUT_MS = 20_000L
        private const val SLOW_HINT_MS = 5_000L
        private const val SAFETY_DISMISS_MS = 80_000L
        private const val READ_DISMISS_MS = 8_000L
        private const val ERROR_DISMISS_MS = 5_000L
        private const val AFTER_SPEECH_DISMISS_MS = 2_500L

        private val WAKE_WORD_PREFIX = Regex("^\\s*(hey\\s+|oye\\s+)?[jy]?[uú]piter\\b[\\s,.:!]*", RegexOption.IGNORE_CASE)

        /** Si el reconocedor alcanzó a oír el "Jupiter" del inicio, no mandarlo como parte de la orden. */
        fun stripWakeWord(text: String): String = text.replace(WAKE_WORD_PREFIX, "").trim()
    }
}
