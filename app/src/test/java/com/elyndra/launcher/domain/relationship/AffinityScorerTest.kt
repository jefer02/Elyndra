package com.elyndra.launcher.domain.relationship

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Las reglas locales de puntuación (sin conexión o sin marca de la IA). */
class AffinityScorerTest {

    private fun score(msg: String, level: AffinityLevel = AffinityLevel.Curious, action: Boolean = false, previous: String? = null) =
        AffinityScorer.score(msg, level, action, previous)

    @Test
    fun greetingsCountOnlyAtTheStart() {
        assertEquals(1, score("hola Lucy", AffinityLevel.Distant))
        assertEquals(1, score("Hi!", AffinityLevel.Curious))
        assertEquals(0, score("hello", AffinityLevel.Friendly))
        assertEquals(0, score("こんにちは", AffinityLevel.Devoted))
    }

    @Test
    fun plainChatAndActionsScoreNothing() {
        assertEquals(0, score("what's the weather like"))
        assertEquals(0, score("juega Okami", action = true))
        assertEquals(0, score("gracias, lanza Zelda", action = true))
        assertEquals(0, score(""))
    }

    @Test
    fun complimentsScaleWithTheLevel() {
        val curious = score("you're amazing", AffinityLevel.Curious)
        val devoted = score("you're amazing", AffinityLevel.Devoted)
        assertTrue(curious in 3..10)
        assertTrue(devoted > curious)
        assertTrue(score("eres la mejor", AffinityLevel.Close) >= 5)
    }

    @Test
    fun curiosityKindnessAndSharingAdd() {
        assertTrue(score("¿qué música te gusta?") > 0)
        assertTrue(score("thanks Lucy") >= 3)
        assertTrue(score("today I finally finished my exams and I feel great") > 0)
        assertTrue(score("je suis fatiguée aujourd'hui") > 0)
    }

    @Test
    fun humourCountsFromFriendlyUp() {
        assertEquals(0, score("jajaja", AffinityLevel.Curious))
        assertTrue(score("jajaja", AffinityLevel.Friendly) > 0)
    }

    @Test
    fun rudenessSubtracts() {
        assertTrue(score("shut up") < 0)
        assertTrue(score("eres idiota") <= -5)
        assertTrue(score("halt die Klappe") < 0)
    }

    @Test
    fun repeatingTheSameMessageSubtractsALittle() {
        assertEquals(-1, score("you're amazing", previous = "You're amazing"))
    }

    @Test
    fun sexualContentNeverScoresAndCapsTheModel() {
        assertEquals(0, score("send nudes"))
        assertEquals(0, score("you're sexy", AffinityLevel.Devoted))
        assertEquals(0, AffinityScorer.guard("quítate la ropa, desnúdate", 8))
        assertEquals(-3, AffinityScorer.guard("send nudes", -3))
        assertEquals(6, AffinityScorer.guard("you're amazing", 6))
    }

    @Test
    fun shortStemsDoNotMatchInsideOtherWords() {
        // "sex" en "sexto", "oi" en "oigo", "hi" en "this".
        assertEquals(0, score("es mi sexto intento"))
        assertEquals(0, score("oigo música", AffinityLevel.Curious))
        assertEquals(0, score("this is it", AffinityLevel.Curious))
    }

    @Test
    fun theResultIsAlwaysWithinTen() {
        val all = "you're amazing thanks do you like cats today I feel happy jaja"
        assertTrue(score(all, AffinityLevel.Devoted) <= Affinity.MAX_DELTA)
    }
}
