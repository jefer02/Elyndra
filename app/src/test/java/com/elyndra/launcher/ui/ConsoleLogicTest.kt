package com.elyndra.launcher.ui

import com.elyndra.launcher.data.Systems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityOrderTest {

    private val order = listOf("ss", "igdb", "steam", "ra", "sgdb", "libretro")

    @Test
    fun movesOneStepAndStopsAtTheEnds() {
        assertEquals(listOf("igdb", "ss", "steam", "ra", "sgdb", "libretro"), PriorityOrder.move(order, "igdb", -1))
        assertEquals(listOf("ss", "steam", "igdb", "ra", "sgdb", "libretro"), PriorityOrder.move(order, "igdb", 1))
        // En los extremos no se mueve (ni da la vuelta).
        assertSame(order, PriorityOrder.move(order, "ss", -1))
        assertSame(order, PriorityOrder.move(order, "libretro", 1))
        // Lo que no está en la lista no cambia nada.
        assertSame(order, PriorityOrder.move(order, "nope", 1))
    }

    @Test
    fun moveToKeepsEveryOtherSourceInOrder() {
        assertEquals(listOf("sgdb", "ss", "igdb", "steam", "ra", "libretro"), PriorityOrder.moveTo(order, 4, 0))
        assertEquals(listOf("igdb", "steam", "ra", "sgdb", "libretro", "ss"), PriorityOrder.moveTo(order, 0, 5))
        assertSame(order, PriorityOrder.moveTo(order, 2, 2))
        assertSame(order, PriorityOrder.moveTo(order, -1, 2))
        assertSame(order, PriorityOrder.moveTo(order, 1, 9))
    }

    @Test
    fun dragChangesPlaceOnceItCrossesHalfARow() {
        val row = 48f
        assertEquals(2, PriorityOrder.dragTarget(2, 23f, row, 6))
        assertEquals(3, PriorityOrder.dragTarget(2, 25f, row, 6))
        assertEquals(1, PriorityOrder.dragTarget(2, -25f, row, 6))
        assertEquals(0, PriorityOrder.dragTarget(2, -500f, row, 6))
        assertEquals(5, PriorityOrder.dragTarget(2, 500f, row, 6))
        assertEquals(2, PriorityOrder.dragTarget(2, 30f, 0f, 6))
    }

    @Test
    fun summaryNamesTheFirstSources() {
        assertEquals("SS › IGDB …", PriorityOrder.summary(order, { it.uppercase() }))
        assertEquals("SS › IGDB", PriorityOrder.summary(listOf("ss", "igdb"), { it.uppercase() }))
        assertEquals("SS …", PriorityOrder.summary(order, { it.uppercase() }, take = 1))
    }
}

class SoundSummaryTest {

    private fun text(events: Int, custom: Int) =
        SoundSummary.text(events, custom, { "$it eventos" }, { "$it propios" })

    @Test
    fun withoutCustomSoundsOnlyCountsTheEvents() {
        assertEquals("9 eventos", text(9, 0))
    }

    @Test
    fun withCustomSoundsAddsHowMany() {
        assertEquals("9 eventos · 2 propios", text(9, 2))
        assertEquals("9 eventos · 9 propios", text(9, 9))
    }
}

class SettingsNavTest {

    @Test
    fun bumpersWrapAroundTheCategories() {
        val all = SettingsCategory.entries
        assertEquals(8, all.size)
        assertEquals(SettingsCategory.Sound, SettingsNav.step(SettingsCategory.Display, 1))
        assertEquals(SettingsCategory.About, SettingsNav.step(SettingsCategory.Display, -1))
        assertEquals(SettingsCategory.Display, SettingsNav.step(SettingsCategory.About, 1))
        // Una vuelta entera vuelve al principio.
        var c = SettingsCategory.Metadata
        repeat(all.size) { c = SettingsNav.step(c, 1) }
        assertEquals(SettingsCategory.Metadata, c)
    }

    @Test
    fun wideLayoutStartsAtSixHundredDp() {
        assertFalse(SettingsNav.isWide(412f))
        assertFalse(SettingsNav.isWide(599f))
        assertTrue(SettingsNav.isWide(600f))
        assertTrue(SettingsNav.isWide(892f))
    }

    @Test
    fun everyCategoryHasItsOwnTitleAndGlyph() {
        val all = SettingsCategory.entries
        assertEquals(all.size, all.map { it.title }.toSet().size)
        assertEquals(all.size, all.map { it.description }.toSet().size)
        assertEquals(all.size, all.map { it.glyph }.toSet().size)
    }
}

class SystemGroupsTest {

    private fun ids(query: String) = SystemGroups.group(Systems.ALL, query).flatMap { (_, list) -> list.map { it.id } }

    @Test
    fun everySystemBelongsToExactlyOneGroupAndNoneIsLost() {
        val grouped = SystemGroups.group(Systems.ALL, "")
        assertEquals(Systems.ALL.size, grouped.sumOf { it.second.size })
        assertEquals(Systems.ALL.map { it.id }.toSet(), ids("").toSet())
        // En el orden de los fabricantes y sin grupos vacíos.
        assertEquals(SystemMaker.entries.toList(), grouped.map { it.first })
    }

    @Test
    fun knownSystemsGoToTheirMaker() {
        assertEquals(SystemMaker.Nintendo, SystemGroups.makerOf("switch"))
        assertEquals(SystemMaker.Nintendo, SystemGroups.makerOf("virtualboy"))
        assertEquals(SystemMaker.Sony, SystemGroups.makerOf("psvita"))
        assertEquals(SystemMaker.Microsoft, SystemGroups.makerOf("xbox360"))
        assertEquals(SystemMaker.Sega, SystemGroups.makerOf("dreamcast"))
        assertEquals(SystemMaker.Pc, SystemGroups.makerOf("dos"))
        assertEquals(SystemMaker.Others, SystemGroups.makerOf("atari2600"))
        assertEquals(SystemMaker.Others, SystemGroups.makerOf("neogeo"))
    }

    @Test
    fun searchMatchesNamesAliasesAndWordsInAnyCase() {
        assertEquals(listOf("ps2"), ids("play 2"))
        assertEquals(listOf("megadrive"), ids("MegaDrive"))
        assertTrue("gba" in ids("advance"))
        assertTrue("n3ds" in ids("3ds"))
        // Sin acentos ni mayúsculas que valgan.
        assertTrue("snes" in ids("SÚPER nintendo"))
        assertTrue(ids("zzz nada").isEmpty())
        assertTrue(SystemGroups.group(Systems.ALL, "zzz nada").isEmpty())
    }

    @Test
    fun filteringKeepsOnlyGroupsWithMatches() {
        val groups = SystemGroups.group(Systems.ALL, "xbox")
        assertEquals(listOf(SystemMaker.Microsoft), groups.map { it.first })
        assertEquals(setOf("xbox", "xbox360"), groups.single().second.map { it.id }.toSet())
    }
}

class AppSearchTest {

    @Test
    fun matchesLabelOrPackageIgnoringCaseAndAccents() {
        assertTrue(AppSearch.matches("Minecraft", "com.mojang.minecraftpe", "mine"))
        assertTrue(AppSearch.matches("Pokémon GO", "com.nianticlabs.pokemongo", "pokemon"))
        assertTrue(AppSearch.matches("Roblox", "com.roblox.client", "ROBLOX client"))
        assertTrue(AppSearch.matches("Free Fire", "com.dts.freefireth", "dts"))
        assertTrue(AppSearch.matches("Anything", "a.b", "  "))
        assertFalse(AppSearch.matches("Roblox", "com.roblox.client", "minecraft"))
    }
}
