package com.elyndra.launcher.masha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El prompt de Lucy: al día con las herramientas reales y sin lo que se quitó del personaje original. */
class MashaPromptTest {

    private val prompt = MashaPrompt.SYSTEM
    private val toolNames = MashaTools.specs.map { it.name }

    /** El prompt sin la única línea que puede nombrar las palabras prohibidas. */
    private val withoutBannedLine = prompt.lines().filterNot { it.trim() == MashaPrompt.BANNED_LINE }.joinToString("\n")

    private fun tools(linePrefix: String): Set<String> {
        val line = prompt.lines().single { it.startsWith(linePrefix) }
        return Regex("""\b[a-z]+(?:_[a-z]+)+\b|\bremember\b|\bforget\b""").findAll(line).map { it.value }.toSet()
    }

    @Test
    fun everyDeclaredToolIsInThePrompt() {
        toolNames.forEach { assertTrue("Falta $it en el prompt", Regex("""\b$it\b""").containsMatchIn(prompt)) }
    }

    @Test
    fun theReadAndWriteGroupsMatchMashaTools() {
        assertEquals(MashaTools.MUTATING, tools("- Actions that change something"))
        assertEquals(toolNames.toSet() - MashaTools.MUTATING, tools("- Use tools to answer with real data"))
    }

    @Test
    fun bannedWordsOnlyAppearInTheLineThatBansThem() {
        assertTrue(prompt.lines().any { it.trim() == MashaPrompt.BANNED_LINE })
        val text = withoutBannedLine.lowercase()
        // Palabra entera, con plural o "-g" ("vibes", "chilling"); "yo" no cuenta dentro de "you".
        MashaPrompt.BANNED_WORDS.forEach { word ->
            assertFalse("\"$word\" en el prompt", Regex("""\b${Regex.escape(word)}[sg]?\b""").containsMatchIn(text))
        }
    }

    @Test
    fun nothingSexualPossessiveOrCodependentIsLeft() {
        val text = prompt.lowercase()
        listOf(
            "horny", "literotica", "undress", "nsfw", "lingerie", "naked", "nude",
            "jealous", "codependen", "adoration", "undivided", "expletive", "obsess",
            "relationship level", "affection level", "heartbeat", "teleport yourself",
        ).forEach { assertFalse("\"$it\" en el prompt", text.contains(it)) }
    }

    @Test
    fun sheIsLucyAndTheOperationalSectionsStay() {
        assertTrue(prompt.startsWith("You are Lucy"))
        assertFalse(prompt.contains("Masha"))
        listOf("WHAT ELYNDRA IS", "THE CONTEXT YOU RECEIVE", "HOW TO ACT", "HARD RULES", "FORMAT", "BOUNDARIES", "LANGUAGE")
            .forEach { assertTrue("Falta la sección $it", prompt.lines().any { l -> l.trim() == it }) }
        assertTrue(prompt.contains("Reply in the language of the user's message"))
        assertFalse(prompt.contains("English only", ignoreCase = true))
    }

    @Test
    fun theVersionIsBumpedForTheNewPersona() {
        assertEquals(6, MashaPrompt.VERSION)
    }
}
