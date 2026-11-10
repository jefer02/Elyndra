package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.Systems

/**
 * Cómo se nombra un port en pantalla (Kotlin puro, PortDisplayTest).
 *
 * Lo primero es siempre el juego original: cápsulas, filas, destacado,
 * título de la ficha, biblioteca. El port va en una línea secundaria
 * ("Port: Ship of Harkinian · por HarbourMasters") junto a la insignia de
 * la plataforma original.
 */
object PortDisplay {

    /** El nombre que se enseña: el del juego original. */
    fun title(port: PortEntry): String = port.game.name.trim().ifEmpty { port.name.trim() }

    /** El nombre del port y quién lo hace, para la línea secundaria (la UI pone "Port: …· por …"). */
    data class PortLine(val portName: String, val developer: String?)

    fun portLine(port: PortEntry): PortLine =
        PortLine(port.name.trim(), port.developer?.trim()?.takeIf { it.isNotEmpty() })

    /** La insignia de la plataforma original ("N64", "XBOX 360", "PC"). */
    fun platformBadge(platform: String): String = Systems.byId(platform)?.short ?: platform.uppercase()
}
