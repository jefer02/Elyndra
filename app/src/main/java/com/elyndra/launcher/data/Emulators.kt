package com.elyndra.launcher.data

/* ─────────────────────────────────────────────────────────────
   Perfiles de lanzamiento de emuladores.

   Cada perfil describe el intent exacto que acepta el emulador:
   componente (paquete/actividad), acción, categoría, qué va en
   `data` y qué extras lleva. Los valores salen de la configuración
   Android de ES-DE (es_find_rules.xml / es_systems.xml):

     %ROMSAF%      → RomArg.SAF       URI content:// del documento (permiso SAF)
     %ROMPROVIDER% → RomArg.PROVIDER  URI de nuestro RomProvider
     %ROM%         → RomArg.PATH      ruta absoluta (/storage/...)
   ───────────────────────────────────────────────────────────── */

enum class RomArg { SAF, PROVIDER, PATH }

sealed interface ExtraValue {
    data class Rom(val arg: RomArg) : ExtraValue
    data class Text(val value: String) : ExtraValue
    data class Flag(val value: Boolean) : ExtraValue
    /** /data/data/<paquete de RetroArch>/cores/<archivo>. */
    data class RetroArchCore(val file: String) : ExtraValue
    /** /storage/emulated/0/Android/data/<paquete de RetroArch>/files/retroarch.cfg */
    data object RetroArchConfig : ExtraValue
    /** String[] {"-r", <TITLE ID leído del archivo .psvita>} para Vita3K. */
    data object VitaTitleArgs : ExtraValue

    /* ── Juegos de PC: lo que entienden los runtimes de Windows ──
       Ninguno acepta la ruta de un .exe. Lo que aceptan es el archivo
       lanzador que ellos mismos exportan (ver PcGames.Launcher). */

    /** Ruta del `.desktop` que exportó Winlator: dentro van contenedor y ejecutable. */
    data object WinShortcut : ExtraValue

    /**
     * Id del juego dentro del runtime.
     *
     * GameHub y sus forks leen el id de dos claves distintas según la build
     * (`steamAppId` para lo que viene de Steam, `localGameId` para el resto).
     * Desde fuera no se sabe cuál mira la build instalada, así que el mismo id
     * se manda en las dos: [alsoKey] es la segunda. La que no le corresponda,
     * el runtime la ignora.
     */
    data class LauncherId(val alsoKey: String? = null) : ExtraValue

    /** El mismo id, como entero: GameNative pide `app_id` así. */
    data object LauncherIdInt : ExtraValue

    /** Tienda de la que sale el juego (STEAM, GOG, EPIC…), por la extensión del lanzador. */
    data object LauncherStore : ExtraValue
}

data class ExtraSpec(val key: String, val value: ExtraValue)

data class EmulatorProfile(
    val id: String,
    val name: String,
    /** "paquete/actividad"; una actividad que empieza por "." es relativa al paquete. */
    val components: List<String>,
    val action: String? = null,
    val category: String? = null,
    val data: RomArg? = null,
    val extras: List<ExtraSpec> = emptyList(),
    /** Extras alternativos cuando el juego es una carpeta (PS3 en formato JB). */
    val dirExtras: List<ExtraSpec>? = null,
    val clearTask: Boolean = false,
    val clearTop: Boolean = false,
    /** Paquete de Google Play, si el emulador está publicado ahí. */
    val storeId: String? = null,
    /**
     * El runtime no admite que le pasen el juego: solo se puede abrir.
     *
     * Queda para los runtimes de Windows que no exponen ningún intent de
     * lanzamiento (Mobox). Elyndra abre la app y se aparta, en vez de mandar
     * un intent con una ruta que el runtime va a ignorar.
     */
    val launchOnly: Boolean = false,
) {
    val packages: List<String> get() = components.map { it.substringBefore('/') }.distinct()
    val isRetroArch: Boolean get() = id.startsWith("ra_")
}

object Emulators {

    const val VIEW = "android.intent.action.VIEW"
    const val MAIN = "android.intent.action.MAIN"

    /**
     * Marca en una acción que hay que sustituirla por el paquete que se lanza.
     *
     * GameHub y sus forks declaran la acción con su propio paquete delante
     * ("gamehub.lite.LAUNCH_GAME", "banner.hub.LAUNCH_GAME"…), y cada build
     * se instala con un paquete distinto, así que la acción se arma al vuelo.
     */
    const val PACKAGE_TOKEN = "%PKG%"
    private const val TECH_DISCOVERED = "android.nfc.action.TECH_DISCOVERED"
    private const val DEFAULT = "android.intent.category.DEFAULT"
    private const val LEANBACK = "android.intent.category.LEANBACK_LAUNCHER"

    /** Prefijo de los emuladores elegidos a mano entre las apps instaladas. */
    const val CUSTOM_PREFIX = "custom:"

    val RETROARCH_COMPONENTS = listOf(
        "com.retroarch.aarch64/com.retroarch.browser.retroactivity.RetroActivityFuture",
        "com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture",
        "com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture",
    )

    private fun rom(key: String, arg: RomArg) = ExtraSpec(key, ExtraValue.Rom(arg))

    private fun ra(core: String, label: String) = EmulatorProfile(
        id = "ra_$core",
        name = "RetroArch · $label",
        components = RETROARCH_COMPONENTS,
        extras = listOf(
            rom("ROM", RomArg.PATH),
            ExtraSpec("LIBRETRO", ExtraValue.RetroArchCore("${core}_libretro_android.so")),
            ExtraSpec("CONFIGFILE", ExtraValue.RetroArchConfig),
        ),
        storeId = "com.retroarch",
    )

    /**
     * Winlator y sus forks: `XServerDisplayActivity` con la ruta del `.desktop`.
     *
     * El acceso directo lo exporta el propio runtime ("Export for frontends") y
     * dentro lleva el contenedor y el ejecutable; sin él no hay nada que lanzar.
     *
     * Se listan varios paquetes por perfil porque cada fork se instala con el
     * suyo y varios publican builds "de reemplazo" con el paquete de otra app.
     * Esos paquetes de reemplazo se repiten entre forks, así que lo que
     * distingue a uno de otro es el nombre de la actividad — por eso va
     * completo en cada componente (ver GameLauncher.installedComponent).
     */
    private fun winlator(id: String, name: String, activity: String, vararg packages: String) = EmulatorProfile(
        id = id,
        name = name,
        components = packages.map { "$it/$activity" },
        action = MAIN,
        extras = listOf(ExtraSpec("shortcut_path", ExtraValue.WinShortcut)),
        clearTask = true,
        clearTop = true,
    )

    /**
     * GameHub Lite y sus forks (BannerHub): `<paquete>.LAUNCH_GAME` con el id
     * del juego dentro de la biblioteca del runtime.
     *
     * Sin ese id el intent abre la portada del runtime y ahí se queda, así que
     * el id es el lanzamiento entero. Sale del archivo que exporta el propio
     * runtime ("Frontend Export" → ES-DE), de un archivo suelto junto al juego
     * o de lo que el usuario haya escrito a mano (ver [BannerHub]).
     *
     * Va en las dos claves a la vez —`steamAppId` y `localGameId`— porque cada
     * build lee la suya y desde fuera no hay forma de saber cuál: la que no
     * corresponda, el runtime la ignora.
     *
     * Cada build publica una actividad distinta; se listan todas y gana la que
     * esté instalada de verdad (ver `GameLauncher.installedComponent`).
     */
    private fun gameHub(id: String, name: String, vararg packages: String) = EmulatorProfile(
        id = id,
        name = name,
        components = packages.flatMap { pkg -> BannerHub.ACTIVITIES.map { "$pkg/$it" } },
        action = PACKAGE_TOKEN + BannerHub.ACTION_SUFFIX,
        category = DEFAULT,
        extras = listOf(
            ExtraSpec(BannerHub.EXTRA_AUTOSTART, ExtraValue.Flag(true)),
            ExtraSpec(BannerHub.EXTRA_STEAM_ID, ExtraValue.LauncherId(alsoKey = BannerHub.EXTRA_LOCAL_ID)),
        ),
    )

    /** Familia yuzu (Eden, Citron, Sudachi…): acción TECH_DISCOVERED + URI en data. */
    private fun yuzuLike(id: String, name: String, vararg components: String) = EmulatorProfile(
        id = id, name = name, components = components.toList(),
        action = TECH_DISCOVERED, data = RomArg.PROVIDER,
    )

    /** Emuladores EX (NES.emu, MD.emu…): motor Imagine, la ROM va en data. */
    private fun imagine(id: String, name: String, pkg: String, arg: RomArg, store: String? = pkg) = EmulatorProfile(
        id = id, name = name, components = listOf("$pkg/com.imagine.BaseActivity"), data = arg, storeId = store,
    )

    private val EDEN = arrayOf(
        "dev.eden.eden_emulator/org.yuzu.yuzu_emu.activities.EmulationActivity",
        "dev.legacy.eden_emulator/org.yuzu.yuzu_emu.activities.EmulationActivity",
        "com.miHoYo.Yuanshen/org.yuzu.yuzu_emu.activities.EmulationActivity",
    )

    val ALL: List<EmulatorProfile> = listOf(
        // ── Nintendo Switch ──
        yuzuLike("eden", "Eden", *EDEN),
        yuzuLike("eden_nightly", "Eden Nightly", *EDEN.map { it.replace("/", ".nightly/") }.toTypedArray()),
        yuzuLike("citron", "Citron", "org.citron.citron_emu/org.citron.citron_emu.activities.EmulationActivity"),
        yuzuLike("sudachi", "Sudachi", "org.sudachi.sudachi_emu/org.sudachi.sudachi_emu.activities.EmulationActivity"),
        yuzuLike(
            "yuzu", "Yuzu",
            "org.yuzu.yuzu_emu/org.yuzu.yuzu_emu.activities.EmulationActivity",
            "org.yuzu.yuzu_emu.ea/org.yuzu.yuzu_emu.activities.EmulationActivity",
        ),
        EmulatorProfile(
            "kenjinx", "Kenji-NX (Ryujinx)", listOf("org.kenjinx.android/.MainActivity"),
            action = "org.kenjinx.android.LAUNCH_GAME", extras = listOf(rom("bootPath", RomArg.SAF)),
        ),
        EmulatorProfile(
            "skyline", "Skyline / Strato",
            listOf("skyline.emu/emu.skyline.EmulationActivity", "org.stratoemu.strato/emu.skyline.EmulationActivity"),
            action = VIEW, data = RomArg.PROVIDER,
        ),

        // ── PlayStation 2 ──
        EmulatorProfile(
            "nethersx2", "NetherSX2 / AetherSX2", listOf("xyz.aethersx2.android/.EmulationActivity"),
            action = MAIN, extras = listOf(rom("bootPath", RomArg.SAF)), clearTask = true, clearTop = true,
        ),
        EmulatorProfile(
            "nethersx2_turnip", "NetherSX2-Turnip",
            listOf(
                "xyz.aethersx2.tturnip/xyz.aethersx2.android.EmulationActivity",
                "xyz.aethersx2.cturnip/xyz.aethersx2.android.EmulationActivity",
            ),
            action = MAIN, extras = listOf(rom("bootPath", RomArg.SAF)), clearTask = true, clearTop = true,
        ),
        EmulatorProfile(
            "armsx2", "ARMSX2",
            listOf(
                "com.armsx2/.MainActivity",
                "come.nanodata.armsx2/kr.co.iefriends.pcsx2.MainActivity",
                "come.nanodata.armsx2.debug/kr.co.iefriends.pcsx2.MainActivity",
            ),
            action = VIEW, data = RomArg.SAF,
        ),
        EmulatorProfile(
            "emucorex", "EmuCoreX", listOf("com.sbro.emucorex/.MainActivity"),
            action = VIEW, data = RomArg.SAF, clearTask = true,
        ),
        EmulatorProfile(
            "play", "Play!", listOf("com.virtualapplications.play/.MainActivity"),
            action = VIEW, data = RomArg.SAF, storeId = "com.virtualapplications.play",
        ),
        ra("pcsx2", "LRPS2"),

        // ── PlayStation 3 ──
        EmulatorProfile(
            "aps3e", "aPS3e",
            listOf("aenu.aps3e.premium/aenu.aps3e.EmulatorActivity", "aenu.aps3e/aenu.aps3e.EmulatorActivity"),
            action = "aenu.intent.action.APS3E",
            extras = listOf(rom("iso_uri", RomArg.SAF)),
            dirExtras = listOf(rom("game_dir", RomArg.PATH)),
        ),
        EmulatorProfile("armsx3", "ARMSX3", listOf("com.armsx3/com.armsx2.Main"), extras = listOf(rom("path", RomArg.PATH))),
        EmulatorProfile(
            "emucorec", "EmuCoreC", listOf("com.sbro.emucorec/.core.ps3.Emulator"),
            extras = listOf(rom("gamePath", RomArg.PATH)),
        ),

        // ── Xbox / Xbox 360 ──
        EmulatorProfile(
            "ax360e", "aX360e",
            listOf("aenu.ax360e/aenu.ax360e.EmulatorActivity", "aenu.ax360e.free/aenu.ax360e.EmulatorActivity"),
            action = "aenu.intent.action.AX360E", extras = listOf(rom("game_uri", RomArg.SAF)),
        ),
        EmulatorProfile("xendroid", "XenDroid", listOf("xendroid.compose/.EmulatorHostActivity"), data = RomArg.SAF),
        EmulatorProfile(
            "xenra", "Xenra",
            listOf("Ali.Xanite.green/Ali.Xanite.LauncherActivity", "Ali.Xanite/Ali.Xanite.LauncherActivity"),
            data = RomArg.SAF,
        ),
        EmulatorProfile("x1box", "X1 BOX", listOf("com.izzy2lost.x1box/.LauncherActivity"), action = VIEW, data = RomArg.SAF),
        EmulatorProfile("hakux", "hakuX", listOf("com.rfandango.haku_x/.LauncherActivity"), action = VIEW, data = RomArg.SAF),

        // ── PSP / Vita ──
        EmulatorProfile(
            "ppsspp", "PPSSPP",
            listOf("org.ppsspp.ppssppgold/org.ppsspp.ppsspp.PpssppActivity", "org.ppsspp.ppsspp/.PpssppActivity"),
            action = VIEW, category = DEFAULT, data = RomArg.SAF, storeId = "org.ppsspp.ppsspp",
        ),
        ra("ppsspp", "PPSSPP"),
        EmulatorProfile(
            "vita3k", "Vita3K",
            listOf("org.vita3k.emulator/.Emulator", "org.vita3k.emulator.ikhoeyZX/org.vita3k.emulator.Emulator"),
            extras = listOf(ExtraSpec("AppStartParameters", ExtraValue.VitaTitleArgs)),
        ),
        EmulatorProfile(
            "emucorev", "EmuCoreV", listOf("com.sbro.emucorev/.core.vita.Emulator"),
            extras = listOf(ExtraSpec("AppStartParameters", ExtraValue.VitaTitleArgs)),
        ),

        // ── PlayStation ──
        EmulatorProfile(
            "duckstation", "DuckStation", listOf("com.github.stenzek.duckstation/.EmulationActivity"),
            extras = listOf(ExtraSpec("resumeState", ExtraValue.Flag(false)), rom("bootPath", RomArg.SAF)),
            clearTask = true, clearTop = true,
        ),
        ra("mednafen_psx_hw", "Beetle PSX HW"),
        ra("pcsx_rearmed", "PCSX ReARMed"),
        ra("swanstation", "SwanStation"),
        EmulatorProfile(
            "epsxe", "ePSXe", listOf("com.epsxe.ePSXe/.ePSXe"),
            action = MAIN, extras = listOf(rom("com.epsxe.ePSXe.isoName", RomArg.PATH)), storeId = "com.epsxe.ePSXe",
        ),
        EmulatorProfile(
            "fpse64", "FPseNG", listOf("com.emulator.fpse64/.Main"),
            action = VIEW, data = RomArg.PROVIDER, storeId = "com.emulator.fpse64",
        ),
        EmulatorProfile(
            "fpse", "FPse", listOf("com.emulator.fpse/.Main"),
            action = VIEW, data = RomArg.PROVIDER, storeId = "com.emulator.fpse",
        ),
        EmulatorProfile("armsx1", "ARMSX1", listOf("com.nanodata.armsx/com.armsx2.Main"), data = RomArg.SAF),

        // ── Nintendo 64 ──
        EmulatorProfile(
            "m64plus_fz", "M64Plus FZ",
            listOf(
                "org.mupen64plusae.v3.fzurita.pro/paulscode.android.mupen64plusae.SplashActivity",
                "org.mupen64plusae.v3.fzurita/paulscode.android.mupen64plusae.SplashActivity",
                "org.mupen64plusae.v3.fzurita.amazon/paulscode.android.mupen64plusae.SplashActivity",
            ),
            action = VIEW, data = RomArg.SAF, storeId = "org.mupen64plusae.v3.fzurita",
        ),
        EmulatorProfile(
            "mupen64plus_ae", "Mupen64Plus AE",
            listOf("org.mupen64plusae.v3.alpha/paulscode.android.mupen64plusae.SplashActivity"),
            action = VIEW, data = RomArg.SAF,
        ),
        ra("mupen64plus_next_gles3", "Mupen64Plus-Next"),
        ra("parallel_n64", "ParaLLEl N64"),

        // ── Nintendo DS ──
        EmulatorProfile(
            "melonds", "melonDS", listOf("me.magnum.melonds/.ui.emulator.EmulatorActivity"),
            action = "me.magnum.melonds.LAUNCH_ROM", extras = listOf(rom("uri", RomArg.SAF)), storeId = "me.magnum.melonds",
        ),
        EmulatorProfile(
            "melonds_nightly", "melonDS Nightly",
            listOf("me.magnum.melonds.nightly/me.magnum.melonds.ui.emulator.EmulatorActivity"),
            action = "me.magnum.melonds.nightly.LAUNCH_ROM", extras = listOf(rom("uri", RomArg.SAF)),
        ),
        EmulatorProfile(
            "drastic", "DraStic", listOf("com.dsemu.drastic/.DraSticActivity"),
            data = RomArg.SAF, clearTask = true, clearTop = true, storeId = "com.dsemu.drastic",
        ),
        EmulatorProfile(
            "noods", "NooDS", listOf("com.hydra.noods/.FileBrowser"),
            extras = listOf(rom("LaunchPath", RomArg.PATH)), clearTask = true, clearTop = true, storeId = "com.hydra.noods",
        ),
        ra("melondsds", "melonDS DS"),
        ra("desmume", "DeSmuME"),

        // ── Nintendo 3DS ──
        EmulatorProfile(
            "azahar", "Azahar",
            listOf(
                "org.azahar_emu.azahar/org.citra.citra_emu.activities.EmulationActivity",
                "io.github.lime3ds.android/org.citra.citra_emu.activities.EmulationActivity",
            ),
            data = RomArg.SAF, clearTask = true, clearTop = true, storeId = "org.azahar_emu.azahar",
        ),
        EmulatorProfile(
            "azaharplus", "AzaharPlus",
            listOf("io.github.azaharplus.android/org.citra.citra_emu.activities.EmulationActivity"),
            data = RomArg.SAF, clearTask = true, clearTop = true,
        ),
        EmulatorProfile(
            "citra", "Citra",
            listOf(
                "org.citra.citra_emu/.activities.EmulationActivity",
                "org.citra.citra_emu.canary/org.citra.citra_emu.activities.EmulationActivity",
            ),
            data = RomArg.SAF, clearTask = true, clearTop = true,
        ),
        EmulatorProfile(
            "lime3ds", "Lime3DS", listOf("io.github.lime3ds.android/.activities.EmulationActivity"),
            data = RomArg.SAF, clearTask = true, clearTop = true,
        ),
        EmulatorProfile(
            "mandarine", "Mandarine", listOf("io.github.mandarine3ds.mandarine/.activities.EmulationActivity"),
            data = RomArg.SAF, clearTask = true, clearTop = true,
        ),
        EmulatorProfile("panda3ds", "Panda3DS", listOf("com.panda3ds.pandroid/.app.MainActivity"), data = RomArg.PROVIDER),
        EmulatorProfile(
            "citra_mmj", "Citra MMJ", listOf("org.citra.emu/.ui.EmulationActivity"),
            extras = listOf(rom("GamePath", RomArg.PATH)),
        ),

        // ── PC (Windows sobre Android) ──
        //
        // Un juego de PC no se entrega como una ROM: no vive suelto en el
        // disco, vive dentro del runtime, en un contenedor con su Wine y sus
        // ajustes. Ninguno de estos acepta la ruta de un .exe. Lo que aceptan
        // es el archivo que ellos mismos exportan para los frontends, y de ahi
        // salen las dos familias de abajo (ver PcGames.Launcher).
        winlator("winlator_cmod", "Winlator Cmod", "com.winlator.cmod.XServerDisplayActivity",
            "com.winlator.cmod", "com.winlator.vanilla", "com.ludashi.benchmark", "com.miHoYo.GenshinImpact"),
        winlator("winlator", "Winlator", "com.winlator.XServerDisplayActivity", "com.winlator"),
        winlator("winlator_proot", "Winlator Cmod PRoot", "com.winlator.XServerDisplayActivity", "com.cmodded.winlator"),
        winlator("bannerlator", "Bannerlator", "com.winlator.star.XServerDisplayActivity",
            "com.winlator.banner", "com.ludashi.benchmark", "com.tencent.ig"),
        winlator("winnative", "WinNative", "com.winlator.cmod.runtime.display.XServerDisplayActivity",
            "com.winnative.cmod", "com.antutu.ABenchMark", "com.ludashi.benchmark", "com.tencent.ig"),
        gameHub(
            "bannerhub", "BannerHub",
            // Los propios primero y los de reemplazo después: si hay dos builds
            // instaladas manda la que se instaló con su nombre de verdad.
            *(BannerHub.PACKAGES + BannerHub.DECOY_PACKAGES +
                listOf("com.antutu.benchmark.full", "com.ludashi.aibench", "com.miHoYo.GenshinImpact")).toTypedArray(),
        ),
        gameHub("gamehub", "GameHub Lite", "gamehub.lite", "emuready.gamehub.lite"),
        EmulatorProfile(
            "gamenative", "GameNative", listOf("app.gamenative/.MainActivity"),
            action = "app.gamenative.LAUNCH_GAME",
            extras = listOf(
                ExtraSpec("game_source", ExtraValue.LauncherStore),
                ExtraSpec("app_id", ExtraValue.LauncherIdInt),
            ),
        ),
        // Mobox y MiceWine no publican ningun intent de lanzamiento: se abren
        // y el juego se elige dentro. De ahi `launchOnly`.
        EmulatorProfile("mobox", "Mobox", listOf("com.mobox.launcher", "com.micewine.emu"), launchOnly = true),

        // ── GameCube / Wii / Wii U ──
        EmulatorProfile(
            "dolphin", "Dolphin", listOf("org.dolphinemu.dolphinemu/.ui.main.TvMainActivity"),
            action = MAIN, category = LEANBACK, extras = listOf(rom("AutoStartFile", RomArg.SAF)),
            storeId = "org.dolphinemu.dolphinemu",
        ),
        EmulatorProfile(
            "dolphin_mmjr", "Dolphin MMJR", listOf("org.mm.jr/org.dolphinemu.dolphinemu.ui.main.MainActivity"),
            action = VIEW, extras = listOf(rom("AutoStartFile", RomArg.SAF)),
        ),
        EmulatorProfile(
            "dolphin_mmjr2", "Dolphin MMJR2", listOf("org.dolphinemu.mmjr/org.dolphinemu.dolphinemu.ui.main.MainActivity"),
            action = VIEW, extras = listOf(rom("AutoStartFile", RomArg.SAF)),
        ),
        ra("dolphin", "Dolphin"),
        EmulatorProfile(
            "cemu", "Cemu",
            listOf(
                "info.cemu.cemu/info.cemu.cemu.emulation.EmulationActivity",
                "info.cemu.Cemu/info.cemu.Cemu.emulation.EmulationActivity",
            ),
            data = RomArg.SAF,
        ),

        // ── Game Boy / Color / Advance ──
        EmulatorProfile(
            "pizzaboy_gba", "Pizza Boy GBA",
            listOf(
                "it.dbtecno.pizzaboygbapro/it.dbtecno.pizzaboygbapro.MainActivity",
                "it.dbtecno.pizzaboygba/it.dbtecno.pizzaboygba.MainActivity",
            ),
            extras = listOf(rom("rom_uri", RomArg.SAF)), clearTask = true, clearTop = true, storeId = "it.dbtecno.pizzaboygba",
        ),
        EmulatorProfile(
            "myboy", "My Boy!", listOf("com.fastemulator.gba/.EmulatorActivity"),
            action = VIEW, data = RomArg.SAF, storeId = "com.fastemulator.gba",
        ),
        ra("mgba", "mGBA"),
        ra("vbam", "VBA-M"),
        ra("gpsp", "gpSP"),
        EmulatorProfile(
            "linkboy", "Linkboy", listOf("com.pixelrespawn.linkboy/.EmulatorActivity"),
            action = VIEW, data = RomArg.SAF, storeId = "com.pixelrespawn.linkboy",
        ),
        imagine("gba_emu", "GBA.emu", "com.explusalpha.GbaEmu", RomArg.PROVIDER),
        EmulatorProfile(
            "skyemu", "SkyEmu", listOf("com.sky.SkyEmu/.EnhancedNativeActivity"),
            action = VIEW, data = RomArg.PROVIDER, clearTask = true, clearTop = true,
        ),
        EmulatorProfile(
            "pizzaboy_gbc", "Pizza Boy GBC",
            listOf(
                "it.dbtecno.pizzaboypro/it.dbtecno.pizzaboypro.MainActivity",
                "it.dbtecno.pizzaboy/it.dbtecno.pizzaboy.MainActivity",
            ),
            extras = listOf(rom("rom_uri", RomArg.SAF)), clearTask = true, clearTop = true, storeId = "it.dbtecno.pizzaboy",
        ),
        EmulatorProfile(
            "myoldboy", "My OldBoy!", listOf("com.fastemulator.gbc/.EmulatorActivity"),
            action = VIEW, data = RomArg.SAF, storeId = "com.fastemulator.gbc",
        ),
        ra("gambatte", "Gambatte"),
        ra("sameboy", "SameBoy"),
        imagine("gbc_emu", "GBC.emu", "com.explusalpha.GbcEmu", RomArg.PROVIDER),

        // ── NES / FDS / SNES ──
        ra("mesen", "Mesen"),
        ra("nestopia", "Nestopia UE"),
        ra("fceumm", "FCEUmm"),
        ra("quicknes", "QuickNES"),
        imagine("nes_emu", "NES.emu", "com.explusalpha.NesEmu", RomArg.PROVIDER),
        EmulatorProfile(
            "ines", "iNES", listOf("com.fms.ines.free/com.fms.emulib.TVActivity"),
            action = VIEW, data = RomArg.SAF, clearTask = true, clearTop = true, storeId = "com.fms.ines.free",
        ),
        ra("snes9x", "Snes9x"),
        ra("snes9x2010", "Snes9x 2010"),
        ra("bsnes", "bsnes"),
        imagine("snes9x_ex", "Snes9x EX+", "com.explusalpha.Snes9xPlus", RomArg.SAF),

        // ── Sega ──
        ra("genesis_plus_gx", "Genesis Plus GX"),
        ra("picodrive", "PicoDrive"),
        ra("blastem", "BlastEm"),
        ra("smsplus", "SMS Plus GX"),
        ra("gearsystem", "Gearsystem"),
        imagine("md_emu", "MD.emu", "com.explusalpha.MdEmu", RomArg.PROVIDER),
        imagine("md_emu_cd", "MD.emu", "com.explusalpha.MdEmu", RomArg.SAF),
        EmulatorProfile(
            "pizzaboy_sc", "Pizza Boy SC",
            listOf("it.dbtecno.pizzaboyscpro/.MainActivity", "it.dbtecno.pizzaboyscbasic/.MainActivity"),
            extras = listOf(rom("rom_uri", RomArg.SAF)), clearTask = true, clearTop = true,
        ),
        EmulatorProfile(
            "mastergear", "MasterGear", listOf("com.fms.mg/com.fms.emulib.TVActivity"),
            action = VIEW, data = RomArg.SAF, clearTask = true, clearTop = true, storeId = "com.fms.mg",
        ),
        EmulatorProfile(
            "yabasanshiro2", "Yaba Sanshiro 2",
            listOf(
                "org.devmiyax.yabasanshioro2.pro/org.uoyabause.android.Yabause",
                "org.devmiyax.yabasanshioro2/org.uoyabause.android.Yabause",
            ),
            action = VIEW, extras = listOf(rom("org.uoyabause.android.FileNameUri", RomArg.SAF)),
            clearTask = true, clearTop = true, storeId = "org.devmiyax.yabasanshioro2",
        ),
        imagine("saturn_emu", "Saturn.emu", "com.explusalpha.SaturnEmu", RomArg.SAF),
        ra("mednafen_saturn", "Beetle Saturn"),
        ra("yabasanshiro", "YabaSanshiro"),
        ra("yabause", "Yabause"),
        EmulatorProfile(
            "flycast", "Flycast",
            listOf("com.flycast.emulator/com.flycast.emulator.MainActivity", "com.flycast.emulator/com.reicast.emulator.MainActivity"),
            action = VIEW, data = RomArg.SAF, storeId = "com.flycast.emulator",
        ),
        EmulatorProfile(
            "redream", "Redream", listOf("io.recompiled.redream/.MainActivity"),
            action = VIEW, data = RomArg.SAF, storeId = "io.recompiled.redream",
        ),
        ra("flycast", "Flycast"),

        // ── NEC ──
        ra("mednafen_pce_fast", "Beetle PCE FAST"),
        ra("mednafen_pce", "Beetle PCE"),
        ra("geargrafx", "Geargrafx"),
        imagine("pce_emu", "PCE.emu", "com.PceEmu", RomArg.PROVIDER),
        imagine("pce_emu_cd", "PCE.emu", "com.PceEmu", RomArg.SAF),

        // ── Arcade / SNK ──
        ra("fbneo", "FinalBurn Neo"),
        ra("mame2003_plus", "MAME 2003-Plus"),
        ra("mamearcade", "MAME"),
        ra("mame2010", "MAME 2010"),
        ra("geolith", "Geolith"),
        EmulatorProfile(
            "mame4droid", "MAME4droid", listOf("com.seleuco.mame4droid/.MAME4droid"),
            action = VIEW, data = RomArg.PROVIDER, storeId = "com.seleuco.mame4droid",
        ),
        EmulatorProfile(
            "mame4droid_2024", "MAME4droid 2024", listOf("com.seleuco.mame4d2024/com.seleuco.mame4droid.MAME4droid"),
            action = VIEW, data = RomArg.PROVIDER, storeId = "com.seleuco.mame4d2024",
        ),
        imagine("neo_emu", "NEO.emu", "com.explusalpha.NeoEmu", RomArg.SAF),
        ra("mednafen_ngp", "Beetle NeoPop"),
        ra("race", "RACE"),
        imagine("ngp_emu", "NGP.emu", "com.explusalpha.NgpEmu", RomArg.PROVIDER),

        // ── Atari ──
        ra("stella", "Stella"),
        imagine("a2600_emu", "2600.emu", "com.explusalpha.A2600Emu", RomArg.PROVIDER),
        ra("prosystem", "ProSystem"),
        ra("handy", "Handy"),
        ra("mednafen_lynx", "Beetle Lynx"),
        imagine("lynx_emu", "Lynx.emu", "com.explusalpha.LynxEmu", RomArg.PROVIDER),
        ra("virtualjaguar", "Virtual Jaguar"),
        EmulatorProfile("iratajaguar", "IrataJaguar", listOf("ru.vastness.altmer.iratajaguar/.EmulatorActivity"), data = RomArg.SAF),

        // ── Bandai / Virtual Boy / 3DO ──
        ra("mednafen_wswan", "Beetle Cygne"),
        imagine("swan_emu", "Swan.emu", "com.explusalpha.SwanEmu", RomArg.PROVIDER),
        ra("mednafen_vb", "Beetle VB"),
        EmulatorProfile(
            "vvb", "Virtual Virtual Boy", listOf("com.simongellis.vvb/.MainActivity"),
            action = VIEW, data = RomArg.PROVIDER, clearTask = true, clearTop = true,
        ),
        ra("opera", "Opera"),
        EmulatorProfile("real3do", "Real3DOPlayer", listOf("ru.vastness.altmer.real3doplayer/.EmulatorActivity"), data = RomArg.SAF),

        // ── Ordenadores ──
        ra("bluemsx", "blueMSX"),
        ra("fmsx", "fMSX"),
        EmulatorProfile(
            "fmsx", "fMSX",
            listOf("com.fms.fmsx.deluxe/com.fms.emulib.TVActivity", "com.fms.fmsx/com.fms.emulib.TVActivity"),
            action = VIEW, data = RomArg.SAF, clearTask = true, clearTop = true, storeId = "com.fms.fmsx",
        ),
        imagine("msx_emu", "MSX.emu", "com.explusalpha.MsxEmu", RomArg.SAF),
        ra("vice_x64sc", "VICE x64sc"),
        ra("vice_x64", "VICE x64"),
        imagine("c64_emu", "C64.emu", "com.explusalpha.C64Emu", RomArg.SAF),
        ra("dosbox_pure", "DOSBox Pure"),
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String?): EmulatorProfile? = id?.let { byId[it] }

    /**
     * ¿Este emulador lanza por id de juego en vez de por archivo?
     *
     * Son los runtimes de Windows con biblioteca propia (BannerHub, GameHub,
     * GameNative). Sirve para ofrecer el id del juego también cuando la
     * carpeta no se dio de alta como PC, que es donde se puede acabar sin
     * saber por qué no arranca nada.
     */
    fun usesGameId(id: String?): Boolean = byId(id)?.extras.orEmpty().any {
        it.value is ExtraValue.LauncherId || it.value == ExtraValue.LauncherIdInt
    }

    /**
     * Paquetes que un emulador toma prestados de otra app.
     *
     * Varios runtimes de Windows y algún emulador de Switch publican builds que
     * se instalan con el paquete de un juego o de un benchmark conocido, para
     * que el driver de la GPU les aplique el perfil de esa app. Sirven para
     * lanzar, pero no delatan a un emulador: lo normal es que ahí esté la app
     * de verdad, y esconderla de la lista de juegos instalados sería peor.
     */
    val SPOOFED_PACKAGES = setOf(
        "com.antutu.ABenchMark", "com.antutu.benchmark.full",
        "com.ludashi.aibench", "com.ludashi.benchmark",
        "com.miHoYo.GenshinImpact", "com.miHoYo.Yuanshen",
        "com.tencent.ig", "com.tencent.tmgp.cf",
    )

    /** Paquetes que solo pueden ser un emulador, nunca un juego del usuario. */
    val emulatorOnlyPackages: Set<String> by lazy { ALL.flatMap { it.packages }.toSet() - SPOOFED_PACKAGES }
}
