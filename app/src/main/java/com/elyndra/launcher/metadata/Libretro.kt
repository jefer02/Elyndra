package com.elyndra.launcher.metadata

import com.elyndra.launcher.library.Names
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/* ─────────────────────────────────────────────────────────────
   Carátulas, capturas y pantallas de título de libretro
   (thumbnails.libretro.com): sin cuenta ni clave.

   Cada sistema es una carpeta con Named_Boxarts, Named_Snaps y
   Named_Titles; cada imagen se llama como el juego en No-Intro
   ("Super Mario Bros. (World).png"), con los caracteres de
   [LibretroNames.fileName] cambiados por _.

   Emparejar, de estricto a flexible:
     1. El nombre del archivo tal cual (muchas colecciones ya vienen
        con nombres No-Intro): una petición HEAD y listo.
     2. Si no, la lista de la carpeta (cacheada un mes) y, en ella,
        el mismo título sin etiquetas de región o revisión.
     3. Si tampoco, el parecido de títulos, solo por encima de un
        umbral alto, con los números de secuela idénticos y sin
        empate con otro juego. Una carátula equivocada es peor que
        ninguna.
   ───────────────────────────────────────────────────────────── */

object LibretroNames {

    const val HOST = "https://thumbnails.libretro.com"

    /** Sistema de Elyndra → carpeta de libretro. Arcade/Neo Geo usan nombres de romset: no casan por título. */
    private val FOLDERS = mapOf(
        "ps2" to "Sony - PlayStation 2", "ps3" to "Sony - PlayStation 3", "ps4" to "Sony - PlayStation 4",
        "psp" to "Sony - PlayStation Portable", "psvita" to "Sony - PlayStation Vita", "psx" to "Sony - PlayStation",
        "xbox360" to "Microsoft - Xbox 360", "xbox" to "Microsoft - Xbox",
        "n64" to "Nintendo - Nintendo 64", "nds" to "Nintendo - Nintendo DS", "n3ds" to "Nintendo - Nintendo 3DS",
        "gc" to "Nintendo - GameCube", "wii" to "Nintendo - Wii", "wiiu" to "Nintendo - Wii U",
        "gba" to "Nintendo - Game Boy Advance", "gbc" to "Nintendo - Game Boy Color", "gb" to "Nintendo - Game Boy",
        "nes" to "Nintendo - Nintendo Entertainment System", "fds" to "Nintendo - Family Computer Disk System",
        "snes" to "Nintendo - Super Nintendo Entertainment System", "virtualboy" to "Nintendo - Virtual Boy",
        "megadrive" to "Sega - Mega Drive - Genesis", "mastersystem" to "Sega - Master System - Mark III",
        "gamegear" to "Sega - Game Gear", "segacd" to "Sega - Mega-CD - Sega CD", "sega32x" to "Sega - 32X",
        "saturn" to "Sega - Saturn", "dreamcast" to "Sega - Dreamcast", "sg1000" to "Sega - SG-1000",
        "pcengine" to "NEC - PC Engine - TurboGrafx 16", "pcenginecd" to "NEC - PC Engine CD - TurboGrafx-CD",
        "atari2600" to "Atari - 2600", "atari7800" to "Atari - 7800", "atarilynx" to "Atari - Lynx",
        "atarijaguar" to "Atari - Jaguar", "ngp" to "SNK - Neo Geo Pocket", "ngpc" to "SNK - Neo Geo Pocket Color",
        "wonderswan" to "Bandai - WonderSwan", "wonderswancolor" to "Bandai - WonderSwan Color",
        "3do" to "The 3DO Company - 3DO", "msx" to "Microsoft - MSX", "c64" to "Commodore - 64", "dos" to "DOS",
    )

    fun folderFor(systemId: String): String? = FOLDERS[systemId]

    enum class Kind(val dir: String) { Boxart("Named_Boxarts"), Snap("Named_Snaps"), Title("Named_Titles") }

    /** La regla de libretro para nombres de archivo: & * / : ` < > ? \ | se cambian por _. */
    fun fileName(gameName: String): String = gameName.map { if (it in "&*/:`<>?\\|") '_' else it }.joinToString("")

    private fun segment(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun url(folder: String, kind: Kind, gameName: String): String =
        "$HOST/${segment(folder)}/${kind.dir}/${segment(fileName(gameName))}.png"

    fun listingUrl(folder: String, kind: Kind = Kind.Boxart): String = "$HOST/${segment(folder)}/${kind.dir}/"

    /** Nombres (sin .png) del índice HTML de una carpeta. */
    fun parseListing(html: String): List<String> =
        Regex("""href="([^"?/][^"]*?)\.png"""").findAll(html)
            .mapNotNull { runCatching { URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8") }.getOrNull() }
            .distinct()
            .toList()

    /** Lo que queda de un nombre para comparar: sin extensión, sin etiquetas (región, revisión), normalizado. */
    fun baseKey(name: String): String = Names.normalize(Names.cleanTitle(fileName(name), stripExtension = false))

    private fun stem(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        // Solo se quita una extensión de verdad (2–4 letras), no el final de "Super Mario Bros."
        return if (dot > 0 && fileName.length - dot - 1 in 2..4 && fileName.substring(dot + 1).all { it.isLetterOrDigit() }) {
            fileName.substring(0, dot)
        } else {
            fileName
        }
    }

    /** Candidato al nombre exacto: el nombre del archivo de la ROM sin extensión. */
    fun exactCandidate(romFileName: String): String = stem(romFileName).trim()

    private val DIGITS = Regex("""\d+""")

    /** Los números del título (secuelas: "Final Fantasy VII" ≠ "VIII", ya en dígitos tras normalizar). */
    private fun numbers(key: String): List<String> = DIGITS.findAll(key).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    /** Umbral del parecido para aceptar una carátula sin coincidencia exacta. */
    const val FUZZY_THRESHOLD = 0.92
    /** Ventaja mínima sobre el siguiente juego distinto: si no, es dudoso y no se pone nada. */
    const val FUZZY_MARGIN = 0.04

    /**
     * El nombre de libretro que corresponde a la ROM, o null si no hay uno
     * fiable. [preferredRegions], para elegir entre variantes del mismo juego.
     */
    fun match(romFileName: String, title: String, names: List<String>, preferredRegions: List<String> = DEFAULT_REGIONS): String? {
        if (names.isEmpty()) return null
        val exact = exactCandidate(romFileName)
        names.firstOrNull { it.equals(exact, ignoreCase = true) || it == fileName(exact) }?.let { return it }

        val wanted = listOf(baseKey(exact), baseKey(title)).filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return null
        val byKey = names.groupBy { baseKey(it) }

        // Mismo título sin etiquetas: la variante de la región preferida.
        for (w in wanted) byKey[w]?.let { return pickRegion(it, preferredRegions) }

        // Parecido alto, mismos números y sin empate.
        val scored = byKey.keys.asSequence()
            .map { k -> k to wanted.maxOf { w -> if (numbers(w) == numbers(k)) Names.similarity(w, k) else 0.0 } }
            .filter { it.second >= FUZZY_THRESHOLD - FUZZY_MARGIN }
            .sortedByDescending { it.second }
            .take(2)
            .toList()
        val best = scored.firstOrNull() ?: return null
        if (best.second < FUZZY_THRESHOLD) return null
        val second = scored.getOrNull(1)?.second ?: 0.0
        if (best.second - second < FUZZY_MARGIN) return null
        return pickRegion(byKey.getValue(best.first), preferredRegions)
    }

    /** Candidatos para el selector de arte: los títulos más parecidos (aquí elige el usuario). */
    fun candidates(title: String, names: List<String>, limit: Int = 12): List<String> {
        val w = baseKey(title)
        if (w.isEmpty()) return emptyList()
        return names.asSequence()
            .map { it to Names.similarity(w, baseKey(it)) }
            .filter { it.second >= 0.6 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    val DEFAULT_REGIONS = listOf("USA", "World", "Europe")

    fun regionsFor(lang: String): List<String> = when (lang) {
        "es" -> listOf("Spain", "Europe", "World", "USA")
        "fr" -> listOf("France", "Europe", "World", "USA")
        "de" -> listOf("Germany", "Europe", "World", "USA")
        "pt" -> listOf("Brazil", "Portugal", "Europe", "World", "USA")
        "ja" -> listOf("Japan", "World", "USA")
        else -> DEFAULT_REGIONS
    }

    private fun pickRegion(variants: List<String>, regions: List<String>): String {
        for (r in regions) variants.firstOrNull { v -> Regex("""\(([^)]*\b$r\b[^)]*)\)""").containsMatchIn(v) && !isOddVariant(v) }?.let { return it }
        return variants.firstOrNull { !isOddVariant(it) } ?: variants.first()
    }

    /** Betas, demos, prototipos: solo si no hay otra cosa. */
    private fun isOddVariant(name: String): Boolean =
        Regex("""\((Beta|Proto|Demo|Sample|Kiosk)[^)]*\)""", RegexOption.IGNORE_CASE).containsMatchIn(name)
}

/** Las tres imágenes de un juego en libretro (puede faltar alguna: la descarga lo dice). */
data class LibretroArt(val name: String, val boxart: String, val snap: String, val title: String)

class LibretroClient(private val cacheDir: File?) {

    private val limiter = RateLimiter(300)
    private val misses = MissCache(cacheDir?.let { File(it, "keyless/libretro_misses.txt") }, 7 * MissCache.DAY_MS)

    private fun listingFile(folder: String) = cacheDir?.let { File(it, "keyless/libretro_${folder.filter { c -> c.isLetterOrDigit() }}.txt") }

    /** Nombres de la carpeta de carátulas, de la caché (un mes) o de la red. */
    suspend fun names(folder: String): List<String> {
        val file = listingFile(folder)
        if (file != null && file.exists() && System.currentTimeMillis() - file.lastModified() < LISTING_TTL_MS) {
            runCatching { return file.readLines().filter { it.isNotBlank() } }
        }
        val result = KeylessHttp.get(LibretroNames.listingUrl(folder), limiter) ?: return file?.takeIf { it.exists() }?.readLines().orEmpty()
        if (result.code != 200) return file?.takeIf { it.exists() }?.readLines().orEmpty()
        val names = LibretroNames.parseListing(result.body)
        if (names.isNotEmpty() && file != null) runCatching {
            file.parentFile?.mkdirs()
            file.writeText(names.joinToString("\n"))
        }
        return names
    }

    /**
     * El arte de una ROM. Primero el nombre exacto (una HEAD); si no, la
     * lista de la carpeta. Lo que no se encuentra queda anotado una semana.
     */
    suspend fun find(systemId: String, romFileName: String, title: String, lang: String): LibretroArt? {
        val folder = LibretroNames.folderFor(systemId) ?: return null
        val missKey = "$systemId|${romFileName.lowercase()}"
        if (misses.isMiss(missKey)) return null
        val exact = LibretroNames.exactCandidate(romFileName)
        val name = when (KeylessHttp.exists(LibretroNames.url(folder, LibretroNames.Kind.Boxart, exact), limiter)) {
            true -> exact
            // Sin red no se puede decir que no esté: no se anota el fallo.
            null -> return null
            false -> LibretroNames.match(romFileName, title, names(folder), LibretroNames.regionsFor(lang))
        }
        if (name == null) {
            misses.markMiss(missKey)
            return null
        }
        return art(folder, name)
    }

    fun art(folder: String, name: String) = LibretroArt(
        name = name,
        boxart = LibretroNames.url(folder, LibretroNames.Kind.Boxart, name),
        snap = LibretroNames.url(folder, LibretroNames.Kind.Snap, name),
        title = LibretroNames.url(folder, LibretroNames.Kind.Title, name),
    )

    private companion object {
        const val LISTING_TTL_MS = 30 * MissCache.DAY_MS
    }
}
