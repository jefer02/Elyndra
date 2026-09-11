package com.elyndra.launcher.data

/* ─────────────────────────────────────────────────────────────
   Sistemas soportados.

   Extensiones y emuladores siguen la configuración Android de ES-DE
   (es_systems.xml / es_find_rules.xml). Los IDs de plataforma son los
   de cada servicio: ScreenScraper (systemesListe.php), IGDB
   (/v4/platforms) y RetroAchievements (rc_consoles.h de rcheevos).
   ───────────────────────────────────────────────────────────── */

/** Cómo calcula RetroAchievements el hash de una ROM de este sistema (rcheevos). */
enum class RaHashKind { None, Plain, Nes, Snes, Lynx, A7800, Pce, N64, Nds, Arcade, Psx, Ps2, Psp }

data class GameSystem(
    val id: String,
    val name: String,
    val short: String,
    val abbr: String,
    /** Extensiones en minúsculas y sin punto. */
    val extensions: Set<String>,
    /** Nombres de carpeta normalizados que delatan el sistema ("psp", "gameboyadvance"…). */
    val aliases: Set<String>,
    val ssId: Int?,
    val igdbIds: List<Int>,
    val raId: Int?,
    val pair: Int,
    /** Perfiles de emulador en orden de preferencia (ver Emulators.kt). */
    val emulators: List<String>,
    /** Juegos en disco: ScreenScraper recibe romtype=iso. */
    val disc: Boolean = false,
    /** Juegos que son carpetas (PS3 en formato JB). */
    val dirGames: Boolean = false,
    val raHash: RaHashKind = RaHashKind.None,
)

private fun sys(
    id: String,
    name: String,
    short: String,
    abbr: String,
    ext: String,
    aliases: String,
    ss: Int?,
    igdb: List<Int>,
    ra: Int?,
    pair: Int,
    emus: List<String>,
    disc: Boolean = false,
    dirGames: Boolean = false,
    raHash: RaHashKind = RaHashKind.None,
) = GameSystem(
    id = id,
    name = name,
    short = short,
    abbr = abbr,
    extensions = ext.split(' ').filter { it.isNotBlank() }.map { it.lowercase() }.toSet(),
    aliases = aliases.split(' ').filter { it.isNotBlank() }.toSet(),
    ssId = ss,
    igdbIds = igdb,
    raId = ra,
    pair = pair,
    emulators = emus,
    disc = disc,
    dirGames = dirGames,
    raHash = raHash,
)

object Systems {

    val ALL: List<GameSystem> = listOf(
        sys(
            "switch", "Nintendo Switch", "SWITCH", "NSW", "nsp xci nca nro nso",
            "switch nsw ns nintendoswitch", ss = 225, igdb = listOf(130), ra = null, pair = 0,
            emus = listOf("eden", "eden_nightly", "citron", "sudachi", "yuzu", "kenjinx", "skyline"),
        ),
        sys(
            "ps2", "PlayStation 2", "PS2", "PS2", "iso chd cso ciso bin img mdf nrg gz m3u elf isz",
            "ps2 playstation2 sonyplaystation2", ss = 58, igdb = listOf(8), ra = 21, pair = 1,
            emus = listOf("nethersx2", "nethersx2_turnip", "armsx2", "emucorex", "play", "ra_pcsx2"),
            disc = true, raHash = RaHashKind.Ps2,
        ),
        sys(
            "ps3", "PlayStation 3", "PS3", "PS3", "iso ps3",
            "ps3 playstation3 sonyplaystation3", ss = 59, igdb = listOf(9), ra = 82, pair = 2,
            emus = listOf("aps3e", "armsx3", "emucorec"), disc = true, dirGames = true,
        ),
        sys(
            "xbox360", "Xbox 360", "XBOX 360", "360", "iso xex zar",
            "xbox360 x360 360 microsoftxbox360", ss = 33, igdb = listOf(12), ra = null, pair = 3,
            emus = listOf("ax360e", "xendroid", "xenra"), disc = true,
        ),
        sys(
            "xbox", "Xbox", "XBOX", "XB", "iso xiso",
            "xbox microsoftxbox xboxoriginal", ss = 32, igdb = listOf(11), ra = null, pair = 8,
            emus = listOf("x1box", "hakux", "xenra"), disc = true,
        ),
        sys(
            "psp", "PlayStation Portable", "PSP", "PSP", "iso cso chd pbp elf prx zip 7z",
            "psp playstationportable sonypsp", ss = 61, igdb = listOf(38), ra = 41, pair = 4,
            emus = listOf("ppsspp", "ra_ppsspp"), disc = true, raHash = RaHashKind.Psp,
        ),
        sys(
            "psvita", "PlayStation Vita", "VITA", "PSV", "psvita",
            "psvita vita psv playstationvita", ss = 62, igdb = listOf(46), ra = null, pair = 9,
            emus = listOf("vita3k", "emucorev"),
        ),
        sys(
            "psx", "PlayStation", "PS1", "PS1", "cue chd iso pbp m3u img ccd mdf toc ecm exe psexe bin cbn z znx 7z zip",
            "psx ps1 playstation playstation1 sonyplaystation", ss = 57, igdb = listOf(7), ra = 12, pair = 5,
            emus = listOf("duckstation", "ra_mednafen_psx_hw", "ra_pcsx_rearmed", "ra_swanstation", "epsxe", "fpse64", "fpse", "armsx1"),
            disc = true, raHash = RaHashKind.Psx,
        ),
        sys(
            "n64", "Nintendo 64", "N64", "N64", "z64 n64 v64 ndd u1 bin 7z zip",
            "n64 nintendo64", ss = 14, igdb = listOf(4), ra = 2, pair = 6,
            emus = listOf("m64plus_fz", "mupen64plus_ae", "ra_mupen64plus_next_gles3", "ra_parallel_n64"),
            raHash = RaHashKind.N64,
        ),
        sys(
            "nds", "Nintendo DS", "DS", "NDS", "nds dsi ids app bin 7z zip",
            "nds ds nintendods", ss = 15, igdb = listOf(20, 159), ra = 18, pair = 7,
            emus = listOf("melonds", "melonds_nightly", "drastic", "noods", "ra_melondsds", "ra_desmume"),
            raHash = RaHashKind.Nds,
        ),
        sys(
            "n3ds", "Nintendo 3DS", "3DS", "3DS", "3ds 3dsx cci cxi cia app axf elf z3dsx zcci zcxi 7z zip",
            "3ds n3ds nintendo3ds", ss = 17, igdb = listOf(37, 137), ra = 62, pair = 10,
            emus = listOf("azahar", "azaharplus", "citra", "lime3ds", "mandarine", "panda3ds", "citra_mmj"),
        ),
        sys(
            "gc", "Nintendo GameCube", "GAMECUBE", "NGC", "iso rvz gcz gcm ciso wbfs wia tgc dol elf m3u",
            "gc gamecube ngc nintendogamecube", ss = 13, igdb = listOf(21), ra = 16, pair = 11,
            emus = listOf("dolphin", "dolphin_mmjr", "dolphin_mmjr2", "ra_dolphin"), disc = true,
        ),
        sys(
            "wii", "Nintendo Wii", "WII", "WII", "iso rvz wbfs wia gcz ciso wad dol elf m3u",
            "wii nintendowii", ss = 16, igdb = listOf(5), ra = 19, pair = 3,
            emus = listOf("dolphin", "dolphin_mmjr", "dolphin_mmjr2", "ra_dolphin"), disc = true,
        ),
        sys(
            "wiiu", "Nintendo Wii U", "WII U", "WIU", "wua wud wux rpx wuhb tmd elf",
            "wiiu nintendowiiu", ss = 18, igdb = listOf(41), ra = 20, pair = 0,
            emus = listOf("cemu"), disc = true,
        ),
        sys(
            "gba", "Game Boy Advance", "GBA", "GBA", "gba agb bin 7z zip",
            "gba gameboyadvance nintendogameboyadvance", ss = 12, igdb = listOf(24), ra = 5, pair = 4,
            emus = listOf("pizzaboy_gba", "myboy", "ra_mgba", "ra_vbam", "ra_gpsp", "linkboy", "gba_emu", "skyemu"),
            raHash = RaHashKind.Plain,
        ),
        sys(
            "gbc", "Game Boy Color", "GBC", "GBC", "gbc cgb gb dmg sgb 7z zip",
            "gbc gameboycolor nintendogameboycolor", ss = 10, igdb = listOf(22), ra = 6, pair = 9,
            emus = listOf("pizzaboy_gbc", "myoldboy", "ra_gambatte", "ra_sameboy", "ra_mgba", "gbc_emu", "linkboy", "skyemu"),
            raHash = RaHashKind.Plain,
        ),
        sys(
            "gb", "Game Boy", "GAME BOY", "GB", "gb dmg sgb gbc 7z zip",
            "gb gameboy nintendogameboy", ss = 9, igdb = listOf(33), ra = 4, pair = 8,
            emus = listOf("pizzaboy_gbc", "myoldboy", "ra_gambatte", "ra_sameboy", "ra_mgba", "gbc_emu", "linkboy", "skyemu"),
            raHash = RaHashKind.Plain,
        ),
        sys(
            "nes", "Nintendo Entertainment System", "NES", "NES", "nes unf unif 7z zip",
            "nes famicom fc nintendoentertainmentsystem", ss = 3, igdb = listOf(18, 99), ra = 7, pair = 5,
            emus = listOf("ra_mesen", "ra_nestopia", "ra_fceumm", "ra_quicknes", "nes_emu", "ines"),
            raHash = RaHashKind.Nes,
        ),
        sys(
            "fds", "Famicom Disk System", "FDS", "FDS", "fds nes 7z zip",
            "fds famicomdisksystem", ss = 106, igdb = listOf(51), ra = 81, pair = 1,
            emus = listOf("ra_mesen", "ra_nestopia", "ra_fceumm", "nes_emu"),
            raHash = RaHashKind.Nes,
        ),
        sys(
            "snes", "Super Nintendo", "SNES", "SNES", "sfc smc fig swc bs st bin 7z zip",
            "snes sfc superfamicom supernintendo supernes", ss = 4, igdb = listOf(19, 58), ra = 3, pair = 4,
            emus = listOf("ra_snes9x", "ra_snes9x2010", "ra_bsnes", "snes9x_ex"),
            raHash = RaHashKind.Snes,
        ),
        sys(
            "megadrive", "Mega Drive / Genesis", "MEGA DRIVE", "MD", "md gen smd bin 68k sgd 7z zip",
            "megadrive genesis md segagenesis segamegadrive", ss = 1, igdb = listOf(29), ra = 1, pair = 3,
            emus = listOf("ra_genesis_plus_gx", "ra_picodrive", "ra_blastem", "md_emu", "pizzaboy_sc"),
            raHash = RaHashKind.Plain,
        ),
        sys(
            "mastersystem", "Master System", "MASTER SYSTEM", "SMS", "sms bin 7z zip",
            "mastersystem sms segamastersystem", ss = 2, igdb = listOf(64), ra = 11, pair = 7,
            emus = listOf("ra_genesis_plus_gx", "ra_smsplus", "ra_gearsystem", "ra_picodrive", "md_emu", "mastergear", "pizzaboy_sc"),
            raHash = RaHashKind.Plain,
        ),
        sys(
            "gamegear", "Game Gear", "GAME GEAR", "GG", "gg bin 7z zip",
            "gamegear gg segagamegear", ss = 21, igdb = listOf(35), ra = 15, pair = 6,
            emus = listOf("ra_genesis_plus_gx", "ra_gearsystem", "ra_smsplus", "mastergear", "pizzaboy_sc"),
            raHash = RaHashKind.Plain,
        ),
        sys(
            "segacd", "Mega-CD / Sega CD", "MEGA-CD", "MCD", "cue chd iso m3u bin 7z zip",
            "segacd megacd", ss = 20, igdb = listOf(78), ra = 9, pair = 10,
            emus = listOf("ra_genesis_plus_gx", "ra_picodrive", "md_emu_cd", "pizzaboy_sc"), disc = true,
        ),
        sys(
            "sega32x", "Sega 32X", "32X", "32X", "32x bin md 7z zip",
            "sega32x 32x", ss = 19, igdb = listOf(30), ra = 10, pair = 2,
            emus = listOf("ra_picodrive"), raHash = RaHashKind.Plain,
        ),
        sys(
            "saturn", "Sega Saturn", "SATURN", "SAT", "cue chd iso m3u ccd mds toc bin 7z zip",
            "saturn segasaturn", ss = 22, igdb = listOf(32), ra = 39, pair = 1,
            emus = listOf("yabasanshiro2", "saturn_emu", "ra_mednafen_saturn", "ra_yabasanshiro", "ra_yabause"), disc = true,
        ),
        sys(
            "dreamcast", "Sega Dreamcast", "DREAMCAST", "DC", "gdi chd cdi cue iso m3u elf lst 7z zip",
            "dreamcast dc segadreamcast", ss = 23, igdb = listOf(23), ra = 40, pair = 0,
            emus = listOf("flycast", "redream", "ra_flycast"), disc = true,
        ),
        sys(
            "pcengine", "PC Engine / TurboGrafx-16", "PC ENGINE", "PCE", "pce sgx bin 7z zip",
            "pcengine pce tg16 turbografx16 turbografx supergrafx", ss = 31, igdb = listOf(86), ra = 8, pair = 11,
            emus = listOf("ra_mednafen_pce_fast", "ra_mednafen_pce", "ra_geargrafx", "pce_emu"),
            raHash = RaHashKind.Pce,
        ),
        sys(
            "pcenginecd", "PC Engine CD", "PCE CD", "PCD", "cue chd ccd img iso m3u toc",
            "pcenginecd pcecd tgcd turbografxcd", ss = 114, igdb = listOf(150), ra = 76, pair = 6,
            emus = listOf("ra_mednafen_pce_fast", "ra_mednafen_pce", "pce_emu_cd"), disc = true,
        ),
        sys(
            "arcade", "Arcade", "ARCADE", "ARC", "zip 7z",
            "arcade mame fbneo fba finalburn finalburnneo cps1 cps2 cps3", ss = 75, igdb = listOf(52), ra = 27, pair = 2,
            emus = listOf("ra_fbneo", "ra_mame2003_plus", "ra_mamearcade", "ra_mame2010", "mame4droid", "mame4droid_2024", "neo_emu"),
            raHash = RaHashKind.Arcade,
        ),
        sys(
            "neogeo", "Neo Geo", "NEO GEO", "NEO", "zip 7z neo",
            "neogeo neogeoaes neogeomvs", ss = 142, igdb = listOf(80, 79), ra = 27, pair = 7,
            emus = listOf("ra_fbneo", "ra_geolith", "neo_emu", "mame4droid"),
            raHash = RaHashKind.Arcade,
        ),
        sys(
            "atari2600", "Atari 2600", "ATARI 2600", "2600", "a26 bin 7z zip",
            "atari2600 2600 a2600", ss = 26, igdb = listOf(59), ra = 25, pair = 1,
            emus = listOf("ra_stella", "a2600_emu"), raHash = RaHashKind.Plain,
        ),
        sys(
            "atari7800", "Atari 7800", "ATARI 7800", "7800", "a78 bin 7z zip",
            "atari7800 7800 a7800", ss = 41, igdb = listOf(60), ra = 51, pair = 5,
            emus = listOf("ra_prosystem"), raHash = RaHashKind.A7800,
        ),
        sys(
            "atarilynx", "Atari Lynx", "LYNX", "LNX", "lnx lyx o 7z zip",
            "atarilynx lynx", ss = 28, igdb = listOf(61), ra = 13, pair = 9,
            emus = listOf("ra_handy", "ra_mednafen_lynx", "lynx_emu"), raHash = RaHashKind.Lynx,
        ),
        sys(
            "atarijaguar", "Atari Jaguar", "JAGUAR", "JAG", "j64 jag abs cof rom bin 7z zip",
            "atarijaguar jaguar", ss = 27, igdb = listOf(62), ra = 17, pair = 11,
            emus = listOf("ra_virtualjaguar", "iratajaguar"), raHash = RaHashKind.Plain,
        ),
        sys(
            "ngp", "Neo Geo Pocket", "NGP", "NGP", "ngp npc 7z zip",
            "ngp neogeopocket", ss = 25, igdb = listOf(119), ra = 14, pair = 8,
            emus = listOf("ra_mednafen_ngp", "ra_race", "ngp_emu"), raHash = RaHashKind.Plain,
        ),
        sys(
            "ngpc", "Neo Geo Pocket Color", "NGPC", "NGC", "ngc ngpc ngp 7z zip",
            "ngpc neogeopocketcolor", ss = 82, igdb = listOf(120), ra = 14, pair = 3,
            emus = listOf("ra_mednafen_ngp", "ra_race", "ngp_emu"), raHash = RaHashKind.Plain,
        ),
        sys(
            "wonderswan", "WonderSwan", "WONDERSWAN", "WS", "ws pc2 7z zip",
            "wonderswan ws bandaiwonderswan", ss = 45, igdb = listOf(57), ra = 53, pair = 10,
            emus = listOf("ra_mednafen_wswan", "swan_emu"), raHash = RaHashKind.Plain,
        ),
        sys(
            "wonderswancolor", "WonderSwan Color", "WS COLOR", "WSC", "wsc ws pc2 7z zip",
            "wonderswancolor wsc bandaiwonderswancolor", ss = 46, igdb = listOf(123), ra = 53, pair = 4,
            emus = listOf("ra_mednafen_wswan", "swan_emu"), raHash = RaHashKind.Plain,
        ),
        sys(
            "virtualboy", "Virtual Boy", "VIRTUAL BOY", "VB", "vb vboy bin 7z zip",
            "virtualboy vb nintendovirtualboy", ss = 11, igdb = listOf(87), ra = 28, pair = 5,
            emus = listOf("ra_mednafen_vb", "vvb"), raHash = RaHashKind.Plain,
        ),
        sys(
            "3do", "3DO Interactive Multiplayer", "3DO", "3DO", "iso chd cue bin 7z zip",
            "3do panasonic3do", ss = 29, igdb = listOf(50), ra = 43, pair = 2,
            emus = listOf("ra_opera", "real3do"), disc = true,
        ),
        sys(
            "msx", "MSX", "MSX", "MSX", "rom mx1 mx2 dsk cas ri col m3u 7z zip",
            "msx msx1 msx2", ss = 113, igdb = listOf(27, 53), ra = 29, pair = 6,
            emus = listOf("ra_bluemsx", "ra_fmsx", "fmsx", "msx_emu"),
        ),
        sys(
            "c64", "Commodore 64", "C64", "C64", "d64 t64 prg crt tap g64 m3u 7z zip",
            "c64 commodore64", ss = 66, igdb = listOf(15), ra = 30, pair = 7,
            emus = listOf("ra_vice_x64sc", "ra_vice_x64", "c64_emu"),
        ),
        sys(
            "dos", "MS-DOS", "DOS", "DOS", "dosz zip 7z iso cue img",
            "dos msdos pcdos", ss = 135, igdb = listOf(13), ra = 26, pair = 10,
            emus = listOf("ra_dosbox_pure"),
        ),
        sys(
            "sg1000", "Sega SG-1000", "SG-1000", "SG1", "sg bin 7z zip",
            "sg1000 segasg1000", ss = 109, igdb = listOf(84), ra = 33, pair = 9,
            emus = listOf("ra_genesis_plus_gx", "ra_gearsystem", "mastergear"), raHash = RaHashKind.Plain,
        ),
    )

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String?): GameSystem? = id?.let { byId[it] }

    /** Normaliza un nombre de carpeta para compararlo con los alias ("Game Boy Advance" → "gameboyadvance"). */
    fun normalizeFolderName(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    /** Sistema que sugiere un nombre de carpeta, o null. */
    fun detect(folderName: String): GameSystem? {
        val n = normalizeFolderName(folderName)
        if (n.isEmpty()) return null
        return ALL.firstOrNull { n in it.aliases || n == it.id }
    }
}
