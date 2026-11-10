package com.elyndra.launcher.domain.ports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/* ─────────────────────────────────────────────────────────────
   Catálogo de ports: la lista curada de ports nativos (decompilaciones,
   recompilaciones, reimplementaciones) publicados en GitHub.

   La fuente de verdad es un JSON en un repositorio (ver
   PortCatalogRepository) con una copia en assets/ports/catalog.json por
   si no hay red. Aquí solo hay datos y validación: Kotlin puro, se
   prueba en la JVM (PortCatalogTest).

   Nunca hay ROMs ni enlaces a ROMs: [Requirement] solo dice qué copia
   propia necesita el port y con qué hashes reconocerla en la biblioteca.

   Esquema 2 (lo escribe tools/ports-catalog): la identidad de cada entrada
   es el juego original ([PortGame], con su arte ya resuelto) y la
   confianza ([PortTrust]) la calcula el generador. El esquema 1 se sigue
   leyendo: [PortCatalogParser.upgrade] lo pasa al 2 antes de decodificar.
   ───────────────────────────────────────────────────────────── */

@Serializable
data class PortCatalog(
    val schema: Int = PortCatalogParser.SCHEMA,
    val updated: String? = null,
    val ports: List<PortEntry> = emptyList(),
)

/** Un repositorio de GitHub. */
@Serializable
data class GhRepo(val owner: String, val name: String) {
    val slug: String get() = "$owner/$name"
    val url: String get() = "https://github.com/$owner/$name"
}

/** El arte del juego original, en URLs https absolutas resueltas al generar el catálogo. */
@Serializable
data class GameArt(
    val cover: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val icon: String? = null,
    val screenshots: List<String> = emptyList(),
) {
    val urls: List<String> get() = listOfNotNull(cover, hero, logo, icon) + screenshots
}

/**
 * El juego original del que sale el port: es el nombre que se enseña en la
 * tienda y en la biblioteca. [originalPlatform] es el id de sistema de
 * Elyndra (`n64`, `xbox360`, `pc`…).
 */
@Serializable
data class PortGame(
    val name: String,
    val originalPlatform: String,
    val igdbId: Long? = null,
    val sgdbId: Long? = null,
    val steamAppId: Long? = null,
    val releaseYear: Int? = null,
    val art: GameArt = GameArt(),
)

/** La última release que vio el generador (para "actualizados hace poco" sin preguntar a GitHub). */
@Serializable
data class PortReleaseInfo(
    val tag: String? = null,
    /** ISO-8601, como lo da GitHub. */
    val publishedAt: String? = null,
    val prerelease: Boolean = false,
) {
    val publishedAtMillis: Long
        get() = publishedAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
}

/**
 * La confianza de un port, calculada por el generador: [verified] solo si el
 * repositorio tiene al menos un año, 100+ estrellas y 3+ colaboradores (o es
 * un fork de GitHub de uno que lo cumple, o está en trusted.yaml).
 */
@Serializable
data class PortTrust(
    val verified: Boolean = false,
    val reason: String? = null,
    val stars: Int? = null,
    val contributors: Int? = null,
    val createdAt: String? = null,
    /**
     * prerelease | check_requirements | debug_build | no_source | discovered.
     * Los catálogos de antes traían además `scan` y el indicador
     * `not_scanned` (VirusTotal, que ya no se usa): se leen y se ignoran.
     */
    val flags: List<String> = emptyList(),
) {
    val prerelease: Boolean get() = PRERELEASE in flags
    val checkRequirements: Boolean get() = CHECK_REQUIREMENTS in flags
    val debugBuild: Boolean get() = DEBUG_BUILD in flags

    /** El APK lo encontró la app después del catálogo (ver PortAvailability): nadie lo ha revisado. */
    val discovered: Boolean get() = DISCOVERED in flags

    companion object {
        const val PRERELEASE = "prerelease"
        const val CHECK_REQUIREMENTS = "check_requirements"
        const val DEBUG_BUILD = "debug_build"
        const val DISCOVERED = "discovered"
    }
}

/**
 * Una compilación del port para una plataforma.
 *
 * [asset] es una expresión regular sobre el nombre del archivo de la
 * release; [abis], el orden de preferencia ("universal" = el APK sin ABI).
 * [repo] manda sobre el del port (p. ej. el build de Windows sale del
 * repositorio original y el de Android de un fork).
 */
@Serializable
data class PortBuild(
    val platform: String,
    val repo: GhRepo? = null,
    val asset: String = DEFAULT_ASSET,
    val abis: List<String> = DEFAULT_ABIS,
    @SerialName("package") val packageName: String? = null,
    /** SHA-256 por nombre de archivo, si el catálogo lo fija (si no, vale el `digest` de la API). */
    val sha256: Map<String, String> = emptyMap(),
    /**
     * Si la release trae el APK dentro de un zip: expresión regular sobre el
     * nombre del APK de dentro (el zip es lo que casa con [asset]). Null = el
     * asset es el APK.
     */
    val archive: String? = null,
    /** SHA-256 del APK de dentro del zip, el que calculó el generador (si lo fijó). */
    val innerSha256: String? = null,
) {
    companion object {
        const val ANDROID = "android"
        const val DEFAULT_ASSET = "(?i)\\.apk$"
        val DEFAULT_ABIS = listOf("arm64-v8a", "universal")
    }
}

/** Un volcado concreto que el port acepta. `crc` en hexadecimal; `md5`/`sha1` en minúsculas (como Hashing). */
@Serializable
data class RomHash(
    val label: String = "",
    val system: String? = null,
    val crc: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
)

/** Lo que pide el port: si hace falta la copia propia del juego y cuál. */
@Serializable
data class Requirement(
    val userRom: Boolean = false,
    val text: Map<String, String> = emptyMap(),
    val roms: List<RomHash> = emptyList(),
)

@Serializable
data class PortEntry(
    val id: String,
    /** El nombre del PORT ("Ship of Harkinian"); el que se enseña primero es el del juego ([game]). */
    val name: String,
    /** decomp | recomp | reimpl */
    val kind: String = "decomp",
    val developer: String? = null,
    val repo: GhRepo,
    val upstream: GhRepo? = null,
    val game: PortGame,
    val builds: List<PortBuild> = emptyList(),
    val requires: Requirement = Requirement(),
    val tags: List<String> = emptyList(),
    val short: Map<String, String> = emptyMap(),
    val long: Map<String, String> = emptyMap(),
    val license: String? = null,
    /** La tarjeta del repositorio: como mucho una miniatura en "Sobre este port". */
    val portImage: String? = null,
    val screenshots: List<String> = emptyList(),
    val release: PortReleaseInfo = PortReleaseInfo(),
    val trust: PortTrust = PortTrust(),
    val featured: Boolean = false,
    /**
     * android = se instala; source = aún no hay APK (solo se enseña, con
     * "Ver el proyecto en GitHub"); vr = el APK es solo para gafas de RV.
     */
    val availability: String = AVAILABLE,
) {
    /** El build de Android; sin él, el port es solo de PC y no se puede instalar (v1). */
    val android: PortBuild? get() = builds.firstOrNull { it.platform.equals(PortBuild.ANDROID, ignoreCase = true) }
    val installable: Boolean get() = android != null && availability == AVAILABLE

    /** Aún no hay APK de Android: la ficha se ve, pero no se instala. */
    val notOnAndroid: Boolean get() = android == null || availability == SOURCE

    /** El APK es solo para gafas de realidad virtual (Meta Quest…). */
    val vrOnly: Boolean get() = availability == VR_ONLY

    /** De dónde salen las releases del build de Android (o del port, si no lo hay). */
    val releaseRepo: GhRepo get() = android?.repo ?: repo

    /** La plataforma original (id de sistema de Elyndra). */
    val platform: String get() = game.originalPlatform

    val verified: Boolean get() = trust.verified

    /** La imagen del port: la del catálogo o la tarjeta OpenGraph que GitHub genera para cada repositorio. */
    val image: String get() = portImage ?: "https://opengraph.githubassets.com/1/${releaseRepo.owner}/${releaseRepo.name}"

    fun shortText(lang: String): String = Localized.pick(short, lang).orEmpty()
    fun longText(lang: String): String = Localized.pick(long, lang) ?: shortText(lang)
    fun requirementText(lang: String): String? = Localized.pick(requires.text, lang)

    companion object {
        const val AVAILABLE = "android"
        const val SOURCE = "source"
        const val VR_ONLY = "vr"
    }
}

/** Textos por idioma con el inglés de respaldo. */
object Localized {
    fun pick(map: Map<String, String>, lang: String): String? {
        val l = lang.lowercase().substringBefore('-').substringBefore('_')
        return map[l]?.takeIf { it.isNotBlank() }
            ?: map["en"]?.takeIf { it.isNotBlank() }
            ?: map.values.firstOrNull { it.isNotBlank() }
    }
}

/**
 * Lee y valida el catálogo.
 *
 * Una entrada mala no tumba el resto: cada una se lee por separado y la que
 * no pasa se descarta con el motivo (que se ve en el registro, y en las
 * pruebas). Los campos que no conoce se ignoran, así un catálogo de un
 * esquema más nuevo sigue sirviendo con lo que esta versión entiende.
 */
object PortCatalogParser {

    /** El esquema que escribe el generador; los de [SCHEMA_V1] se pasan al 2 al leerlos. */
    const val SCHEMA = 2
    const val SCHEMA_V1 = 1

    data class Parsed(val catalog: PortCatalog, val rejected: List<String>)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        isLenient = true
    }

    private val ID = Regex("""[a-z0-9][a-z0-9-]{0,47}""")
    private val GH_NAME = Regex("""[A-Za-z0-9_.-]{1,100}""")

    /** Null si el texto no es un catálogo en absoluto (no es JSON, o no tiene `ports`). */
    fun parse(text: String): Parsed? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val array = root["ports"] as? JsonArray ?: return null
        val schema = runCatching { root["schema"]?.jsonPrimitive?.int }.getOrNull() ?: SCHEMA
        val updated = runCatching { root["updated"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        val rejected = mutableListOf<String>()
        val seen = HashSet<String>()
        val ports = array.mapIndexedNotNull { i, element ->
            val entry = runCatching { json.decodeFromJsonElement<PortEntry>(upgrade(element)) }.getOrElse {
                rejected += "#$i: ${it.message?.lineSequence()?.firstOrNull() ?: "unreadable"}"
                return@mapIndexedNotNull null
            }
            val problem = validate(entry) ?: if (!seen.add(entry.id)) "duplicate id" else null
            if (problem != null) {
                rejected += "${entry.id.ifBlank { "#$i" }}: $problem"
                null
            } else {
                entry
            }
        }
        return Parsed(PortCatalog(schema, updated, ports), rejected)
    }

    /**
     * Una entrada de esquema 1 en la forma del 2: `original{title, platform,
     * igdbId, sgdbId}` pasa a `game{name, originalPlatform, …}` y `verified`
     * a `trust.verified`. Una entrada que ya trae `game` no se toca.
     */
    internal fun upgrade(element: JsonElement): JsonElement {
        val o = element as? JsonObject ?: return element
        if (o.containsKey("game")) return o
        val out = o.toMutableMap()
        (out.remove("original") as? JsonObject)?.let { original ->
            out["game"] = buildJsonObject {
                original["title"]?.let { put("name", it) }
                original["platform"]?.let { put("originalPlatform", it) }
                original["igdbId"]?.let { put("igdbId", it) }
                original["sgdbId"]?.let { put("sgdbId", it) }
            }
        }
        out.remove("verified")?.let { v -> out["trust"] = buildJsonObject { put("verified", v) } }
        return JsonObject(out)
    }

    private fun https(url: String?): Boolean = url == null || url.startsWith("https://")

    /** El motivo por el que [e] no vale, o null si vale. */
    fun validate(e: PortEntry): String? = when {
        !ID.matches(e.id) -> "bad id"
        e.name.isBlank() -> "no name"
        !validRepo(e.repo) -> "bad repo"
        e.upstream != null && !validRepo(e.upstream) -> "bad upstream"
        e.game.name.isBlank() || e.game.originalPlatform.isBlank() -> "no original game"
        e.builds.any { it.platform.isBlank() } -> "build without platform"
        e.builds.any { b -> b.repo != null && !validRepo(b.repo) } -> "bad build repo"
        e.builds.any { runCatching { Regex(it.asset) }.isFailure } -> "bad asset pattern"
        e.builds.any { b -> b.archive != null && runCatching { Regex(b.archive) }.isFailure } -> "bad archive pattern"
        e.availability !in setOf(PortEntry.AVAILABLE, PortEntry.SOURCE, PortEntry.VR_ONLY) -> "bad availability"
        !https(e.portImage) -> "image must be https"
        e.screenshots.any { !https(it) } -> "screenshot must be https"
        e.game.art.urls.any { !https(it) } -> "art must be https"
        else -> null
    }

    private fun validRepo(r: GhRepo): Boolean = GH_NAME.matches(r.owner) && GH_NAME.matches(r.name) && r.name != "." && r.name != ".."
}
