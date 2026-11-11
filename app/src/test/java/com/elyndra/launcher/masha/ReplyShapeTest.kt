package com.elyndra.launcher.masha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Con tarjetas, una frase corta: nunca un muro de texto. */
class ReplyShapeTest {

    private val cards = MashaAttachment.Picks(listOf(MashaAttachment.Pick(MashaAttachment.Pick.GAME, "rom:okami", "You left it at the best part.")))

    private val wall = "Okay, I've got two for you. The first one is perfect for tonight. Okami is a gorgeous adventure where you " +
        "paint the world back to life, and you left it at the second hour. Hollow Knight is harder, but you said you wanted a challenge, " +
        "and it runs great here with no emulator at all."

    @Test
    fun aReplyWithCardsProducesNoLongBubble() {
        val shown = ReplyShape.display(wall, cards)
        assertEquals("Okay, I've got two for you. The first one is perfect for tonight.", shown)
        assertTrue(shown.length <= ReplyShape.MAX_CHARS)
        assertFalse(shown.contains("Hollow Knight"))
    }

    @Test
    fun withoutCardsTheTextIsUntouched() {
        assertEquals(wall, ReplyShape.display(wall, null))
        assertEquals(wall, ReplyShape.display(wall, MashaAttachment.Done("▶ Okami")))
    }

    @Test
    fun aSingleRunOnSentenceIsCutAtAWord() {
        val long = "word ".repeat(80).trim()
        val shown = ReplyShape.spokenLine(long)
        assertTrue(shown.length <= ReplyShape.MAX_CHARS + 1)
        assertTrue(shown.endsWith("…"))
        assertFalse(ReplyShape.spokenLine(long, ellipsis = false).endsWith("…"))
    }

    @Test
    fun itIsAlwaysAPrefixWhileStreaming() {
        // La voz lee el texto según llega: cada recorte parcial empieza igual que el final.
        val final = ReplyShape.spokenLine(wall, ellipsis = false)
        for (n in 1..wall.length) {
            val partial = ReplyShape.spokenLine(wall.substring(0, n), ellipsis = false)
            assertTrue("en $n", final.startsWith(partial) || partial.startsWith(final))
        }
    }

    @Test
    fun japaneseSentencesCountToo() {
        val ja = "二つあるよ。一つ目は今夜にぴったり。オカミは前回の続きから遊べるし、とてもきれい。"
        assertEquals("二つあるよ。一つ目は今夜にぴったり。", ReplyShape.display(ja, cards))
    }

    @Test
    fun aShortReplyIsKeptWhole() {
        assertEquals("Here you go!", ReplyShape.display("Here you go!", cards))
    }
}
