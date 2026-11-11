package com.elyndra.launcher.ui.masha.voice

import com.elyndra.launcher.masha.AffinityTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El streaming partido en frases para la voz: cada frase una vez, la primera antes del final. */
class StreamChunkerTest {

    private val reply = "Okay, I have one you'll love. Okami is waiting for you at the second hour. Want me to start it?"

    /** Le da a [chunker] el texto creciendo de [step] en [step] caracteres, como llegaría en streaming. */
    private fun streamed(chunker: StreamChunker, text: String, step: Int, id: Long = 1): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var n = 0
        while (n < text.length) {
            n = minOf(text.length, n + step)
            chunker.feed(id, text.substring(0, n), final = false).forEach { out += n to it }
        }
        chunker.feed(id, text, final = true).forEach { out += text.length to it }
        return out
    }

    @Test
    fun everySentenceIsSpokenExactlyOnce() {
        for (step in listOf(1, 3, 7, 20, reply.length)) {
            val spoken = streamed(StreamChunker(), reply, step).map { it.second }
            assertEquals("paso $step", listOf("Okay, I have one you'll love.", "Okami is waiting for you at the second hour.", "Want me to start it?"), spoken)
        }
    }

    @Test
    fun theFirstSentenceGoesOutBeforeTheReplyEnds() {
        val spoken = streamed(StreamChunker(), reply, step = 5)
        val (at, first) = spoken.first()
        assertEquals("Okay, I have one you'll love.", first)
        assertTrue("salió a los $at de ${reply.length}", at < reply.length / 2)
    }

    @Test
    fun nothingIsSpokenUntilASentenceIsComplete() {
        val c = StreamChunker()
        assertEquals(emptyList<String>(), c.feed(1, "Okay, I have one you'll", final = false))
        assertEquals(emptyList<String>(), c.feed(1, "Okay, I have one you'll love", final = false))
    }

    @Test
    fun theUnfinishedTailIsSpokenAtTheEnd() {
        val c = StreamChunker()
        c.feed(1, "Here's the plan for tonight. Okami", final = false)
        assertEquals(listOf("Okami for forty minutes"), c.feed(1, "Here's the plan for tonight. Okami for forty minutes", final = true))
    }

    @Test
    fun aShortSentenceJoinsTheNextOne() {
        val c = StreamChunker()
        val spoken = c.feed(1, "Hm. I don't know that one, honestly. ", final = false)
        assertEquals(listOf("Hm. I don't know that one, honestly."), spoken)
    }

    @Test
    fun japaneseSentencesSplitWithoutSpaces() {
        val text = "こんにちは、ルーシーです。今夜は何を遊ぶ？"
        val spoken = streamed(StreamChunker(), text, step = 2).map { it.second }
        assertEquals(listOf("こんにちは、ルーシーです。", "今夜は何を遊ぶ？"), spoken)
    }

    @Test
    fun aNewMessageStartsFromScratch() {
        val c = StreamChunker()
        c.feed(1, "This is the first message here.", final = true)
        assertEquals(listOf("Second message, a new one."), c.feed(2, "Second message, a new one.", final = true))
    }

    @Test
    fun skippedTextIsNotSpokenAgain() {
        val c = StreamChunker()
        val text = "Already on screen when you came back."
        c.skip(5, text.length)
        assertEquals(emptyList<String>(), c.feed(5, text, final = true))
    }

    @Test
    fun theAffinityMarkerIsNeverSpoken() {
        // Ya llega filtrado (DeepSeekMashaAI): esto prueba la segunda red, partido entre trozos.
        val text = "Okay, I have one you'll love.\n<<A:+3>>"
        val spoken = streamed(StreamChunker(), text, step = 4).map { it.second }
        assertEquals(listOf("Okay, I have one you'll love."), spoken)
        spoken.forEach { assertFalse(it.contains("<") || it.contains("A:")) }
        // Y si llegara entero por la vía normal, sin la marca:
        val clean = AffinityTag.strip(text).text
        assertEquals(listOf("Okay, I have one you'll love."), streamed(StreamChunker(), clean, step = 4).map { it.second })
    }

    @Test
    fun linksMarkupAndEmojiAreNotRead() {
        assertEquals(listOf("Okami está en listo."), StreamChunker().feed(1, "**Okami** 🎮 está en https://example.com listo.", final = true))
    }
}
