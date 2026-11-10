package com.elyndra.launcher.domain.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PortGroupingTest {

    private fun port(id: String, game: String, platform: String = "wiiu", verified: Boolean = false, stars: Int? = null, published: String? = null) = PortEntry(
        id = id,
        name = "Port $id",
        repo = GhRepo("o", id),
        game = PortGame(game, platform),
        builds = listOf(PortBuild("android")),
        release = PortReleaseInfo("v1", published),
        trust = PortTrust(verified = verified, stars = stars),
    )

    private val a = port("a", "The Legend of Zelda: The Wind Waker HD", stars = 50, published = "2026-01-01T00:00:00Z")
    private val b = port("b", "Legend of Zelda, The - The Wind Waker HD", stars = 50, published = "2026-06-01T00:00:00Z")
    private val c = port("c", "the legend of zelda: the wind waker hd", verified = true, stars = 5)
    private val d = port("d", "The Legend of Zelda: The Wind Waker HD", stars = 900)
    private val gc = port("gc", "The Legend of Zelda: The Wind Waker", platform = "gc")

    @Test fun `the same game on the same platform is one group whatever the spelling`() {
        assertEquals(PortGrouping.key(a), PortGrouping.key(b))
        assertEquals(PortGrouping.key(a), PortGrouping.key(c))
        // Otra plataforma (o el original de GameCube) es otro juego.
        assertNotEquals(PortGrouping.key(a), PortGrouping.key(gc))
        assertNotEquals(PortGrouping.key(a), PortGrouping.key(a.copy(game = a.game.copy(originalPlatform = "switch"))))
    }

    @Test fun `ports are ordered by trust then stars then the latest release`() {
        val group = PortGrouping.group(listOf(a, b, c, d)).single()
        assertEquals(listOf("c", "d", "b", "a"), group.ports.map { it.id })
        assertEquals("c", group.lead.id)
    }

    @Test fun `release dates already read override the catalog date`() {
        val order = PortGrouping.group(listOf(a, b), publishedAt = mapOf("a" to Long.MAX_VALUE)).single().ports.map { it.id }
        assertEquals(listOf("a", "b"), order)
    }

    @Test fun `an installed port leads its group`() {
        assertEquals("a", PortGrouping.group(listOf(a, b, c, d), installedIds = setOf("a")).single().lead.id)
    }

    @Test fun `groups keep the order in which each game first appears`() {
        val groups = PortGrouping.group(listOf(gc, a, b))
        assertEquals(listOf(listOf("gc"), listOf("b", "a")), groups.map { g -> g.ports.map { it.id } })
    }

    @Test fun `siblings are the picker list and include the port itself`() {
        assertEquals(listOf("c", "d", "b", "a"), PortGrouping.siblings(a, listOf(gc, a, b, c, d)).map { it.id })
        assertEquals(listOf("gc"), PortGrouping.siblings(gc, listOf(a, b)).map { it.id })
    }
}
