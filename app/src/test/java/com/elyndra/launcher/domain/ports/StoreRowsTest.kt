package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.domain.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreRowsTest {

    private fun port(
        id: String,
        platform: String,
        android: Boolean = true,
        featured: Boolean = false,
        tags: List<String> = emptyList(),
        game: String = "Game $id",
        stars: Int? = null,
    ) = PortEntry(
        id = id,
        name = "Port $id",
        repo = GhRepo("owner", id),
        game = PortGame(game, platform),
        builds = if (android) listOf(PortBuild("android")) else listOf(PortBuild("windows")),
        tags = tags,
        featured = featured,
        trust = PortTrust(stars = stars),
    )

    private val soh = port("soh", "n64", featured = true, tags = listOf("adventure", "mods"), game = "The Legend of Zelda: Ocarina of Time", stars = 100)
    private val shipwright = port("shipwright", "n64", game = "Legend of Zelda, The - Ocarina of Time", stars = 400)
    private val nsmbw = port("nsmbw", "wii", tags = listOf("platformer"))
    private val unleashed = port("unleashed", "xbox360", featured = true, tags = listOf("platformer", "recomp"))
    private val starship = port("starship", "n64", android = false, tags = listOf("shooter"))
    private val all = listOf(soh, nsmbw, unleashed, starship)

    @Test fun `the platform filter from a folder keeps the ports that would go to that folder`() {
        val filter = StoreFilter(platform = "n64")
        assertTrue(filter.active)
        assertEquals(listOf("soh", "starship"), StoreRows.visible(all, filter).map { it.id })
        // PC also takes MS-DOS ports and platforms the library does not know.
        val doom = port("doom", "dos")
        val odd = port("odd", "amiga")
        assertEquals(listOf("doom", "odd"), StoreRows.visible(listOf(soh, doom, odd), StoreFilter(platform = "pc")).map { it.id })
        assertEquals(listOf("doom"), StoreRows.visible(listOf(doom, odd), StoreFilter(platform = "dos")).map { it.id })
        // Back clears it with the rest of the filter.
        assertEquals(null, filter.cleared().platform)
    }

    @Test fun `ports not on android yet show in grids and search but only in their own home row`() {
        assertEquals(listOf("soh", "nsmbw", "unleashed", "starship"), StoreRows.visible(all, StoreFilter()).map { it.id })
        assertEquals(listOf("starship"), StoreRows.visible(all, StoreFilter(notOnAndroid = true)).map { it.id })
        val rows = StoreRows.home(all, StoreFilter(), emptyList(), installedIds = emptySet(), publishedAt = emptyMap())
        for (row in rows.filter { it.kind != StoreRowKind.ComingToAndroid }) {
            assertTrue(row.kind.name, row.ports.all { it.installable })
        }
        assertEquals(StoreRowKind.ComingToAndroid, rows.last().kind)
        assertEquals(listOf("starship"), rows.last().ports.map { it.id })
        // Nunca en "Porque tienes…", aunque la biblioteca tenga el juego.
        val owned = listOf(RomPortMatch(starship, Fixtures.rom("1", Fixtures.folder("n64", "n64")), MatchStrength.Hash))
        assertTrue(StoreRows.home(all, StoreFilter(), owned, emptySet(), emptyMap()).none { it.kind == StoreRowKind.BecauseYouHave })
    }

    @Test fun `a game that already installs with another port is not announced as coming`() {
        val viewOnlyOot = port("oot-pc", "n64", android = false, game = "The Legend of Zelda: Ocarina of Time")
        val rows = StoreRows.home(listOf(soh, viewOnlyOot), StoreFilter(), emptyList(), emptySet(), emptyMap())
        assertTrue(rows.none { it.kind == StoreRowKind.ComingToAndroid })
    }

    @Test fun `search matches the game name and the port name`() {
        assertEquals(listOf("soh"), StoreRows.visible(all, StoreFilter(query = "ocarina")).map { it.id })
        assertEquals(listOf("soh"), StoreRows.visible(all, StoreFilter(query = "port soh")).map { it.id })
        assertEquals(listOf("unleashed"), StoreRows.visible(all, StoreFilter(query = "recomp game")).map { it.id })
        assertEquals(listOf("soh"), StoreRows.visible(all, StoreFilter(query = "OWNER/SOH")).map { it.id })
        assertTrue(StoreRows.visible(all, StoreFilter(query = "zzz")).isEmpty())
    }

    @Test fun `platform family and genre filters combine`() {
        val f = StoreFilter(groups = setOf(PlatformGroup.Nintendo, PlatformGroup.Xbox), genres = setOf("platformer"))
        assertEquals(listOf("nsmbw", "unleashed"), StoreRows.visible(all, f).map { it.id })
        assertEquals(listOf("soh", "nsmbw", "starship"), StoreRows.visible(all, StoreFilter(groups = setOf(PlatformGroup.Nintendo))).map { it.id })
        assertEquals(PlatformGroup.Nintendo, StoreRows.group("switch2"))
    }

    @Test fun `raw github topics never become chips and genres come from a short curated list`() {
        val ported = port("x", "pc", tags = listOf("game-port", "android-port", "decompilation", "fps"))
        assertEquals(setOf("shooter"), StoreRows.genres(ported))
        assertEquals(listOf("platformer", "shooter"), StoreRows.genreChips(all + ported))
        assertTrue(StoreRows.GENRES.size <= 5)
        assertEquals(listOf(PlatformGroup.Nintendo, PlatformGroup.Xbox, PlatformGroup.Pc), StoreRows.groupChips(all + ported))
    }

    @Test fun `the grid sorts by name, recent, trust and stars with installable games first`() {
        val a = port("a", "pc", game = "Alpha", stars = 5).copy(release = PortReleaseInfo("v1", "2026-01-01T00:00:00Z"))
        val b = port("b", "pc", game = "Bravo", stars = 50).copy(release = PortReleaseInfo("v1", "2026-06-01T00:00:00Z"))
        val c = port("c", "pc", game = "Charlie", stars = 10).copy(trust = PortTrust(verified = true, stars = 10))
        val d = port("d", "pc", game = "Aardvark", android = false, stars = 999)
        val groups = PortGrouping.group(listOf(a, b, c, d))
        fun ids(sort: StoreSort) = StoreRows.sorted(groups, sort).map { it.lead.id }
        assertEquals(listOf("a", "b", "c", "d"), ids(StoreSort.Name))
        assertEquals(listOf("b", "a", "c", "d"), ids(StoreSort.RecentlyUpdated))
        assertEquals(listOf("c", "b", "a", "d"), ids(StoreSort.Trust))
        assertEquals(listOf("b", "c", "a", "d"), ids(StoreSort.Stars))
        val grid = StoreRows.home(listOf(a, b, c, d), StoreFilter(groups = setOf(PlatformGroup.Pc), sort = StoreSort.Stars), emptyList(), emptySet(), emptyMap())
        assertEquals(listOf("b", "c", "a", "d"), grid.single().ports.map { it.id })
    }

    @Test fun `back clears the whole filter but keeps the sort, and L1 R1 cycle the families`() {
        val f = StoreFilter(query = "x", groups = setOf(PlatformGroup.Pc), genres = setOf("rpg"), notOnAndroid = true, sort = StoreSort.Stars)
        assertEquals(StoreFilter(sort = StoreSort.Stars), f.cleared())
        assertTrue(!f.cleared().active)
        val av = listOf(PlatformGroup.Nintendo, PlatformGroup.Xbox, PlatformGroup.Pc)
        assertEquals(setOf(PlatformGroup.Nintendo), StoreRows.cycleGroup(emptySet(), av, 1))
        assertEquals(setOf(PlatformGroup.Pc), StoreRows.cycleGroup(emptySet(), av, -1))
        assertEquals(emptySet<PlatformGroup>(), StoreRows.cycleGroup(setOf(PlatformGroup.Pc), av, 1))
        assertEquals(setOf(PlatformGroup.Xbox), StoreRows.cycleGroup(setOf(PlatformGroup.Nintendo), av, 1))
        assertEquals(emptySet<PlatformGroup>(), StoreRows.cycleGroup(emptySet(), emptyList(), 1))
    }

    @Test fun `the home rows follow the store order and skip empty rows`() {
        val owned = listOf(RomPortMatch(soh, Fixtures.rom("1", Fixtures.folder("n64", "n64"), fileName = "Zelda OoT.z64"), MatchStrength.Hash))
        val rows = StoreRows.home(all, StoreFilter(), owned, installedIds = setOf("nsmbw"), publishedAt = mapOf("unleashed" to 20L, "soh" to 10L))
        assertEquals(
            listOf(
                StoreRowKind.Featured, StoreRowKind.BecauseYouHave, StoreRowKind.Nintendo, StoreRowKind.Xbox,
                StoreRowKind.RecentlyUpdated, StoreRowKind.Installed, StoreRowKind.ComingToAndroid,
            ),
            rows.map { it.kind },
        )
        assertEquals(listOf("soh", "unleashed"), rows[0].ports.map { it.id })
        assertEquals("Zelda OoT", rows[1].subject)
        assertEquals(listOf("soh", "nsmbw"), rows[2].ports.map { it.id })
        assertEquals(listOf("unleashed", "soh"), rows[4].ports.map { it.id })
        assertEquals(listOf("nsmbw"), rows[5].ports.map { it.id })
    }

    @Test fun `two ports of one game are one capsule in every row and in search`() {
        val catalog = all + shipwright
        val rows = StoreRows.home(catalog, StoreFilter(), emptyList(), installedIds = emptySet(), publishedAt = emptyMap())
        val nintendo = rows.single { it.kind == StoreRowKind.Nintendo }
        assertEquals(2, nintendo.groups.size)
        // El de más estrellas representa al juego si no hay ninguno instalado.
        assertEquals("shipwright", nintendo.groups.first().lead.id)
        assertEquals(listOf("shipwright", "soh"), nintendo.groups.first().ports.map { it.id })
        // Uno instalado pasa a ser la cápsula.
        val installed = StoreRows.home(catalog, StoreFilter(), emptyList(), installedIds = setOf("soh"), publishedAt = emptyMap())
        assertEquals("soh", installed.single { it.kind == StoreRowKind.Installed }.groups.single().lead.id)
        val results = StoreRows.home(catalog, StoreFilter(query = "ocarina"), emptyList(), emptySet(), emptyMap()).single()
        assertEquals(StoreRowKind.Results, results.kind)
        assertEquals(1, results.groups.size)
        assertEquals(2, results.groups.single().ports.size)
    }

    @Test fun `every platform maps to its row and the rows come in store order`() {
        assertEquals(StoreRowKind.Nintendo, StoreRows.platformRow("switch"))
        assertEquals(StoreRowKind.PlayStation, StoreRows.platformRow("psp"))
        assertEquals(StoreRowKind.Sega, StoreRows.platformRow("sega32x"))
        assertEquals(StoreRowKind.Xbox, StoreRows.platformRow("xbox"))
        assertEquals(StoreRowKind.Pc, StoreRows.platformRow("pc"))
        assertEquals(StoreRowKind.Pc, StoreRows.platformRow("dos"))
        assertEquals(null, StoreRows.platformRow("3do"))
        val catalog = listOf(
            port("ps", "ps2"), port("sega", "saturn"), port("pc", "pc"), port("dos", "dos"), port("x", "xbox"), port("n", "gba"), port("odd", "3do"),
        )
        val rows = StoreRows.home(catalog, StoreFilter(), emptyList(), emptySet(), emptyMap())
        assertEquals(
            listOf(StoreRowKind.Featured, StoreRowKind.Nintendo, StoreRowKind.PlayStation, StoreRowKind.Sega, StoreRowKind.Xbox, StoreRowKind.Pc),
            rows.map { it.kind },
        )
        assertEquals(listOf("pc", "dos"), rows.single { it.kind == StoreRowKind.Pc }.ports.map { it.id })
    }

    @Test fun `the updates row lists installed games with a newer release and has no cap`() {
        val many = (1..15).map { port("u$it", "pc") }
        val ids = many.map { it.id }.toSet()
        val rows = StoreRows.home(many + soh, StoreFilter(), emptyList(), installedIds = ids, publishedAt = emptyMap(), updateIds = ids + "soh")
        assertEquals(15, rows.single { it.kind == StoreRowKind.UpdatesAvailable }.groups.size)
        // Una "actualización" de algo que no está instalado no cuenta.
        assertTrue(rows.single { it.kind == StoreRowKind.UpdatesAvailable }.ports.none { it.id == "soh" })
        val order = rows.map { it.kind }
        assertTrue(order.indexOf(StoreRowKind.UpdatesAvailable) in (order.indexOf(StoreRowKind.Pc) + 1) until order.indexOf(StoreRowKind.Installed))
    }

    @Test fun `recently updated falls back to the catalog release date`() {
        val dated = nsmbw.copy(release = PortReleaseInfo("v1", "2026-10-01T00:00:00Z"))
        val rows = StoreRows.home(listOf(soh, dated), StoreFilter(), emptyList(), emptySet(), emptyMap())
        assertEquals(listOf("nsmbw"), rows.single { it.kind == StoreRowKind.RecentlyUpdated }.ports.map { it.id })
    }

    @Test fun `an active filter gives a single results row`() {
        val rows = StoreRows.home(all, StoreFilter(query = "soh"), emptyList(), emptySet(), emptyMap())
        assertEquals(listOf(StoreRowKind.Results), rows.map { it.kind })
    }

    @Test fun `masha finds one result per game by query or by what the library has`() {
        val found = StoreRows.find(all + shipwright, "ocarina", emptyList())
        assertEquals(1, found.size)
        assertEquals(setOf("soh", "shipwright"), found.single().ids)
        // Los instalables primero, aunque con la búsqueda también salgan los que aún no tienen APK.
        val n64 = StoreRows.find(all, "n64", emptyList())
        assertTrue(n64.indexOfFirst { g -> g.ports.none { it.installable } } > n64.indexOfLast { g -> g.ports.any { it.installable } })
        val owned = listOf(RomPortMatch(soh, Fixtures.rom("1", Fixtures.folder("n64", "n64")), MatchStrength.Hash))
        assertEquals(listOf("soh"), StoreRows.find(all, null, owned).map { it.lead.id })
        assertTrue(StoreRows.find(all, "  ", emptyList()).isEmpty())
    }

    @Test fun `masha finds pc games by their original name`() {
        val diablo = port("devilutionx", "pc", game = "Diablo").copy(name = "DevilutionX")
        assertEquals(listOf("devilutionx"), StoreRows.find(all + diablo, "diablo", emptyList()).map { it.lead.id })
    }
}
