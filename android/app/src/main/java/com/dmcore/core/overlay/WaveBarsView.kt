package com.dmcore.core.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Barras de audio a la derecha de la isla. Escuchando: siguen el volumen real del
 * micrófono (setLevel), que es la prueba visible de que C.O.R.E. te está oyendo.
 * Respondiendo: se mueven solas mientras habla. Pensando/error: casi quietas.
 */
class WaveBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { LEVEL, SPEAKING, IDLE }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val startTime = SystemClock.uptimeMillis()
    private var mode = Mode.IDLE
    private var targetLevel = 0f
    private var level = 0f

    // Desfase de cada barra para que no suban todas a la vez.
    private val phases = floatArrayOf(0f, 1.9f, 0.7f, 2.6f, 1.3f)

    fun setMode(newMode: Mode, color: Int) {
        mode = newMode
        paint.color = color
        invalidate()
    }

    /** 0..1, típicamente derivado del RMS que reporta SpeechRecognizer. */
    fun setLevel(value: Float) {
        targetLevel = value.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - startTime) / 1000f
        // Suavizado: sube rápido, baja más lento, como un VU meter.
        level += (targetLevel - level) * if (targetLevel > level) 0.45f else 0.12f

        val n = phases.size
        val barW = width / (n * 2f - 1f)
        val maxH = height.toFloat()
        val minH = barW
        paint.alpha = if (mode == Mode.IDLE) 110 else 255

        for (i in 0 until n) {
            val wiggle = abs(sin(t * 6.5f + phases[i]))
            val amount = when (mode) {
                Mode.LEVEL -> 0.12f + level * (0.45f + 0.55f * wiggle)
                Mode.SPEAKING -> 0.3f + 0.7f * abs(sin(t * 4.2f + phases[i] * 1.7f))
                Mode.IDLE -> 0.12f + 0.08f * wiggle
            }
            val h = (minH + (maxH - minH) * amount.coerceIn(0f, 1f))
            val left = i * barW * 2f
            val top = (maxH - h) / 2f
            canvas.drawRoundRect(left, top, left + barW, top + h, barW / 2f, barW / 2f, paint)
        }
        postInvalidateOnAnimation()
    }

    init {
        paint.color = Color.WHITE
    }
}
