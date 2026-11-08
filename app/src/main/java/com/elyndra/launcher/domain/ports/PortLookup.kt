package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.FileHashes
import com.elyndra.launcher.data.Library
import com.elyndra.launcher.data.RomEntry
import java.text.Normalizer

/* Qué ports casan con las ROMs de la biblioteca. Kotlin puro (PortLookupTest). */

/** Cómo de seguro es que la ROM sea la que pide el port. */
enum class MatchStrength {
    /** Coincide un hash de los que da el catálogo: es ese volcado. */
    Hash,

    /** Mismo sistema y mismo título: probablemente, pero puede ser otra región o revisión. */
    Title,
}

data class RomPortMatch(val port: PortEntry, val rom: RomEntry, val strength: MatchStrength)

object RomMatch {

    /**
     * ¿[hashes] es el volcado [want]?
     *
     * Vale cualquiera de los hashes fuertes (SHA-1, MD5) que dé el catálogo;
     * el CRC solo cuenta si el catálogo no da otro. Sin distinguir mayúsculas.
     */
    fun matches(hashes: FileHashes?, want: RomHash): Boolean {
        if (hashes == null) return false
        if (eq(hashes.sha1, want.sha1) || eq(hashes.md5, want.md5)) return true
        return want.sha1.isNullOrBlank() && want.md5.isNullOrBlank() && eq(hashes.crc, want.crc)
    }

    private fun eq(have: String?, want: String?): Boolean =
        !have.isNullOrBlank() && !want.isNullOrBlank() && have.trim().equals(want.trim(), ignoreCase = true)
}

object PortLookup {

    private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
    private val NON_ALNUM = Regex("""[^a-z0-9]+""")
    private val ARTICLES = Regex("""\b(the|a|an)\b""")
    private val MARKS = Regex("""\p{M}+""")

    /**
     * Un título reducido a lo que importa para compararlo: sin regiones ni
     * revisiones entre paréntesis, sin acentos, sin artículos ingleses (los
     * No-Intro escriben "Legend of Zelda, The - …") y sin signos.
     */
    fun normalizeTitle(raw: String): String {
        val noTags = raw.replace(BRACKETS, " ")
        val folded = MARKS.replace(Normalizer.normalize(noTags, Normalizer.Form.NFD), "").lowercase()
        return folded.replace(ARTICLES, " ").replace(NON_ALNUM, "")
    }

    /** Las palabras de una búsqueda, cada una reducida como [normalizeTitle] (las vacías fuera). */
    fun normalizeWords(query: String): List<String> =
        query.split(Regex("""\s+""")).map { normalizeTitle(it) }.filter { it.isNotEmpty() }

    /** Los ports que casan con [rom], los de hash primero. */
    fun portsFor(rom: RomEntry, ports: List<PortEntry>): List<RomPortMatch> =
        ports.mapNotNull { match(it, rom) }.sortedBy { it.strength.ordinal }

    /** La mejor ROM de la biblioteca para [port] (hash antes que título; la más jugada antes), o null. */
    fun owned(port: PortEntry, library: Library): RomPortMatch? =
        library.roms.asSequence()
            .mapNotNull { match(port, it) }
            .sortedWith(compareBy<RomPortMatch> { it.strength.ordinal }.thenByDescending { it.rom.stats.lastPlayed })
            .firstOrNull()

    /** Un emparejamiento por port con algo en la biblioteca, los de lo último jugado primero. */
    fun becauseYouHave(library: Library, ports: List<PortEntry>): List<RomPortMatch> =
        ports.mapNotNull { owned(it, library) }
            .sortedWith(compareByDescending<RomPortMatch> { it.rom.stats.lastPlayed }.thenBy { it.strength.ordinal })

    private fun match(port: PortEntry, rom: RomEntry): RomPortMatch? {
        val wanted = port.requires.roms
        if (wanted.any { w -> (w.system == null || w.system == rom.systemId) && RomMatch.matches(rom.hashes, w) }) {
            return RomPortMatch(port, rom, MatchStrength.Hash)
        }
        if (rom.systemId != port.original.platform) return null
        val want = normalizeTitle(port.original.title)
        if (want.isEmpty()) return null
        val titles = listOfNotNull(rom.displayTitle, rom.title, rom.meta.name, rom.meta.libretroName)
        return if (titles.any { normalizeTitle(it) == want }) RomPortMatch(port, rom, MatchStrength.Title) else null
    }
}
