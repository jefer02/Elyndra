package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.Systems

/*
 * Las filas de la portada de la tienda de ports y su búsqueda. Kotlin puro (StoreRowsTest).
 *
 * Todo va por juego, no por port: varios ports del mismo juego son una
 * sola cápsula ([GameGroup], ver PortGrouping) en cada fila y en la búsqueda.
 */

/** Las familias de plataformas de los chips (y de las filas de la portada). */
enum class PlatformGroup { Nintendo, PlayStation, Sega, Xbox, Pc }

/** Cómo se ordena la cuadrícula de resultados. */
enum class StoreSort { Name, RecentlyUpdated, Trust, Stars }

/**
 * Lo que el usuario filtra en la tienda. Sin nada activo se ve la portada
 * (filas); con algo, la cuadrícula de todos los juegos que lo cumplen.
 */
data class StoreFilter(
    val query: String = "",
    val groups: Set<PlatformGroup> = emptySet(),
    /** Géneros de [StoreRows.GENRES]. */
    val genres: Set<String> = emptySet(),
    /** Solo los que aún no tienen APK de Android. */
    val notOnAndroid: Boolean = false,
    val sort: StoreSort = StoreSort.Name,
    /**
     * Solo los de un sistema de la biblioteca ("Añadir juego" → "Buscar un
     * port" desde una carpeta): los que irían a una carpeta de [platform] (ver [inPlatform]).
     */
    val platform: String? = null,
) {
    val active: Boolean get() = query.isNotBlank() || groups.isNotEmpty() || genres.isNotEmpty() || notOnAndroid || platform != null

    /** Lo que quita "Atrás" antes de salir de la tienda: todo el filtro, con el orden elegido. */
    fun cleared(): StoreFilter = StoreFilter(sort = sort)
}

enum class StoreRowKind {
    Featured, BecauseYouHave, Nintendo, PlayStation, Sega, Xbox, Pc, RecentlyUpdated, UpdatesAvailable, Installed,

    /** Los que aún no tienen APK: la última fila de la portada. */
    ComingToAndroid,

    /** La cuadrícula de un filtro o una búsqueda. */
    Results,
}

/** Una fila de cápsulas, una por juego. [subject]: el juego de "Porque tienes…". */
data class StoreRow(val kind: StoreRowKind, val groups: List<GameGroup>, val subject: String? = null) {
    /** El port de cada cápsula. */
    val ports: List<PortEntry> get() = groups.map { it.lead }
}

object StoreRows {

    val NINTENDO = setOf("nes", "fds", "snes", "n64", "gc", "wii", "wiiu", "gb", "gbc", "gba", "nds", "n3ds", "switch", "switch2", "virtualboy")
    val PLAYSTATION = setOf("psx", "ps2", "ps3", "ps4", "psp", "psvita")
    val SEGA = setOf("sg1000", "mastersystem", "gamegear", "megadrive", "segacd", "sega32x", "saturn", "dreamcast")
    val XBOX = setOf("xbox", "xbox360")
    val PC = setOf("pc", "dos")

    /** La fila de plataforma de un juego, o null si su plataforma no tiene fila (sale en las demás y en la búsqueda). */
    fun platformRow(platform: String): StoreRowKind? = when (platform) {
        in NINTENDO -> StoreRowKind.Nintendo
        in PLAYSTATION -> StoreRowKind.PlayStation
        in SEGA -> StoreRowKind.Sega
        in XBOX -> StoreRowKind.Xbox
        in PC -> StoreRowKind.Pc
        else -> null
    }

    private val PLATFORM_ROWS = listOf(StoreRowKind.Nintendo, StoreRowKind.PlayStation, StoreRowKind.Sega, StoreRowKind.Xbox, StoreRowKind.Pc)

    fun group(platform: String): PlatformGroup? = when (platformRow(platform)) {
        StoreRowKind.Nintendo -> PlatformGroup.Nintendo
        StoreRowKind.PlayStation -> PlatformGroup.PlayStation
        StoreRowKind.Sega -> PlatformGroup.Sega
        StoreRowKind.Xbox -> PlatformGroup.Xbox
        StoreRowKind.Pc -> PlatformGroup.Pc
        else -> null
    }

    /**
     * Los pocos géneros que salen como chip, y los temas de GitHub que cuentan
     * como cada uno. Los temas sueltos ("game-port", "android-port"…) no son
     * chips: dicen cómo está hecho el port, no a qué se juega.
     */
    val GENRES: Map<String, Set<String>> = linkedMapOf(
        "platformer" to setOf("platformer", "platform", "platforming", "metroidvania", "3d-platformer"),
        "racing" to setOf("racing", "racing-game", "kart", "driving"),
        "rpg" to setOf("rpg", "jrpg", "roguelike", "roguelite", "role-playing", "crpg", "action-rpg"),
        "shooter" to setOf("shooter", "fps", "first-person-shooter", "shmup"),
        "strategy" to setOf("strategy", "rts", "tycoon", "city-builder", "turn-based-strategy", "4x"),
    )

    /** Los géneros de [p], de [GENRES] (por sus etiquetas). */
    fun genres(p: PortEntry): Set<String> {
        val tags = p.tags.mapTo(HashSet()) { it.lowercase() }
        return GENRES.filterValues { aliases -> aliases.any { it in tags } }.keys
    }

    /** Los géneros que salen como chip: los que tiene algún port de [ports], en el orden de [GENRES]. */
    fun genreChips(ports: List<PortEntry>): List<String> {
        val present = ports.flatMapTo(HashSet()) { genres(it) }
        return GENRES.keys.filter { it in present }
    }

    /**
     * L1/R1 en la tienda: la familia siguiente de [available], de una en una;
     * pasada la última (o antes de la primera), "Todos" (vacío).
     */
    fun cycleGroup(current: Set<PlatformGroup>, available: List<PlatformGroup>, delta: Int): Set<PlatformGroup> {
        if (available.isEmpty()) return emptySet()
        // Posición 0 = Todos; 1..n = cada familia.
        val at = current.singleOrNull()?.let { available.indexOf(it) + 1 } ?: 0
        val next = Math.floorMod(at + delta, available.size + 1)
        return if (next == 0) emptySet() else setOf(available[next - 1])
    }

    /** Las familias que tienen algún port, en el orden de los chips. */
    fun groupChips(ports: List<PortEntry>): List<PlatformGroup> {
        val present = ports.mapNotNullTo(HashSet()) { group(it.platform) }
        return PlatformGroup.entries.filter { it in present }
    }

    /** Cuántas cápsulas como mucho por fila (la portada es un escaparate, no la lista entera). */
    const val ROW_MAX = 12

    /**
     * Los ports que pasan el filtro, también los que aún no tienen APK (en
     * la cuadrícula y la búsqueda se ven; en la portada, solo en su fila).
     */
    fun visible(ports: List<PortEntry>, filter: StoreFilter): List<PortEntry> {
        val words = PortLookup.normalizeWords(filter.query)
        return ports.filter { p ->
            (!filter.notOnAndroid || !p.installable) &&
                (filter.platform == null || inPlatform(p, filter.platform)) &&
                (filter.groups.isEmpty() || group(p.platform) in filter.groups) &&
                (filter.genres.isEmpty() || genres(p).any { it in filter.genres }) &&
                (words.isEmpty() || words.all { w -> haystack(p).contains(w) })
        }
    }

    /**
     * La cuadrícula, ordenada por [sort]. Lo que se instala va siempre antes
     * que lo que aún no tiene APK; dentro, el orden elegido.
     */
    fun sorted(groups: List<GameGroup>, sort: StoreSort, publishedAt: Map<String, Long> = emptyMap()): List<GameGroup> {
        fun date(g: GameGroup): Long = g.ports.maxOf { publishedAt[it.id] ?: it.release.publishedAtMillis }
        val byName = compareBy<GameGroup> { PortLookup.normalizeTitle(it.game.name) }
        val trust = PortGrouping.order(publishedAt)
        val order = when (sort) {
            StoreSort.Name -> byName
            StoreSort.RecentlyUpdated -> compareByDescending<GameGroup> { date(it) }.then(byName)
            StoreSort.Trust -> Comparator<GameGroup> { a, b -> trust.compare(a.lead, b.lead) }.then(byName)
            StoreSort.Stars -> compareByDescending<GameGroup> { g -> g.ports.maxOf { it.trust.stars ?: -1 } }.then(byName)
        }
        return groups.sortedWith(compareByDescending<GameGroup> { g -> g.ports.any { it.installable } }.then(order))
    }

    /** Dónde busca la búsqueda: el juego original y el port (nombre, quién lo hace, repositorio). */
    private fun haystack(p: PortEntry): String =
        PortLookup.normalizeTitle(
            listOf(
                p.game.name,
                p.name,
                p.platform,
                Systems.byId(p.platform)?.name.orEmpty(),
                p.repo.slug,
                p.releaseRepo.slug,
                p.developer.orEmpty(),
                p.tags.joinToString(" "),
            ).joinToString(" "),
        )

    /**
     * La portada.
     *
     * Con un filtro activo, una sola fila de resultados, que se pinta como
     * cuadrícula ([sorted]). Si no: destacados, "Porque tienes <juego>" (lo de
     * la biblioteca), Nintendo, PlayStation, Sega, Xbox, PC, actualizados hace
     * poco ([publishedAt] por id, lo que ya se sepa; si no, la fecha del
     * catálogo), con actualización ([updateIds]), instalados y, la última,
     * "¿Llegará a Android?". Lo que aún no tiene APK solo sale en esa última.
     * Las filas vacías no salen.
     */
    fun home(
        ports: List<PortEntry>,
        filter: StoreFilter,
        owned: List<RomPortMatch>,
        installedIds: Set<String>,
        publishedAt: Map<String, Long>,
        updateIds: Set<String> = emptySet(),
    ): List<StoreRow> {
        val shown = visible(ports, filter)
        if (filter.active) {
            return listOf(StoreRow(StoreRowKind.Results, sorted(PortGrouping.group(shown, installedIds, publishedAt), filter.sort, publishedAt)))
        }
        val groups = PortGrouping.group(shown.filter { it.installable }, installedIds, publishedAt)
        // Un juego que ya se instala con otro port no se anuncia como "por llegar".
        val playable = groups.mapTo(HashSet()) { it.key }
        val coming = PortGrouping.group(shown.filter { !it.installable }, installedIds, publishedAt).filter { it.key !in playable }
        val byPort = HashMap<String, GameGroup>().apply { groups.forEach { g -> g.ports.forEach { put(it.id, g) } } }
        fun date(g: GameGroup): Long = g.ports.maxOf { publishedAt[it.id] ?: it.release.publishedAtMillis }

        val rows = mutableListOf<StoreRow>()
        rows += StoreRow(StoreRowKind.Featured, groups.filter { g -> g.ports.any { it.featured } }.ifEmpty { groups.take(5) }.take(ROW_MAX))
        val mine = owned.filter { it.port.id in byPort }
        if (mine.isNotEmpty()) {
            rows += StoreRow(
                StoreRowKind.BecauseYouHave,
                mine.mapNotNull { byPort[it.port.id] }.distinctBy { it.key }.take(ROW_MAX),
                subject = mine.first().rom.displayTitle,
            )
        }
        for (kind in PLATFORM_ROWS) {
            rows += StoreRow(kind, groups.filter { platformRow(it.game.originalPlatform) == kind }.take(ROW_MAX))
        }
        rows += StoreRow(StoreRowKind.RecentlyUpdated, groups.filter { date(it) > 0L }.sortedByDescending { date(it) }.take(ROW_MAX))
        // Todas las que tienen actualización, sin tope: "Actualizar todo" actúa sobre la fila entera.
        rows += StoreRow(StoreRowKind.UpdatesAvailable, groups.filter { g -> g.ports.any { it.id in updateIds && it.id in installedIds } })
        rows += StoreRow(StoreRowKind.Installed, groups.filter { g -> g.ports.any { it.id in installedIds } }.take(ROW_MAX))
        rows += StoreRow(StoreRowKind.ComingToAndroid, sorted(coming, StoreSort.Stars).take(ROW_MAX))
        return rows.filter { it.groups.isNotEmpty() }
    }

    /**
     * Lo que encuentra Masha (`find_port`), un juego por resultado: con
     * [query], lo que la busca por el nombre del juego o del port (también los
     * que aún no tienen APK, que Masha sabe explicar), los instalables
     * primero; sin ella, los juegos de lo que hay en la biblioteca
     * y, si no hay ninguno, nada.
     */
    fun find(ports: List<PortEntry>, query: String?, owned: List<RomPortMatch>, limit: Int = 8): List<GameGroup> {
        val found = if (query.isNullOrBlank()) {
            PortGrouping.group(owned.map { it.port }.distinctBy { it.id })
        } else {
            PortGrouping.group(visible(ports, StoreFilter(query = query)))
                .sortedByDescending { g -> g.ports.any { it.installable } }
        }
        return found.take(limit)
    }

    /**
     * ¿Va [p] a una carpeta de [systemId]? La plataforma del port como la
     * coloca la biblioteca (las desconocidas, a PC; ver PortPlacement), y los
     * de MS-DOS también en una de PC.
     */
    fun inPlatform(p: PortEntry, systemId: String): Boolean {
        val system = PortPlacement.systemFor(p.platform)
        return system == systemId || (systemId == "pc" && system == "dos")
    }

    /** Las plataformas originales presentes, Nintendo primero. */
    fun platforms(ports: List<PortEntry>): List<String> =
        ports.map { it.platform }.distinct().sortedWith(compareBy<String> { it !in NINTENDO }.thenBy { it })
}
