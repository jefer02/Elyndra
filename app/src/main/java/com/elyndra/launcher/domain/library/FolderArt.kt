package com.elyndra.launcher.domain.library

import com.elyndra.launcher.data.RomEntry

/**
 * El dibujo de aparato de una carpeta de sistema. Son vectores propios de
 * Elyndra (ver ui/components/SystemArt.kt y THIRD_PARTY_NOTICES.md): siluetas
 * genéricas por tipo de aparato, sin logos, marcas ni fotos de nadie.
 */
enum class DeviceGlyph {
    /** Consola de sobremesa de cartucho. */
    Cartridge,

    /** Consola de sobremesa de disco. */
    Disc,

    /** Portátil vertical (pantalla arriba, cruceta y botones abajo). */
    Handheld,

    /** Portátil apaisada (pantalla en medio, mandos a los lados). */
    Widescreen,

    /** Portátil de dos pantallas. */
    DualScreen,

    /** Tableta con los mandos a los lados que se separan. */
    Hybrid,

    /** Visor de realidad virtual sobre un soporte. */
    Goggles,

    /** Mueble recreativo. */
    Cabinet,

    /** Ordenador doméstico de teclado. */
    Computer,

    /** Monitor de PC. */
    Monitor,
}

/** El arte de serie de un sistema: un degradado en su color ([top] → [bottom], ARGB) y su aparato. */
data class SystemArtSpec(val top: Long, val bottom: Long, val glyph: DeviceGlyph)

/**
 * El arte de serie de cada sistema de [com.elyndra.launcher.data.Systems]
 * (Kotlin puro). Los colores son los que se asocian a cada aparato o a su
 * fabricante; el dibujo, el de su tipo ([DeviceGlyph]).
 */
object SystemArt {

    private fun s(top: Long, bottom: Long, glyph: DeviceGlyph) = SystemArtSpec(top, bottom, glyph)

    val SPECS: Map<String, SystemArtSpec> = mapOf(
        // Nintendo
        "nes" to s(0xFFC62828, 0xFF5C0F0F, DeviceGlyph.Cartridge),
        "fds" to s(0xFFB71C1C, 0xFF4E0B0B, DeviceGlyph.Cartridge),
        "snes" to s(0xFF5E4B8B, 0xFF2B1F45, DeviceGlyph.Cartridge),
        "n64" to s(0xFF2A4BA0, 0xFF111F48, DeviceGlyph.Cartridge),
        "gc" to s(0xFF5B4B9A, 0xFF2A2250, DeviceGlyph.Disc),
        "wii" to s(0xFF6D8BA8, 0xFF2C3E52, DeviceGlyph.Disc),
        "wiiu" to s(0xFF009AC7, 0xFF004A60, DeviceGlyph.Widescreen),
        "switch" to s(0xFFE60012, 0xFF8A000B, DeviceGlyph.Hybrid),
        "switch2" to s(0xFF2A3550, 0xFF0B1020, DeviceGlyph.Hybrid),
        "gb" to s(0xFF8B956D, 0xFF3F4630, DeviceGlyph.Handheld),
        "gbc" to s(0xFF7A2E8E, 0xFF3A1245, DeviceGlyph.Handheld),
        "gba" to s(0xFF4B3F9E, 0xFF211A4E, DeviceGlyph.Widescreen),
        "nds" to s(0xFF7D7D85, 0xFF3A3A40, DeviceGlyph.DualScreen),
        "n3ds" to s(0xFFC8102E, 0xFF6B0818, DeviceGlyph.DualScreen),
        "virtualboy" to s(0xFFC62828, 0xFF3A0000, DeviceGlyph.Goggles),
        // Sony
        "psx" to s(0xFF6E6E78, 0xFF2E2E36, DeviceGlyph.Disc),
        "ps2" to s(0xFF1B2A6B, 0xFF0A1030, DeviceGlyph.Disc),
        "ps3" to s(0xFF3A3A3A, 0xFF0C0C0C, DeviceGlyph.Disc),
        "ps4" to s(0xFF003791, 0xFF001A45, DeviceGlyph.Disc),
        "psp" to s(0xFF3A3A40, 0xFF101012, DeviceGlyph.Widescreen),
        "psvita" to s(0xFF0E3B8C, 0xFF061B40, DeviceGlyph.Widescreen),
        // Microsoft
        "xbox" to s(0xFF107C10, 0xFF053805, DeviceGlyph.Disc),
        "xbox360" to s(0xFF6FA800, 0xFF2F4A00, DeviceGlyph.Disc),
        // Sega
        "sg1000" to s(0xFF1E88E5, 0xFF0B3A66, DeviceGlyph.Cartridge),
        "mastersystem" to s(0xFF37474F, 0xFF0D1215, DeviceGlyph.Cartridge),
        "megadrive" to s(0xFF0060A8, 0xFF002850, DeviceGlyph.Cartridge),
        "segacd" to s(0xFF1565C0, 0xFF0A2E5C, DeviceGlyph.Disc),
        "sega32x" to s(0xFFC62828, 0xFF4A0F0F, DeviceGlyph.Cartridge),
        "saturn" to s(0xFF37474F, 0xFF131A1E, DeviceGlyph.Disc),
        "dreamcast" to s(0xFFF57C00, 0xFF8A3F00, DeviceGlyph.Disc),
        "gamegear" to s(0xFF3A3A3A, 0xFF0E0E0E, DeviceGlyph.Widescreen),
        // NEC, SNK, Bandai
        "pcengine" to s(0xFFEF6C00, 0xFF6B2E00, DeviceGlyph.Cartridge),
        "pcenginecd" to s(0xFFD84315, 0xFF5E1A07, DeviceGlyph.Disc),
        "neogeo" to s(0xFFC9971A, 0xFF5E4300, DeviceGlyph.Cartridge),
        "ngp" to s(0xFF1565C0, 0xFF0B2E5A, DeviceGlyph.Handheld),
        "ngpc" to s(0xFF00838F, 0xFF003A40, DeviceGlyph.Handheld),
        "wonderswan" to s(0xFF8E8E8E, 0xFF3C3C3C, DeviceGlyph.Widescreen),
        "wonderswancolor" to s(0xFF3949AB, 0xFF1A2357, DeviceGlyph.Widescreen),
        // Atari
        "atari2600" to s(0xFF8D6E63, 0xFF3E2723, DeviceGlyph.Cartridge),
        "atari7800" to s(0xFF455A64, 0xFF1C262B, DeviceGlyph.Cartridge),
        "atarilynx" to s(0xFF4A4A4A, 0xFF151515, DeviceGlyph.Widescreen),
        "atarijaguar" to s(0xFFB71C1C, 0xFF2A0000, DeviceGlyph.Cartridge),
        // Otros
        "3do" to s(0xFF37474F, 0xFF101518, DeviceGlyph.Disc),
        "arcade" to s(0xFF6A1B9A, 0xFF2A0A40, DeviceGlyph.Cabinet),
        "msx" to s(0xFF455A64, 0xFF1B2428, DeviceGlyph.Computer),
        "c64" to s(0xFF8D7B68, 0xFF3C3329, DeviceGlyph.Computer),
        "pc" to s(0xFF2B5797, 0xFF0F1F3D, DeviceGlyph.Monitor),
        "dos" to s(0xFF1A237E, 0xFF000033, DeviceGlyph.Monitor),
    )

    fun spec(systemId: String): SystemArtSpec? = SPECS[systemId]
}

/** Lo que se pinta en la card de una carpeta. */
sealed interface FolderArt {
    /** El icono que eligió el usuario: manda siempre. */
    data class Manual(val iconPath: String) : FolderArt

    /** El arte de serie de su sistema. */
    data class System(val systemId: String, val spec: SystemArtSpec) : FolderArt

    /** Sin arte de serie: hasta cuatro carátulas de sus juegos. */
    data class Collage(val covers: List<String>) : FolderArt

    /** Nada de lo anterior: el degradado de siempre con el nombre del sistema. */
    data object Gradient : FolderArt
}

/**
 * El arte de una carpeta (Kotlin puro, FolderArtTest), en este orden: el
 * icono elegido a mano, el arte de serie de su sistema ([SystemArt]), un
 * mosaico de las carátulas de sus juegos y, al final, el degradado.
 *
 * Se calcula cada vez a partir de la carpeta y sus juegos: una carpeta nueva,
 * una que cambia de sistema o una de antes de esto tienen ya el suyo sin
 * guardar nada, y el mosaico sigue a los juegos de la carpeta.
 */
object FolderArtResolver {

    const val COLLAGE_MAX = 4

    fun resolve(manualIcon: String?, systemId: String, covers: List<String>): FolderArt {
        manualIcon?.takeIf { it.isNotBlank() }?.let { return FolderArt.Manual(it) }
        SystemArt.spec(systemId)?.let { return FolderArt.System(systemId, it) }
        val shown = covers.filter { it.isNotBlank() }.distinct().take(COLLAGE_MAX)
        return if (shown.isEmpty()) FolderArt.Gradient else FolderArt.Collage(shown)
    }

    /** Las carátulas del mosaico: lo último jugado primero; luego lo último añadido y el nombre. */
    fun collageCovers(roms: List<RomEntry>): List<String> =
        roms.filter { !it.meta.cover.isNullOrBlank() }
            .sortedWith(
                compareByDescending<RomEntry> { it.stats.lastPlayed }
                    .thenByDescending { it.addedAt }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle },
            )
            .mapNotNull { it.meta.cover }
            .distinct()
            .take(COLLAGE_MAX)
}
