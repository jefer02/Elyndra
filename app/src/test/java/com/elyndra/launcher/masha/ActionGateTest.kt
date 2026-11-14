package com.elyndra.launcher.masha

import com.elyndra.launcher.masha.ActionGate.Decision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que el usuario pide o confirma se hace ya; lo ambiguo se pregunta; las ideas de Lucy se proponen. */
class ActionGateTest {

    private var n = 0
    private val writes = MashaWrites { "w${++n}" }

    @Test
    fun directRequestsInEveryLanguage() {
        listOf(
            "añade estos juegos", "¿puedes añadirlos?", "abre el port de God of War III", "cambia el acento a morado", "lanza Okami",
            "add these games", "open the God of War III port", "change the accent to purple", "launch Okami",
            "adiciona esses jogos", "ajoute ces jeux", "füge diese Spiele hinzu", "大神を起動して",
        ).forEach { assertTrue(it, DirectRequest.isDirect(it)) }
        listOf("¿qué juegos puedo añadir?", "should I add them?", "what's new this week", "hola Lucy", "estoy cansado")
            .forEach { assertFalse(it, DirectRequest.isDirect(it)) }
    }

    @Test
    fun askedOrConfirmedRunsNowHerIdeaIsProposedAndAmbiguityAsks() {
        assertEquals(Decision.Execute, ActionGate.decide(ActionGate.userAsked("añade estos juegos")))
        assertEquals(Decision.Execute, ActionGate.decide(ActionGate.userAsked("dale")))
        assertEquals(Decision.Execute, ActionGate.decide(ActionGate.userAsked("¿qué tal?", modelSaysRequested = true)))
        assertEquals(Decision.Propose, ActionGate.decide(ActionGate.userAsked("estoy aburrido")))
        assertEquals(Decision.Ask, ActionGate.decide(userAsked = true, candidates = 2))
    }

    @Test
    fun aDirectAddRunsWithNoCard() = runBlocking {
        var added = 0
        val asked = ActionGate.userAsked("añade estos juegos")
        val s = writes.submit(ConfirmKind.ADD_GAMES, listOf("Okami", "Ico"), execute = asked) {
            added++
            WriteOutcome("＋ 2") { "added 2 games" }
        }
        assertTrue(s is MashaWrites.Submitted.Done)
        assertEquals(1, added)
        assertNull(writes.latest())
        // Y se puede deshacer.
        assertEquals("added 2 games", writes.undoLast())
    }

    @Test
    fun confirmingAPendingAddGamesCardInWordsRunsIt() = runBlocking {
        var added = 0
        val s = writes.submit(ConfirmKind.ADD_GAMES, List(8) { "Game $it" }, execute = ActionGate.userAsked("te propongo algo")) {
            added++
            WriteOutcome("＋ 8") { "added 8 games" }
        }
        assertTrue(s is MashaWrites.Submitted.Proposed)
        assertEquals(0, added)
        val answered = writes.answer("confirmo")
        assertTrue(answered is MashaWrites.Answered.Confirmed)
        assertEquals(ConfirmKind.ADD_GAMES, answered!!.write.kind)
        assertEquals(1, added)
        assertNull(writes.latest())
    }

    @Test
    fun noDismissesAndUnrelatedLeavesTheCardWaiting() = runBlocking {
        var runs = 0
        writes.propose(ConfirmKind.RENAME, listOf("a", "b")) { runs++; WriteOutcome("x") }
        assertNull(writes.answer("¿y Okami qué tal va?"))
        assertTrue(writes.latest() != null)
        assertTrue(writes.answer("no, mejor no") is MashaWrites.Answered.Cancelled)
        assertNull(writes.latest())
        assertEquals(0, runs)
    }

    @Test
    fun anAmbiguousTargetAsksAndNothingRuns() {
        val titles = listOf("Final Fantasy VII", "Final Fantasy X", "Okami HD")
        val ff = TargetMatch.candidates("final fantasy", titles) { it }
        assertEquals(listOf("Final Fantasy VII", "Final Fantasy X"), ff)
        assertEquals(Decision.Ask, ActionGate.decide(userAsked = true, candidates = ff.size))
        val result = toolAmbiguous("final fantasy", ff)
        assertFalse(result.ok)
        assertEquals("true", result.payload["needs_clarification"].toString())
        // El título exacto o uno solo que encaja no es ambiguo.
        assertEquals(listOf("Final Fantasy X"), TargetMatch.candidates("Final Fantasy X", titles) { it })
        assertEquals(listOf("Okami HD"), TargetMatch.candidates("okami", titles) { it })
        assertTrue(TargetMatch.candidates("zelda", titles) { it }.isEmpty())
    }
}
