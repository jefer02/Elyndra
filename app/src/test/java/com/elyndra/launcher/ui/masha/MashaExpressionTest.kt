package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.ui.masha.lipsync.Ch
import com.elyndra.launcher.ui.masha.lipsync.LipSyncConfig
import com.elyndra.launcher.ui.masha.lipsync.Track
import com.elyndra.launcher.ui.masha.lipsync.Vis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** La cara de Masha: ánimo → morphs ([MoodFace]), su dinámica ([FaceExpression]), parpadeo, mirada y cabeza. */
class MashaExpressionTest {

    /** Los 47 morphs de `Masha_Head` (contrato v2, orden del GLB). */
    private val names = listOf(
        "eyeBlinkLeft", "eyeBlinkRight", "eyeSquintLeft", "eyeSquintRight", "eyeWideLeft", "eyeWideRight",
        "browInnerUp", "browDownLeft", "browDownRight", "browOuterUpLeft", "browOuterUpRight",
        "cheekSquintLeft", "cheekSquintRight", "mouthSmileLeft", "mouthSmileRight", "mouthFrownLeft", "mouthFrownRight",
        "jawOpen", "mouthPucker", "mouthPress", "mouthLeft", "viseme_aa", "viseme_E", "viseme_I", "viseme_O", "viseme_U",
        "viseme_PP", "viseme_FF", "viseme_SS", "viseme_DD", "viseme_CH", "viseme_kk", "viseme_nn", "viseme_RR", "viseme_TH",
        "mouthClose", "mouthFunnel", "mouthRollLower", "mouthRollUpper", "mouthUpperUp", "mouthLowerDown",
        "mouthShrugLower", "mouthShrugUpper", "mouthStretch", "mouthDimple", "mouthRight", "tongueOut",
    )

    /** Los morphs que no son de la boca del habla ni del parpadeo: los de la expresión. */
    private val expressionMorphs = Ex.MORPH.filterNotNull().toSet()

    /** La expresión asentada de un ánimo, tal como llega a los morphs (sin ruido ni microexpresiones). */
    private fun settled(m: MashaMood, listen: Float = 0f, budget: Int = Int.MAX_VALUE, setup: FaceChannels.() -> Unit = {}): Map<String, Float> {
        val morphs = FaceMorphs.forNames(names).also { it.exprBudget = budget }
        val c = FaceChannels()
        MoodFace.pose(m, listen, c.expr)
        c.setup()
        val out = FloatArray(names.size)
        morphs.write(c, out)
        return names.indices.associate { names[it] to out[it] }
    }

    private fun Map<String, Float>.active() = filter { it.key in expressionMorphs && it.value > 0f }.keys

    /* ── ánimo → morphs ── */

    @Test
    fun `alegre - sonrisa de Duchenne (boca, mejillas y ojos)`() {
        val w = settled(MashaMood.Playful)
        assertTrue(w.getValue("mouthSmileLeft") >= 0.4f && w.getValue("mouthSmileRight") >= 0.4f)
        assertTrue(w.getValue("cheekSquintLeft") >= 0.25f && w.getValue("cheekSquintRight") >= 0.25f)
        assertTrue(w.getValue("eyeSquintLeft") >= 0.2f && w.getValue("eyeSquintRight") >= 0.2f)
        for (n in listOf("mouthFrownLeft", "mouthFrownRight", "browDownLeft", "browDownRight", "mouthPress")) assertEquals(n, 0f, w.getValue(n))
        // Cálida: la misma receta, más suave, y algo de ternura en las cejas.
        val warm = settled(MashaMood.Warm)
        assertTrue(warm.getValue("mouthSmileLeft") in 0.2f..w.getValue("mouthSmileLeft") - 0.1f)
        assertTrue(warm.getValue("cheekSquintLeft") > 0.12f && warm.getValue("eyeSquintLeft") > 0.1f)
        assertTrue(warm.getValue("browInnerUp") > 0.1f)
    }

    @Test
    fun `curiosa - cejas arriba y ojos abiertos`() {
        val w = settled(MashaMood.Curious)
        assertTrue(w.getValue("browInnerUp") >= 0.25f)
        assertTrue(w.getValue("browOuterUpLeft") >= 0.15f && w.getValue("browOuterUpRight") >= 0.15f)
        assertTrue(w.getValue("eyeWideLeft") >= 0.15f && w.getValue("eyeWideRight") >= 0.15f)
        for (n in listOf("eyeSquintLeft", "eyeSquintRight", "browDownLeft", "browDownRight", "mouthFrownLeft", "mouthPress")) assertEquals(n, 0f, w.getValue(n))
    }

    @Test
    fun `pensando - una ceja abajo, labios apretados y de lado`() {
        val w = settled(MashaMood.Thinking)
        assertTrue("browDown de un lado", abs(w.getValue("browDownLeft") - w.getValue("browDownRight")) >= 0.25f)
        assertTrue(w.getValue("mouthPress") >= 0.25f)
        assertTrue(maxOf(w.getValue("mouthLeft"), w.getValue("mouthRight")) >= 0.15f)
        // Párpados algo bajos: el canal LID va al parpadeo.
        assertTrue(MoodFace.target(MashaMood.Thinking)[Ex.LID] > 0f)
        // Y la mirada se va a un lado (5–15°) al poco de empezar a pensar.
        val s = Saccades(LookConfig(), 3L)
        var maxYaw = 0f
        var t = 0f
        while (t < 4f) { s.update(t, thinking = true); maxYaw = maxOf(maxYaw, abs(s.yaw)); t += 1f / 60f }
        assertTrue("mirada a un lado: $maxYaw°", maxYaw >= 5f)
    }

    @Test
    fun `preocupada - cejas de preocupacion y comisuras abajo`() {
        val w = settled(MashaMood.Concerned)
        assertTrue(w.getValue("browInnerUp") >= 0.4f)
        assertTrue(w.getValue("mouthFrownLeft") >= 0.25f && w.getValue("mouthFrownRight") >= 0.25f)
        assertEquals(0f, w.getValue("mouthSmileLeft"))
        assertEquals(0f, w.getValue("mouthSmileRight"))
    }

    @Test
    fun `analitica y neutral - poco y contenido`() {
        val a = settled(MashaMood.Analytical)
        assertTrue(a.getValue("browDownLeft") > 0.08f && a.getValue("browDownRight") > 0.08f)
        assertTrue(a.getValue("eyeSquintLeft") > 0.1f)
        assertEquals(0f, a.getValue("mouthSmileLeft"))
        val n = settled(MashaMood.Neutral)
        assertEquals(setOf("mouthSmileLeft", "mouthSmileRight"), n.active())
        assertTrue(n.getValue("mouthSmileLeft") < 0.1f)
    }

    @Test
    fun `cada animo es asimetrico y usa pocos morphs`() {
        for (m in MashaMood.entries) {
            val w = settled(m)
            val active = w.active()
            assertTrue("$m: ${active.size} morphs $active", active.size in 2..8)
            val asym = listOf("mouthSmile", "cheekSquint", "eyeSquint", "eyeWide", "browOuterUp", "browDown", "mouthFrown")
                .any { abs(w.getValue(it + "Left") - w.getValue(it + "Right")) > 0.01f }
            assertTrue("$m simétrico del todo", asym)
            // Con la atención encima, como mucho 12; y el presupuesto de cada calidad se respeta.
            assertTrue(settled(m, listen = 1f).active().size <= 12)
            assertTrue(settled(m, listen = 1f, budget = HoloRig.EXPR_BUDGET_HIGH).active().size <= HoloRig.EXPR_BUDGET_HIGH)
            assertTrue(settled(m, listen = 1f, budget = HoloRig.EXPR_BUDGET_LITE).active().size <= HoloRig.EXPR_BUDGET_LITE)
        }
    }

    @Test
    fun `escuchar abre los ojos, alza las cejas y dilata la pupila`() {
        val plain = settled(MashaMood.Neutral)
        val listening = settled(MashaMood.Neutral, listen = 1f)
        assertTrue(listening.getValue("eyeWideLeft") > plain.getValue("eyeWideLeft") + 0.08f)
        assertTrue(listening.getValue("browInnerUp") > plain.getValue("browInnerUp") + 0.1f)
        val neutral = converged(MashaMood.Neutral).pupil
        assertTrue(converged(MashaMood.Neutral, listening = true).pupil > neutral + 0.15f)
        assertTrue(converged(MashaMood.Curious).pupil > neutral + 0.15f)
        assertTrue(converged(MashaMood.Analytical).pupil < neutral - 0.05f)
    }

    /* ── dinámica ── */

    private fun converged(m: MashaMood, listening: Boolean = false, seconds: Float = 6f): FaceExpression {
        val e = FaceExpression(5L)
        val c = FaceChannels()
        val input = ExpressionInput().apply { mood = m; this.listening = listening }
        var t = 0f
        while (t < seconds) { e.update(t, 1f / 60f, input, c); t += 1f / 60f }
        return e
    }

    @Test
    fun `al cambiar de animo hay un pico que luego se asienta`() {
        // Sin ruido ni microexpresiones: solo la transición.
        val e = FaceExpression(5L, noise = 0f, micros = false)
        val c = FaceChannels()
        val input = ExpressionInput().apply { mood = MashaMood.Neutral }
        var t = 0f
        val dt = 1f / 60f
        while (t < 2f) { e.update(t, dt, input, c); t += dt }
        input.mood = MashaMood.Playful
        val target = MoodFace.target(MashaMood.Playful)[Ex.CHEEK_L]
        var peak = 0f
        while (t < 3f) { e.update(t, dt, input, c); peak = maxOf(peak, c.expr[Ex.CHEEK_L]); t += dt }
        assertTrue("pico $peak sobre $target", peak > target * 1.15f)
        while (t < 8f) { e.update(t, dt, input, c); t += dt }
        assertEquals(target, c.expr[Ex.CHEEK_L], target * 0.03f)
    }

    @Test
    fun `nunca quieta del todo, sin encender morphs apagados`() {
        val e = FaceExpression(9L)
        val c = FaceChannels()
        val input = ExpressionInput().apply { mood = MashaMood.Neutral }
        var lo = 1f; var hi = 0f; var asym = 0
        var t = 0f
        var frames = 0
        while (t < 20f) {
            e.update(t, 1f / 60f, input, c)
            if (t > 2f) {
                lo = minOf(lo, c.expr[Ex.SMILE_L]); hi = maxOf(hi, c.expr[Ex.SMILE_L])
                if (abs(c.expr[Ex.SMILE_L] - c.expr[Ex.SMILE_R]) > 1e-3f) asym++
                frames++
                // Lo que el ánimo neutral no usa y ninguna microexpresión toca, apagado siempre.
                for (k in intArrayOf(Ex.FROWN_L, Ex.FROWN_R, Ex.BROW_DOWN_L, Ex.BROW_DOWN_R, Ex.DIMPLE, Ex.MOUTH_LEFT, Ex.MOUTH_RIGHT)) {
                    assertEquals(0f, c.expr[k])
                }
            }
            t += 1f / 60f
        }
        assertTrue("la sonrisa vive: $lo..$hi", hi - lo > 0.008f)
        assertTrue("asimétrica casi siempre", asym > frames * 0.9f)
    }

    @Test
    fun `hay microexpresiones y hablando no tocan la boca`() {
        val e = FaceExpression(3L)
        val c = FaceChannels()
        val input = ExpressionInput().apply { mood = MashaMood.Playful; speaking = true }
        val seen = HashSet<Int>()
        var t = 0f
        while (t < 120f) {
            e.update(t, 1f / 60f, input, c)
            if (e.microKind >= 0) seen += e.microKind
            t += 1f / 60f
        }
        assertTrue("microexpresiones: $seen", seen.size >= 2)
        assertTrue(FaceExpression.M_SMIRK !in seen && FaceExpression.M_PRESS !in seen)
    }

    @Test
    fun `hablando, la boca de la expresion cede ante los visemas`() {
        val quiet = settled(MashaMood.Thinking)
        val talking = settled(MashaMood.Thinking) { lips[Vis.AA.ordinal] = 0.5f; jaw = 0.35f }
        assertTrue(quiet.getValue("mouthPress") > 0.25f)
        assertEquals(0f, talking.getValue("mouthPress"))
        assertEquals(0f, talking.getValue("mouthLeft"))
        // Las cejas y los ojos no ceden.
        assertEquals(quiet.getValue("browDownLeft"), talking.getValue("browDownLeft"), 1e-4f)
        // Y la sonrisa sigue cediendo en O/U/P/F (como antes).
        val u = settled(MashaMood.Playful) { lips[Vis.U.ordinal] = 0.6f }
        assertTrue(u.getValue("mouthSmileLeft") < 0.5f * settled(MashaMood.Playful).getValue("mouthSmileLeft"))
    }

    @Test
    fun `los morphs de expresion casi invisibles no se encienden`() {
        // 0,02 no se ve y costaría un morph entero en la GPU.
        val w = settled(MashaMood.Neutral) { expr[Ex.BROW_INNER] = 0.02f; expr[Ex.SMILE_L] = 0.05f }
        assertEquals(0f, w.getValue("browInnerUp"))
        assertEquals(0.05f, w.getValue("mouthSmileLeft"), 1e-4f)
    }

    @Test
    fun `presupuesto de morphs de expresion, sin saltos`() {
        val full = settled(MashaMood.Playful, listen = 1f)
        assertTrue(full.active().size > 6)
        val capped = settled(MashaMood.Playful, listen = 1f, budget = 6)
        assertTrue("${capped.active()}", capped.active().size <= 6)
        // Lo más fuerte sigue casi igual.
        assertEquals(full.getValue("mouthSmileLeft"), capped.getValue("mouthSmileLeft"), 0.12f)
        // Continuo: un cambio pequeño de la entrada no hace saltar nada.
        val a = settled(MashaMood.Playful, listen = 0.50f, budget = 6)
        val b = settled(MashaMood.Playful, listen = 0.51f, budget = 6)
        for (n in names) assertEquals(n, a.getValue(n), b.getValue(n), 0.02f)
    }

    /* ── párpados, mirada y pupila ── */

    @Test
    fun `los parpados siguen a la mirada`() {
        assertEquals(GazeConfig.LID_DOWN, GazeConfig.lidDown(-GazeConfig.EYE_PITCH_DOWN), 1e-5f)
        assertEquals(GazeConfig.LID_DOWN / 2f, GazeConfig.lidDown(-GazeConfig.EYE_PITCH_DOWN / 2f), 1e-5f)
        assertEquals(0f, GazeConfig.lidDown(5f))
        val e = FaceExpression(1L)
        val c = FaceChannels()
        val input = ExpressionInput().apply { mood = MashaMood.Neutral; eyePitch = -15f }
        e.update(0f, 1f / 60f, input, c)
        assertEquals(GazeConfig.lidDown(-15f), c.blinkL, 1e-4f)
        // Un parpadeo a medias encima: se suman sin pasar de 1.
        input.blinkL = 0.5f
        e.update(1f / 60f, 1f / 60f, input, c)
        assertEquals(0.5f + 0.5f * GazeConfig.lidDown(-15f), c.blinkL, 1e-4f)
        // Mirar arriba abre los ojos y, pasados unos grados, alza las cejas.
        input.blinkL = 0f
        input.eyePitch = 12f
        e.update(2f / 60f, 1f / 60f, input, c)
        assertTrue(c.expr[Ex.WIDE_L] >= GazeConfig.lidUp(12f) - 1e-4f && c.expr[Ex.WIDE_L] > 0.3f)
        assertTrue(c.expr[Ex.BROW_INNER] > 0.05f)
        assertEquals(0f, c.blinkL)
    }

    /* ── parpadeo ── */

    /** Simula [seconds] s a 60 fps y devuelve los parpadeos: (inicio, pico, duración cerrada > 0,5). */
    private fun blinks(mode: Blink.Mode, seconds: Float = 600f, seed: Int = 4): List<Triple<Float, Float, Float>> {
        val b = Blink(Random(seed))
        val out = ArrayList<Triple<Float, Float, Float>>()
        var t = 0f
        var start = -1f
        var peak = 0f
        var closed = 0f
        while (t < seconds) {
            b.update(t, mode)
            val v = b.left(t)
            // Umbral 0,1: separa los dos de un doble (el primero aún no ha abierto del todo).
            if (v > 0.1f && start < 0f) { start = t; peak = 0f; closed = 0f }
            if (start >= 0f) {
                peak = maxOf(peak, v)
                if (v > 0.5f) closed += 1f / 60f
                if (v <= 0.1f) { out += Triple(start, peak, closed); start = -1f }
            }
            t += 1f / 60f
        }
        return out
    }

    @Test
    fun `parpadeo - intervalos variables, dobles y alguno incompleto`() {
        val bs = blinks(Blink.Mode.Rest)
        val gaps = bs.zipWithNext { a, b -> b.first - a.first }
        val mean = gaps.average()
        val sd = sqrt(gaps.map { (it - mean) * (it - mean) }.average())
        assertTrue("media $mean s", mean in 2.5..5.0)
        assertTrue("no es un metrónomo: sd $sd", sd > 0.8)
        // Dobles: dos parpadeos separados por menos de 0,35 s.
        val doubles = gaps.count { it < 0.35f }
        assertTrue("dobles: $doubles de ${bs.size}", doubles >= bs.size * 0.05 && doubles <= bs.size * 0.3)
        assertTrue(bs.any { it.second < 0.9f })
        assertTrue(bs.count { it.second >= 0.99f } > bs.size / 2)
    }

    @Test
    fun `pensando hay medios parpadeos, calida alguno lento, hablando mas que escuchando`() {
        val thinking = blinks(Blink.Mode.Thinking)
        val half = thinking.count { it.second in 0.35f..0.65f }
        assertTrue("medios parpadeos: $half de ${thinking.size}", half >= thinking.size * 0.35)
        val warm = blinks(Blink.Mode.Warm)
        val rest = blinks(Blink.Mode.Rest)
        assertTrue("lento: ${warm.maxOf { it.third }} s cerrado", warm.maxOf { it.third } > 0.22f)
        assertTrue(rest.maxOf { it.third } < 0.16f)
        assertTrue(blinks(Blink.Mode.Speaking).size > blinks(Blink.Mode.Listening).size * 1.3)
    }

    @Test
    fun `el parpadeo pedido es completo y no se pisa`() {
        // Azar fijo en 0,99: ni lento, ni incompleto, ni doble.
        val b = Blink(object : Random() { override fun nextBits(bitCount: Int) = (0.99 * (1 shl bitCount)).toInt() })
        b.trigger(1f)
        assertEquals(1f, b.currentDepth)
        assertEquals(1f, b.left(1f + Blink.CLOSE + 0.01f), 1e-4f)
        b.trigger(1.05f)
        // Sigue el primero (no vuelve a empezar).
        assertEquals(1f, b.left(1f + Blink.CLOSE + 0.01f), 1e-4f)
    }

    /* ── cabeza ── */

    @Test
    fun `escuchando inclina la cabeza y asiente en las pausas`() {
        val g = HeadGestures(2L)
        var t = 0f
        val dt = 1f / 60f
        var minNod = 0f
        var maxNod = 0f
        // Quien habla: 1,2 s de voz, 0,6 s de pausa, seis veces.
        while (t < 12f) {
            val mic = if ((t % 1.8f) < 1.2f) 0.7f else 0f
            g.update(t, dt, MashaMood.Neutral, speaking = false, listening = true, micLevel = mic, speechBrow = 0f)
            if (t > 3f) { minNod = minOf(minNod, g.nod); maxNod = maxOf(maxNod, g.nod) }
            t += dt
        }
        assertEquals(HeadGestures.LISTEN_TILT * DEG, abs(g.roll), 0.3f * DEG)
        assertTrue("cabeceos: ${g.backchannels}", g.backchannels >= 2)
        assertTrue("barbilla abajo ${maxNod / DEG}°", maxNod / DEG > 1f)
        // Sin escuchar ni hablar, en neutral, vuelve al centro.
        while (t < 16f) { g.update(t, dt, MashaMood.Neutral, false, false, 0f, 0f); t += dt }
        assertEquals(0f, g.roll, 0.1f * DEG)
    }

    @Test
    fun `hablando, los acentos cambian el lado de la inclinacion`() {
        val g = HeadGestures(4L)
        var t = 0f
        val dt = 1f / 60f
        val sides = HashSet<Boolean>()
        while (t < 8f) {
            // Un acento cada 1,5 s.
            val brow = if ((t % 1.5f) in 0.2f..0.5f) 0.3f else 0f
            g.update(t, dt, MashaMood.Neutral, speaking = true, listening = false, micLevel = 0f, speechBrow = brow)
            if (t > 1f && abs(g.roll) > 0.4f * DEG) sides += g.roll > 0f
            t += dt
        }
        assertEquals(setOf(true, false), sides)
        // El ánimo curioso inclina la cabeza más que ninguno.
        assertTrue(MashaMood.entries.all { MoodFace.tiltDeg(it) <= MoodFace.tiltDeg(MashaMood.Curious) })
    }

    /* ── el ánimo de una respuesta ── */

    @Test
    fun `curiosa cuando acaba preguntando y no hay otra senal`() {
        assertEquals(MashaMood.Curious, MashaMood.read("Te recomiendo Okami. ¿Lo has jugado?", emptyList(), failed = false))
        assertEquals(MashaMood.Curious, MashaMood.read("¿Quieres que te lo prepare? 🙂", emptyList(), failed = false))
        assertEquals(MashaMood.Neutral, MashaMood.read("¿Qué tal? Hecho.", emptyList(), failed = false))
        assertEquals(MashaMood.Playful, MashaMood.read("Jaja, ¿otra vez?", emptyList(), failed = false))
        assertEquals(MashaMood.Concerned, MashaMood.read("¿Puedes repetirlo?", emptyList(), failed = true))
    }

    /* ── sin objetos por fotograma ── */

    @Test
    fun `cara, parpadeo, voz y cabeza no crean objetos por fotograma`() {
        val allocated = threadAllocatedBytes()
        assumeTrue("la JVM no cuenta bytes por hilo", allocated != null)
        val lip = LipSync(LipSyncConfig())
        val frames = 200
        val data = FloatArray(frames * Ch.COUNT) { if (it % Ch.COUNT == Vis.AA.ordinal) 0.4f else 0.05f }
        lip.add(LipSync.Item("a").apply { track = Track(frames, data, intArrayOf(50), frames - 20, true); sampleRate = 24000 })
        lip.clock = object : LipSync.Clock {
            override fun seconds(item: LipSync.Item, nowNanos: Long) = nowNanos / 1e9
        }
        val speech = LipSync.Frame()
        val blink = Blink(Random(1))
        val expression = FaceExpression()
        val gestures = HeadGestures()
        val input = ExpressionInput()
        val c = FaceChannels()
        val morphs = FaceMorphs.forNames(names).also { it.exprBudget = 9 }
        val out = FloatArray(names.size)
        val moods = MashaMood.entries.toTypedArray()
        fun run(from: Int, n: Int) {
            for (i in from until from + n) {
                val t = i / 60f
                val now = (i * 1e9 / 60).toLong() % 2_000_000_000L
                lip.sample(now, 1f / 60f, speech)
                val mood = moods[(i / 300) % moods.size]
                val listening = (i / 700) % 2 == 1
                gestures.update(t, 1f / 60f, mood, speaking = !listening, listening = listening, micLevel = if (i % 90 < 60) 0.6f else 0f, speechBrow = speech.brow)
                if (speech.blink) blink.trigger(t)
                blink.update(t, if (listening) Blink.Mode.Listening else Blink.Mode.Speaking)
                speech.lips.copyInto(c.lips)
                c.jaw = speech.jaw
                input.mood = mood; input.listening = listening; input.speaking = !listening
                input.speechBrow = speech.brow; input.eyePitch = -5f + 10f * ((i % 240) / 240f)
                input.blinkL = blink.left(t); input.blinkR = blink.right(t)
                expression.update(t, 1f / 60f, input, c)
                morphs.write(c, out)
            }
        }
        // Calentamiento (carga de clases, JIT), y a medir.
        run(0, 3000)
        // El contador funciona: una reserva conocida se ve.
        val probe = allocated!!()
        val junk = FloatArray(2048)
        assertTrue(allocated() - probe >= 8192 && junk.size == 2048)
        val before = allocated()
        run(3000, 20_000)
        val bytes = allocated() - before
        assertTrue("$bytes bytes en 20 000 fotogramas", bytes < 4096)
    }

    /**
     * Bytes reservados por este hilo (`com.sun.management.ThreadMXBean`, por reflexión: no está en
     * los stubs de Android, sí en la JVM de las pruebas), o null si la JVM no lo ofrece.
     */
    private fun threadAllocatedBytes(): (() -> Long)? = runCatching {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val api = Class.forName("com.sun.management.ThreadMXBean")
        if (!api.isInstance(bean)) return null
        api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val get = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id: Any = Thread.currentThread().id
        val read: () -> Long = { get.invoke(bean, id) as Long }
        read().takeIf { it >= 0 } ?: return null
        read
    }.getOrNull()
}
