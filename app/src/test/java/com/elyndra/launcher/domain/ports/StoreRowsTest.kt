package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.domain.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreRowsTest {

    private fun port(id: String, platform: String, android: Boolean = true, featured: Boolean = false, tags: List<String> = emptyList()) = PortEntry(
        id = id,
        name = "Port $id",
        repo = GhRepo("owner", id),
        original = OriginalGame("Game $id", platform),
        builds = if (android) listOf(PortBuild("android")) else listOf(PortBuild("windows")),
        tags = tags,
        featured = featured,
    )

    private val soh = port("soh", "n64", featured = true, tags = listOf("adventure", "mods"))
    private val nsmbw = port("nsmbw", "wii", tags = listOf("platformer"))
    private val unleashed = port("unleashed", "xbox360", featured = true, tags = listOf("platformer", "recomp"))
    private val starship = port("starship", "n64", android = false, tags = listOf("shooter"))
    private val all = listOf(soh, nsmbw, unleashed, starship)

    @Test fun `pc only ports are hidden unless asked for`() {
        assertEquals(listOf("soh", "nsmbw", "unleashed"), StoreRows.visible(all, StoreFilter()).map { it.id })
        assertEquals(4, StoreRows.visible(all, StoreFilter(showPcOnly = true)).size)
    }

    @Test fun `search matches every word in name original title repo and tags`() {
        assertEquals(listOf("unleashed"), StoreRows.visible(all, StoreFilter(query = "recomp game")).map { it.id })
        assertEquals(listOf("soh"), StoreRows.visible(all, StoreFilter(query = "OWNER/SOH")).map { it.id })
        assertTrue(StoreRows.visible(all, StoreFilter(query = "zzz")).isEmpty())
    }

    @Test fun `platform and tag filters combine`() {
        val f = StoreFilter(platforms = setOf("wii", "xbox360"), tags = setOf("platformer"))
        assertEquals(listOf("nsmbw", "unleashed"), StoreRows.visible(all, f).map { it.id })
    }

    @Test fun `the home rows follow the store order and skip empty rows`() {
        val owned = listOf(RomPortMatch(soh, Fixtures.rom("1", Fixtures.folder("n64", "n64"), fileName = "Zelda OoT.z64"), MatchStrength.Hash))
        val rows = StoreRows.home(all, StoreFilter(), owned, installedIds = setOf("nsmbw"), publishedAt = mapOf("unleashed" to 20L, "soh" to 10L))
        assertEquals(
            listOf(StoreRowKind.Featured, StoreRowKind.BecauseYouHave, StoreRowKind.Nintendo, StoreRowKind.Xbox, StoreRowKind.RecentlyUpdated, StoreRowKind.Installed),
            rows.map { it.kind },
        )
        assertEquals(listOf("soh", "unleashed"), rows[0].ports.map { it.id })
        assertEquals("Zelda OoT", rows[1].subject)
        assertEquals(listOf("soh", "nsmbw"), rows[2].ports.map { it.id })
        assertEquals(listOf("unleashed", "soh"), rows[4].ports.map { it.id })
        assertEquals(listOf("nsmbw"), rows[5].ports.map { it.id })
    }

    @Test fun `an active filter gives a single results row`() {
        val rows = StoreRows.home(all, StoreFilter(query = "soh"), emptyList(), emptySet(), emptyMap())
        assertEquals(listOf(StoreRowKind.Results), rows.map { it.kind })
    }

    @Test fun `tags and platforms are listed for the filter chips`() {
        assertEquals("platformer", StoreRows.tags(all).first())
        assertEquals(listOf("n64", "wii", "xbox360"), StoreRows.platforms(all))
    }

    @Test fun `masha finds ports by query or by what the library has`() {
        assertEquals(listOf("soh", "starship"), StoreRows.find(all, "port", emptyList()).map { it.id }.filter { it == "soh" || it == "starship" })
        // Los instalables primero, aunque con la búsqueda también salgan los de PC.
        val n64 = StoreRows.find(all, "n64", emptyList())
        assertTrue(n64.indexOfFirst { !it.installable } > n64.indexOfLast { it.installable })
        val owned = listOf(RomPortMatch(soh, Fixtures.rom("1", Fixtures.folder("n64", "n64")), MatchStrength.Hash))
        assertEquals(listOf("soh"), StoreRows.find(all, null, owned).map { it.id })
        assertTrue(StoreRows.find(all, "  ", emptyList()).isEmpty())
    }
}
