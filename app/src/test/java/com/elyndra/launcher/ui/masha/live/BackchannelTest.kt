package com.elyndra.launcher.ui.masha.live

import com.elyndra.launcher.ui.masha.live.HoldDecision.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Muletilla o interrupción: "mm" deja seguir a Lucy, una frase la corta. */
class BackchannelTest {

    @Test
    fun backchannelsInEveryLanguage() {
        listOf(
            "yeah", "Mm-hmm.", "mhm", "uh-huh", "ok", "right", // en
            "ajá", "Sí, sí", "vale", "claro", // es
            "aham", "sim", "tá", // pt
            "ouais", "oui", "d'accord", // fr
            "ja", "genau", "Mhm", // de
            "うん", "はい", "へえ", "なるほど", "そうだね", "うん、うん", // ja
            "mmmm", "", "  ",
        ).forEach { assertTrue("\"$it\" es muletilla", Backchannel.isOnly(it)) }
    }

    @Test
    fun aRealSentenceIsNot() {
        listOf(
            "yeah, but what about Okami",
            "wait",
            "espera, ¿y el de Zelda?",
            "não, outro",
            "attends une seconde",
            "nein, was anderes",
            "ちょっと待って",
            "ok ok ok ok", // demasiado larga para ser solo eso
        ).forEach { assertFalse("\"$it\" va en serio", Backchannel.isOnly(it)) }
    }

    @Test
    fun aBackchannelResumesHerOnceTheUserGoesQuiet() {
        assertEquals(Decision.Wait, HoldDecision.decide(speechMs = 300, paused = false, partial = "mm", lastIsQuestion = false))
        assertEquals(Decision.Resume, HoldDecision.decide(speechMs = 400, paused = true, partial = "ajá", lastIsQuestion = false))
        // Sin parciales (relevo) o sin nada reconocible: por la duración.
        assertEquals(Decision.Resume, HoldDecision.decide(speechMs = 350, paused = true, partial = null, lastIsQuestion = false))
    }

    @Test
    fun anyRealWordCommitsAtOnce() {
        assertEquals(Decision.Commit, HoldDecision.decide(speechMs = 260, paused = false, partial = "wait what", lastIsQuestion = false))
    }

    @Test
    fun talkingLongCommitsEvenWithoutText() {
        assertEquals(Decision.Commit, HoldDecision.decide(speechMs = 950, paused = false, partial = null, lastIsQuestion = false))
        assertEquals(Decision.Commit, HoldDecision.decide(speechMs = 800, paused = true, partial = "", lastIsQuestion = false))
        // Un "mmmmmm" largo también.
        assertEquals(Decision.Commit, HoldDecision.decide(speechMs = 1_000, paused = false, partial = "mmmmmm", lastIsQuestion = false))
    }

    @Test
    fun yesToHerClosingQuestionIsAnAnswer() {
        assertEquals(Decision.Commit, HoldDecision.decide(speechMs = 300, paused = false, partial = "yeah", lastIsQuestion = true))
        assertEquals(Decision.Commit, HoldDecision.decide(speechMs = 300, paused = true, partial = "sí", lastIsQuestion = true))
        assertTrue(HoldDecision.isQuestion("Want me to start it?"))
        assertTrue(HoldDecision.isQuestion("¿Te lo pongo?\""))
        assertTrue(HoldDecision.isQuestion("始めようか"))
        assertTrue(HoldDecision.isQuestion("今夜は何を遊ぶ？"))
        assertFalse(HoldDecision.isQuestion("Okami is waiting for you."))
        assertFalse(HoldDecision.isQuestion(null))
    }
}
