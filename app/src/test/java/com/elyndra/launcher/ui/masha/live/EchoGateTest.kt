package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/** La puerta de eco: su voz por el altavoz no pasa; el usuario hablando encima, sí. */
class EchoGateTest {

    /** Envolvente (dB por tramo de 16 ms) de alguien hablando: sílabas, huecos y pausas. */
    private fun speech(frames: Int, level: Float, rnd: Random): FloatArray {
        val out = FloatArray(frames) { EchoGate.SILENT }
        var i = 0
        while (i < frames) {
            val syllable = rnd.nextInt(8, 16)
            val db = level + rnd.nextFloat() * 6f - 3f
            for (k in 0 until syllable) if (i + k < frames) out[i + k] = db - if (k == 0 || k == syllable - 1) 6f else 0f
            i += syllable + if (rnd.nextInt(6) == 0) rnd.nextInt(20, 35) else rnd.nextInt(2, 6)
        }
        return out
    }

    private fun power(db: Float) = 10.0.pow(db / 10.0)
    private fun db(p: Double) = (10 * log10(max(p, 1e-10))).toFloat()

    /** Lo que oye el micro: la salida con [lag] tramos de retraso, una cola y [couplingDb], más ruido. */
    private fun echo(ref: FloatArray, lag: Int, couplingDb: Float): FloatArray = FloatArray(ref.size) { t ->
        var p = power(-68f)
        for (k in 0..3) {
            val i = t - lag - k
            if (i >= 0 && ref[i] > EchoGate.SILENT) p += power(ref[i] + couplingDb - 4f * k)
        }
        db(p)
    }

    @Test
    fun theDelayIsFoundFromTheEnvelopes() {
        val rnd = Random(1)
        val ref = speech(400, -20f, rnd)
        val mic = echo(ref, lag = 6, couplingDb = -20f)
        val gate = EchoGate()
        for (t in ref.indices) gate.accept(mic[t], ref[t])
        // Con la cola de reverberación el pico puede caer un tramo después; la puerta mira ±1.
        assertTrue("desfase ${gate.lag}", gate.lag in 6..7)
        assertTrue(gate.lagConfidence > 0.8f)
    }

    @Test
    fun theCouplingIsLearnedAndHerEchoNeverPasses() {
        val rnd = Random(2)
        val ref = speech(1_200, -20f, rnd)
        val mic = echo(ref, lag = 7, couplingDb = -18f)
        val gate = EchoGate()
        val passed = ref.indices.map { gate.accept(mic[it], ref[it]) }
        assertEquals("acoplamiento ${gate.couplingDb}", -18f, gate.couplingDb, 4f)
        // Tras el primer segundo y medio, ni un tramo con eco cuenta como usuario (en silencio la puerta
        // está abierta a propósito: ahí decide el VAD, y solo hay ruido de fondo).
        assertEquals(0, ref.indices.drop(100).count { passed[it] && mic[it] > -60f })
    }

    @Test
    fun theUserTalkingOverHerPasses() {
        val rnd = Random(3)
        val ref = speech(900, -20f, rnd)
        val mic = echo(ref, lag = 7, couplingDb = -18f)
        val user = speech(120, -26f, Random(9))
        val gate = EchoGate()
        val start = 600
        val passed = ref.indices.map { t ->
            val m = if (t in start until start + user.size && user[t - start] > EchoGate.SILENT) db(power(mic[t]) + power(user[t - start])) else mic[t]
            gate.accept(m, ref[t])
        }
        val voiced = (start until start + user.size).count { user[it - start] > EchoGate.SILENT }
        val hits = (start until start + user.size).count { passed[it] && user[it - start] > EchoGate.SILENT }
        assertTrue("$hits de $voiced tramos con voz del usuario", hits > voiced * 0.8)
    }

    @Test
    fun theGateIsOpenWhileSheIsSilent() {
        val gate = EchoGate()
        repeat(50) { assertTrue(gate.accept(-30f, EchoGate.SILENT)) }
        assertEquals(EchoGate.SILENT, gate.predictedDb)
    }

    @Test
    fun aLearnedCouplingCarriesOverToTheNextSession() {
        val gate = EchoGate(initialCouplingDb = -24f, warmupMs = 0)
        assertEquals(-24f, gate.couplingDb)
        assertEquals(-60f, EchoGate(initialCouplingDb = -200f).couplingDb)
    }
}
