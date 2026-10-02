package com.elyndra.launcher.library

/* ─────────────────────────────────────────────────────────────
   PlayStation 4: juegos extraídos por Bachata S4 (fork de shadPS4).

   Bachata instala un .pkg extrayéndolo en la misma carpeta donde está
   el paquete, así que la carpeta de PS4 del usuario tiene a la vez
   los .pkg y las carpetas de los juegos ya extraídos:

       PS4/
         Bloodborne.pkg
         CUSA00900/            ← juego extraído (el nombre no importa)
           eboot.bin
           sce_sys/
             param.sfo         ← TITLE, TITLE_ID, APP_VER, CATEGORY
             icon0.png         ← icono
             pic1.png          ← fondo
         .bachata-import-…/    ← extracción en curso: se ignora

   Un juego se reconoce por su contenido (sce_sys/param.sfo y
   eboot.bin), nunca por el nombre de la carpeta. Del .pkg solo se
   leen los primeros 0x80 bytes de su cabecera, que van en claro: el
   Content ID y los flags. Elyndra nunca descifra ni extrae un .pkg.

   Todo esto es Kotlin puro: se prueba en la JVM con bytes sintéticos.
   ───────────────────────────────────────────────────────────── */

/**
 * Lector de PARAM.SFO (formato PSF de Sony).
 *
 * Cabecera de 0x14 bytes: magia `\0PSF` (big-endian), versión, desplazamiento
 * de la tabla de claves, de la tabla de datos y número de entradas (estos
 * cuatro en little-endian). Cada entrada ocupa 16 bytes: desplazamiento de la
 * clave (u16), formato (u16), longitud usada (u32), longitud máxima (u32) y
 * desplazamiento del dato (u32). Formatos: 0x0204 texto UTF-8 terminado en
 * NUL, 0x0004 texto sin terminar, 0x0404 entero de 32 bits.
 */
object Sfo {

    data class Params(val values: Map<String, String>) {
        val title: String? get() = values["TITLE"]?.takeIf { it.isNotBlank() }
        val titleId: String? get() = values["TITLE_ID"]?.takeIf { it.isNotBlank() }
        val appVer: String? get() = values["APP_VER"]?.takeIf { it.isNotBlank() }
        val category: String? get() = values["CATEGORY"]?.takeIf { it.isNotBlank() }
        val contentId: String? get() = values["CONTENT_ID"]?.takeIf { it.isNotBlank() }
    }

    private const val HEADER = 0x14
    private const val ENTRY = 0x10
    private const val FMT_INT = 0x0404

    /** Tamaño máximo razonable de un PARAM.SFO (los reales rondan 1–2 KB). */
    const val MAX_BYTES = 64 * 1024

    fun parse(bytes: ByteArray): Params? {
        if (bytes.size < HEADER) return null
        if (bytes[0] != 0.toByte() || bytes[1] != 'P'.code.toByte() || bytes[2] != 'S'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
            return null
        }
        val keyTable = le32(bytes, 8)
        val dataTable = le32(bytes, 12)
        val count = le32(bytes, 16)
        if (keyTable < 0 || dataTable < 0 || count < 0 || count > 256) return null
        if (HEADER + count.toLong() * ENTRY > bytes.size) return null
        val out = LinkedHashMap<String, String>()
        for (i in 0 until count) {
            val e = HEADER + i * ENTRY
            val keyAt = keyTable + le16(bytes, e)
            val fmt = le16(bytes, e + 2)
            val len = le32(bytes, e + 4)
            val dataAt = dataTable + le32(bytes, e + 12)
            val key = cString(bytes, keyAt, 64) ?: continue
            if (len < 0 || dataAt < 0 || dataAt.toLong() + len > bytes.size) continue
            out[key] = if (fmt == FMT_INT && len >= 4) {
                le32(bytes, dataAt).toString()
            } else {
                // Texto: la longitud incluye el NUL final (y a veces relleno).
                var end = dataAt + len
                while (end > dataAt && bytes[end - 1] == 0.toByte()) end--
                String(bytes, dataAt, end - dataAt, Charsets.UTF_8).trim()
            }
        }
        return Params(out)
    }

    private fun cString(b: ByteArray, at: Int, max: Int): String? {
        if (at < 0 || at >= b.size) return null
        var end = at
        while (end < b.size && end - at < max && b[end] != 0.toByte()) end++
        return if (end == at) null else String(b, at, end - at, Charsets.US_ASCII)
    }

    private fun le16(b: ByteArray, at: Int): Int =
        if (at + 2 > b.size) -1 else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int): Int =
        if (at < 0 || at + 4 > b.size) -1
        else (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)
}

/**
 * Cabecera de un .pkg de PS4: solo los primeros [HEADER_BYTES] bytes, que van
 * en claro. Big-endian. Magia `\x7FCNT` en 0x00, Content ID de 36 bytes en 0x40
 * ("UP9000-CUSA00900_00-BLOODBORNE0000000": el CUSA empieza en 0x47), tipo de
 * contenido en 0x74 y flags de contenido en 0x78 (ver shadPS4, `pkg.h`).
 */
object PkgHeader {

    const val HEADER_BYTES = 0x80

    enum class Kind { Game, Patch, Addon }

    data class Info(val contentId: String, val titleId: String, val kind: Kind)

    private const val CONTENT_ID_AT = 0x40
    private const val CONTENT_ID_LEN = 0x24
    private const val CONTENT_TYPE_AT = 0x74
    private const val CONTENT_FLAGS_AT = 0x78

    /** Tipos de contenido de un DLC: "AC" (additional content) y "AL" (su licencia). */
    private const val TYPE_ADDON = 0x1B
    private const val TYPE_ADDON_LICENSE = 0x1C

    /** Flags que marcan un parche: primero, siguiente, delta o acumulativo. */
    private const val FIRST_PATCH = 0x00100000
    private const val SUBSEQUENT_PATCH = 0x40000000

    fun parse(bytes: ByteArray): Info? {
        if (bytes.size < HEADER_BYTES) return null
        if ((bytes[0].toInt() and 0xFF) != 0x7F || bytes[1] != 'C'.code.toByte() || bytes[2] != 'N'.code.toByte() || bytes[3] != 'T'.code.toByte()) {
            return null
        }
        var end = CONTENT_ID_AT
        while (end < CONTENT_ID_AT + CONTENT_ID_LEN && bytes[end] != 0.toByte()) end++
        val contentId = String(bytes, CONTENT_ID_AT, end - CONTENT_ID_AT, Charsets.US_ASCII)
        val titleId = Ps4.titleIdOfContentId(contentId) ?: return null
        val type = be32(bytes, CONTENT_TYPE_AT)
        val flags = be32(bytes, CONTENT_FLAGS_AT)
        val kind = when {
            type == TYPE_ADDON || type == TYPE_ADDON_LICENSE -> Kind.Addon
            flags and (FIRST_PATCH or SUBSEQUENT_PATCH) != 0 -> Kind.Patch
            else -> Kind.Game
        }
        return Info(contentId, titleId, kind)
    }

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}

object Ps4 {

    const val SYSTEM_ID = "ps4"

    /** Id de juego de PS4 (CUSA00900, PLAS10001…): 4 letras y 5 cifras, como exige Bachata. */
    private val TITLE_ID = Regex("^[A-Z]{4}\\d{5}$")

    fun isTitleId(id: String?): Boolean = id != null && TITLE_ID.matches(id)

    /** "UP9000-CUSA00900_00-BLOODBORNE0000000" → "CUSA00900" (posiciones 7..15). */
    fun titleIdOfContentId(contentId: String): String? =
        contentId.takeIf { it.length >= 16 && it[6] == '-' }?.substring(7, 16)?.takeIf(::isTitleId)

    /**
     * Carpetas que nunca son juegos: ocultas y las temporales de Bachata
     * (`.bachata-import-<uuid>`, `.staging-*`, `.bachata-write-check-*`), que
     * son una extracción o una comprobación a medias.
     */
    fun isIgnoredDir(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith(".") || TEMP_PREFIXES.any { n.startsWith(it) }
    }

    private val TEMP_PREFIXES = listOf("bachata-import-", "staging-", "staging_", "bachata-write-check-")

    /** ¿Tiene lo que tiene un juego extraído? `sce_sys/` y `eboot.bin` (el PARAM.SFO se lee aparte). */
    fun looksLikeGameDir(children: List<Pair<String, Boolean>>): Boolean =
        children.any { (name, dir) -> dir && name.equals("sce_sys", ignoreCase = true) } &&
            children.any { (name, dir) -> !dir && name.equals("eboot.bin", ignoreCase = true) }

    /** Categoría del PARAM.SFO. */
    enum class Category { Base, Patch, Addon, Other }

    fun categoryOf(value: String?): Category {
        val c = value?.lowercase()?.trim().orEmpty()
        return when {
            c.startsWith("gd") -> Category.Base
            c.startsWith("gp") -> Category.Patch
            c.startsWith("ac") -> Category.Addon
            // Sin categoría: si tiene eboot.bin se trata como juego (lo decide quien llama).
            c.isEmpty() -> Category.Base
            else -> Category.Other
        }
    }

    /** Una carpeta con `sce_sys/param.sfo`, tal como la encontró el análisis. */
    data class GameDir(
        val docId: String,
        val name: String,
        val relPath: String,
        val modified: Long,
        val hasEboot: Boolean,
        val params: Sfo.Params,
    )

    /** Un .pkg y lo que dice su cabecera (null si no se pudo leer). */
    data class PkgFile(
        val docId: String,
        val name: String,
        val size: Long,
        val modified: Long,
        val info: PkgHeader.Info?,
    )

    /** Un juego de la biblioteca: su carpeta base y las actualizaciones que tenga aparte. */
    data class Game(
        val base: GameDir,
        val titleId: String,
        val title: String,
        /** La versión más alta entre la base y sus actualizaciones. */
        val appVer: String?,
        val updates: List<GameDir>,
    ) {
        val modified: Long get() = maxOf(base.modified, updates.maxOfOrNull { it.modified } ?: 0L)
    }

    data class Resolved(
        val games: List<Game>,
        /** Paquetes de juego cuyo juego aún no está extraído (se ofrecen para instalar en Bachata). */
        val notInstalled: List<PkgFile>,
    )

    /**
     * De lo encontrado en la carpeta a la biblioteca.
     *
     * - Solo cuentan como juegos las carpetas base (CATEGORY gd, con eboot.bin).
     * - Una actualización (gp) en su propia carpeta se agrupa con su base por
     *   TITLE_ID; sin base, no se enseña.
     * - Si Bachata fundió la actualización en la carpeta del juego, su
     *   PARAM.SFO pasa a ser el del parche (gp): una carpeta gp con eboot.bin
     *   y sin base aparte es el juego. Las de nombre `-UPDATE`/`-patch`
     *   (actualización suelta de shadPS4) no.
     * - Los DLC (ac) no son juegos: se ignoran.
     * - Dos carpetas base con el mismo TITLE_ID son el mismo juego: gana la
     *   primera por ruta (el análisis es estable).
     * - Un .pkg cuyo juego ya está extraído no se enseña. Uno de juego sin
     *   extraer va a [Resolved.notInstalled]; parches y DLC sueltos no.
     */
    fun resolve(dirs: List<GameDir>, pkgs: List<PkgFile>): Resolved {
        val sorted = dirs.sortedBy { it.relPath.lowercase() }
        val bases = LinkedHashMap<String, GameDir>()
        val updates = HashMap<String, MutableList<GameDir>>()
        for (d in sorted) {
            val id = d.params.titleId?.uppercase()?.takeIf(::isTitleId) ?: continue
            when (categoryOf(d.params.category)) {
                Category.Base -> if (d.hasEboot) bases.putIfAbsent(id, d)
                Category.Patch -> updates.getOrPut(id) { ArrayList() } += d
                Category.Addon, Category.Other -> Unit
            }
        }
        // Bachata funde una actualización en la carpeta del juego y el PARAM.SFO
        // que queda es el del parche (CATEGORY gp). Sin carpeta base aparte, la
        // carpeta de parche que tiene eboot.bin ES el juego, ya actualizado.
        for ((id, ups) in updates) {
            if (id in bases) continue
            val merged = ups.firstOrNull { it.hasEboot && !isSeparateUpdateFolder(it.name) } ?: continue
            bases[id] = merged
            ups.remove(merged)
        }
        val games = bases.map { (id, base) ->
            val ups = updates[id].orEmpty()
            Game(
                base = base,
                titleId = id,
                title = base.params.title ?: ups.firstNotNullOfOrNull { it.params.title } ?: base.name,
                appVer = (listOf(base) + ups).mapNotNull { it.params.appVer }.maxWithOrNull(::compareVersions),
                updates = ups,
            )
        }
        val notInstalled = pkgs.filter { p ->
            val info = p.info ?: return@filter false
            info.kind == PkgHeader.Kind.Game && info.titleId !in bases
        }.distinctBy { it.info!!.titleId }
        return Resolved(games, notInstalled)
    }

    /** Carpeta de actualización suelta al estilo de shadPS4 ("CUSA01715-UPDATE", "…-patch"). */
    fun isSeparateUpdateFolder(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith("-update") || n.endsWith("_update") || n.endsWith("-patch") || n.endsWith("_patch")
    }

    /** "01.09" frente a "01.10": por números, no por texto. */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.').map { it.trim().toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.trim().toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
