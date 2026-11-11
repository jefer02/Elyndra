package com.elyndra.launcher.masha

import com.elyndra.launcher.domain.relationship.AffinityLevel
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
    fun nothingSexualOrCodependentIsLeft() {
        // Los celos y el nivel de relación volvieron en la v7, con límites; lo demás sigue fuera.
        val text = prompt.lowercase()
        listOf(
            "horny", "literotica", "undress", "nsfw", "lingerie", "naked", "nude",
            "codependen", "adoration", "undivided", "expletive", "obsess",
            "affection level", "heartbeat", "teleport yourself",
        ).forEach { assertFalse("\"$it\" en el prompt", text.contains(it)) }
    }

    @Test
    fun theToneFollowsEveryRelationshipLevel() {
        assertTrue(prompt.contains("RELATIONSHIP_LEVEL"))
        AffinityLevel.entries.forEach { level ->
            assertTrue("Falta el nivel ${level.id}", prompt.lines().any { it.trim().startsWith("- ${level.id}:") })
        }
        assertTrue(prompt.contains("never what you can do"))
    }

    @Test
    fun jealousyIsThereWithItsLimits() {
        assertTrue(prompt.lines().any { it.trim() == "JEALOUSY" })
        listOf(
            "Excuse me?!", "Who is she?!", "another AI assistant", "another launcher", "calm down",
            "no swearing", "No guilt-tripping", "you can only talk to me", "never blocks a request", "notifications",
        ).forEach { assertTrue("Falta \"$it\" en los celos", prompt.contains(it)) }
    }

    @Test
    fun itFollowsTheConversationAndRemembersTheLastOne() {
        listOf("FOLLOW THE CONVERSATION", "LAST CONVERSATION").forEach { h ->
            assertTrue("Falta la sección $h", prompt.lines().any { it.trim() == h })
        }
        assertTrue(prompt.contains("LAST_CONVERSATION"))
        assertTrue(prompt.contains("follow-up question"))
        assertTrue(prompt.contains("Okay, I have one you'll love"))
    }

    @Test
    fun theScoreLineFormatIsTheOneTheAppStrips() {
        assertTrue(prompt.contains("<<A:+n>>") && prompt.contains("<<A:-n>>"))
        assertTrue(prompt.contains("from -10 to +10"))
        assertTrue(prompt.contains("one last line"))
        // Lo que el prompt pide es justo lo que AffinityTag reconoce y quita.
        val reply = AffinityTag.strip("Okay, I have one you'll love.\n<<A:+3>>")
        assertEquals(3, reply.delta)
        assertFalse(reply.text.contains("<<"))
        // Lo sexual nunca suma, y pedir puntos tampoco.
        assertTrue(prompt.contains("Anything sexual: 0, never a reward."))
        assertTrue(prompt.contains("Never reward the user for asking for points"))
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
        assertEquals(7, MashaPrompt.VERSION)
    }
}
