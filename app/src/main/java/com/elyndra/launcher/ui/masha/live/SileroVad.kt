package com.elyndra.launcher.ui.masha.live

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 (MIT, `assets/live/silero_vad.onnx`, ~2,3 MB) en el ONNX
 * Runtime que ya lleva la voz natural: la probabilidad de que una ventana de
 * [WINDOW] muestras a 16 kHz (32 ms) sea voz humana.
 *
 * El modelo es recurrente: cada llamada lleva el estado (`state` 2×1×128) y
 * las últimas [CONTEXT] muestras de la ventana anterior, como hace su
 * envoltorio oficial. Un hilo (el del micrófono); [close] al terminar la sesión.
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    private val state = FloatArray(2 * 128)
    private val context = FloatArray(CONTEXT)
    private val input = FloatArray(CONTEXT + WINDOW)
    private val sr = LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong()))

    /** Probabilidad de voz (0..1) de las [WINDOW] muestras desde [from]. */
    fun prob(samples: ShortArray, from: Int = 0): Float {
        System.arraycopy(context, 0, input, 0, CONTEXT)
        for (i in 0 until WINDOW) input[CONTEXT + i] = samples[from + i] / 32768f
        System.arraycopy(input, WINDOW, context, 0, CONTEXT)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + WINDOW).toLong())).use { x ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128)).use { s ->
                OnnxTensor.createTensor(env, sr, longArrayOf()).use { rate ->
                    session.run(mapOf("input" to x, "state" to s, "sr" to rate)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val p = (out[0].value as Array<FloatArray>)[0][0]
                        (out[1] as OnnxTensor).floatBuffer.get(state)
                        return p
                    }
                }
            }
        }
    }

    /** Vuelve al estado inicial (nueva sesión). */
    fun reset() {
        state.fill(0f)
        context.fill(0f)
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 512
        const val CONTEXT = 64
        const val ASSET = "live/silero_vad.onnx"

        /** Sesión con [model] (los bytes del .onnx); null si el runtime no está o falla. */
        fun load(model: ByteArray): SileroVad? = runCatching {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            SileroVad(env, env.createSession(model, opts))
        }.getOrNull()
    }
}
