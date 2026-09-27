package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.masha.MashaTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MashaPresenceLogicTest {

    /* ── ánimo ── */

    @Test
    fun `datos de herramientas se leen como analisis`() {
        assertEquals(MashaMood.Analytical, MashaMood.read("Esta semana 3 h 20 min en 12 sesiones.", listOf(MashaTools.GET_STATS), failed = false))
    }

    @Test
    fun `bromas y consuelo cambian el animo`() {
        assertEquals(MashaMood.Playful, MashaMood.read("Jaja, otra vez Okami. Clásico.", emptyList(), failed = false))
        assertEquals(MashaMood.Warm, MashaMood.read("Lo siento, eso ha sido frustrante. Tranquilo.", emptyList(), failed = false))
    }

    @Test
    fun `un fallo es preocupacion y sin senales es neutral`() {
        assertEquals(MashaMood.Concerned, MashaMood.read("Jaja", emptyList(), failed = true))
        assertEquals(MashaMood.Neutral, MashaMood.read("Hecho.", emptyList(), failed = false))
    }

    /* ── voz ── */

    @Test
    fun `solo se leen frases terminadas mientras llega el texto`() {
        val text = "Hola. Te recomiendo Okami porque lo dejaste a medias. Y des"
        val end = MashaVoice.lastSentenceEnd(text, 0)
        assertEquals("Hola. Te recomiendo Okami porque lo dejaste a medias. ", text.substring(0, end))
    }

    @Test
    fun `sin frase completa no se lee nada`() {
        assertEquals(0, MashaVoice.lastSentenceEnd("Te recomiendo", 0))
    }

    @Test
    fun `lo que se lee va sin emojis ni formato ni enlaces`() {
        val spoken = MashaVoice.speakable("**Okami** 🎮 está en https://example.com ▍ listo")
        assertEquals("Okami está en listo", spoken)
    }

    /* ── labios ── */

    @Test
    fun `una palabra abre la boca y vuelve a cerrarse`() {
        val lip = LipSync()
        val w = LipSync.Weights()
        lip.onSpeechStart(0L)
        lip.onWord("mapa", 0L)
        lip.sample(62_000_000L, w) // la "a"
        assertTrue(w.v[LipSync.V.AA.ordinal] > 0.5f)
        lip.onSpeechEnd()
        lip.sample(62_000_000L, w)
        assertTrue(w.v.all { it == 0f })
    }
}
