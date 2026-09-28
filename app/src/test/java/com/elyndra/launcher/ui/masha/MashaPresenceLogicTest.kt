package com.elyndra.launcher.ui.masha

import android.media.AudioFormat
import com.elyndra.launcher.masha.MashaTools
import com.elyndra.launcher.ui.masha.lipsync.SpanishG2p
import com.elyndra.launcher.ui.masha.lipsync.Synth
import com.elyndra.launcher.ui.masha.lipsync.Utterance
import com.elyndra.launcher.ui.masha.lipsync.Vis
import com.elyndra.launcher.ui.masha.lipsync.VowelProfile
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
    fun `en japones el punto cierra la frase aunque no haya espacio`() {
        val text = "こんにちは、マーシャです。今夜は短いゲームで遊びませんか？まだ"
        val end = MashaVoice.lastSentenceEnd(text, 0)
        assertEquals("こんにちは、マーシャです。今夜は短いゲームで遊びませんか？", text.substring(0, end))
        // Una frase japonesa corta ya se lee sola (sus 13 caracteres son ~13 sílabas).
        assertEquals("こんにちは、マーシャです。", "こんにちは、マーシャです。今夜".let { it.substring(0, MashaVoice.lastSentenceEnd(it, 0)) })
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
        // "mapa" con audio sintético (m, a, cierre, a) y el rango de la palabra a los 100 ms.
        val audio = Synth.render(Synth.Sil(100), Synth.Nasal(70), Synth.A(130), Synth.Gap(70), Synth.A(160), Synth.Sil(200))
        val u = Utterance("mapa", SpanishG2p(distincion = false, sheismo = false), seed = 1)
        u.begin(Synth.SR, AudioFormat.ENCODING_PCM_16BIT, 1)
        u.audio(Synth.pcm16(audio))
        u.range(0, 4, 2400)
        u.finish()
        val lip = LipSync()
        val item = LipSync.Item("masha-0").apply { track = u.build(lip.config, VowelProfile(), 0.075f); sampleRate = Synth.SR }
        lip.add(item)
        lip.clock = object : LipSync.Clock {
            override fun seconds(item: LipSync.Item, nowNanos: Long) = nowNanos / 1e9
        }
        val w = LipSync.Frame()
        var now = 0L
        var maxAA = 0f
        while (now < 560_000_000L) {
            lip.sample(now, 1 / 60f, w)
            maxAA = maxOf(maxAA, w.lips[Vis.AA.ordinal])
            now += 16_666_667L
        }
        assertTrue(maxAA > 0.35f)
        assertTrue(w.jaw > 0f || maxAA > 0f)
        // Callada (se quita la frase): la boca se cierra sola.
        lip.clear()
        repeat(60) { lip.sample(now, 1 / 60f, w); now += 16_666_667L }
        assertTrue(w.lips.all { it < 0.01f } && w.jaw < 0.01f)
    }
}
