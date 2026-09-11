package com.elyndra.launcher.launch

import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.Systems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchPlannerTest {

    private val rom = RomRef(
        safUri = "content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fpsp%2Fgame.iso",
        providerUri = "content://com.elyndra.launcher.roms/rom/abc/game.iso",
        path = "/storage/emulated/0/ROMs/psp/game.iso",
        isDirectory = false,
    )

    private fun ok(result: PlanResult): LaunchSpec = (result as PlanResult.Ok).spec

    @Test
    fun componentsExpand() {
        assertEquals("org.ppsspp.ppsspp" to "org.ppsspp.ppsspp.PpssppActivity", LaunchPlanner.expandComponent("org.ppsspp.ppsspp/.PpssppActivity"))
        assertEquals("a.b" to "c.d.E", LaunchPlanner.expandComponent("a.b/c.d.E"))
        assertEquals("a.b" to null, LaunchPlanner.expandComponent("a.b"))
    }

    @Test
    fun ppssppGetsSafUriAsDataWithGrant() {
        val p = Emulators.byId("ppsspp")!!
        val spec = ok(LaunchPlanner.plan(p, p.components.last(), rom))
        assertEquals("android.intent.action.VIEW", spec.action)
        assertEquals("android.intent.category.DEFAULT", spec.category)
        assertEquals(rom.safUri, spec.data)
        assertEquals(listOf(rom.safUri), spec.grantUris)
    }

    @Test
    fun retroArchGetsPathCoreAndConfig() {
        val p = Emulators.byId("ra_mgba")!!
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), rom))
        assertEquals("com.retroarch.aarch64", spec.packageName)
        assertEquals(rom.path, spec.stringExtras["ROM"])
        assertEquals("/data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so", spec.stringExtras["LIBRETRO"])
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg", spec.stringExtras["CONFIGFILE"])
        assertTrue(spec.grantUris.isEmpty())
    }

    @Test
    fun pathOnlyEmulatorNeedsLocalStorage() {
        val p = Emulators.byId("ra_mgba")!!
        assertEquals(PlanResult.NeedsPath, LaunchPlanner.plan(p, p.components.first(), rom.copy(path = null)))
    }

    @Test
    fun extrasUrisAreGrantedToo() {
        val p = Emulators.byId("nethersx2")!!
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), rom))
        assertEquals(rom.safUri, spec.stringExtras["bootPath"])
        assertEquals(listOf(rom.safUri), spec.grantUris)
        assertTrue(spec.clearTask && spec.clearTop)
        assertNull(spec.data)
    }

    @Test
    fun yuzuFamilyUsesProviderUri() {
        val p = Emulators.byId("eden")!!
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), rom))
        assertEquals("android.nfc.action.TECH_DISCOVERED", spec.action)
        assertEquals(rom.providerUri, spec.data)
        assertEquals("org.yuzu.yuzu_emu.activities.EmulationActivity", spec.className)
    }

    @Test
    fun ps3FolderGamesUseDirectoryExtras() {
        val p = Emulators.byId("aps3e")!!
        val dir = rom.copy(isDirectory = true, path = "/storage/emulated/0/ROMs/ps3/Game")
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), dir))
        assertEquals(dir.path, spec.stringExtras["game_dir"])
        assertNull(spec.stringExtras["iso_uri"])
        assertEquals(rom.safUri, ok(LaunchPlanner.plan(p, p.components.first(), rom)).stringExtras["iso_uri"])
    }

    @Test
    fun vitaNeedsTitleId() {
        val p = Emulators.byId("vita3k")!!
        assertEquals(PlanResult.NeedsVitaTitle, LaunchPlanner.plan(p, p.components.first(), rom))
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), rom.copy(vitaTitleId = "PCSE00000")))
        assertEquals(listOf("-r", "PCSE00000"), spec.arrayExtras["AppStartParameters"])
    }

    @Test
    fun customAppGetsViewIntent() {
        val spec = LaunchPlanner.custom("com.example.emu", rom)
        assertEquals("android.intent.action.VIEW", spec.action)
        assertNull(spec.className)
        assertEquals(listOf(rom.safUri), spec.grantUris)
    }

    @Test
    fun everySystemEmulatorExistsAndIsUnique() {
        val ids = Emulators.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Systems.ALL.forEach { system ->
            assertTrue("${system.id} has no emulators", system.emulators.isNotEmpty())
            system.emulators.forEach { assertNotNull("${system.id} → $it", Emulators.byId(it)) }
        }
        assertEquals(Systems.ALL.size, Systems.ALL.map { it.id }.toSet().size)
    }

    @Test
    fun folderNamesDetectSystems() {
        assertEquals("psp", Systems.detect("PSP")?.id)
        assertEquals("gba", Systems.detect("Game Boy Advance")?.id)
        assertEquals("megadrive", Systems.detect("genesis")?.id)
        assertEquals("ps2", Systems.detect("PlayStation 2")?.id)
        assertNull(Systems.detect("Música"))
    }
}
