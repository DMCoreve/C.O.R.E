package com.dmcore.core.overlay

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Orbe animado del overlay, dibujado a mano en Canvas (el XML no da para esto).
 * Cada estado tiene su propio movimiento, no solo otro color:
 *   LISTENING  -> ondas que se expanden hacia afuera (captando)
 *   THINKING   -> un arco tipo cometa girando alrededor de la esfera
 *   SPEAKING   -> el borde de la esfera ondula como una membrana que vibra
 */
class VoiceOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { LISTENING, THINKING, SPEAKING, ERROR }

    var mode: Mode = Mode.LISTENING
        private set

    private var color = Color.parseColor("#FF3B30")
    private var targetLevel = 0f
    private var level = 0f
    private var colorAnimator: ValueAnimator? = null
    private val startTime = SystemClock.uptimeMillis()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        maskFilter = BlurMaskFilter(1f, BlurMaskFilter.Blur.NORMAL) // se recalcula en onSizeChanged
    }
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val blobPath = Path()

    private var cx = 0f
    private var cy = 0f
    private var sphereR = 0f

    init {
        // BlurMaskFilter no funciona con aceleración por hardware en todas las versiones.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setMode(newMode: Mode, newColor: Int) {
        mode = newMode
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), color, newColor).apply {
            duration = 450
            addUpdateListener { color = it.animatedValue as Int }
            start()
        }
    }

    /** Volumen del micrófono (0..1): mientras escucha, la esfera late con tu voz. */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        // Deja margen alrededor de la esfera para las ondas y el brillo.
        sphereR = min(w, h) * 0.30f
        glowPaint.maskFilter = BlurMaskFilter(sphereR * 0.9f, BlurMaskFilter.Blur.NORMAL)
        highlightPaint.maskFilter = BlurMaskFilter(sphereR * 0.18f, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - startTime) / 1000f
        level += (targetLevel - level) * if (targetLevel > level) 0.4f else 0.1f

        drawGlow(canvas, t)
        when (mode) {
            Mode.LISTENING -> drawRipples(canvas, t)
            Mode.THINKING -> drawOrbitArc(canvas, t)
            Mode.SPEAKING, Mode.ERROR -> Unit
        }
        drawSphere(canvas, t)

        postInvalidateOnAnimation()
    }

    private fun drawGlow(canvas: Canvas, t: Float) {
        val breathe = 1f + 0.08f * sin(t * 2.2f) + 0.25f * level
        glowPaint.color = withAlpha(color, if (mode == Mode.ERROR) 0.25f else 0.55f)
        canvas.drawCircle(cx, cy, sphereR * 1.05f * breathe, glowPaint)
    }

    private fun drawRipples(canvas: Canvas, t: Float) {
        val period = 1.8f
        for (i in 0 until 3) {
            val p = ((t / period) + i / 3f) % 1f
            val eased = 1f - (1f - p) * (1f - p) // ease-out
            ringPaint.strokeWidth = sphereR * 0.07f * (1f - p) + 1f
            ringPaint.color = withAlpha(color, 0.6f * (1f - p))
            canvas.drawCircle(cx, cy, sphereR * (1f + 0.65f * eased), ringPaint)
        }
    }

    private fun drawOrbitArc(canvas: Canvas, t: Float) {
        val r = sphereR * 1.32f
        arcPaint.strokeWidth = sphereR * 0.11f
        arcPaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(Color.TRANSPARENT, withAlpha(color, 0.15f), color),
            floatArrayOf(0f, 0.55f, 0.75f),
        )
        canvas.save()
        canvas.rotate(t * 300f % 360f, cx, cy)
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 0f, 270f, false, arcPaint)
        canvas.restore()
        arcPaint.shader = null
    }

    private fun drawSphere(canvas: Canvas, t: Float) {
        val light = blend(color, Color.WHITE, 0.55f)
        val deep = blend(color, Color.BLACK, 0.55f)
        spherePaint.shader = RadialGradient(
            cx - sphereR * 0.35f, cy - sphereR * 0.4f, sphereR * 1.5f,
            intArrayOf(light, color, deep),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )

        if (mode == Mode.SPEAKING) {
            // Borde ondulado: suma de dos senos con distinta frecuencia para que no se
            // vea como un patrón mecánico repetido.
            blobPath.reset()
            val steps = 72
            for (i in 0..steps) {
                val a = (i.toFloat() / steps) * 2f * PI.toFloat()
                val wobble = 0.07f * sin(a * 3f + t * 5f) + 0.045f * sin(a * 5f - t * 7.3f)
                val r = sphereR * (1f + wobble)
                val x = cx + r * cos(a)
                val y = cy + r * sin(a)
                if (i == 0) blobPath.moveTo(x, y) else blobPath.lineTo(x, y)
            }
            blobPath.close()
            canvas.drawPath(blobPath, spherePaint)
        } else {
            val pulse = if (mode == Mode.LISTENING) 1f + 0.04f * sin(t * 4f) + 0.16f * level else 1f
            canvas.drawCircle(cx, cy, sphereR * pulse, spherePaint)
        }

        // Reflejo especular arriba a la izquierda: es lo que hace que se lea como esfera.
        highlightPaint.color = withAlpha(Color.WHITE, 0.55f)
        canvas.drawOval(
            cx - sphereR * 0.55f, cy - sphereR * 0.62f,
            cx - sphereR * 0.05f, cy - sphereR * 0.25f,
            highlightPaint,
        )
    }

    override fun onDetachedFromWindow() {
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun withAlpha(c: Int, a: Float) =
        Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun blend(a: Int, b: Int, f: Float) = ArgbEvaluator().evaluate(f, a, b) as Int
}
