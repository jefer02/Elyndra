package com.elyndra.launcher.ui.masha.voice

import com.elyndra.launcher.ui.masha.MashaVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Piezas de la voz sin motor: PCM, diezmado, partición de frases y elección de voz del sistema. */
class VoicePipelineTest {

    private val sr = 44_100

    private fun tone(hz: Double, seconds: Double, amp: Float = 0.3f) =
        FloatArray((sr * seconds).toInt()) { (amp * sin(2 * PI * hz * it / sr)).toFloat() }

    private fun rms(x: FloatArray): Float = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size).toFloat()

    @Test
    fun trimQuitaElSilencioDeCabezaYDejaElMargen() {
        val x = FloatArray(sr / 2) + tone(220.0, 1.0) + FloatArray(sr)
        val y = Pcm.trim(x, sr)
        val lead = y.indexOfFirst { abs(it) > 0.01f }
        assertTrue("cabeza ${lead * 1000 / sr} ms", lead in (20 * sr / 1000)..(45 * sr / 1000))
        val tail = y.size - 1 - y.indexOfLast { abs(it) > 0.01f }
        assertTrue("cola ${tail * 1000 / sr} ms", tail in (90 * sr / 1000)..(130 * sr / 1000))
    }

    @Test
    fun trimDeSilencioEsVacio() {
        assertEquals(0, Pcm.trim(FloatArray(sr), sr).size)
    }

    @Test
    fun normalizeLlevaElRmsAlObjetivoSinPasarElPico() {
        val x = tone(220.0, 1.0, amp = 0.05f)
        Pcm.normalize(x, sr, targetDbfs = -16f)
        assertEquals(-16f, Pcm.lin2db(rms(x)), 0.5f)
        val loud = tone(220.0, 1.0, amp = 0.9f) // un seno a -16 dBFS rms pediría pico > 0 dBFS
        Pcm.normalize(loud, sr, targetDbfs = -3f, peakDbfs = -1f)
        assertTrue(loud.maxOf { abs(it) } <= Pcm.db2lin(-1f) + 1e-4f)
    }

    @Test
    fun pcm16EsLittleEndianYSatura() {
        val b = Pcm.toPcm16(floatArrayOf(0f, 1f, -1f, 2f))
        assertEquals(8, b.size)
        assertEquals(32767, (b[2].toInt() and 0xFF) or (b[3].toInt() shl 8))
        assertEquals(-32767, (b[4].toInt() and 0xFF) or (b[5].toInt() shl 8))
        assertEquals(32767, (b[6].toInt() and 0xFF) or (b[7].toInt() shl 8))
    }

    private fun decimate(x: FloatArray, chunk: Int): FloatArray {
        val d = HalfbandDecimator()
        val pcm = Pcm.toPcm16(x)
        val out = ArrayList<Float>()
        var o = 0
        while (o < pcm.size) {
            val e = minOf(pcm.size, o + chunk * 2)
            val y = d.process(pcm.copyOfRange(o, e))
            for (i in 0 until y.size / 2) out += ((y[2 * i].toInt() and 0xFF) or (y[2 * i + 1].toInt() shl 8)) / 32768f
            o = e
        }
        return out.toFloatArray()
    }

    @Test
    fun diezmadorDejaPasarLaVozYQuitaLoQueNoCabe() {
        val voice = decimate(tone(1_000.0, 0.5), chunk = 441)
        assertTrue("muestras ${voice.size}", abs(voice.size - sr / 4) <= 2)
        assertEquals(Pcm.lin2db(0.3f / sqrt(2f)), Pcm.lin2db(rms(voice.copyOfRange(200, voice.size))), 0.5f)
        // 16 kHz no cabe en 22,05 kHz: debe quedar muy atenuado (sin aliasing audible en el análisis).
        val high = decimate(tone(16_000.0, 0.5), chunk = 1_000)
        assertTrue("16 kHz: ${Pcm.lin2db(rms(high.copyOfRange(200, high.size)))} dBFS", Pcm.lin2db(rms(high.copyOfRange(200, high.size))) < -50f)
    }

    @Test
    fun diezmadorIgualConTrozosImpares() {
        val x = tone(700.0, 0.3)
        val a = decimate(x, chunk = 4_410)
        val b = decimate(x, chunk = 333)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 1e-4f)
    }

    /** Ninguna parte pasa de 2,2 veces la anterior (se sintetiza mientras suena la anterior). */
    private fun assertBalanced(p: List<String>) {
        for (i in 1 until p.size) assertTrue("${p[i - 1]} | ${p[i]}", p[i].length <= p[i - 1].length * 2.2f + 1)
    }

    @Test
    fun partesCortaLaPrimeraYNoDesequilibra() {
        val t = "Si te gustó Hollow Knight, tienes que probar The Legend of Zelda: Tears of the Kingdom, que es enorme y tiene un mundo abierto precioso."
        val p = NeuralVoiceEngine.parts(t)
        assertTrue(p.size >= 2)
        assertTrue(p[0].length <= 70)
        assertBalanced(p)
        assertEquals(t.replace(" ", ""), p.joinToString("").replace(" ", ""))
    }

    @Test
    fun partesNoDejaUnaPrimeraCortaSeguidaDeUnaLarga() {
        // "If you liked Hollow Knight," (27) seguido de 70 caracteres sin coma: partir ahí atascaba (QA en_02).
        val t = "If you liked Hollow Knight, you should definitely try The Legend of Zelda Tears of the Kingdom next."
        val p = NeuralVoiceEngine.parts(t)
        assertBalanced(p)
    }

    @Test
    fun partesCortaTambienEnExclamacionesInteriores() {
        val t = "¡Claro que sí, con mucho gusto! He revisado tu biblioteca y creo que te encantaría algo tranquilo para esta noche."
        // Móvil rápido (RTF ~0,3 → crecimiento 2,8): corta tras la exclamación.
        val p = NeuralVoiceEngine.parts(t, growth = 2.8f)
        assertEquals("¡Claro que sí, con mucho gusto!", p[0])
        // Sin medida (2,2): el resto no cabe en 2,2 veces; mejor no partir que atascarse.
        assertEquals(listOf(t), NeuralVoiceEngine.parts(t))
    }

    @Test
    fun partesCortaTrasUnaExclamacionCorta() {
        // Tras "¡…!" vale una primera parte corta si lo que sigue cabe (hay otra coma después).
        val t = "¡Claro que sí! He revisado tu biblioteca, y creo que te encantaría algo tranquilo."
        val p = NeuralVoiceEngine.parts(t, growth = 3.0f)
        assertEquals("¡Claro que sí!", p[0])
        assertEquals("He revisado tu biblioteca,", p[1])
        // Si lo que sigue es muy largo y sin comas, no: se atascaría (mejor la frase entera).
        val long = "¡Claro que sí! He revisado tu biblioteca y creo que te encantaría algo tranquilo esta noche."
        assertEquals(listOf(long), NeuralVoiceEngine.parts(long, growth = 3.0f))
        // Tras una coma, nunca una parte tan corta.
        assertTrue(NeuralVoiceEngine.parts("Claro, he revisado tu biblioteca, y creo que te encantaría algo tranquilo esta noche.", growth = 3.0f)[0].length > 20)
    }

    @Test
    fun partesNoDejaUnFinalDemasiadoCorto() {
        val t = "Si te gustó Hollow Knight, tienes que probar The Legend of Zelda: Tears of the Kingdom."
        for (g in listOf(1.6f, 2.2f, 3.0f)) assertTrue(NeuralVoiceEngine.parts(t, g).last().length >= 25)
    }

    @Test
    fun partesNoTocaFrasesCortas() {
        assertEquals(listOf("Hola, soy Masha."), NeuralVoiceEngine.parts("Hola, soy Masha."))
        assertEquals(listOf("¡Claro!"), NeuralVoiceEngine.parts("¡Claro!"))
    }

    @Test
    fun pausaSegunLaPuntuacion() {
        assertEquals(300, NeuralVoiceEngine.tailMs("Hola, soy Masha."))
        assertEquals(300, NeuralVoiceEngine.tailMs("¿Qué te apetece jugar hoy?"))
        assertEquals(300, NeuralVoiceEngine.tailMs("Dijo «vale.»"))
        assertEquals(150, NeuralVoiceEngine.tailMs("Si te gustó Hollow Knight,"))
        assertEquals(110, NeuralVoiceEngine.tailMs("sin puntuación"))
    }

    @Test
    fun unPicoDeCargaNoApagaLaVozNatural() {
        // Una sesión con picos (1,3) pero la mayoría de frases rápidas: el percentil bajo manda.
        assertEquals(0.3f, RtfGate.session(listOf(0.3f, 1.3f, 0.28f, 1.1f, 0.32f))!!, 0.05f)
        assertEquals(null, RtfGate.session(listOf(1.3f, 1.2f)))
        // Una sola sesión lenta no basta.
        assertTrue(!RtfGate.tooSlow(listOf(1.2f)))
        assertTrue(!RtfGate.tooSlow(listOf(1.2f, 0.3f, 0.35f)))
        // Lenta en varias sesiones: voz del sistema.
        assertTrue(RtfGate.tooSlow(listOf(0.95f, 1.1f)))
        assertTrue(RtfGate.estimate(emptyList()).isNaN())
    }

    @Test
    fun sentencesPartePorFrasesYUneLasCortas() {
        val t = "Hola. Soy Masha, tu asistente de juegos. ¿Qué te apetece jugar hoy? ¡Vale!"
        val s = MashaVoice.sentences(t, 0, t.length)
        assertEquals(listOf("Hola. Soy Masha, tu asistente de juegos. ", "¿Qué te apetece jugar hoy? ¡Vale!"), s)
        assertEquals(t, s.joinToString(""))
    }

    @Test
    fun sentencesSinPuntoFinal() {
        val t = "Tu partida guardada está lista para continuar cuando quieras"
        assertEquals(listOf(t), MashaVoice.sentences(t, 0, t.length))
    }

    @Test
    fun vozDelSistemaPrefiereLasFemeninasConocidas() {
        val es = Locale.forLanguageTag("es")
        val female = SystemTtsEngine.score("es-us-x-sfb-local", 400, 200, false, Locale.forLanguageTag("es-US"), es, emptySet())
        val male = SystemTtsEngine.score("es-us-x-esd-local", 400, 200, false, Locale.forLanguageTag("es-US"), es, emptySet())
        val generic = SystemTtsEngine.score("es-US-language", 400, 200, false, Locale.forLanguageTag("es-US"), es, emptySet())
        val network = SystemTtsEngine.score("es-us-x-sfb-network", 400, 200, true, Locale.forLanguageTag("es-US"), es, emptySet())
        assertTrue(female > male)
        assertTrue(female > generic)
        assertTrue("local antes que en red", female > network)
    }

    @Test
    fun localeDeLaVozNatural() {
        assertEquals("es", NeuralVoiceEngine.localeFor("es").language)
        assertEquals("US", NeuralVoiceEngine.localeFor("en").country)
        assertEquals("BR", NeuralVoiceEngine.localeFor("pt").country)
    }
}
