package com.elyndra.launcher.metadata

import com.elyndra.launcher.library.Names
import java.io.File
import java.net.URLEncoder

/* ─────────────────────────────────────────────────────────────
   Tienda de Steam, sin clave: buscador (storesearch) y ficha
   (appdetails) en el idioma que se pida (`l=`), e imágenes de su
   CDN por appid (cabecera, hero, logo, carátula vertical).

   No es una API documentada: puede cambiar y limita peticiones
   (del orden de 200 cada 5 minutos por IP). Por eso: una petición
   cada 1,5 s, caché de fallos, y si algo no cuadra, no hay datos.

   Steam devuelve la ficha en inglés cuando el juego no está
   traducido al idioma pedido, aunque se pida otro: para no
   etiquetarla mal, se compara con la inglesa (ver [SteamDescriptions]).
   ───────────────────────────────────────────────────────────── */

data class SteamSearchItem(val id: Long, val name: String, val image: String? = null)

data class SteamDetails(
    val id: Long,
    val name: String,
    val type: String,
    val shortDescription: String?,
    val developers: List<String>,
    val publishers: List<String>,
    val genres: List<String>,
)

object SteamParser {

    fun search(body: String): List<SteamSearchItem> {
        val items = Http.parse(body).asObject()?.get("items").asArray() ?: return emptyList()
        return items.mapNotNull { e ->
            val o = e.asObject() ?: return@mapNotNull null
            val id = o["id"]?.let { it.asString()?.toLongOrNull() } ?: return@mapNotNull null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SteamSearchItem(id, name, o.str("tiny_image"))
        }
    }

    fun details(body: String, appId: Long): SteamDetails? {
        val entry = Http.parse(body).asObject()?.get(appId.toString()).asObject() ?: return null
        if (entry["success"]?.asString() != "true") return null
        val d = entry["data"].asObject() ?: return null
        return SteamDetails(
            id = appId,
            name = d.str("name")?.trim().orEmpty(),
            type = d.str("type").orEmpty(),
            shortDescription = d.str("short_description")?.let(::cleanHtml)?.takeIf { it.isNotBlank() },
            developers = d["developers"].asArray()?.mapNotNull { it.asString() }.orEmpty(),
            publishers = d["publishers"].asArray()?.mapNotNull { it.asString() }.orEmpty(),
            genres = d["genres"].asArray()?.mapNotNull { it.asObject()?.str("description") }.orEmpty(),
        )
    }

    /** La sinopsis de Steam trae HTML y entidades: texto plano. */
    fun cleanHtml(s: String): String = s
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"").replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&iexcl;", "¡").replace("&iquest;", "¿")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Palabras que delatan que un resultado no es el juego en sí. */
    private val NOT_A_GAME = Regex("""\b(soundtrack|ost|dlc|demo|artbook|season pass|expansion pack|bundle|playtest|server|sdk|tool)\b""", RegexOption.IGNORE_CASE)

    /** Umbral para dar por bueno un resultado del buscador. */
    const val MATCH_THRESHOLD = 0.9

    /** El resultado que es de verdad [title] (o null): parecido alto, mismos números, sin "Soundtrack" ni "DLC". */
    fun bestMatch(title: String, items: List<SteamSearchItem>): Pair<SteamSearchItem, Double>? {
        val wantedNumbers = Regex("""\d+""").findAll(Names.normalize(title)).map { it.value }.toList()
        val queryHasExtra = NOT_A_GAME.containsMatchIn(title)
        return items.asSequence()
            .filter { queryHasExtra || !NOT_A_GAME.containsMatchIn(it.name) }
            .filter { Regex("""\d+""").findAll(Names.normalize(it.name)).map { m -> m.value }.toList() == wantedNumbers }
            .map { it to Names.similarity(title, it.name) }
            .filter { it.second >= MATCH_THRESHOLD }
            .maxByOrNull { it.second }
    }

    /** Arte de la CDN por appid (no hace falta preguntar a la API). */
    fun cover(appId: Long) = "$CDN/$appId/library_600x900_2x.jpg"
    fun hero(appId: Long) = "$CDN/$appId/library_hero.jpg"
    fun logo(appId: Long) = "$CDN/$appId/logo.png"
    fun header(appId: Long) = "$CDN/$appId/header.jpg"

    private const val CDN = "https://cdn.akamai.steamstatic.com/steam/apps"
}

object SteamDescriptions {

    /**
     * Las descripciones de una ficha pedida en [lang] (y en inglés), con su
     * idioma de verdad: si la "traducida" es igual que la inglesa, es que el
     * juego no está traducido y solo cuenta como inglés.
     */
    fun tag(lang: String, localized: String?, english: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val en = english?.trim()?.takeIf { it.isNotEmpty() }
        val loc = localized?.trim()?.takeIf { it.isNotEmpty() }
        if (lang != DescriptionLangs.FALLBACK && loc != null && loc != en) out[lang] = loc
        if (en != null) out[DescriptionLangs.FALLBACK] = en
        else if (lang == DescriptionLangs.FALLBACK && loc != null) out[DescriptionLangs.FALLBACK] = loc
        return out
    }
}

/** Lo que Steam sabe de un juego, ya emparejado. */
data class SteamGame(val details: SteamDetails, val similarity: Double, val descriptions: Map<String, String>)

class SteamStoreClient(
    cacheDir: File?,
    private val base: String = "https://store.steampowered.com",
    private val limiter: RateLimiter = RateLimiter(1_500),
) {

    private val misses = MissCache(cacheDir?.let { File(it, "keyless/steam_misses.txt") }, 7 * MissCache.DAY_MS)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun search(term: String, lang: String): List<SteamSearchItem> = searchOrNull(term, lang).orEmpty()

    /** null = sin respuesta (red, servidor): no es lo mismo que "no está". */
    private suspend fun searchOrNull(term: String, lang: String): List<SteamSearchItem>? {
        val r = KeylessHttp.get("$base/api/storesearch/?term=${enc(term)}&l=${DescriptionLangs.steamName(lang)}&cc=US", limiter)
        if (r == null || r.code != 200) return null
        return runCatching { SteamParser.search(r.body) }.getOrNull()
    }

    suspend fun details(appId: Long, lang: String): SteamDetails? {
        val r = KeylessHttp.get("$base/api/appdetails?appids=$appId&l=${DescriptionLangs.steamName(lang)}&cc=US", limiter)
        if (r == null || r.code != 200) return null
        return runCatching { SteamParser.details(r.body, appId) }.getOrNull()
    }

    /**
     * El juego de Steam que es [title], con su descripción en [lang] (y la
     * inglesa). [knownId]: si ya se emparejó antes, se usa directamente.
     */
    suspend fun find(title: String, lang: String, knownId: Long? = null): SteamGame? {
        val missKey = title.lowercase().trim()
        if (knownId == null && misses.isMiss(missKey)) return null
        val (id, similarity) = if (knownId != null) knownId to 1.0 else {
            val results = searchOrNull(title, lang) ?: return null
            val hit = SteamParser.bestMatch(title, results)
            if (hit == null) {
                misses.markMiss(missKey)
                return null
            }
            hit.first.id to hit.second
        }
        val localized = details(id, lang) ?: return null
        // Una DLC o una banda sonora con el mismo nombre no es el juego.
        if (localized.type != "game") {
            misses.markMiss(missKey)
            return null
        }
        val english = if (lang == DescriptionLangs.FALLBACK) localized else details(id, DescriptionLangs.FALLBACK)
        val descriptions = SteamDescriptions.tag(lang, localized.shortDescription, english?.shortDescription)
        return SteamGame(localized, similarity, descriptions)
    }
}
