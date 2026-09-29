package com.elyndra.launcher.domain.curation

import com.elyndra.launcher.library.Names

/**
 * Lo que dice el nombre de un volcado: "Final Fantasy VII (USA) (Disc 2 of 3)
 * (Rev 1) [!].bin" → región USA, disco 2 de 3, revisión 1, verificado.
 *
 * Entiende las convenciones de No-Intro y Redump (países con nombre completo,
 * idiomas "(En,Fr,De)") y las de GoodTools ("(U)", "(E)", "[b1]", "[T+Spa]").
 */
data class RomTags(
    /** Título sin etiquetas ni extensión, listo para enseñar. */
    val baseTitle: String,
    /** Regiones normalizadas: "USA", "Europe", "Japan", "World"… */
    val regions: Set<String>,
    val languages: Set<String>,
    val disc: Int?,
    val discTotal: Int?,
    val revision: String?,
    val beta: Boolean = false,
    val prototype: Boolean = false,
    val demo: Boolean = false,
    val hack: Boolean = false,
    val badDump: Boolean = false,
    val verified: Boolean = false,
    val translation: String? = null,
    val unlicensed: Boolean = false,
) {
    /** Clave para agrupar volcados del mismo juego (sin región, revisión ni disco). */
    val groupKey: String get() = Names.normalize(baseTitle)

    companion object {
        private val GROUPS = Regex("""\(([^)]*)\)|\[([^\]]*)\]""")
        private val DISC = Regex("""^(?:disc|disk|cd|dvd)\s*([0-9]+)(?:\s*(?:of|/)\s*([0-9]+))?$""", RegexOption.IGNORE_CASE)
        private val DISC_ANYWHERE = Regex("""(?:disc|disk|cd)\s*([0-9]+)(?:\s*(?:of|/)\s*([0-9]+))?""", RegexOption.IGNORE_CASE)
        private val REVISION = Regex("""^(?:rev\s*([0-9a-z.]+)|v\s*([0-9][0-9a-z.]*))$""", RegexOption.IGNORE_CASE)
        private val LANGUAGE = Regex("""^[A-Z][a-z](?:-[A-Z][a-z])?$""")
        private val TRANSLATION = Regex("""^T[+-]([A-Za-z]{2,3})""")

        private val REGION_NAMES = mapOf(
            "usa" to "USA", "us" to "USA", "u" to "USA", "america" to "USA", "canada" to "USA",
            "europe" to "Europe", "eur" to "Europe", "e" to "Europe", "uk" to "Europe", "pal" to "Europe",
            "japan" to "Japan", "jpn" to "Japan", "j" to "Japan", "ntsc-j" to "Japan",
            "world" to "World", "w" to "World",
            "spain" to "Spain", "s" to "Spain",
            "france" to "France", "f" to "France",
            "germany" to "Germany", "g" to "Germany",
            "italy" to "Italy", "i" to "Italy",
            "korea" to "Korea", "k" to "Korea",
            "china" to "China", "ch" to "China", "hong kong" to "China", "taiwan" to "China",
            "brazil" to "Brazil", "b" to "Brazil",
            "australia" to "Australia", "a" to "Australia",
            "asia" to "Asia", "netherlands" to "Europe", "sweden" to "Europe", "scandinavia" to "Europe",
            "russia" to "Russia", "portugal" to "Europe",
        )

        /** GoodTools junta regiones en una sola sigla: "(UE)", "(JU)". */
        private val GOOD_COMBINED = mapOf('U' to "USA", 'E' to "Europe", 'J' to "Japan", 'W' to "World")

        fun parse(fileName: String, stripExtension: Boolean = true): RomTags {
            val name = if (stripExtension && fileName.contains('.')) fileName.substringBeforeLast('.') else fileName
            val regions = LinkedHashSet<String>()
            val languages = LinkedHashSet<String>()
            var disc: Int? = null
            var discTotal: Int? = null
            var revision: String? = null
            var beta = false
            var proto = false
            var demo = false
            var hack = false
            var bad = false
            var verified = false
            var translation: String? = null
            var unlicensed = false

            for (m in GROUPS.findAll(name)) {
                val paren = m.groups[1]?.value
                val bracket = m.groups[2]?.value
                if (bracket != null) {
                    val b = bracket.trim()
                    when {
                        b == "!" -> verified = true
                        b.startsWith("b", ignoreCase = false) && (b.length == 1 || b[1].isDigit() || b[1] == ' ') -> bad = true
                        b.startsWith("h") && (b.length == 1 || b[1].isDigit() || b[1] == ' ' || b[1] == 'I') -> hack = true
                        TRANSLATION.containsMatchIn(b) -> translation = TRANSLATION.find(b)?.groupValues?.get(1)
                        else -> DISC.matchEntire(b)?.let { d ->
                            disc = d.groupValues[1].toIntOrNull()
                            discTotal = d.groupValues[2].toIntOrNull()
                        }
                    }
                    continue
                }
                val content = paren?.trim().orEmpty()
                if (content.isEmpty()) continue
                val discTag = DISC.matchEntire(content)
                if (discTag != null) {
                    disc = discTag.groupValues[1].toIntOrNull()
                    discTotal = discTag.groupValues[2].toIntOrNull()
                    continue
                }
                val revisionTag = REVISION.matchEntire(content)
                if (revisionTag != null) {
                    revision = revisionTag.groupValues[1].ifEmpty { revisionTag.groupValues[2] }
                    continue
                }
                val lower = content.lowercase()
                when {
                    lower.startsWith("beta") -> beta = true
                    lower.startsWith("proto") -> proto = true
                    lower.startsWith("demo") || lower.startsWith("sample") || lower.startsWith("kiosk") -> demo = true
                    lower == "unl" || lower == "pirate" -> unlicensed = true
                    lower.contains("hack") -> hack = true
                    else -> {
                        val parts = content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        if (parts.isNotEmpty() && parts.all { LANGUAGE.matches(it) }) {
                            languages += parts.map { it.substringBefore('-').lowercase() }
                        } else {
                            val found = parts.mapNotNull { REGION_NAMES[it.lowercase()] }
                            if (found.isNotEmpty() && found.size == parts.size) {
                                regions += found
                            } else if (content.length in 2..3 && content.all { it in GOOD_COMBINED }) {
                                regions += content.map { GOOD_COMBINED.getValue(it) }
                            }
                        }
                    }
                }
            }
            // Discos sin paréntesis: "Final Fantasy VII CD2.bin".
            if (disc == null) {
                val tail = GROUPS.replace(name, " ")
                DISC_ANYWHERE.findAll(tail).lastOrNull()?.let { d ->
                    if (d.range.first > 0 && tail.getOrNull(d.range.first - 1)?.isLetterOrDigit() != true) {
                        disc = d.groupValues[1].toIntOrNull()
                        discTotal = d.groupValues[2].toIntOrNull()
                    }
                }
            }
            var base = Names.cleanTitle(name, stripExtension = false)
            if (disc != null) base = DISC_ANYWHERE.replace(base, " ").replace(Regex("""\s+"""), " ").trim().trimEnd('-', ' ')
            return RomTags(
                baseTitle = base.ifBlank { name },
                regions = regions,
                languages = languages,
                disc = disc,
                discTotal = discTotal,
                revision = revision,
                beta = beta,
                prototype = proto,
                demo = demo,
                hack = hack,
                badDump = bad,
                verified = verified,
                translation = translation,
                unlicensed = unlicensed,
            )
        }
    }
}
