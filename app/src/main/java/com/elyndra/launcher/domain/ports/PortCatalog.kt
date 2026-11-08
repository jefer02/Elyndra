package com.elyndra.launcher.domain.ports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

/** El juego original del que sale el port. [platform] es el id de sistema de Elyndra (`n64`, `xbox360`…). */
@Serializable
data class OriginalGame(
    val title: String,
    val platform: String,
    val igdbId: Long? = null,
    val sgdbId: Long? = null,
)

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
    val name: String,
    /** decomp | recomp | reimpl */
    val kind: String = "decomp",
    val developer: String? = null,
    val repo: GhRepo,
    val upstream: GhRepo? = null,
    val original: OriginalGame,
    val builds: List<PortBuild> = emptyList(),
    val requires: Requirement = Requirement(),
    val tags: List<String> = emptyList(),
    val short: Map<String, String> = emptyMap(),
    val long: Map<String, String> = emptyMap(),
    val license: String? = null,
    val portImage: String? = null,
    val screenshots: List<String> = emptyList(),
    val verified: Boolean = false,
    val featured: Boolean = false,
) {
    /** El build de Android; sin él, el port es solo de PC y no se puede instalar (v1). */
    val android: PortBuild? get() = builds.firstOrNull { it.platform.equals(PortBuild.ANDROID, ignoreCase = true) }
    val installable: Boolean get() = android != null

    /** De dónde salen las releases del build de Android (o del port, si no lo hay). */
    val releaseRepo: GhRepo get() = android?.repo ?: repo

    /** La imagen del port: la del catálogo o la tarjeta OpenGraph que GitHub genera para cada repositorio. */
    val image: String get() = portImage ?: "https://opengraph.githubassets.com/1/${releaseRepo.owner}/${releaseRepo.name}"

    fun shortText(lang: String): String = Localized.pick(short, lang).orEmpty()
    fun longText(lang: String): String = Localized.pick(long, lang) ?: shortText(lang)
    fun requirementText(lang: String): String? = Localized.pick(requires.text, lang)
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

    const val SCHEMA = 1

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
            val entry = runCatching { json.decodeFromJsonElement<PortEntry>(element) }.getOrElse {
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

    /** El motivo por el que [e] no vale, o null si vale. */
    fun validate(e: PortEntry): String? = when {
        !ID.matches(e.id) -> "bad id"
        e.name.isBlank() -> "no name"
        !validRepo(e.repo) -> "bad repo"
        e.upstream != null && !validRepo(e.upstream) -> "bad upstream"
        e.original.title.isBlank() || e.original.platform.isBlank() -> "no original game"
        e.builds.any { it.platform.isBlank() } -> "build without platform"
        e.builds.any { b -> b.repo != null && !validRepo(b.repo) } -> "bad build repo"
        e.builds.any { runCatching { Regex(it.asset) }.isFailure } -> "bad asset pattern"
        e.portImage != null && !e.portImage.startsWith("https://") -> "image must be https"
        e.screenshots.any { !it.startsWith("https://") } -> "screenshot must be https"
        else -> null
    }

    private fun validRepo(r: GhRepo): Boolean = GH_NAME.matches(r.owner) && GH_NAME.matches(r.name) && r.name != "." && r.name != ".."
}
