package com.elyndra.launcher.masha

import com.elyndra.launcher.masha.ConfirmationResolver.Answer
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Sí", "no" u otra cosa, por voz o por escrito, en los seis idiomas de la app. */
class ConfirmationResolverTest {

    private fun check(expected: Answer, vararg texts: String) =
        texts.forEach { assertEquals(it, expected, ConfirmationResolver.resolve(it)) }

    @Test
    fun spanish() {
        check(Answer.Affirmative, "sí", "Si.", "dale", "confirmo", "hazlo", "vale, hazlo", "sí, por favor", "¡Claro que sí!", "venga", "de acuerdo", "añádelos")
        check(Answer.Negative, "no", "No, gracias", "cancela", "mejor no", "déjalo", "olvídalo")
        check(Answer.Unrelated, "sí, pero mejor Okami", "¿qué juegos tengo?", "no sé qué jugar hoy", "dale caña al Doom esta noche con mis amigos de siempre")
    }

    @Test
    fun english() {
        check(Answer.Affirmative, "yes", "Yes!", "ok do it", "OK", "sure", "go ahead", "yes please", "let's do it", "sounds good")
        check(Answer.Negative, "no", "nope", "cancel", "never mind", "no thanks", "don't")
        check(Answer.Unrelated, "yes but add Zelda too", "what do I have?")
    }

    @Test
    fun portuguese() {
        check(Answer.Affirmative, "sim", "pode", "pode ser", "faz isso", "claro", "bora")
        check(Answer.Negative, "não", "nao", "deixa", "esquece")
    }

    @Test
    fun french() {
        check(Answer.Affirmative, "oui", "d'accord", "vas-y", "ouais, merci", "fais-le")
        check(Answer.Negative, "non", "annule", "laisse tomber", "non merci")
    }

    @Test
    fun german() {
        check(Answer.Affirmative, "ja", "ja bitte", "klar", "mach es", "bestätigen", "auf jeden Fall")
        check(Answer.Negative, "nein", "abbrechen", "lass es", "nein danke")
    }

    @Test
    fun japanese() {
        check(Answer.Affirmative, "はい", "うん", "はい、お願いします", "いいよ", "オッケー")
        check(Answer.Negative, "いいえ", "やめて", "キャンセル", "だめ")
        check(Answer.Unrelated, "大神を起動して、それとゼルダも")
    }

    @Test
    fun theOldYesNoRulesStillAnswerTheSame() {
        assertEquals(true, MashaWriteRules.answer("confirmo"))
        assertEquals(false, MashaWriteRules.answer("cancela"))
        assertEquals(null, MashaWriteRules.answer("sí, pero añade también Zelda"))
    }
}
