package com.elyndra.launcher.domain.library

import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.UserFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFolderLogicTest {

    private fun rom(id: String) = RomEntry(id, "f1", "psx", "d$id", "$id.cue", "$id.cue", title = id)

    private val base = Library(
        roms = listOf(rom("1"), rom("2"), rom("3")),
        apps = listOf(AppEntry("com.a", "A")),
    )

    private val withFolder = UserFolderLogic.create(base, "u-1", "RPGs", now = 7)

    private fun folder(lib: Library) = lib.userFolders.single()

    @Test
    fun namesAreOneTrimmedLineAndNeverBlank() {
        assertEquals("Mis juegos", UserFolderLogic.cleanName("  Mis \n  juegos\t"))
        assertNull(UserFolderLogic.cleanName("   \n "))
        assertNull(UserFolderLogic.cleanName(null))
        assertEquals(UserFolderLogic.NAME_MAX, UserFolderLogic.cleanName("x".repeat(500))!!.length)
    }

    @Test
    fun trimmingDoesNotSplitAnEmoji() {
        val name = "x".repeat(UserFolderLogic.NAME_MAX - 1) + "😀"
        assertEquals("x".repeat(UserFolderLogic.NAME_MAX - 1), UserFolderLogic.cleanName(name))
    }

    @Test
    fun descriptionsKeepLineBreaksAndBlankClearsThem() {
        assertEquals("a\nb", UserFolderLogic.cleanDescription(" a\nb "))
        assertNull(UserFolderLogic.cleanDescription("  "))
    }

    @Test
    fun createAppendsWithTheNextPosition() {
        val two = UserFolderLogic.create(withFolder, "u-2", "Otra", now = 8)
        assertEquals(listOf(0, 1), two.userFolders.map { it.position })
        assertEquals(7, two.userFolders.first().createdAt)
    }

    @Test
    fun aBlankNameCreatesAndRenamesNothing() {
        assertSame(base, UserFolderLogic.create(base, "u-1", "   ", now = 1))
        assertSame(withFolder, UserFolderLogic.rename(withFolder, "u-1", " "))
    }

    @Test
    fun renameAndDescribeChangeOnlyThatFolder() {
        val renamed = UserFolderLogic.rename(withFolder, "u-1", "JRPG")
        assertEquals("JRPG", folder(renamed).name)
        assertSame(renamed, UserFolderLogic.rename(renamed, "u-1", "JRPG"))
        assertEquals("texto", folder(UserFolderLogic.describe(renamed, "u-1", "texto")).description)
    }

    @Test
    fun addMixesGamesInOrderWithoutDuplicatesOrUnknownKeys() {
        val a = UserFolderLogic.add(withFolder, "u-1", listOf("r:2", "a:com.a", "r:2", "r:missing"))
        assertEquals(listOf("r:2", "a:com.a"), folder(a).items)
        val b = UserFolderLogic.add(a, "u-1", listOf("r:1", "r:2"))
        assertEquals(listOf("r:2", "a:com.a", "r:1"), folder(b).items)
        assertSame(b, UserFolderLogic.add(b, "u-1", listOf("r:1")))
        assertSame(b, UserFolderLogic.add(b, "nope", listOf("r:3")))
    }

    @Test
    fun aGameCanBeInSeveralFolders() {
        var lib = UserFolderLogic.create(withFolder, "u-2", "Otra", now = 8)
        lib = UserFolderLogic.add(lib, "u-1", listOf("r:1"))
        lib = UserFolderLogic.add(lib, "u-2", listOf("r:1"))
        assertEquals(listOf("u-1", "u-2"), UserFolderLogic.containing(lib, "r:1").map { it.id })
    }

    @Test
    fun removeOnlyTouchesTheFolder() {
        val lib = UserFolderLogic.add(withFolder, "u-1", listOf("r:1", "r:2"))
        val out = UserFolderLogic.remove(lib, "u-1", listOf("r:1"))
        assertEquals(listOf("r:2"), folder(out).items)
        assertEquals(lib.roms, out.roms)
        assertSame(out, UserFolderLogic.remove(out, "u-1", listOf("r:1")))
    }

    @Test
    fun deleteLeavesTheGamesAlone() {
        val lib = UserFolderLogic.add(withFolder, "u-1", listOf("r:1", "a:com.a"))
        val out = UserFolderLogic.delete(lib, "u-1")
        assertTrue(out.userFolders.isEmpty())
        assertSame(lib.roms, out.roms)
        assertSame(lib.apps, out.apps)
    }

    @Test
    fun moveShiftsAndStopsAtTheEnds() {
        val items = listOf("a", "b", "c")
        val all = items.toSet()
        assertEquals(listOf("b", "a", "c"), UserFolderLogic.moved(items, all, "a", 1))
        assertEquals(listOf("c", "a", "b"), UserFolderLogic.moved(items, all, "c", -5))
        assertSame(items, UserFolderLogic.moved(items, all, "a", -1))
        assertSame(items, UserFolderLogic.moved(items, all, "z", 1))
    }

    @Test
    fun hiddenGamesDoNotCostAStep() {
        val items = listOf("a", "hidden", "b", "c")
        val visible = setOf("a", "b", "c")
        assertEquals(listOf("hidden", "b", "a", "c"), UserFolderLogic.moved(items, visible, "a", 1))
        assertEquals(listOf("b", "a", "hidden", "c"), UserFolderLogic.moved(items, visible, "b", -1))
        assertSame(items, UserFolderLogic.moved(items, setOf("a"), "a", 1))
    }

    @Test
    fun distanceCountsOnlyWhatIsVisible() {
        val items = listOf("a", "hidden", "b", "c")
        val visible = setOf("a", "b", "c")
        assertEquals(2, UserFolderLogic.distance(items, visible, "a", "c"))
        assertEquals(-1, UserFolderLogic.distance(items, visible, "b", "a"))
        assertNull(UserFolderLogic.distance(items, visible, "a", "hidden"))
    }

    @Test
    fun moveInTheLibraryKeepsTheInstanceAtTheEnds() {
        val lib = UserFolderLogic.add(withFolder, "u-1", listOf("r:1", "r:2"))
        val visible = setOf("r:1", "r:2")
        assertEquals(listOf("r:2", "r:1"), folder(UserFolderLogic.move(lib, "u-1", "r:1", 1, visible)).items)
        assertSame(lib, UserFolderLogic.move(lib, "u-1", "r:2", 1, visible))
    }

    @Test
    fun pruneDropsGamesThatLeftTheLibrary() {
        val lib = UserFolderLogic.add(withFolder, "u-1", listOf("r:1", "r:2", "a:com.a"))
        val after = lib.copy(roms = lib.roms.filterNot { it.id == "1" }, apps = emptyList())
        assertEquals(listOf("r:2"), folder(UserFolderLogic.prune(lib, after)).items)
    }

    @Test
    fun pruneNoticesASwapThatKeepsTheSize() {
        val lib = UserFolderLogic.add(withFolder, "u-1", listOf("r:1"))
        val after = lib.copy(roms = lib.roms.map { if (it.id == "1") rom("9") else it })
        assertTrue(folder(UserFolderLogic.prune(lib, after)).items.isEmpty())
    }

    @Test
    fun pruneKeepsTheSameInstanceWhenTheSameGamesRemain() {
        val lib = UserFolderLogic.add(withFolder, "u-1", listOf("r:1"))
        val after = lib.copy(roms = lib.roms.map { it.copy(title = it.title + "!") })
        assertSame(after, UserFolderLogic.prune(lib, after))
    }

    @Test
    fun collageTakesTheFirstCoversInFolderOrder() {
        val folder = UserFolder("u-1", "x", items = listOf("e", "a", "b", "c", "d", "f"))
        val covers = mapOf("a" to "1", "b" to "2", "c" to "1", "d" to "", "f" to "3")
        assertEquals(listOf("1", "2", "3"), UserFolderLogic.collageCovers(folder) { covers[it] })
        val many = UserFolder("u-1", "x", items = (0..9).map { "$it" })
        assertEquals(FolderArtResolver.COLLAGE_MAX, UserFolderLogic.collageCovers(many) { "c$it" }.size)
    }

    @Test
    fun androidOnlyNeedsItemsAndAllOfThemApps() {
        assertTrue(UserFolderLogic.isAndroidOnly(UserFolder("u-1", "x", items = listOf("a:one", "a:two"))))
        assertFalse(UserFolderLogic.isAndroidOnly(UserFolder("u-1", "x", items = listOf("a:one", "r:1"))))
        assertFalse(UserFolderLogic.isAndroidOnly(UserFolder("u-1", "x")))
    }

    @Test
    fun abbreviationKeepsWholeCharacters() {
        assertEquals("RPG", UserFolderLogic.abbr("rpgs viejos"))
        assertEquals("AB", UserFolderLogic.abbr("ab"))
        assertEquals("😀AB", UserFolderLogic.abbr("😀abcd"))
    }

    @Test
    fun userKeysDoNotMatchRomFolders() {
        assertTrue(UserFolderLogic.isUserKey("f:u-abc"))
        assertFalse(UserFolderLogic.isUserKey("f:v-psx"))
        assertFalse(UserFolderLogic.isUserKey(null))
    }
}
