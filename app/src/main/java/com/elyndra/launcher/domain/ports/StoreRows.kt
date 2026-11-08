package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.Systems

/* Las filas de la portada de la tienda de ports y su búsqueda. Kotlin puro (StoreRowsTest). */

/** Lo que el usuario filtra en la tienda. */
data class StoreFilter(
    val query: String = "",
    val platforms: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val showPcOnly: Boolean = false,
) {
    val active: Boolean get() = query.isNotBlank() || platforms.isNotEmpty() || tags.isNotEmpty()
}

enum class StoreRowKind { Featured, BecauseYouHave, Nintendo, Xbox, RecentlyUpdated, Installed, Results }

/** Una fila de cápsulas. [subject]: el juego de "Porque tienes…". */
data class StoreRow(val kind: StoreRowKind, val ports: List<PortEntry>, val subject: String? = null)

object StoreRows {

    val NINTENDO = setOf("nes", "fds", "snes", "n64", "gc", "wii", "wiiu", "gb", "gbc", "gba", "nds", "n3ds", "switch", "virtualboy")
    val XBOX = setOf("xbox", "xbox360")

    /** Cuántas cápsulas como mucho por fila (la portada es un escaparate, no la lista entera). */
    const val ROW_MAX = 12

    /** Los ports que pasan el filtro. Sin "mostrar solo PC", los que no tienen build de Android no salen. */
    fun visible(ports: List<PortEntry>, filter: StoreFilter): List<PortEntry> {
        val words = PortLookup.normalizeWords(filter.query)
        return ports.filter { p ->
            (filter.showPcOnly || p.installable) &&
                (filter.platforms.isEmpty() || p.original.platform in filter.platforms) &&
                (filter.tags.isEmpty() || p.tags.any { it in filter.tags }) &&
                (words.isEmpty() || words.all { w -> haystack(p).contains(w) })
        }
    }

    private fun haystack(p: PortEntry): String =
        PortLookup.normalizeTitle(
            listOf(
                p.name,
                p.original.title,
                p.original.platform,
                Systems.byId(p.original.platform)?.name.orEmpty(),
                p.repo.slug,
                p.developer.orEmpty(),
                p.tags.joinToString(" "),
            ).joinToString(" "),
        )

    /**
     * La portada.
     *
     * Con un filtro activo, una sola fila de resultados. Si no: destacados,
     * "Porque tienes <juego>" (lo de la biblioteca), Nintendo, Xbox,
     * actualizados hace poco ([publishedAt] por id, lo que se sepa ya) e
     * instalados. Las filas vacías no salen.
     */
    fun home(
        ports: List<PortEntry>,
        filter: StoreFilter,
        owned: List<RomPortMatch>,
        installedIds: Set<String>,
        publishedAt: Map<String, Long>,
    ): List<StoreRow> {
        val shown = visible(ports, filter)
        if (filter.active) return listOf(StoreRow(StoreRowKind.Results, shown))
        val ids = shown.map { it.id }.toSet()
        val rows = mutableListOf<StoreRow>()
        rows += StoreRow(StoreRowKind.Featured, shown.filter { it.featured }.ifEmpty { shown.take(5) }.take(ROW_MAX))
        val mine = owned.filter { it.port.id in ids }
        if (mine.isNotEmpty()) {
            rows += StoreRow(StoreRowKind.BecauseYouHave, mine.map { it.port }.distinctBy { it.id }.take(ROW_MAX), subject = mine.first().rom.displayTitle)
        }
        rows += StoreRow(StoreRowKind.Nintendo, shown.filter { it.original.platform in NINTENDO }.take(ROW_MAX))
        rows += StoreRow(StoreRowKind.Xbox, shown.filter { it.original.platform in XBOX }.take(ROW_MAX))
        rows += StoreRow(
            StoreRowKind.RecentlyUpdated,
            shown.filter { (publishedAt[it.id] ?: 0L) > 0L }.sortedByDescending { publishedAt[it.id] }.take(ROW_MAX),
        )
        rows += StoreRow(StoreRowKind.Installed, shown.filter { it.id in installedIds }.take(ROW_MAX))
        return rows.filter { it.ports.isNotEmpty() }
    }

    /**
     * Lo que encuentra Masha (`find_port`): con [query], lo que la busca (también
     * los de PC, que Masha sabe explicar), los instalables primero; sin ella,
     * los ports de lo que hay en la biblioteca y, si no hay ninguno, nada.
     */
    fun find(ports: List<PortEntry>, query: String?, owned: List<RomPortMatch>, limit: Int = 8): List<PortEntry> {
        val found = if (query.isNullOrBlank()) {
            owned.map { it.port }.distinctBy { it.id }
        } else {
            visible(ports, StoreFilter(query = query, showPcOnly = true)).sortedByDescending { it.installable }
        }
        return found.take(limit)
    }

    /** Las etiquetas del catálogo, de la más usada a la menos (para los chips de filtro). */
    fun tags(ports: List<PortEntry>): List<String> =
        ports.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    /** Las plataformas originales presentes, Nintendo primero. */
    fun platforms(ports: List<PortEntry>): List<String> =
        ports.map { it.original.platform }.distinct().sortedWith(compareBy<String> { it !in NINTENDO }.thenBy { it })
}
