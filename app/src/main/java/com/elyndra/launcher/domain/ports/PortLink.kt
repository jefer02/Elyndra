package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.MatchMethod

/**
 * Cómo entra un port recién instalado en la biblioteca (Kotlin puro,
 * PortLinkTest): como la app `a:<paquete>`, con el nombre del juego original
 * fijado (las pasadas de metadatos no lo cambian), los ids del original para
 * buscar su arte y la descripción del catálogo. Que es un port lo dice la
 * ficha ("PORT · <port>"), no el nombre.
 */
object PortLink {

    fun key(packageName: String): String = "a:$packageName"

    fun displayName(port: PortEntry): String = PortDisplay.title(port)

    /** El sufijo que ponía la primera versión de la tienda al nombre fijado. */
    const val LEGACY_SUFFIX = " (Port)"

    /** El meta de un port instalado con la primera versión, sin el sufijo; null si no hace falta tocarlo. */
    fun withoutLegacySuffix(meta: GameMeta): GameMeta? {
        val name = meta.userName ?: return null
        if (!meta.nameLocked || !name.endsWith(LEGACY_SUFFIX)) return null
        return meta.copy(userName = name.removeSuffix(LEGACY_SUFFIX).trimEnd())
    }

    fun meta(old: GameMeta, port: PortEntry, lang: String): GameMeta {
        val description = port.longText(lang).takeIf { it.isNotBlank() }
        return old.copy(
            userName = displayName(port),
            nameLocked = true,
            igdbId = port.game.igdbId ?: old.igdbId,
            sgdbId = port.game.sgdbId ?: old.sgdbId,
            steamAppId = port.game.steamAppId ?: old.steamAppId,
            matchedBy = MatchMethod.MANUAL,
            matchConfidence = 1f,
            description = description ?: old.description,
            descriptionLang = if (description != null) lang else old.descriptionLang,
        )
    }
}
