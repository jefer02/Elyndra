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

    /**
     * El nombre del port para enseñarlo, o null si es un identificador (slug,
     * paquete o archivo: "gow3_android", "com.foo.bar", "game.apk"): eso no se
     * enseña nunca al usuario.
     */
    fun portName(port: PortEntry): String? = port.name.trim().takeUnless { looksLikeId(it) }

    private val FILE_EXT = Regex("""\.(apk|zip|7z|so|pak|wad|pk3|obb|bin|iso|json)$""", RegexOption.IGNORE_CASE)
    private val PACKAGE = Regex("""^[a-z][a-z0-9_]*(\.[a-z0-9_]+){2,}$""")

    /** ¿Parece un identificador y no un nombre? (sin espacios y con _ / . de paquete, - en minúsculas o extensión). */
    fun looksLikeId(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        if (t.contains(' ')) return false
        return '_' in t || '/' in t || FILE_EXT.containsMatchIn(t) || PACKAGE.matches(t) ||
            ('-' in t && t == t.lowercase() && t.any { it.isLetter() })
    }

    /** La insignia de la plataforma original ("N64", "XBOX 360", "PC"). */
    fun platformBadge(platform: String): String = Systems.byId(platform)?.short ?: platform.uppercase()
}
