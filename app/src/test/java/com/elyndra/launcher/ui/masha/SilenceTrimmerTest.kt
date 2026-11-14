package com.elyndra.launcher.ui.masha

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * El silencio que el motor de voz pone al principio y al final de cada frase
 * (medido en la tablet con el TTS del sistema: ~20–50 ms antes y ~340 ms
 * después) se recorta: entre frase y frase quedaba más de medio segundo
 * callada y parecía que se detenía. Las pausas de dentro de la frase se quedan.
 */
class SilenceTrimmerTest {

    private val rate = 24_000

    private fun silence(ms: Int) = ShortArray(rate * ms / 1000)
    // Empieza y acaba en su pico (coseno, ciclos enteros): dónde empieza la voz no es ambiguo.
    private fun tone(ms: Int) = ShortArray(rate * ms / 1000) { (cos(2 * PI * 220 * it / rate) * 8000).toInt().toShort() }

    private fun bytes(vararg parts: ShortArray): ByteArray {
        val all = parts.flatMap { it.toList() }
        return ByteArray(all.size * 2).also { b ->
            all.forEachIndexed { i, s ->
                b[2 * i] = s.toByte()
                b[2 * i + 1] = (s.toInt() shr 8).toByte()
            }
        }
    }

    private fun ms(bytes: Int) = bytes / 2 * 1000 / rate

    private fun trim(pcm: ByteArray, chunk: (Int) -> Int = { pcm.size }): Pair<ByteArray, SilenceTrimmer> {
        val t = SilenceTrimmer(rate)
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < pcm.size) {
            val n = chunk(i).coerceIn(1, pcm.size - i)
            out.write(t.process(pcm.copyOfRange(i, i + n)))
            i += n
        }
        out.write(t.finish())
        return out.toByteArray() to t
    }

    @Test
    fun theEnginesSilenceAroundASentenceIsTrimmed() {
        val (out, t) = trim(bytes(silence(50), tone(1000), silence(340)))
        assertEquals(SilenceTrimmer.LEAD_KEEP_MS + 1000 + SilenceTrimmer.TAIL_KEEP_MS, ms(out.size))
        // Lo recortado al principio corre las marcas de tiempo del motor (onRange).
        assertEquals((50 - SilenceTrimmer.LEAD_KEEP_MS) * rate / 1000, t.droppedLeadFrames)
    }

    @Test
    fun pausesInsideTheSentenceStay() {
        val (out, _) = trim(bytes(tone(400), silence(300), tone(400), silence(400)))
        assertEquals(400 + 300 + 400 + SilenceTrimmer.TAIL_KEEP_MS, ms(out.size))
    }

    @Test
    fun theResultDoesNotDependOnHowTheAudioArrives() {
        val pcm = bytes(silence(120), tone(300), silence(200), tone(250), silence(500))
        val whole = trim(pcm).first
        val rnd = Random(4)
        // Trozos de cualquier tamaño, también impares (media muestra en un trozo y media en el siguiente).
        val chunked = trim(pcm) { rnd.nextInt(1, 3_000) }.first
        assertArrayEquals(whole, chunked)
    }

    @Test
    fun aSentenceWithNoSoundGivesNothing() {
        val (out, _) = trim(bytes(silence(600)))
        assertTrue(out.isEmpty())
    }

    @Test
    fun theVoiceItselfIsUntouched() {
        val voice = bytes(tone(500))
        val (out, _) = trim(bytes(silence(200)) + voice + bytes(silence(200)))
        val lead = SilenceTrimmer.LEAD_KEEP_MS * rate / 1000 * 2
        assertArrayEquals(voice, out.copyOfRange(lead, lead + voice.size))
    }
}
