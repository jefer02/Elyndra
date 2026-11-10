package com.elyndra.launcher.domain.library

import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.UserFolder
import com.elyndra.launcher.domain.library.UserFolderPickLogic.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFolderPickLogicTest {

    private fun rom(id: String, title: String, system: String = "psx", meta: GameMeta = GameMeta()) =
        RomEntry(id, "f1", system, "d$id", "$id.cue", "$id.cue", title = title, meta = meta)

    private val library = Library(
        roms = listOf(rom("1", "Zelda"), rom("2", "Pokémon Azul", "gba"), rom("3", "Crash")),
        apps = listOf(AppEntry("com.port", "Ship of Harkinian"), AppEntry("com.app", "Alto"), AppEntry("com.gone", "Gone")),
        userFolders = listOf(UserFolder("u-1", "Mine", items = listOf("r:3"))),
    )

    private val installed = setOf("com.port", "com.app")

    private fun all() = UserFolderPickLogic.candidates(library, installed, setOf("com.port")) { it.uppercase() }

    private fun titles(list: List<UserFolderPickLogic.Candidate>) = list.map { it.title }

    @Test
    fun offersWhatIsNotInTheFolderSortedByTitle() {
        assertEquals(listOf("Alto", "Pokémon Azul", "Ship of Harkinian", "Zelda"), titles(all()))
    }

    @Test
    fun leavesOutWhatIsInAnyFolder() {
        val two = library.copy(userFolders = library.userFolders + UserFolder("u-2", "Other", items = listOf("r:1", "a:com.app")))
        val list = UserFolderPickLogic.candidates(two, installed, setOf("com.port")) { it.uppercase() }
        assertEquals(listOf("Pokémon Azul", "Ship of Harkinian"), titles(list))
    }

    @Test
    fun marksKindsAndPlatforms() {
        val byTitle = all().associateBy { it.title }
        assertEquals(Kind.Port, byTitle.getValue("Ship of Harkinian").kind)
        assertEquals(Kind.App, byTitle.getValue("Alto").kind)
        assertEquals("GBA", byTitle.getValue("Pokémon Azul").platform)
    }

    @Test
    fun unknownInstalledSetKeepsEveryApp() {
        val list = UserFolderPickLogic.candidates(library, emptySet(), emptySet()) { null }
        assertTrue("Gone" in titles(list))
    }

    @Test
    fun searchIgnoresCaseAccentsAndWordOrder() {
        val everything = all()
        fun find(q: String) = titles(UserFolderPickLogic.search(everything, q, "", emptyList()))
        assertEquals(listOf("Pokémon Azul"), find("POKEMON"))
        assertEquals(listOf("Pokémon Azul"), find("azul  poke"))
        assertEquals(listOf("Pokémon Azul"), find("gba"))
        assertEquals(listOf("Ship of Harkinian"), find("port"))
        assertTrue(find("nothing like this").isEmpty())
    }

    @Test
    fun aBlankQueryReturnsTheIndexItself() {
        val everything = all()
        assertSame(everything, UserFolderPickLogic.search(everything, "   ", "abc", emptyList()))
    }

    @Test
    fun extendingTheQueryNarrowsThePreviousResults() {
        val everything = all()
        val previous = everything.filter { it.title == "Zelda" }
        // "Alto" está en el índice, pero no entre los resultados de antes.
        assertTrue(UserFolderPickLogic.search(everything, "alto", "al", previous).isEmpty())
        assertEquals(listOf("Zelda"), titles(UserFolderPickLogic.search(everything, "zel", "z", previous)))
    }

    @Test
    fun aDifferentQuerySearchesEverythingAgain() {
        val everything = all()
        val previous = everything.filter { it.title == "Zelda" }
        assertEquals(listOf("Alto"), titles(UserFolderPickLogic.search(everything, "alto", "zel", previous)))
    }

    @Test
    fun indexesAndFindsAcrossTenThousandGames() {
        val many = (0 until 10_000).map { rom("$it", "Game número $it") }
        val big = Library(roms = many, userFolders = listOf(UserFolder("u-1", "Mine")))
        val index = UserFolderPickLogic.candidates(big, emptySet(), emptySet()) { "PSX" }
        assertEquals(10_000, index.size)
        assertEquals(1, UserFolderPickLogic.search(index, "numero 9999", "", emptyList()).size)
    }
}
