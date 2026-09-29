package com.elyndra.launcher.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageGuessTest {

    @Test
    fun recognisesTheAppLanguages() {
        assertEquals("en", LanguageGuess.detect("The player controls Mario as he travels through the Mushroom Kingdom to rescue the princess."))
        assertEquals("es", LanguageGuess.detect("El jugador controla a Mario en su viaje por el Reino Champiñón para rescatar a la princesa."))
        assertEquals("pt", LanguageGuess.detect("O jogador controla o Mario em sua jornada pelo Reino dos Cogumelos para resgatar a princesa."))
        assertEquals("fr", LanguageGuess.detect("Le joueur contrôle Mario dans son voyage à travers le Royaume Champignon pour sauver la princesse."))
        assertEquals("de", LanguageGuess.detect("Der Spieler steuert Mario auf seiner Reise durch das Pilzkönigreich, um die Prinzessin zu retten."))
        assertEquals("ja", LanguageGuess.detect("プレイヤーはマリオを操作して、キノコ王国を旅し、姫を救出する。"))
    }

    @Test
    fun saysNothingWhenItCannotTell() {
        assertNull(LanguageGuess.detect("Tetris"))
        assertNull(LanguageGuess.detect(""))
    }
}
