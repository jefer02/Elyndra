package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.MatchMethod

/**
 * Cómo entra un port recién instalado en la biblioteca (Kotlin puro,
 * PortLinkTest): como la app `a:<paquete>`, con el nombre del juego
 * original más "(Port)" fijado (las pasadas de metadatos no lo cambian),
 * los ids del original para buscar su arte y la descripción del catálogo.
 */
object PortLink {

    const val SUFFIX = " (Port)"

    fun key(packageName: String): String = "a:$packageName"

    fun displayName(port: PortEntry): String = port.original.title.trim() + SUFFIX

    fun meta(old: GameMeta, port: PortEntry, lang: String): GameMeta {
        val description = port.longText(lang).takeIf { it.isNotBlank() }
        return old.copy(
            userName = displayName(port),
            nameLocked = true,
            igdbId = port.original.igdbId ?: old.igdbId,
            sgdbId = port.original.sgdbId ?: old.sgdbId,
            matchedBy = MatchMethod.MANUAL,
            matchConfidence = 1f,
            description = description ?: old.description,
            descriptionLang = if (description != null) lang else old.descriptionLang,
        )
    }
}
