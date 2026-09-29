package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.ui.masha.lipsync.Ch
import com.elyndra.launcher.ui.masha.lipsync.LipSyncConfig
import com.elyndra.launcher.ui.masha.lipsync.Track
import com.elyndra.launcher.ui.masha.lipsync.Vis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Lado del render ([LipSync]) y la mezcla a morphs ([FaceMorphs]). */
class LipSyncRenderTest {

    /** Reloj de prueba: la frase empieza a sonar en [start] ns. */
    private class FakeClock(val start: Long) : LipSync.Clock {
        override fun seconds(item: LipSync.Item, nowNanos: Long) = (nowNanos - start) / 1e9
    }

    /** Pista sintética de 1 s: AA en seno, PP breve, un parpadeo en la trama 40. */
    private fun track(): Track {
        val frames = 100
        val d = FloatArray(frames * Ch.COUNT)
        for (k in 0 until frames) {
            val t = k * 0.01f
            // Cola que se cierra, como las pistas reales.
            val tail = ((frames - 1 - k) / 10f).coerceIn(0f, 1f)
            d[k * Ch.COUNT + Vis.AA.ordinal] = (0.35f + 0.3f * sin(t * 12f)) * tail
            d[k * Ch.COUNT + Ch.JAW] = (0.2f + 0.15f * sin(t * 12f)) * tail
            // Cierre de P como los reales: 60 ms arriba con rampas suaves de 30 ms.
            val pp = when (k) {
                in 47..49 -> 0.85f * (k - 46) / 4f
                in 50..56 -> 0.85f
                in 57..59 -> 0.85f * (60 - k) / 4f
                else -> 0f
            }
            d[k * Ch.COUNT + Vis.PP.ordinal] = pp
            d[k * Ch.COUNT + Ch.ENV] = 0.6f
        }
        return Track(frames, d, intArrayOf(40), frames - 10, true)
    }

    private fun run(fps: Int, cfg: LipSyncConfig = LipSyncConfig()): Pair<Map<Int, FloatArray>, Int> {
        val lip = LipSync(cfg)
        val item = LipSync.Item("t").apply { this.track = track(); sampleRate = 24000 }
        lip.add(item)
        lip.clock = FakeClock(0L)
        val f = LipSync.Frame()
        val out = HashMap<Int, FloatArray>()
        var blinks = 0
        val dt = 1f / fps
        var i = 0
        while (true) {
            val now = (i * 1e9 / fps).toLong()
            if (now > 1_200_000_000L) break
            // Como HoloRig: el primer fotograma, dt = 0.
            lip.sample(now, if (i == 0) 0f else dt, f)
            if (f.blink) blinks++
            // Guarda en los instantes comunes (cada 1/30 s).
            val ms = (now / 1_000_000L).toInt()
            if ((i * 30) % fps == 0) out[ms] = floatArrayOf(f.lips[Vis.AA.ordinal], f.jaw, f.lips[Vis.PP.ordinal])
            i++
        }
        return out to blinks
    }

    @Test
    fun `independiente de los fotogramas por segundo`() {
        val (a, _) = run(30)
        val (b, _) = run(120)
        var worst = 0f
        var where = ""
        for ((ms, va) in a) {
            val vb = b[ms] ?: b[ms + 1] ?: b[ms - 1] ?: continue
            for (c in va.indices) {
                val d = abs(va[c] - vb[c])
                if (d > worst) { worst = d; where = "t=$ms ms canal $c: ${va[c]} vs ${vb[c]}" }
            }
        }
        assertTrue("diferencia 30 vs 120 fps = $worst ($where)", worst < 0.05f)
    }

    @Test
    fun `un parpadeo por coma, una sola vez, y la boca se cierra al acabar`() {
        val (_, blinks30) = run(30)
        val (_, blinks120) = run(120)
        assertEquals(1, blinks30)
        assertEquals(1, blinks120)
        val lip = LipSync()
        val item = LipSync.Item("t").apply { this.track = track(); sampleRate = 24000 }
        lip.add(item)
        lip.clock = FakeClock(0L)
        val f = LipSync.Frame()
        lip.sample(300_000_000L, 1 / 60f, f)
        // Tras la pista (completa), la boca vuelve a cero con el suavizado.
        var now = 1_100_000_000L
        repeat(60) { lip.sample(now, 1 / 60f, f); now += 16_666_667L }
        assertTrue(f.lips.all { it < 0.01f } && f.jaw < 0.01f && !f.active)
    }

    @Test
    fun `la boca va por delante del audio (adelanto visual)`() {
        val cfg = LipSyncConfig(visualLeadMs = 60f, smoothing = 1000f)
        val lip = LipSync(cfg)
        val tr = track()
        lip.add(LipSync.Item("t").apply { this.track = tr; sampleRate = 24000 })
        lip.clock = FakeClock(0L)
        val f = LipSync.Frame()
        lip.sample(200_000_000L, 1f, f)
        // A los 200 ms de audio se ve lo de los 260 ms.
        assertEquals(tr.at(26, Vis.AA.ordinal), f.lips[Vis.AA.ordinal], 1e-3f)
        // El nivel de la voz, sin adelanto.
        assertEquals(0.6f, f.env, 1e-3f)
    }

    /* ── mezcla a morphs ── */

    private val oldNames = listOf(
        "eyeBlinkLeft", "eyeBlinkRight", "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight",
        "browInnerUp", "browDownLeft", "browDownRight", "browOuterUpLeft", "browOuterUpRight", "cheekSquintLeft",
        "cheekSquintRight", "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight", "jawOpen",
        "mouthPucker", "mouthPress", "mouthLeft", "viseme_aa", "viseme_E", "viseme_I", "viseme_O", "viseme_U",
        "viseme_PP", "viseme_FF", "viseme_SS", "viseme_DD", "viseme_CH",
    )
    private val newNames = oldNames + listOf("viseme_kk", "viseme_nn", "viseme_RR", "viseme_TH", "mouthClose", "mouthFunnel", "mouthRollLower")

    private fun mix(names: List<String>, setup: FaceChannels.() -> Unit): Map<String, Float> {
        val m = FaceMorphs.forNames(names)
        val c = FaceChannels().apply(setup)
        val out = FloatArray(names.size)
        m.write(c, out)
        return names.indices.associate { names[it] to out[it] }
    }

    @Test
    fun `GLB anterior - la mandibula horneada del visema no se cuenta dos veces`() {
        val w = mix(oldNames) { lips[Vis.AA.ordinal] = 0.7f; jaw = 0.35f }
        assertEquals(0f, w.getValue("jawOpen"), 1e-4f)
        assertEquals(0.7f, w.getValue("viseme_aa"), 1e-4f)
        // Una i no trae mandíbula: jawOpen entero.
        val i = mix(oldNames) { lips[Vis.I.ordinal] = 0.5f; jaw = 0.15f }
        assertEquals(0.15f, i.getValue("jawOpen"), 1e-4f)
    }

    @Test
    fun `contrato de lip-sync - visemas solo de labios y mandibula aparte`() {
        val w = mix(newNames) { lips[Vis.AA.ordinal] = 0.6f; jaw = 0.35f }
        assertEquals(0.35f, w.getValue("jawOpen"), 1e-4f)
        assertEquals(0.6f, w.getValue("viseme_aa"), 1e-4f)
        val k = mix(newNames) { lips[Vis.KK.ordinal] = 0.4f }
        assertEquals(0.4f, k.getValue("viseme_kk"), 1e-4f)
        assertEquals(0f, k.getValue("viseme_DD"), 1e-4f)
        // Sellado (contrato v2): P con la mandíbula algo abierta -> mouthClose = jawOpen * PP.
        val p = mix(newNames) { lips[Vis.PP.ordinal] = 0.85f; jaw = 0.2f }
        assertEquals(0.2f * 0.85f, p.getValue("mouthClose"), 1e-4f)
        // Cierre de reposo exacto: U = 0.6 + 0.3 + 0.6*0.5 = 1.2 -> mouthClose = 0.2174*0.2.
        val r = mix(newNames) { lips[Vis.AA.ordinal] = 0.6f; lips[Vis.E.ordinal] = 0.3f; jaw = 0.5f }
        assertEquals(0.15f / 0.69f * 0.2f, r.getValue("mouthClose"), 1e-4f)
        assertEquals(0.5f, r.getValue("jawOpen"), 1e-4f)
        // Por debajo de 1, nada.
        assertEquals(0f, mix(newNames) { lips[Vis.AA.ordinal] = 0.5f; jaw = 0.3f }.getValue("mouthClose"), 1e-6f)
        // TH abre lo justo para que asome la lengua.
        assertTrue(mix(newNames) { lips[Vis.TH.ordinal] = 0.4f }.getValue("jawOpen") >= 0.12f - 1e-4f)
        // F y P con poca mandíbula; O nunca sin ella.
        assertTrue(mix(newNames) { lips[Vis.FF.ordinal] = 0.6f; jaw = 0.4f }.getValue("jawOpen") <= 0.15f + 1e-4f)
        assertTrue(mix(newNames) { lips[Vis.PP.ordinal] = 0.6f; jaw = 0.4f }.getValue("jawOpen") <= 0.2f + 1e-4f)
        assertTrue(mix(newNames) { lips[Vis.O.ordinal] = 0.55f; jaw = 0.05f }.getValue("jawOpen") >= 0.2f - 1e-4f)
    }

    @Test
    fun `visemas que faltan se sustituyen y la suma se normaliza`() {
        val k = mix(oldNames) { lips[Vis.KK.ordinal] = 0.4f }
        assertEquals(0.24f, k.getValue("viseme_DD"), 1e-4f)
        val all = mix(oldNames) { for (v in Vis.entries) lips[v.ordinal] = 0.5f; jaw = 0.4f }
        val lips = oldNames.filter { it.startsWith("viseme_") }.sumOf { all.getValue(it).toDouble() }
        assertTrue("suma de visemas $lips", lips <= FaceMorphs.LIP_SUM_MAX + 1e-4)
        val mouth = lips + all.getValue("jawOpen") + all.getValue("mouthPress")
        assertTrue("suma de boca $mouth", mouth <= FaceMorphs.MOUTH_SUM_MAX + 1e-4)
    }

    @Test
    fun `como mucho tres visemas activos, sin saltos al cruzarse`() {
        val visemes = newNames.filter { it.startsWith("viseme_") }
        // Colas de coarticulación en todos: solo quedan los tres más fuertes.
        val w = mix(newNames) {
            lips.fill(0.04f)
            lips[Vis.AA.ordinal] = 0.5f; lips[Vis.E.ordinal] = 0.2f; lips[Vis.I.ordinal] = 0.15f; lips[Vis.O.ordinal] = 0.08f
        }
        assertEquals(3, visemes.count { w.getValue(it) > 0f })
        assertTrue(w.getValue("viseme_aa") > w.getValue("viseme_E") && w.getValue("viseme_E") > w.getValue("viseme_I"))
        // Al cruzarse el 3.º y el 4.º no hay salto: el que sale ya valía casi 0.
        fun third(o: Float) = mix(newNames) { lips[Vis.AA.ordinal] = 0.5f; lips[Vis.E.ordinal] = 0.3f; lips[Vis.I.ordinal] = 0.1f; lips[Vis.O.ordinal] = o }
        assertEquals(third(0.099f).getValue("viseme_I"), third(0.101f).getValue("viseme_O"), 0.01f)
        assertEquals(third(0.099f).getValue("viseme_aa"), third(0.101f).getValue("viseme_aa"), 0.01f)
        // Lo que no se ve no se escribe.
        assertEquals(0f, mix(newNames) { lips[Vis.U.ordinal] = 0.02f }.getValue("viseme_U"), 0f)
    }

    @Test
    fun `la sonrisa cede en la u y en la p`() {
        val plain = mix(newNames) { smile = 0.6f }
        val u = mix(newNames) { smile = 0.6f; lips[Vis.U.ordinal] = 0.6f }
        val p = mix(newNames) { smile = 0.6f; lips[Vis.PP.ordinal] = 0.85f }
        assertTrue(u.getValue("mouthSmileLeft") < 0.5f * plain.getValue("mouthSmileLeft"))
        assertTrue(p.getValue("mouthSmileLeft") < 0.5f * plain.getValue("mouthSmileLeft"))
    }
}
