package com.elyndra.launcher.masha

import com.elyndra.launcher.masha.MashaTurn.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Retomar la última conversación: los plazos, el resumen sin IA y que nunca lleve rutas. */
class ConversationRecallTest {

    private val minute = 60_000L
    private val now = 1_800_000_000_000L

    private fun store(summary: String? = "We talked about Okami.", lastActivity: Long = now - 45 * minute) =
        MemoryConversationStore().apply {
            this.summary = summary
            lastActivityAt = lastActivity
        }

    @Test
    fun sheWelcomesBackOnlyAfterHalfAnHour() {
        assertTrue(ConversationRecall.shouldWelcomeBack(now, store(lastActivity = now - 31 * minute)))
        assertFalse(ConversationRecall.shouldWelcomeBack(now, store(lastActivity = now - 30 * minute)))
        assertFalse(ConversationRecall.shouldWelcomeBack(now, store(lastActivity = now - 5 * minute)))
    }

    @Test
    fun withoutATopicThereIsNoWelcomeBack() {
        assertFalse(ConversationRecall.shouldWelcomeBack(now, store(summary = null, lastActivity = now - 5 * 60 * minute)))
        assertFalse(ConversationRecall.shouldWelcomeBack(now, store(lastActivity = 0)))
    }

    @Test
    fun aConversationEndsAfterTenIdleMinutes() {
        assertFalse(ConversationRecall.isIdle(now, now - 9 * minute))
        assertTrue(ConversationRecall.isIdle(now, now - 10 * minute))
        assertFalse(ConversationRecall.isIdle(now, 0))
    }

    @Test
    fun itOnlyResummarizesWhenThereAreNewMessages() {
        val s = store(lastActivity = 100)
        s.summarizedUpTo = 100
        assertFalse(ConversationRecall.needsSummary(s))
        s.lastActivityAt = 200
        assertTrue(ConversationRecall.needsSummary(s))
    }

    @Test
    fun theFallbackPrefersTheLastGameMentioned() {
        val turns = listOf(
            MashaTurn(Role.User, "I'm stuck in Okami"),
            MashaTurn(Role.Masha, "Super Mario Bros. or Super Mario Bros. 3 would be a nice break."),
        )
        val topic = ConversationRecall.fallback(turns, listOf("Okami", "Super Mario Bros.", "Super Mario Bros. 3"))!!
        assertEquals("Super Mario Bros. 3", topic.game)
        assertEquals("We talked about Super Mario Bros. 3.", topic.summary)
    }

    @Test
    fun withoutGamesTheFallbackUsesWhatTheUserAskedFor() {
        val plan = ConversationRecall.fallback(listOf(MashaTurn(Role.User, "tengo 30-40 minutos")), emptyList())!!
        assertEquals("We planned a 30 to 40 minute gaming session.", plan.summary)
        assertNull(plan.game)
        val launch = ConversationRecall.fallback(listOf(MashaTurn(Role.User, "play Hollow Knight")), emptyList())!!
        assertEquals("Hollow Knight", launch.game)
        val chat = ConversationRecall.fallback(listOf(MashaTurn(Role.User, "hmm")), emptyList())!!
        assertEquals("We chatted for a bit.", chat.summary)
        assertNull(ConversationRecall.fallback(emptyList(), listOf("Okami")))
    }

    @Test
    fun theSummaryNeverCarriesPathsUrisOrFiles() {
        val dirty = "We talked about content://com.android.externalstorage/tree/primary%3AROMs and /storage/emulated/0/ROMs/okami.iso, " +
            "see https://example.com and C:\\Games\\okami.chd, plus file:///sdcard/x."
        val clean = ConversationRecall.sanitize(dirty)!!
        listOf("content:", "primary", "/storage", ".iso", "http", "C:\\", ".chd", "file:", "sdcard").forEach {
            assertFalse("\"$it\" en \"$clean\"", clean.contains(it, ignoreCase = true))
        }
        assertTrue(clean.startsWith("We talked about"))
    }

    @Test
    fun theSummaryIsOneShortSentence() {
        assertEquals("We talked about Okami.", ConversationRecall.sanitize("\"We talked about Okami.\" Then about Zelda."))
        val long = ConversationRecall.sanitize("We " + "really ".repeat(60) + "talked")!!
        assertTrue(long.length <= 181)
        assertTrue(long.endsWith("…"))
        assertNull(ConversationRecall.sanitize("  ..  "))
        assertEquals("Hm.", ConversationRecall.sanitize("Hm.\n<<A:+2>>"))
    }

    @Test
    fun theTranscriptIsShortAndHasNoMarkers() {
        val turns = (1..20).map { MashaTurn(if (it % 2 == 0) Role.Masha else Role.User, "line $it <<A:+3>>") }
        val t = ConversationRecall.transcript(turns)
        assertEquals(12, t.lines().size)
        assertFalse(t.contains("<<A:"))
        assertTrue(t.lines().first().startsWith("User: line 9"))
    }
}
