package com.elyndra.launcher.launch

import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.PcGames
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

    /* ── Juegos de PC ─────────────────────────────────────────── */

    /** Carpeta de juego con su .exe: lo que hay hoy en una biblioteca de PC. */
    private val pcFolder = rom.copy(
        path = "/storage/emulated/0/Juegos PC/Hades/Hades.exe",
        pcLauncher = null,
    )

    /** Acceso directo exportado por Winlator. */
    private val pcShortcut = rom.copy(
        path = "/storage/emulated/0/Winlator/Hades.desktop",
        pcLauncher = PcGames.Launcher.Desktop,
    )

    private fun pcId(launcher: PcGames.Launcher, id: String) =
        rom.copy(pcLauncher = launcher, launcherId = id)

    @Test
    fun winlatorGetsTheShortcutPath() {
        val p = Emulators.byId("winlator_cmod")!!
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), pcShortcut))
        assertEquals("com.winlator.cmod", spec.packageName)
        assertEquals("com.winlator.cmod.XServerDisplayActivity", spec.className)
        assertEquals(pcShortcut.path, spec.stringExtras["shortcut_path"])
        assertTrue(spec.clearTask && spec.clearTop)
        assertTrue(spec.grantUris.isEmpty())
    }

    @Test
    fun winlatorWontTakeAnExecutable() {
        // Pasarle el .exe abriría la ventana del runtime y se cerraría sola:
        // mejor decirlo que fingir que se ha lanzado algo.
        val p = Emulators.byId("winlator_cmod")!!
        assertEquals(PlanResult.NeedsPcLauncher, LaunchPlanner.plan(p, p.components.first(), pcFolder))
    }

    @Test
    fun bannerHubGetsTheGameIdAndItsOwnAction() {
        val p = Emulators.byId("bannerhub")!!
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), pcId(PcGames.Launcher.Steam, "1145360")))
        assertEquals("banner.hub", spec.packageName)
        assertEquals("com.xiaoji.egggame.DeepLinkActivity", spec.className)
        // La acción lleva delante el paquete de la build instalada.
        assertEquals("banner.hub.LAUNCH_GAME", spec.action)
        // El id va en las dos claves: cada build lee la suya.
        assertEquals("1145360", spec.stringExtras["steamAppId"])
        assertEquals("1145360", spec.stringExtras["localGameId"])
        assertEquals(true, spec.boolExtras["autoStartGame"])
    }

    @Test
    fun bannerHubKeepsBothActivitiesOfEveryPackage() {
        val p = Emulators.byId("bannerhub")!!
        val banner = p.components.filter { it.startsWith("banner.hub/") }
        assertEquals(
            listOf(
                "banner.hub/com.xiaoji.egggame.DeepLinkActivity",
                "banner.hub/com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity",
            ),
            banner,
        )
        assertTrue(p.packages.containsAll(listOf("banner.hub", "gamehub.lite", "com.xiaoji.egggame")))
    }

    /** Id puesto a mano sobre la carpeta del juego: no hay archivo lanzador y da igual. */
    @Test
    fun bannerHubTakesAnAssignedIdWithoutAnExportedFile() {
        val p = Emulators.byId("bannerhub")!!
        val assigned = pcFolder.copy(launcherId = "268910", idIsAssigned = true)
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), assigned))
        assertEquals("268910", spec.stringExtras["steamAppId"])
        assertEquals("268910", spec.stringExtras["localGameId"])
    }

    @Test
    fun gameNativeTreatsAnAssignedIdAsSteam() {
        val p = Emulators.byId("gamenative")!!
        val assigned = pcFolder.copy(launcherId = "2551", idIsAssigned = true)
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), assigned))
        assertEquals("STEAM", spec.stringExtras["game_source"])
        assertEquals(2551, spec.intExtras["app_id"])
    }

    @Test
    fun bannerHubOnADecoyPackageKeepsItsOwnAction() {
        val p = Emulators.byId("bannerhub")!!
        val component = p.components.first { it.startsWith("com.tencent.ig/") }
        val spec = ok(LaunchPlanner.plan(p, component, pcId(PcGames.Launcher.Steam, "70")))
        assertEquals("com.tencent.ig.LAUNCH_GAME", spec.action)
    }

    @Test
    fun gamesOutsideSteamTravelWithTheSameIdInBothKeys() {
        val p = Emulators.byId("bannerhub")!!
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), pcId(PcGames.Launcher.Gog, "42")))
        assertEquals("42", spec.stringExtras["localGameId"])
        assertEquals("42", spec.stringExtras["steamAppId"])
    }

    @Test
    fun bannerHubWithoutAnIdAsksForTheExportedFile() {
        val p = Emulators.byId("bannerhub")!!
        assertEquals(PlanResult.NeedsPcLauncher, LaunchPlanner.plan(p, p.components.first(), pcFolder))
        // El .desktop de Winlator no lleva id dentro: tampoco le sirve.
        assertEquals(PlanResult.NeedsPcLauncher, LaunchPlanner.plan(p, p.components.first(), pcShortcut))
    }

    @Test
    fun gameNativeGetsTheStoreAndAnIntegerAppId() {
        val p = Emulators.byId("gamenative")!!
        val spec = ok(LaunchPlanner.plan(p, p.components.first(), pcId(PcGames.Launcher.Epic, "1017")))
        assertEquals("app.gamenative.LAUNCH_GAME", spec.action)
        assertEquals("EPIC", spec.stringExtras["game_source"])
        assertEquals(1017, spec.intExtras["app_id"])
    }

    @Test
    fun onlyMoboxIsOpenedWithoutTheGame() {
        val pc = Systems.byId("pc")!!
        val launchOnly = pc.emulators.filter { Emulators.byId(it)!!.launchOnly }
        assertEquals(listOf("mobox"), launchOnly)
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
