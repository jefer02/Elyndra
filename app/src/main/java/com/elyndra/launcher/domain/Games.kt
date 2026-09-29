package com.elyndra.launcher.domain

import com.elyndra.launcher.data.AppEntry
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.GameSystem
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.Systems

/* ─────────────────────────────────────────────────────────────
   Capa de dominio: la lógica con la que Masha piensa.

   Todo lo de este paquete es Kotlin puro —ni Context, ni Room, ni
   Compose—: recibe la instantánea de la biblioteca y devuelve
   conclusiones. Así se prueba en la JVM y no depende de cómo se
   guarden o se pinten las cosas.
   ───────────────────────────────────────────────────────────── */

/**
 * Un juego de la biblioteca, sea ROM o app Android, visto igual por la lógica
 * de Masha. [system] es null en los juegos Android.
 */
data class Game(
    val key: String,
    val title: String,
    val system: GameSystem?,
    val rom: RomEntry?,
    val app: AppEntry?,
    /** Emulador con el que se lanzaría (el de la ROM o, si no, el de su carpeta). */
    val emulatorId: String?,
    /** Cuándo entró en la biblioteca. */
    val addedAt: Long = 0,
) {
    val meta: GameMeta get() = rom?.meta ?: app?.meta ?: GameMeta()
    val stats: PlayStats get() = rom?.stats ?: app?.stats ?: PlayStats()
    val isApp: Boolean get() = app != null
    val systemId: String? get() = system?.id
    val platform: String get() = system?.name ?: ANDROID

    /** Tiene su imagen principal: carátula en una ROM, icono en un juego Android. */
    val hasPrimaryArt: Boolean get() = if (isApp) meta.icon != null else meta.cover != null

    /** Arte completo: imagen principal, fondo y logo. */
    val hasCompleteArt: Boolean
        get() = hasPrimaryArt && (meta.hero != null || meta.screenshot != null) && meta.logo != null

    companion object {
        const val ANDROID = "Android"
    }
}

/** Todos los juegos de la biblioteca: ROMs de carpetas que existen y apps Android. */
fun Library.games(): List<Game> {
    val folders = folders.associateBy { it.id }
    val roms = roms.mapNotNull { r ->
        val folder = folders[r.folderId] ?: return@mapNotNull null
        Game(
            key = r.key,
            title = r.displayTitle,
            system = Systems.byId(r.systemId),
            rom = r,
            app = null,
            emulatorId = r.emulatorId ?: folder.emulatorId,
            addedAt = r.addedAt.takeIf { it > 0 } ?: folder.addedAt,
        )
    }
    val apps = apps.map { a -> Game(a.key, a.displayTitle, null, null, a, null, a.addedAt) }
    return roms + apps
}

/** Cuánto le cuesta al dispositivo mover un sistema: decide duraciones y avisos de batería/temperatura. */
enum class SystemWeight {
    /** Consolas de 8/16 bits, portátiles antiguas, arcade: casi cualquier móvil las mueve frío. */
    Light,

    /** PS1, N64, PSP, DS, Saturn, Dreamcast… */
    Medium,

    /** PS2, GameCube/Wii, Switch, PS3, Xbox, PC: calientan y gastan. */
    Heavy,
    ;

    companion object {
        private val HEAVY = setOf("switch", "pc", "ps2", "ps3", "xbox", "xbox360", "gc", "wii", "wiiu", "psvita", "n3ds")
        private val MEDIUM = setOf(
            "psx", "n64", "psp", "nds", "saturn", "dreamcast", "segacd", "sega32x", "3do",
            "atarijaguar", "pcenginecd", "dos",
        )

        fun of(systemId: String?): SystemWeight = when (systemId) {
            null -> Medium
            in HEAVY -> Heavy
            in MEDIUM -> Medium
            else -> Light
        }
    }
}
