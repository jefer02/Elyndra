package com.elyndra.launcher.ui.masha.voice.supertonic

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Supertonic 3 en la JVM (ONNX Runtime de escritorio). Solo corre con el modelo en disco:
 * variable de entorno MASHA_SUPERTONIC_DIR = carpeta del export int8 de sherpa-onnx.
 *
 * Opcionales:
 * - MASHA_SUPERTONIC_REF: PCM float32 LE de la referencia en Python (mismo ruido, semilla 42)
 *   para la frase [ES]; se exige correlación alta.
 * - MASHA_SUPERTONIC_OUT: carpeta donde dejar el PCM de Kotlin (`kotlin_seed42.f32`) y las medidas.
 * - MASHA_SUPERTONIC_BENCH=1: mide carga y RTF (1 y 4 hilos, 5 y 3 pasos).
 */
class SupertonicModelTest {

    companion object {
        const val ES = "Si te gustó Hollow Knight, tienes que probar The Legend of Zelda: Tears of the Kingdom."
        private var model: SupertonicModel? = null
        private var dir: File? = null

        @BeforeClass @JvmStatic fun load() {
            dir = SupertonicTextTest.modelDir()
            if (dir != null) model = SupertonicModel(dir!!, threads = 4)
        }

        @AfterClass @JvmStatic fun free() { model?.close() }

        private fun env(k: String): String? = System.getenv(k) ?: System.getProperty(k)

        private fun stats(w: FloatArray): Pair<Double, Float> {
            var s = 0.0; var peak = 0f
            for (x in w) { s += x * x; peak = maxOf(peak, abs(x)) }
            return sqrt(s / w.size) to peak
        }
    }

    private fun m(): SupertonicModel {
        assumeTrue("MASHA_SUPERTONIC_DIR sin modelo", model != null)
        return model!!
    }

    @Test fun fraseEspanola() {
        val m = m()
        assertEquals(44100, m.sampleRate)
        assertEquals(10, m.speakers)
        val w = m.synthesize(ES, "es", sid = 0, steps = 5, seed = 42)!!
        // Misma duración que sherpa-onnx 1.13.8 con esta frase (6,51 s).
        assertEquals(287176, w.size)
        assertTrue("NaN en la salida", w.none { it.isNaN() })
        val (rms, peak) = stats(w)
        assertTrue("silencio: rms=$rms", rms > 0.01)
        assertTrue("satura: pico=$peak", peak < 1.5f)
        env("MASHA_SUPERTONIC_OUT")?.let { out ->
            val bb = ByteBuffer.allocate(w.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(w)
            File(out).mkdirs(); File(out, "kotlin_seed42.f32").writeBytes(bb.array())
        }
        env("MASHA_SUPERTONIC_REF")?.let { path ->
            val bb = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val ref = FloatArray(bb.remaining()).also { bb.get(it) }
            assertEquals(ref.size, w.size)
            var sxy = 0.0; var sxx = 0.0; var syy = 0.0; var maxd = 0f
            for (i in w.indices) {
                sxy += w[i] * ref[i]; sxx += w[i] * w[i]; syy += ref[i] * ref[i]
                maxd = maxOf(maxd, abs(w[i] - ref[i]))
            }
            val corr = sxy / sqrt(sxx * syy)
            println("paridad Kotlin vs Python: corr=$corr maxdiff=$maxd")
            assertTrue("corr=$corr", corr > 0.99)
        }
    }

    @Test fun semillaReproducible() {
        val m = m()
        val a = m.synthesize("Hola, soy Masha.", "es", 0, seed = 7)!!
        val b = m.synthesize("Hola, soy Masha.", "es", 0, seed = 7)!!
        assertTrue(a.contentEquals(b))
    }

    @Test fun ruidoIgualQueLaReferencia() {
        // Primeros valores de mt19937(42) + normal polar de MSVC (numpy, ver informe).
        val n = SupertonicModel.Noise(42)
        val ref = floatArrayOf(-0.5169643f, 1.2219212f, 0.72133267f, 0.86963594f, 1.6182168f, 1.5885568f, -1.1883085f, -0.18712473f)
        for (r in ref) assertEquals(r, n.next(), 1e-6f)
    }

    @Test fun cancelar() {
        val m = m()
        var calls = 0
        assertNull(m.synthesize(ES, "es", 0, seed = 1, isCancelled = { ++calls > 2 }))
        assertNull(m.synthesizeMixed(listOf("Hola " to "es", "world" to "en"), 0, isCancelled = { true }))
    }

    @Test fun mezclaDeIdiomas() {
        val m = m()
        val w = m.synthesizeMixed(
            listOf("Si te gustó " to "es", "Hollow Knight" to "en", ", tienes que probar " to "es",
                "The Legend of Zelda: Tears of the Kingdom" to "en", "." to "es"),
            sid = 0, seed = 3,
        )
        assertNotNull(w)
        assertTrue(w!!.size > m.sampleRate * 3)
        assertTrue(w.none { it.isNaN() })
        assertEquals(0, m.synthesize("  ", "es", 0)!!.size)
    }

    @Test fun rendimiento() {
        assumeTrue(env("MASHA_SUPERTONIC_BENCH") == "1")
        val d = dir ?: return
        val sb = StringBuilder()
        for (threads in intArrayOf(1, 4)) {
            val t0 = System.nanoTime()
            val mm = SupertonicModel(d, threads)
            val load = (System.nanoTime() - t0) / 1e9
            mm.synthesize("Hola.", "es", 0, seed = 1) // calentamiento
            for (steps in intArrayOf(5, 3)) {
                var best = Double.MAX_VALUE; var dur = 0.0
                repeat(3) {
                    val s = System.nanoTime()
                    val w = mm.synthesize(ES, "es", 0, steps = steps, seed = 42)!!
                    best = minOf(best, (System.nanoTime() - s) / 1e9); dur = w.size / 44100.0
                }
                sb.append("threads=$threads steps=$steps load=${"%.2f".format(load)}s audio=${"%.2f".format(dur)}s " +
                    "gen=${"%.3f".format(best)}s RTF=${"%.3f".format(best / dur)}\n")
            }
            mm.close()
        }
        val rt = Runtime.getRuntime()
        sb.append("heapUsed=${(rt.totalMemory() - rt.freeMemory()) / 1_000_000}MB\n")
        println(sb)
        env("MASHA_SUPERTONIC_OUT")?.let { File(it).mkdirs(); File(it, "bench.txt").writeText(sb.toString()) }
    }
}
