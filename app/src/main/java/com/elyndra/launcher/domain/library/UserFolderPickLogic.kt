package com.elyndra.launcher.domain.library

import com.elyndra.launcher.data.Library
import java.text.Normalizer

/**
 * Qué se puede añadir a una carpeta del usuario y cómo se busca (Kotlin puro,
 * UserFolderPickLogicTest).
 *
 * El índice se monta una vez al abrir el selector; cada búsqueda solo filtra
 * sus entradas, y si el texto amplía el anterior, solo los resultados de antes.
 */
object UserFolderPickLogic {

    enum class Kind { Rom, App, Port }

    data class Candidate(
        val key: String,
        val title: String,
        val kind: Kind,
        /** Plataforma corta de una ROM. */
        val platform: String?,
        val cover: String?,
        val icon: String?,
        val packageName: String?,
        /** Título y plataforma normalizados: contra esto se busca. */
        val search: String,
    )

    /**
     * Lo de la biblioteca que aún no está en ninguna carpeta, por título. Las
     * apps no instaladas no salen ([installed] vacío = aún sin saberlo: salen).
     */
    fun candidates(
        library: Library,
        installed: Set<String>,
        ports: Set<String>,
        platformOf: (String) -> String?,
    ): List<Candidate> {
        val taken = UserFolderLogic.owners(library).keys
        val platforms = HashMap<String, String?>()
        val out = ArrayList<Candidate>(library.roms.size + library.apps.size)
        for (r in library.roms) {
            if (r.key in taken) continue
            val platform = platforms.getOrPut(r.systemId) { platformOf(r.systemId) }
            val title = r.displayTitle
            out += Candidate(r.key, title, Kind.Rom, platform, r.meta.cover, r.meta.icon, null, normalize("$title ${platform.orEmpty()}"))
        }
        for (a in library.apps) {
            if (a.key in taken || (installed.isNotEmpty() && a.packageName !in installed)) continue
            val port = a.packageName in ports
            val title = a.displayTitle
            val tags = if (port) "android port" else "android"
            out += Candidate(a.key, title, if (port) Kind.Port else Kind.App, null, a.meta.cover, a.meta.icon, a.packageName, normalize("$title $tags"))
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        return out
    }

    /** Sin mayúsculas ni acentos: "pokémon" y "Pokemon" son lo mismo. */
    fun normalize(text: String): String {
        val lower = text.lowercase()
        if (lower.all { it.code < 128 }) return lower
        return MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
    }

    fun normalizeQuery(query: String): String = normalize(query).trim().replace(SPACES, " ")

    /**
     * Lo que encaja con [query]: todas sus palabras, en cualquier orden.
     * [previousQuery] (ya normalizada) y [previous] son la búsqueda anterior.
     */
    fun search(all: List<Candidate>, query: String, previousQuery: String, previous: List<Candidate>): List<Candidate> {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return all
        val base = if (previousQuery.isNotEmpty() && q.startsWith(previousQuery)) previous else all
        val terms = q.split(' ')
        return base.filter { c -> terms.all { it in c.search } }
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")
}
