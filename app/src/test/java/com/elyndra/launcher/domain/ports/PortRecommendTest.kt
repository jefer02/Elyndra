package com.elyndra.launcher.domain.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lo que Lucy busca en la tienda de ports: por nombre, género o ánimo, y solo lo que corre aquí. */
class PortRecommendTest {

    private fun port(
        id: String,
        game: String,
        platform: String = "pc",
        android: Boolean = true,
        tags: List<String> = emptyList(),
        userRom: Boolean = false,
        availability: String = PortEntry.AVAILABLE,
    ) = PortEntry(
        id = id,
        name = "Port $id",
        repo = GhRepo("owner", id),
        game = PortGame(game, platform),
        builds = if (android) listOf(PortBuild("android")) else listOf(PortBuild("windows")),
        tags = tags,
        requires = Requirement(userRom = userRom),
        availability = availability,
    )

    private val doom = port("gzdoom", "Doom", "dos", tags = listOf("shooter", "fps", "horror"), userRom = true)
    private val quake = port("quake", "Quake", tags = listOf("fps", "shooter"), userRom = true)
    private val kart = port("srb2k", "Sonic Robo Blast 2 Kart", tags = listOf("racing", "kart", "multiplayer"))
    private val pcOnly = port("starship", "Star Fox 64", "n64", android = false, tags = listOf("shooter"))
    private val vr = port("questzdoom", "Doom 3", tags = listOf("fps", "horror"), availability = PortEntry.VR_ONLY)
    private val all = listOf(doom, quake, kart, pcOnly, vr)

    @Test
    fun byNameItFindsTheOriginalGame() {
        val found = PortRecommend.search(all, "Doom", null, null, emptyList())
        assertEquals(listOf("gzdoom"), found.groups.map { PortRecommend.best(it).id })
        // Doom 3 solo para gafas de RV: no corre en este móvil, se cuenta pero no sale.
        assertEquals(1, found.incompatible)
    }

    @Test
    fun incompatiblePortsOnlyWhenAskedAndAfterTheRest() {
        val found = PortRecommend.search(all, "Doom", null, null, emptyList(), includeIncompatible = true)
        assertEquals(listOf("gzdoom", "questzdoom"), found.groups.map { it.lead.id })
        assertEquals(0, found.incompatible)
    }

    @Test
    fun byGenreInAnyLanguage() {
        assertEquals("shooter", PortRecommend.genreOf("FPS"))
        assertEquals("racing", PortRecommend.genreOf("carreras"))
        assertEquals("platformer", PortRecommend.genreOf("plataformas"))
        assertNull(PortRecommend.genreOf("cooking"))
        val shooters = PortRecommend.search(all, null, "shooter", null, emptyList())
        assertEquals(setOf("gzdoom", "quake"), shooters.groups.map { it.lead.id }.toSet())
        assertEquals(2, shooters.incompatible) // Star Fox (sin APK) y Doom 3 (solo gafas de RV)
    }

    @Test
    fun byMood() {
        assertEquals(listOf("gzdoom"), PortRecommend.search(all, null, null, "something scary", emptyList()).groups.map { it.lead.id })
        assertEquals(listOf("srb2k"), PortRecommend.search(all, null, null, "para jugar con amigos", emptyList()).groups.map { it.lead.id })
        assertNull(PortRecommend.moodTags("whatever"))
        assertNull(PortRecommend.moodTags(""))
    }

    @Test
    fun stateAndRequiredGameData() {
        assertEquals(PortRecommend.AVAILABLE, PortRecommend.state(doom, emptySet(), emptySet()))
        assertEquals(PortRecommend.INSTALLED, PortRecommend.state(doom, setOf("gzdoom"), emptySet()))
        assertEquals(PortRecommend.UPDATE_AVAILABLE, PortRecommend.state(doom, setOf("gzdoom"), setOf("gzdoom")))
        assertEquals(PortRecommend.NOT_ON_ANDROID, PortRecommend.state(pcOnly, emptySet(), emptySet()))
        assertEquals("Doom", PortRecommend.requiredGame(doom))
        assertNull(PortRecommend.requiredGame(kart))
    }

    @Test
    fun theSummaryHasCountsAndGameNamesOnly() {
        val s = PortRecommend.summary(all, installedIds = setOf("quake"), updateIds = setOf("quake"), owned = emptyList())
        assertEquals(5, s.total)
        assertEquals(3, s.compatible)
        assertEquals(1, s.installed)
        assertEquals(1, s.updates)
        assertTrue(s.forYourLibrary.isEmpty())
        assertFalse(s.toString().contains("github"))
    }
}
