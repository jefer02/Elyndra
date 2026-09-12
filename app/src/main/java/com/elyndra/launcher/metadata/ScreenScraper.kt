package com.elyndra.launcher.metadata

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/* ─────────────────────────────────────────────────────────────
   ScreenScraper — API v2 (https://api.screenscraper.fr/api2/).

   Toda llamada lleva las credenciales de DESARROLLADOR (devid,
   devpassword, softname), que ScreenScraper concede por software en
   su foro. Las credenciales de USUARIO (ssid, sspassword) son
   opcionales y suben el cupo diario y el número de hilos.
   ───────────────────────────────────────────────────────────── */

data class SsCredentials(
    val devId: String,
    val devPassword: String,
    val softName: String,
    val user: String,
    val password: String,
) {
    val hasDev: Boolean get() = devId.isNotBlank() && devPassword.isNotBlank()
    val hasUser: Boolean get() = user.isNotBlank() && password.isNotBlank()
}

data class SsUser(
    val id: String,
    val level: Int,
    val maxThreads: Int,
    val requestsToday: Int,
    val maxRequestsPerDay: Int,
    val requestsKoToday: Int,
    val maxRequestsKoPerDay: Int,
    val maxRequestsPerMin: Int,
)

data class SsMedia(val type: String, val url: String, val region: String?, val format: String?)

data class SsLocalized(val key: String, val text: String)

data class SsGame(
    val id: String,
    val names: List<SsLocalized>,
    val synopsis: List<SsLocalized>,
    val dates: List<SsLocalized>,
    val genres: List<List<SsLocalized>>,
    val developer: String?,
    val publisher: String?,
    val players: String?,
    /** Nota sobre 20. */
    val rating: Double?,
    val medias: List<SsMedia>,
    val notGame: Boolean,
) {
    fun name(regions: List<String>): String? = pick(names, regions + listOf("ss", "wor", "us", "eu", "jp"))
        ?.let(ScreenScraperParser::cleanText)

    fun description(languages: List<String>): String? = pick(synopsis, languages + listOf("en"))
        ?.let(ScreenScraperParser::cleanText)

    fun releaseDate(regions: List<String>): String? = pick(dates, regions + listOf("wor", "us", "eu", "jp"))

    fun genre(languages: List<String>): String? = genres
        .mapNotNull { pick(it, languages + listOf("en")) }
        .distinct().take(3).joinToString(", ").ifEmpty { null }

    /** Primer medio de los tipos dados (en orden) para la región preferida. */
    fun media(types: List<String>, regions: List<String>): SsMedia? {
        val order = regions + listOf("wor", "us", "eu", "jp", "ss", "cus")
        for (type in types) {
            val ofType = medias.filter { it.type == type }
            if (ofType.isEmpty()) continue
            for (r in order) ofType.firstOrNull { it.region == r }?.let { return it }
            return ofType.first()
        }
        return null
    }

    private fun pick(items: List<SsLocalized>, keys: List<String>): String? {
        for (k in keys) items.firstOrNull { it.key == k && it.text.isNotBlank() }?.let { return it.text }
        return items.firstOrNull { it.text.isNotBlank() }?.text
    }
}

object ScreenScraperParser {

    fun cleanText(s: String): String = s
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&copy;", "©")
        .replace("&#039;", "'").replace("&#39;", "'").replace("&#x26;", "&").replace("&amp;", "&")
        .replace("\r", "").trim()

    fun user(root: JsonElement): SsUser? {
        val u = root.asObject()?.get("response").asObject()?.get("ssuser").asObject() ?: return null
        val id = u.str("id") ?: return null
        return SsUser(
            id = id,
            level = u["niveau"].asInt() ?: 0,
            maxThreads = u["maxthreads"].asInt() ?: 1,
            requestsToday = u["requeststoday"].asInt() ?: 0,
            maxRequestsPerDay = u["maxrequestsperday"].asInt() ?: 0,
            requestsKoToday = u["requestskotoday"].asInt() ?: 0,
            maxRequestsKoPerDay = u["maxrequestskoperday"].asInt() ?: 0,
            maxRequestsPerMin = u["maxrequestspermin"].asInt() ?: 0,
        )
    }

    fun gameInfo(root: JsonElement): SsGame? =
        root.asObject()?.get("response").asObject()?.get("jeu").asObject()?.let(::game)

    fun search(root: JsonElement): List<SsGame> =
        root.asObject()?.get("response").asObject()?.get("jeux").asArray()
            ?.mapNotNull { it.asObject()?.let(::game) }
            .orEmpty()

    fun game(o: JsonObject): SsGame? {
        val id = o.str("id") ?: return null
        val names = localized(o["noms"], "region")
        if (names.isEmpty()) return null
        val notGame = o["notgame"].asBool() == true || names.any { it.text.startsWith("ZZZ(notgame)", ignoreCase = true) }
        return SsGame(
            id = id,
            names = names,
            synopsis = localized(o["synopsis"], "langue"),
            dates = localized(o["dates"], "region"),
            genres = o["genres"].asArray()?.mapNotNull { g -> g.asObject()?.let { localized(it["noms"], "langue") } }.orEmpty(),
            developer = textOf(o["developpeur"])?.let(::cleanText),
            publisher = textOf(o["editeur"])?.let(::cleanText),
            players = textOf(o["joueurs"]),
            rating = textOf(o["note"])?.toDoubleOrNull(),
            medias = o["medias"].asArray()?.mapNotNull { m ->
                val mo = m.asObject() ?: return@mapNotNull null
                val url = mo.str("url") ?: return@mapNotNull null
                SsMedia(mo.str("type") ?: return@mapNotNull null, url.replace(" ", "%20"), mo.str("region"), mo.str("format"))
            }.orEmpty(),
            notGame = notGame,
        )
    }

    private fun textOf(e: JsonElement?): String? = e.asObject()?.str("text") ?: e.asString()?.takeIf { it.isNotBlank() }

    private fun localized(e: JsonElement?, keyName: String): List<SsLocalized> =
        e.asArray()?.mapNotNull { item ->
            val o = item.asObject() ?: return@mapNotNull null
            val text = o.str("text") ?: return@mapNotNull null
            SsLocalized(o.str(keyName) ?: "", text)
        }.orEmpty()
}

class ScreenScraperClient(private val credentials: () -> SsCredentials) {

    private val gate = RateGate(1_300)

    private fun base(endpoint: String, creds: SsCredentials) = "$BASE/$endpoint".toHttpUrl().newBuilder()
        .addQueryParameter("devid", creds.devId)
        .addQueryParameter("devpassword", creds.devPassword)
        .addQueryParameter("softname", creds.softName)
        .addQueryParameter("output", "json")
        .apply {
            if (creds.hasUser) {
                addQueryParameter("ssid", creds.user)
                addQueryParameter("sspassword", creds.password)
            }
        }

    private suspend fun call(endpoint: String, params: Map<String, String?>): JsonElement {
        val creds = credentials()
        if (!creds.hasDev) throw ApiException.Unauthorized("missing developer credentials")
        val url = base(endpoint, creds).apply {
            params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
        }.build()
        val result = withRateRetry(attempts = 4, baseDelayMs = 4_000) {
            gate.run { Http.text(Request.Builder().url(url).get().build()) }.also { checkStatus(it) }
        }
        return Http.parse(result.body)
    }

    private fun checkStatus(r: HttpResult) {
        val msg = r.body.take(200).trim()
        when (r.code) {
            200 -> {
                if (!r.body.trimStart().startsWith("{")) {
                    if (msg.contains("non trouv", ignoreCase = true)) throw ApiException.NotFound(msg)
                    throw ApiException.BadResponse(msg)
                }
            }
            400 -> throw ApiException.BadResponse(msg)
            401 -> throw ApiException.Server(401, msg.ifEmpty { "API closed for non-members" })
            403 -> throw ApiException.Unauthorized(msg)
            404 -> throw ApiException.NotFound(msg)
            423 -> throw ApiException.Server(423, msg.ifEmpty { "API closed" })
            426 -> throw ApiException.Blocked(msg)
            429 -> throw ApiException.RateLimited(msg)
            430, 431 -> throw ApiException.QuotaExceeded(msg)
            else -> throw ApiException.Server(r.code, msg)
        }
    }

    /**
     * Valida las credenciales de desarrollador sin cuenta de usuario. ssinfraInfos.php
     * responde 200 aunque sean falsas, así que se usa la consulta de ejemplo de la
     * documentación (Sonic 2, Mega Drive): con credenciales malas devuelve 403.
     */
    suspend fun checkDeveloper() {
        try {
            call(
                "jeuInfos.php",
                mapOf(
                    "systemeid" to "1",
                    "romtype" to "rom",
                    "romnom" to "Sonic The Hedgehog 2 (World).zip",
                    "romtaille" to "749652",
                    "crc" to "50ABC90A",
                ),
            )
        } catch (e: ApiException.NotFound) {
            // Credenciales aceptadas aunque el juego de ejemplo no aparezca.
        }
    }

    /** Comprueba credenciales y devuelve cupos (ssuserInfos.php). */
    suspend fun user(): SsUser {
        val creds = credentials()
        if (!creds.hasUser) throw ApiException.Unauthorized("missing user credentials")
        val root = call("ssuserInfos.php", emptyMap())
        return ScreenScraperParser.user(root) ?: throw ApiException.Unauthorized("invalid user")
    }

    /** Identificación exacta por hash + nombre + tamaño (jeuInfos.php). Null si no la reconoce. */
    suspend fun gameInfo(
        systemId: Int,
        romName: String,
        size: Long,
        romType: String,
        crc: String?,
        md5: String?,
        sha1: String?,
    ): SsGame? = try {
        val root = call(
            "jeuInfos.php",
            mapOf(
                "systemeid" to systemId.toString(),
                "romtype" to romType,
                "romnom" to romName,
                "romtaille" to size.takeIf { it > 0 }?.toString(),
                "crc" to crc,
                "md5" to md5,
                "sha1" to sha1,
            ),
        )
        ScreenScraperParser.gameInfo(root)?.takeUnless { it.notGame }
    } catch (e: ApiException.NotFound) {
        null
    }

    /** Juego ya identificado, por su id de ScreenScraper (jeuInfos.php?gameid=). */
    suspend fun gameById(gameId: String): SsGame? = try {
        ScreenScraperParser.gameInfo(call("jeuInfos.php", mapOf("gameid" to gameId)))?.takeUnless { it.notGame }
    } catch (e: ApiException.NotFound) {
        null
    }

    /** Búsqueda por nombre (jeuRecherche.php), resultados ordenados por probabilidad. */
    suspend fun search(systemId: Int?, name: String): List<SsGame> = try {
        val root = call("jeuRecherche.php", mapOf("systemeid" to systemId?.toString(), "recherche" to name))
        ScreenScraperParser.search(root).filterNot { it.notGame }
    } catch (e: ApiException.NotFound) {
        emptyList()
    }

    companion object {
        const val BASE = "https://api.screenscraper.fr/api2"

        /** La URL del medio ya lleva credenciales; se le pide un tamaño razonable para el móvil. */
        fun sizedMediaUrl(media: SsMedia, maxWidth: Int, jpg: Boolean): String {
            val sep = if (media.url.contains('?')) "&" else "?"
            return media.url + sep + "maxwidth=$maxWidth" + if (jpg) "&outputformat=jpg" else "&outputformat=png"
        }
    }
}
