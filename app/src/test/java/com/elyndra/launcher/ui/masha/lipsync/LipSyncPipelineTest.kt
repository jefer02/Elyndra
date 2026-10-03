package com.elyndra.launcher.ui.masha.lipsync

import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Alineado, coarticulación y repuesto solo-audio sobre voz sintética
 * ([Synth]): 24 kHz, vocales con formantes, cierres casi en silencio.
 */
class LipSyncPipelineTest {

    private val cfg = LipSyncConfig()
    private val es = SpanishG2p(distincion = false, sheismo = false)

    /** Frase con audio sintético y (opcional) rangos de palabra en ms. */
    private fun utterance(text: String, audio: FloatArray, rangesMs: List<Int>?, g2p: G2p? = es): Utterance {
        val u = Utterance(text, g2p, seed = 7)
        u.begin(Synth.SR, AudioFormat.ENCODING_PCM_16BIT, 1)
        val pcm = Synth.pcm16(audio)
        // A trozos, como el motor.
        var o = 0
        while (o < pcm.size) {
            val n = minOf(4800, pcm.size - o)
            u.audio(pcm, o, n)
            o += n
        }
        rangesMs?.forEachIndexed { i, ms ->
            val t = u.tokens[i]
            u.range(t.start, t.end, ms * Synth.SR / 1000)
        }
        u.finish()
        return u
    }

    private fun Track.v(t: Float, ch: Int) = at((t / Aligner.FRAME).toInt().coerceIn(0, frames - 1), ch)

    /** "mapa": sil 100 | m 70 | a 130 | p (cierre) 70 | a 160 | sil 200. */
    private val mapaAudio = Synth.render(
        Synth.Sil(100), Synth.Nasal(70), Synth.A(130), Synth.Gap(70), Synth.A(160), Synth.Sil(200),
    )

    @Test
    fun `alineado con rangos pone vocales en los picos y la p en el cierre`() {
        val u = utterance("mapa", mapaAudio, listOf(100))
        val track = u.build(cfg, VowelProfile(), 0.075f)
        val segs = u.segments
        assertEquals(listOf(Ph.M, Ph.A, Ph.P, Ph.A), segs.filter { it.ph != null }.map { it.ph })
        val p = segs.first { it.ph == Ph.P }
        val a1 = segs.first { it.ph == Ph.A }
        val a2 = segs.last { it.ph == Ph.A }
        assertTrue("p en el cierre (0.30–0.37 s): $p", p.mid in 0.28f..0.40f)
        assertTrue("primera a en su vocal (0.17–0.30 s): $a1", a1.mid in 0.16f..0.31f)
        assertTrue("segunda a en su vocal (0.37–0.53 s): $a2", a2.mid in 0.36f..0.54f)
        // Cierre de labios garantizado en la p, con la mandíbula arriba.
        assertTrue(track.v(p.mid, Vis.PP.ordinal) >= 0.8f)
        assertTrue(track.v(p.mid, Ch.JAW) <= 0.02f)
        for (c in 0 until Ch.LIPS) if (c != Vis.PP.ordinal) assertTrue(track.v(p.mid, c) < 0.2f)
        // Vocal abierta, con mandíbula, sin pasarse del tope.
        assertTrue(track.v(a2.mid, Vis.AA.ordinal) in 0.35f..Vis.AA.cap)
        assertTrue(track.v(a2.mid, Ch.JAW) > 0.12f)
    }

    @Test
    fun `nada se queda en 1 y todo respeta sus topes`() {
        val u = utterance("mapa", mapaAudio, listOf(100))
        val track = u.build(cfg, VowelProfile(), 0.075f)
        for (k in 0 until track.frames) {
            for (v in Vis.entries) {
                val x = track.at(k, v.ordinal)
                assertTrue("$v=$x en $k", x <= v.cap + 1e-4f && x < 0.95f)
                assertTrue(x >= 0f)
            }
            assertTrue(track.at(k, Ch.JAW) <= cfg.jawMax + 1e-4f)
        }
    }

    @Test
    fun `la boca se cierra al final de la frase`() {
        val u = utterance("mapa", mapaAudio, listOf(100))
        val track = u.build(cfg, VowelProfile(), 0.075f)
        assertTrue(track.complete)
        val last = track.frames - 1
        for (c in 0 until Ch.LIPS) assertTrue(track.at(last, c) < 0.03f)
        assertTrue(track.at(last, Ch.JAW) < 0.01f)
        // Y en el silencio del final (≥ 150 ms después de la última vocal) ya está casi cerrada.
        assertTrue(track.v(0.72f, Vis.AA.ordinal) < 0.1f)
    }

    @Test
    fun `los labios se redondean antes de la u`() {
        // "a tu": sil 100 | a 150 | t 60 | u 200 | sil 200.
        val audio = Synth.render(Synth.Sil(100), Synth.A(150), Synth.Gap(60), Synth.U(200), Synth.Sil(200))
        val u = utterance("a tu", audio, listOf(100, 250))
        val track = u.build(cfg, VowelProfile(), 0.075f)
        val useg = u.segments.first { it.ph == Ph.U }
        val t = u.segments.first { it.ph == Ph.T }
        var peak = 0f
        for (k in 0 until track.frames) peak = max(peak, track.at(k, Vis.U.ordinal))
        assertTrue(peak > 0.3f)
        // Ya en la t (antes de la u) los labios van redondeados…
        assertTrue("U en la t = ${track.v(t.mid, Vis.U.ordinal)}", track.v(t.mid, Vis.U.ordinal) > 0.35f * peak)
        // …y se nota ~120 ms antes de que empiece la u.
        assertTrue(track.v(useg.t0 - 0.12f, Vis.U.ordinal) > 0.12f * peak)
        // Una vocal no redondeada (a) no se anticipa tanto: 120 ms antes de la a, casi nada.
        val a = u.segments.first { it.ph == Ph.A }
        assertTrue(track.v(a.t0 - 0.12f, Vis.AA.ordinal) < track.v(useg.t0 - 0.12f, Vis.U.ordinal))
    }

    @Test
    fun `sin rangos las palabras se reparten por los tramos sonoros`() {
        // "hola, mundo": hola 100–340 ms, pausa hasta 590, mundo 590–1030.
        val audio = Synth.render(
            Synth.Sil(100), Synth.O(120), Synth.A(120), Synth.Sil(250),
            Synth.Nasal(60), Synth.U(120), Synth.Nasal(60), Synth.Gap(50), Synth.O(150), Synth.Sil(200),
        )
        val u = utterance("hola, mundo", audio, rangesMs = null)
        u.build(cfg, VowelProfile(), 0.075f)
        val segs = u.segments
        val holaEnd = segs.last { it.word == 0 }.t1
        val mundoStart = segs.first { it.word == 1 }.t0
        assertTrue("hola acaba antes de la pausa: $holaEnd", holaEnd <= 0.40f)
        assertTrue("mundo empieza tras la pausa: $mundoStart", mundoStart in 0.55f..0.68f)
        assertTrue(segs.any { it.ph == null })
        assertEquals(Ph.M, segs.first { it.word == 1 }.ph)
    }

    @Test
    fun `solo audio distingue vocales, sibilantes y silencio (no es abrir con el volumen)`() {
        val audio = Synth.render(
            Synth.Sil(100), Synth.A(250), Synth.Sil(100), Synth.I(250), Synth.Sil(100), Synth.Noise(150), Synth.Sil(150),
        )
        val u = utterance("これは", audio, rangesMs = null, g2p = null)
        assertTrue(u.audioOnly)
        val track = u.build(cfg, VowelProfile(), 0.075f)
        fun mean(a: Float, b: Float, ch: Int): Float {
            var s = 0f; var n = 0
            var t = a
            while (t < b) { s += track.v(t, ch); n++; t += 0.01f }
            return s / n
        }
        val aAA = mean(0.15f, 0.30f, Vis.AA.ordinal)
        val aI = mean(0.15f, 0.30f, Vis.I.ordinal)
        val iAA = mean(0.50f, 0.65f, Vis.AA.ordinal)
        val iI = mean(0.50f, 0.65f, Vis.I.ordinal)
        assertTrue("en la a: AA=$aAA I=$aI", aAA > aI)
        assertTrue("en la i: I=$iI AA=$iAA", iI > iAA)
        // Misma energía, distinta mandíbula: la a abre más que la i.
        assertTrue(mean(0.15f, 0.30f, Ch.JAW) > mean(0.50f, 0.65f, Ch.JAW))
        // Ruido agudo → S, sin abrir la boca.
        val ss = mean(0.83f, 0.93f, Vis.SS.ordinal)
        assertTrue("SS=$ss", ss > 0.15f)
        assertTrue(mean(0.83f, 0.93f, Vis.AA.ordinal) < ss)
        // Silencio entre medias: cerrada.
        assertTrue(mean(0.42f, 0.44f, Vis.AA.ordinal) < 0.15f)
    }

    @Test
    fun `pregunta levanta la cabeza al final y la afirmacion no`() {
        val audio = Synth.render(Synth.Sil(100), Synth.A(150), Synth.Gap(60), Synth.U(200), Synth.Sil(200))
        val q = utterance("¿a tu?", audio, listOf(100, 250)).build(cfg, VowelProfile(), 0.075f)
        val s = utterance("a tu.", audio, listOf(100, 250)).build(cfg, VowelProfile(), 0.075f)
        var minQ = 0f; var minS = 0f; var browQ = 0f
        for (k in 0 until q.frames) { minQ = minOf(minQ, q.at(k, Ch.NOD)); browQ = max(browQ, q.at(k, Ch.BROW)) }
        for (k in 0 until s.frames) minS = minOf(minS, s.at(k, Ch.NOD))
        assertTrue("cabeza arriba: $minQ", minQ < -0.03f)
        assertTrue(browQ > 0.2f)
        assertTrue(minS > -0.005f)
    }

    @Test
    fun `las cejas suben un poco en las otras tonicas, no solo en el acento fuerte`() {
        // "camino palabra momento": tónicas (mi, la, men) más fuertes que las átonas.
        val loud = 0.42f
        val audio = Synth.render(
            Synth.Sil(100),
            Synth.Gap(50), Synth.Vowel(120, 850f, 1450f, 0.2f), Synth.Nasal(60), Synth.Vowel(160, 330f, 2650f, loud), Synth.Nasal(60), Synth.Vowel(120, 540f, 1050f, 0.2f),
            Synth.Gap(60), Synth.Vowel(120, 850f, 1450f, 0.2f), Synth.Nasal(50), Synth.Vowel(170, 850f, 1450f, loud), Synth.Gap(60), Synth.Nasal(40), Synth.Vowel(120, 850f, 1450f, 0.2f),
            Synth.Nasal(60), Synth.Vowel(120, 540f, 1050f, 0.2f), Synth.Nasal(60), Synth.Vowel(170, 520f, 2150f, loud), Synth.Nasal(50), Synth.Gap(50), Synth.Vowel(120, 540f, 1050f, 0.2f),
            Synth.Sil(200),
        )
        val ranges = listOf(100, 670, 1290)
        fun peaks(c: LipSyncConfig): List<Float> {
            val tr = utterance("camino palabra momento", audio, ranges).build(c, VowelProfile(), 0.075f)
            val out = ArrayList<Float>()
            for (k in 1 until tr.frames - 1) {
                val b = tr.at(k, Ch.BROW)
                if (b > 0.02f && b >= tr.at(k - 1, Ch.BROW) && b > tr.at(k + 1, Ch.BROW)) out += b
            }
            return out
        }
        val majorOnly = peaks(cfg.copy(minorBrowAccent = 0f))
        val all = peaks(cfg)
        assertTrue("solo fuertes: $majorOnly", majorOnly.isNotEmpty())
        assertTrue("con pequeños: $all frente a $majorOnly", all.size > majorOnly.size)
        // Los pequeños, más pequeños que el fuerte.
        assertTrue(all.minOrNull()!! < majorOnly.maxOrNull()!!)
    }

    @Test
    fun `alineado en streaming no inventa lo que aun no ha llegado`() {
        val u = Utterance("mapa mapa", es, seed = 1)
        u.begin(Synth.SR, AudioFormat.ENCODING_PCM_16BIT, 1)
        val pcm = Synth.pcm16(mapaAudio)
        u.audio(pcm, 0, pcm.size / 2)
        u.range(0, 4, 2400)
        val partial = u.build(cfg, VowelProfile(), 0.075f)
        assertTrue(!partial.complete)
        assertEquals(partial.audioFrames, partial.frames)
        assertTrue(u.segments.all { it.word == 0 || it.word == -1 })
        assertTrue(u.segments.last().t1 <= partial.audioFrames * Aligner.FRAME + 1e-3f)
    }

    @Test
    fun `hash de parpadeos determinista`() {
        assertEquals(Coarticulator.hash01(3, 4), Coarticulator.hash01(3, 4))
        var inRange = true
        for (i in 0 until 100) { val h = Coarticulator.hash01(9, i); if (h < 0f || h >= 1f) inRange = false }
        assertTrue(inRange)
        assertTrue(abs(Coarticulator.hash01(1, 1) - Coarticulator.hash01(1, 2)) > 1e-6f)
    }
}
