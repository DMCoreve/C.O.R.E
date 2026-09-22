package com.dmcore.core.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer
import java.util.ArrayDeque

/**
 * Reimplementación en Kotlin del pipeline de streaming de openWakeWord
 * (openwakeword/utils.py → AudioFeatures), validado contra el original en Python
 * antes de escribirse aquí. Tres modelos encadenados:
 *   audio crudo -> melspectrograma -> embeddings -> clasificador ("hey jarvis")
 */
class WakeWordDetector(context: Context) {

    private val env = OrtEnvironment.getEnvironment()
    private val melSession = loadSession(context, "melspectrogram.onnx")
    private val embSession = loadSession(context, "embedding_model.onnx")
    private val clfSession = loadSession(context, "hey_jarvis_v0.1.onnx")

    // Ventana deslizante de audio crudo: el chunk nuevo + 480 muestras de contexto
    // previo (igual que el streaming real de openWakeWord), para que el modelo de
    // melspectrograma produzca bordes de frame consistentes.
    private val rawWindow = FloatArray(RAW_WINDOW_SAMPLES)

    private val melBuffer = ArrayDeque<FloatArray>() // filas de 32 floats
    private val featureBuffer = ArrayDeque<FloatArray>() // filas de 96 floats

    private fun loadSession(context: Context, assetName: String): OrtSession {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        return env.createSession(bytes, OrtSession.SessionOptions())
    }

    /**
     * Procesa exactamente [CHUNK_SAMPLES] muestras (80ms a 16kHz) y devuelve el
     * score de "hey jarvis" (0.0–1.0), o null si todavía no hay suficiente
     * contexto acumulado para calcular uno (los primeros ~1.3s de audio).
     */
    fun processChunk(chunk: ShortArray): Float? {
        require(chunk.size == CHUNK_SAMPLES) { "processChunk espera exactamente $CHUNK_SAMPLES muestras" }

        System.arraycopy(rawWindow, CHUNK_SAMPLES, rawWindow, 0, RAW_WINDOW_SAMPLES - CHUNK_SAMPLES)
        val offset = RAW_WINDOW_SAMPLES - CHUNK_SAMPLES
        for (i in chunk.indices) {
            rawWindow[offset + i] = chunk[i].toFloat()
        }

        runMelspectrogram(rawWindow).forEach { melBuffer.addLast(it) }
        while (melBuffer.size > MEL_BUFFER_MAX) melBuffer.removeFirst()

        if (melBuffer.size >= EMBED_WINDOW_FRAMES) {
            val window = melBuffer.toList().takeLast(EMBED_WINDOW_FRAMES)
            featureBuffer.addLast(runEmbedding(window))
            while (featureBuffer.size > FEATURE_BUFFER_MAX) featureBuffer.removeFirst()
        }

        if (featureBuffer.size < CLASSIFIER_WINDOW_FRAMES) return null
        return runClassifier(featureBuffer.toList().takeLast(CLASSIFIER_WINDOW_FRAMES))
    }

    private fun runMelspectrogram(samples: FloatArray): List<FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { input ->
            melSession.run(mapOf("input" to input)).use { result ->
                val output = result[0] as OnnxTensor
                val frameCount = output.info.shape[2].toInt()
                val buf = output.floatBuffer
                return List(frameCount) { i ->
                    FloatArray(32) { j -> buf.get(i * 32 + j) / 10f + 2f }
                }
            }
        }
    }

    private fun runEmbedding(melWindow: List<FloatArray>): FloatArray {
        val flat = FloatArray(EMBED_WINDOW_FRAMES * 32)
        for (i in 0 until EMBED_WINDOW_FRAMES) {
            System.arraycopy(melWindow[i], 0, flat, i * 32, 32)
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, EMBED_WINDOW_FRAMES.toLong(), 32, 1)).use { input ->
            embSession.run(mapOf("input_1" to input)).use { result ->
                val buf = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(96) { j -> buf.get(j) }
            }
        }
    }

    private fun runClassifier(featureWindow: List<FloatArray>): Float {
        val flat = FloatArray(CLASSIFIER_WINDOW_FRAMES * 96)
        for (i in 0 until CLASSIFIER_WINDOW_FRAMES) {
            System.arraycopy(featureWindow[i], 0, flat, i * 96, 96)
        }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, CLASSIFIER_WINDOW_FRAMES.toLong(), 96)).use { input ->
            clfSession.run(mapOf("x.1" to input)).use { result ->
                return (result[0] as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    fun close() {
        melSession.close()
        embSession.close()
        clfSession.close()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_SAMPLES = 1280 // 80ms

        private const val CONTEXT_SAMPLES = 480
        private const val RAW_WINDOW_SAMPLES = CHUNK_SAMPLES + CONTEXT_SAMPLES
        private const val EMBED_WINDOW_FRAMES = 76
        private const val CLASSIFIER_WINDOW_FRAMES = 16
        private const val MEL_BUFFER_MAX = 200
        private const val FEATURE_BUFFER_MAX = 32
    }
}
