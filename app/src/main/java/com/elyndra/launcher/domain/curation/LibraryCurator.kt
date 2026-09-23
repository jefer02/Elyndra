package com.elyndra.launcher.domain.curation

import com.elyndra.launcher.domain.Game
import com.elyndra.launcher.library.Names

/** Varias entradas que son el mismo archivo o el mismo juego. */
data class DuplicateGroup(
    val kind: Kind,
    val systemId: String,
    val title: String,
    /** Claves de juego, la "buena" primero (verificada, sin fallos, más reciente). */
    val keys: List<String>,
) {
    enum class Kind {
        /** Mismo hash: copias idénticas del mismo archivo. */
        SameFile,

        /** Mismo juego y región en versiones distintas (revisiones, volcados). */
        Versions,
    }
}

/** Un juego con el mismo título en varias regiones. */
data class RegionGroup(val systemId: String, val title: String, val regions: List<String>, val keys: List<String>)

/** Un juego de varios discos al que le faltan discos. */
data class IncompleteSet(
    val systemId: String,
    val title: String,
    val keys: List<String>,
    val present: List<Int>,
    val missing: List<Int>,
    val total: Int?,
)

/** Una ROM con un nombre que no ayuda (ni al usuario ni a los servicios de metadatos). */
data class NamingIssue(val key: String, val reason: Reason, val suggestion: String?) {
    enum class Reason {
        /** "Final_Fantasy_VII", "final.fantasy.vii": nombre de escena. */
        Scene,

        /** "SLUS-00892": número de serie del disco en vez de título. */
        Serial,

        /** Dos o tres letras, o solo números. */
        Cryptic,

        /** Volcado marcado como malo ([b]). */
        BadDump,

        /** Se buscó en los servicios y ninguno lo reconoció. */
        Unmatched,
    }
}

/** Juegos de una misma saga. */
data class Series(val name: String, val keys: List<String>)

data class CurationReport(
    val duplicates: List<DuplicateGroup> = emptyList(),
    val regions: List<RegionGroup> = emptyList(),
    val incomplete: List<IncompleteSet> = emptyList(),
    val naming: List<NamingIssue> = emptyList(),
    val series: List<Series> = emptyList(),
) {
    val issueCount: Int get() = duplicates.size + incomplete.size + naming.size

    fun seriesOf(key: String): Series? = series.firstOrNull { key in it.keys }
}

/**
 * Revisión de la biblioteca: duplicados, regiones mezcladas, juegos de varios
 * discos incompletos, nombres que no ayudan y agrupación por sagas.
 *
 * Solo mira y cuenta. Elyndra nunca toca los archivos: Masha lo explica y el
 * usuario decide (quitar de la biblioteca, renombrar en su gestor…).
 */
object LibraryCurator {

    private val SCENE = Regex("""^[A-Za-z0-9]+(?:[._][A-Za-z0-9]+){2,}$""")
    private val SERIAL = Regex("""^[A-Z]{4}[-_. ]?[0-9]{3}[._]?[0-9]{2}$""")
    private val NUMBER_TOKEN = Regex("""^x?[0-9]+$""")
    private val SEPARATOR = Regex("""\s*(?::|\s-\s|\s–\s|\s—\s)\s*""")

    /** Palabras que no dan nombre a una saga por sí solas. */
    private val GENERIC_KEYS = setOf(
        "the", "a", "an", "game", "games", "super", "new", "pro", "world", "classic", "classics",
        "collection", "ultimate", "deluxe", "edition", "hd", "remastered", "gold", "plus",
    )

    fun analyze(games: List<Game>): CurationReport {
        val roms = games.filter { it.rom != null }
        val tags = roms.associate { it.key to RomTags.parse(it.rom!!.fileName, stripExtension = !it.rom.isDirectory) }
        return CurationReport(
            duplicates = duplicates(roms, tags),
            regions = regions(roms, tags),
            incomplete = incomplete(roms, tags),
            naming = naming(roms, tags),
            series = series(games, tags),
        )
    }

    private fun duplicates(roms: List<Game>, tags: Map<String, RomTags>): List<DuplicateGroup> {
        val out = ArrayList<DuplicateGroup>()
        val sameFile = HashSet<String>()
        // Copias idénticas: mismo hash (el más fuerte que haya), mismo sistema.
        roms.groupBy { g ->
            val h = g.rom!!.hashes
            when {
                h?.md5 != null -> "md5:${h.md5}"
                h?.sha1 != null -> "sha1:${h.sha1}"
                h?.crc != null -> "crc:${h.crc}:${g.rom.size}"
                else -> null
            }
        }.forEach { (hash, list) ->
            if (hash == null || list.size < 2) return@forEach
            val ordered = best(list, tags)
            out += DuplicateGroup(DuplicateGroup.Kind.SameFile, list.first().systemId.orEmpty(), ordered.first().title, ordered.map { it.key })
            sameFile += list.map { it.key }
        }
        // Mismo juego, misma región, varias versiones (revisiones, volcados buenos y malos).
        roms.filterNot { it.key in sameFile }
            .groupBy { g -> versionKey(g, tags.getValue(g.key)) }
            .forEach { (key, list) ->
                if (key == null || list.size < 2) return@forEach
                val regionSets = list.map { tags.getValue(it.key).regions }.toSet()
                if (regionSets.size != 1) return@forEach
                val ordered = best(list, tags)
                out += DuplicateGroup(DuplicateGroup.Kind.Versions, list.first().systemId.orEmpty(), tags.getValue(ordered.first().key).baseTitle, ordered.map { it.key })
            }
        return out.sortedBy { it.title.lowercase() }
    }

    private fun regions(roms: List<Game>, tags: Map<String, RomTags>): List<RegionGroup> =
        roms.filter { tags.getValue(it.key).regions.isNotEmpty() }
            .groupBy { g -> versionKey(g, tags.getValue(g.key)) }
            .mapNotNull { (key, list) ->
                if (key == null || list.size < 2) return@mapNotNull null
                val regionsByGame = list.map { tags.getValue(it.key).regions }
                if (regionsByGame.toSet().size < 2) return@mapNotNull null
                RegionGroup(
                    systemId = list.first().systemId.orEmpty(),
                    title = tags.getValue(list.first().key).baseTitle,
                    regions = regionsByGame.flatten().distinct(),
                    keys = list.map { it.key },
                )
            }
            .sortedBy { it.title.lowercase() }

    private fun incomplete(roms: List<Game>, tags: Map<String, RomTags>): List<IncompleteSet> =
        roms.filter { tags.getValue(it.key).disc != null }
            .groupBy { g ->
                val t = tags.getValue(g.key)
                // La región entra en la clave: el disco 2 europeo no completa el juego americano.
                "${g.systemId}|${t.groupKey}|${t.regions.sorted().joinToString(",")}"
            }
            .mapNotNull { (_, list) ->
                val discs = list.mapNotNull { tags.getValue(it.key).disc }.toSortedSet()
                val total = list.mapNotNull { tags.getValue(it.key).discTotal }.maxOrNull()
                val last = maxOf(total ?: 0, discs.last())
                val missing = (1..last).filterNot { it in discs }
                if (missing.isEmpty()) return@mapNotNull null
                IncompleteSet(
                    systemId = list.first().systemId.orEmpty(),
                    title = tags.getValue(list.first().key).baseTitle,
                    keys = list.sortedBy { tags.getValue(it.key).disc }.map { it.key },
                    present = discs.toList(),
                    missing = missing,
                    total = total,
                )
            }
            .sortedBy { it.title.lowercase() }

    private fun naming(roms: List<Game>, tags: Map<String, RomTags>): List<NamingIssue> = roms.mapNotNull { g ->
        val rom = g.rom!!
        val t = tags.getValue(g.key)
        val base = if (rom.isDirectory) rom.fileName else rom.fileName.substringBeforeLast('.')
        val bare = base.replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), "").trim()
        val suggestion = g.meta.name?.takeIf { it.isNotBlank() && !it.equals(t.baseTitle, ignoreCase = true) }
        val reason = when {
            t.badDump -> NamingIssue.Reason.BadDump
            SERIAL.matches(bare.uppercase()) && bare.any { it.isDigit() } -> NamingIssue.Reason.Serial
            !bare.contains(' ') && SCENE.matches(bare) -> NamingIssue.Reason.Scene
            Names.normalize(t.baseTitle).replace(" ", "").let { it.length <= 3 || it.all(Char::isDigit) } -> NamingIssue.Reason.Cryptic
            g.meta.scrapedAt > 0 && !g.meta.matched -> NamingIssue.Reason.Unmatched
            else -> null
        } ?: return@mapNotNull null
        NamingIssue(g.key, reason, suggestion)
    }

    /**
     * Sagas: juegos que comparten el principio del título ("Final Fantasy VII"
     * y "Final Fantasy IX", "Castlevania: Symphony of the Night" y
     * "Castlevania: Aria of Sorrow"). Una saga necesita al menos dos juegos
     * distintos; dos regiones del mismo juego no lo son.
     */
    fun series(games: List<Game>, tags: Map<String, RomTags> = emptyMap()): List<Series> {
        val baseTitles = games.associate { g -> g.key to (tags[g.key]?.baseTitle ?: g.title) }
        val keyed = games.mapNotNull { g ->
            val title = g.meta.name?.takeIf { it.isNotBlank() } ?: baseTitles.getValue(g.key)
            seriesKey(title)?.let { it to g }
        }
        val groups = keyed.groupBy({ it.first }, { it.second }).toMutableMap()
        // "resident evil code veronica" entra en "resident evil" si esa saga existe.
        for (key in groups.keys.sortedByDescending { it.length }) {
            val parent = groups.keys.filter { it != key && key.startsWith("$it ") }.maxByOrNull { it.length } ?: continue
            groups[parent] = groups.getValue(parent) + groups.getValue(key)
            groups.remove(key)
        }
        return groups.mapNotNull { (key, list) ->
            val distinct = list.distinctBy { Names.normalize(baseTitles.getValue(it.key)) }
            if (distinct.size < 2) return@mapNotNull null
            val ordered = list.sortedWith(
                compareBy<Game>({ it.meta.releaseDate ?: "9999" }, { Names.normalize(baseTitles.getValue(it.key)) }),
            )
            Series(displayName(key, ordered.map { it.meta.name ?: baseTitles.getValue(it.key) }), ordered.map { it.key })
        }.sortedBy { it.name.lowercase() }
    }

    /** Clave de saga: el título hasta el primer separador y sin numeración final. */
    fun seriesKey(title: String): String? {
        val head = SEPARATOR.split(title, limit = 2).first()
        val tokens = Names.normalize(head).split(' ').filter { it.isNotEmpty() }.toMutableList()
        if (tokens.firstOrNull() == "the") tokens.removeAt(0)
        // Se corta en el primer número a partir de la segunda palabra: "final fantasy 7" → "final fantasy".
        val cut = tokens.indexOfFirst { NUMBER_TOKEN.matches(it) }.let { if (it <= 0) tokens.size else it }
        val key = tokens.take(cut).joinToString(" ")
        if (key.length < 4 || key in GENERIC_KEYS) return null
        return key
    }

    /** "final fantasy" → "Final Fantasy", con la ortografía del primer juego de la saga. */
    private fun displayName(key: String, titles: List<String>): String {
        val words = key.split(' ').size
        for (t in titles) {
            val head = SEPARATOR.split(t, limit = 2).first().removePrefix("The ").trim()
            val candidate = head.split(Regex("""\s+""")).take(words).joinToString(" ").trimEnd(':', '-', ',', '.')
            if (Names.normalize(candidate) == key) return candidate
        }
        return key.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    }

    /** Juego + disco, sin región ni revisión: lo que tienen en común las versiones de un mismo juego. */
    private fun versionKey(g: Game, t: RomTags): String? {
        val title = t.groupKey
        if (title.isBlank()) return null
        return "${g.systemId}|$title|${t.disc ?: 0}"
    }

    /** La mejor copia primero: verificada, sin fallo de volcado, revisión más alta, la más jugada. */
    private fun best(list: List<Game>, tags: Map<String, RomTags>): List<Game> = list.sortedWith(
        compareByDescending<Game> { tags[it.key]?.verified == true }
            .thenBy { tags[it.key]?.badDump == true }
            .thenByDescending { tags[it.key]?.revision.orEmpty() }
            .thenByDescending { it.stats.minutes },
    )
}
