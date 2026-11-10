package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.GameMeta

/**
 * La pasada única que mejora el arte de la biblioteca tras poner SteamGridDB
 * e IGDB primero (Kotlin puro, ArtUpgradeTest). Qué clases de imagen de un
 * juego merecen volver a pedirse:
 *
 *  · nunca lo fijado a mano ([GameMeta.pinned]: lo elegido en "Personalizar…")
 *    ni lo que salió de la carpeta del propio juego (origen `local`);
 *  · solo si se sabe de dónde salió ([GameMeta.artOrigins]) y hay ahora una
 *    fuente configurada que da esa clase y va antes en la prioridad.
 *
 * Solo pregunta a las que buscan por nombre ([SOURCES]): no hace falta leer
 * el archivo del juego (hashes) para saber a quién preguntar.
 */
object ArtUpgrade {

    /** Las fuentes que la pasada usa: buscan por el nombre ya conocido. */
    val SOURCES = setOf(Service.SteamGridDb, Service.Igdb)

    const val LOCAL = "local"

    /** Las clases que se pueden mejorar (la de icono, solo en juegos Android). */
    fun fields(isApp: Boolean): List<MetaField> =
        listOfNotNull(MetaField.Cover, MetaField.Hero, MetaField.Logo, MetaField.Icon.takeIf { isApp }, MetaField.Screenshot)

    /**
     * Las clases de [meta] que alguna fuente de [configured] (entre
     * [SOURCES]) daría con más prioridad que la que las dio. Vacío = nada que
     * mejorar.
     */
    fun kinds(meta: GameMeta, priority: MetadataPriority, configured: Set<Service>, isApp: Boolean): Set<MetaField> {
        val better = SOURCES.filter { it in configured }
        if (better.isEmpty()) return emptySet()
        return fields(isApp).filterTo(LinkedHashSet()) { field ->
            val kind = field.artKind ?: return@filterTo false
            if (kind in meta.pinned) return@filterTo false
            val origin = meta.artOrigins[kind] ?: return@filterTo false
            if (origin.source == LOCAL) return@filterTo false
            // Una fuente que esta versión no conoce: mejor no tocar.
            val from = Service.entries.firstOrNull { it.id == origin.source } ?: return@filterTo false
            val current = priority.rank(from, field)
            better.any { s -> field in MetadataMerge.provides(s) && priority.rank(s, field) < current }
        }
    }

    /** De lo que trajo la pasada, lo que de verdad mejora [field]: de una fuente con más prioridad que la de antes. */
    fun improves(meta: GameMeta, field: MetaField, service: Service, priority: MetadataPriority): Boolean {
        val kind = field.artKind ?: return false
        val from = meta.artOrigins[kind]?.source?.let { id -> Service.entries.firstOrNull { it.id == id } } ?: return false
        return priority.rank(service, field) < priority.rank(from, field)
    }
}
