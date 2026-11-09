package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.domain.ports.PortArt.Source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortArtTest {

    private val switchGame = PortEntry(
        id = "la",
        name = "Link's Awakening Port",
        repo = GhRepo("o", "la"),
        game = PortGame("The Legend of Zelda: Link's Awakening", "switch"),
    )
    private val diablo = PortEntry(
        id = "devilutionx",
        name = "DevilutionX",
        repo = GhRepo("diasurgical", "devilutionX"),
        game = PortGame("Diablo", "pc", steamAppId = 2_456_740),
    )

    @Test fun `the chain is catalog then keyed services then steam then libretro then github`() {
        assertEquals(
            listOf(Source.Catalog, Source.SteamGridDb, Source.Igdb, Source.Steam, Source.Libretro, Source.GitHub),
            PortArt.chain(diablo, hasSgdbKey = true, hasIgdbKeys = true, hasLibretroFolder = true),
        )
        // Sin claves ni carpeta de libretro (Switch): el catálogo y GitHub.
        assertEquals(listOf(Source.Catalog, Source.GitHub), PortArt.chain(switchGame, false, false, false))
        // Steam solo para juegos de PC con appid.
        assertEquals(false, Source.Steam in PortArt.chain(switchGame.copy(game = switchGame.game.copy(steamAppId = 1)), false, false, true))
        assertEquals(false, Source.Steam in PortArt.chain(diablo.copy(game = diablo.game.copy(steamAppId = null)), false, false, false))
    }

    @Test fun `each step fills only what is missing and a complete set stops the chain`() {
        val asked = mutableListOf<Source>()
        val art = runBlocking {
            PortArt.resolve(PortArt.chain(diablo, true, true, true)) { s ->
                asked += s
                when (s) {
                    Source.Catalog -> OriginalArt(cover = "cat-cover")
                    Source.SteamGridDb -> null // falló: no corta la cadena
                    Source.Igdb -> OriginalArt(cover = "igdb-cover", logo = "igdb-logo")
                    Source.Steam -> OriginalArt(cover = "steam-cover", hero = "steam-hero", logo = "steam-logo")
                    else -> error("not reached")
                }
            }
        }
        assertEquals(OriginalArt("cat-cover", "steam-hero", "igdb-logo"), art)
        assertEquals(listOf(Source.Catalog, Source.SteamGridDb, Source.Igdb, Source.Steam), asked)
    }

    @Test fun `the github card is only a last resort cover`() {
        val none = runBlocking {
            PortArt.resolve(PortArt.chain(switchGame, false, false, false)) { s -> if (s == Source.GitHub) PortArt.gitHub(switchGame) else OriginalArt() }
        }
        assertEquals("https://opengraph.githubassets.com/1/o/la", none.cover)
        assertNull(none.hero)
        // Con una carátula del juego, la tarjeta no se pide.
        val asked = mutableListOf<Source>()
        runBlocking {
            PortArt.resolve(PortArt.chain(switchGame, false, false, false)) { s -> asked += s; if (s == Source.Catalog) OriginalArt(cover = "c") else PortArt.gitHub(switchGame) }
        }
        assertEquals(listOf(Source.Catalog), asked)
    }

    @Test fun `catalog art uses the first screenshot when there is no hero`() {
        val p = switchGame.copy(game = switchGame.game.copy(art = GameArt(cover = "https://c", screenshots = listOf("https://s1", "https://s2"))))
        assertEquals(OriginalArt("https://c", "https://s1", null, null), PortArt.fromCatalog(p))
    }

    @Test fun `steam art comes from the cdn by app id`() {
        val art = PortArt.steam(2_456_740)
        assertEquals("https://cdn.akamai.steamstatic.com/steam/apps/2456740/library_600x900_2x.jpg", art.cover)
        assertEquals("https://cdn.akamai.steamstatic.com/steam/apps/2456740/library_hero.jpg", art.hero)
    }
}
