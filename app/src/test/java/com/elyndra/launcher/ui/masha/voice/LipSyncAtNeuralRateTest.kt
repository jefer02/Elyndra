package com.elyndra.launcher.ui.masha.voice

import android.media.AudioFormat
import com.elyndra.launcher.ui.masha.lipsync.Aligner
import com.elyndra.launcher.ui.masha.lipsync.Ch
import com.elyndra.launcher.ui.masha.lipsync.LipSyncConfig
import com.elyndra.launcher.ui.masha.lipsync.Ph
import com.elyndra.launcher.ui.masha.lipsync.SpanishG2p
import com.elyndra.launcher.ui.masha.lipsync.Synth
import com.elyndra.launcher.ui.masha.lipsync.Track
import com.elyndra.launcher.ui.masha.lipsync.Utterance
import com.elyndra.launcher.ui.masha.lipsync.Vis
import com.elyndra.launcher.ui.masha.lipsync.VowelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * La voz natural sale a 44,1 kHz; la sincronía de labios está afinada a ~24 kHz. MashaVoice
 * le pasa al análisis una copia diezmada a 22,05 kHz ([HalfbandDecimator]). Aquí se comprueba
 * que, con esa copia, el alineado y las curvas salen como con la voz de 24 kHz de referencia
 * (mismo texto, sin rangos de palabra: el modo en que trabaja la voz natural).
 */
class LipSyncAtNeuralRateTest {

    private val cfg = LipSyncConfig()
    private val es = SpanishG2p(distincion = false, sheismo = false)

    /** "mapa": sil 100 | m 70 | a 130 | p (cierre) 70 | a 160 | sil 200 (a 24 kHz). */
    private val ref = Synth.render(
        Synth.Sil(100), Synth.Nasal(70), Synth.A(130), Synth.Gap(70), Synth.A(160), Synth.Sil(200),
    )

    /** Remuestreo lineal 24 → 44,1 kHz (la voz sintética apenas tiene energía por encima de 8 kHz). */
    private fun to44k(x: FloatArray): FloatArray {
        val n = (x.size.toLong() * 44_100 / Synth.SR).toInt()
        return FloatArray(n) { i ->
            val p = i.toDouble() * Synth.SR / 44_100
            val k = p.toInt().coerceAtMost(x.size - 2)
            val f = (p - k).toFloat()
            x[k] * (1 - f) + x[k + 1] * f
        }
    }

    private fun utterance(pcm: ByteArray, rate: Int): Utterance {
        val u = Utterance("mapa", es, seed = 7)
        u.begin(rate, AudioFormat.ENCODING_PCM_16BIT, 1)
        var o = 0
        while (o < pcm.size) {
            val n = minOf(4410 * 2, pcm.size - o)
            u.audio(pcm, o, n)
            o += n
        }
        u.finish()
        return u
    }

    /** Como MashaVoice: PCM de 44,1 kHz a trozos de 100 ms por el diezmador. */
    private fun decimated(x44: FloatArray): ByteArray {
        val d = HalfbandDecimator()
        val pcm = Pcm.toPcm16(x44)
        val out = java.io.ByteArrayOutputStream()
        var o = 0
        while (o < pcm.size) {
            val e = minOf(pcm.size, o + 4410 * 2)
            out.write(d.process(pcm.copyOfRange(o, e)))
            o = e
        }
        return out.toByteArray()
    }

    private fun Track.v(t: Float, ch: Int) = at((t / Aligner.FRAME).toInt().coerceIn(0, frames - 1), ch)

    @Test
    fun `el alineado a 22 kHz coincide con el de 24 kHz`() {
        val a = utterance(Synth.pcm16(ref), Synth.SR)
        a.build(cfg, VowelProfile(), 0.075f)
        val b = utterance(decimated(to44k(ref)), 22_050)
        val track = b.build(cfg, VowelProfile(), 0.075f)

        val sa = a.segments.filter { it.ph != null }
        val sb = b.segments.filter { it.ph != null }
        assertEquals(listOf(Ph.M, Ph.A, Ph.P, Ph.A), sb.map { it.ph })
        assertEquals(sa.map { it.ph }, sb.map { it.ph })
        // Cada fonema en el mismo sitio (±20 ms: dos tramas de análisis).
        for (i in sa.indices) assertTrue("${sa[i].ph}: ${sa[i].mid} vs ${sb[i].mid}", abs(sa[i].mid - sb[i].mid) <= 0.02f)

        // La p sigue sellando los labios con la mandíbula arriba, y la a abre.
        val p = sb.first { it.ph == Ph.P }
        val a2 = sb.last { it.ph == Ph.A }
        assertTrue(track.v(p.mid, Vis.PP.ordinal) >= 0.8f)
        assertTrue(track.v(p.mid, Ch.JAW) <= 0.02f)
        assertTrue(track.v(a2.mid, Vis.AA.ordinal) >= 0.35f)
        assertTrue(track.v(a2.mid, Ch.JAW) > 0.12f)
    }

    @Test
    fun `la energia (gestos y brillo) no cambia con el diezmado`() {
        val a = utterance(Synth.pcm16(ref), Synth.SR).also { it.build(cfg, VowelProfile(), 0.075f) }
        val b = utterance(decimated(to44k(ref)), 22_050).also { it.build(cfg, VowelProfile(), 0.075f) }
        val fa = a.features!!
        val fb = b.features!!
        assertTrue(abs(fa.count - fb.count) <= 2)
        // Nivel (dBFS) en el centro de la segunda a: el mismo con ±1,5 dB.
        val k = 45
        assertEquals(fa.db[k], fb.db[k], 1.5f)
    }
}
