package com.elyndra.launcher.metadata

import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/* ─────────────────────────────────────────────────────────────
   SteamGridDB — API v2 (https://www.steamgriddb.com/api/v2).

   Credencial: una API key personal, generada en
   https://www.steamgriddb.com/profile/preferences/api (la cuenta se
   crea iniciando sesión con Steam). Va en la cabecera
   "Authorization: Bearer <key>".
   ───────────────────────────────────────────────────────────── */

data class SgdbGame(val id: Long, val name: String, val verified: Boolean)

data class SgdbImage(
    val id: Long,
    val url: String,
    val width: Int,
    val height: Int,
    val style: String?,
    val score: Int,
    /** Miniatura para el selector de imágenes. */
    val thumb: String = url,
)

object SgdbParser {

    fun games(root: JsonElement): List<SgdbGame> = data(root).mapNotNull { e ->
        val o = e.asObject() ?: return@mapNotNull null
        SgdbGame(
            id = o["id"].asLong() ?: return@mapNotNull null,
            name = o.str("name") ?: return@mapNotNull null,
            verified = o["verified"].asBool() == true,
        )
    }

    fun images(root: JsonElement): List<SgdbImage> = data(root).mapNotNull { e ->
        val o = e.asObject() ?: return@mapNotNull null
        val url = o.str("url") ?: return@mapNotNull null
        SgdbImage(
            id = o["id"].asLong() ?: return@mapNotNull null,
            url = url,
            width = o["width"].asInt() ?: 0,
            height = o["height"].asInt() ?: 0,
            style = o.str("style"),
            score = o["score"].asInt() ?: 0,
            thumb = o.str("thumb") ?: url,
        )
    }

    fun error(root: JsonElement): String? =
        root.asObject()?.get("errors").asArray()?.mapNotNull { it.asString() }?.joinToString(", ")?.ifEmpty { null }

    private fun data(root: JsonElement) = root.asObject()?.get("data").asArray().orEmpty()
}

class SteamGridDbClient(private val apiKey: () -> String) {

    private val gate = RateGate(300)

    private suspend fun get(url: HttpUrl): JsonElement {
        val key = apiKey().trim()
        if (key.isEmpty()) throw ApiException.Unauthorized("missing API key")
        val r = withRateRetry(attempts = 3, baseDelayMs = 2_000) {
            gate.run {
                Http.text(Request.Builder().url(url).header("Authorization", "Bearer $key").get().build())
            }.also { if (it.code == 429) throw ApiException.RateLimited("SteamGridDB 429") }
        }
        val message = runCatching { SgdbParser.error(Http.parse(r.body)) }.getOrNull() ?: r.body.take(160)
        return when (r.code) {
            200 -> Http.parse(r.body)
            401, 403 -> throw ApiException.Unauthorized(message)
            404 -> throw ApiException.NotFound(message)
            else -> throw ApiException.Server(r.code, message)
        }
    }

    private fun url(vararg segments: String, params: Map<String, String> = emptyMap()): HttpUrl =
        BASE.toHttpUrl().newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    /** Comprueba la clave con una búsqueda cualquiera. */
    suspend fun validate() {
        get(url("search", "autocomplete", "zelda"))
    }

    suspend fun search(term: String): List<SgdbGame> = try {
        SgdbParser.games(get(url("search", "autocomplete", term)))
    } catch (e: ApiException.NotFound) {
        emptyList()
    }

    private val filters = mapOf("types" to "static", "nsfw" to "false", "humor" to "false", "epilepsy" to "false")

    /** Carátulas: 600x900 (vertical) o 512x512/1024x1024 (cuadradas). */
    suspend fun grids(gameId: Long, dimensions: List<String>): List<SgdbImage> = images(
        url("grids", "game", gameId.toString(), params = filters + ("dimensions" to dimensions.joinToString(","))),
    )

    /** Fondos panorámicos para el hero. */
    suspend fun heroes(gameId: Long): List<SgdbImage> = images(url("heroes", "game", gameId.toString(), params = filters))

    /** Logos con transparencia. */
    suspend fun logos(gameId: Long): List<SgdbImage> = images(url("logos", "game", gameId.toString(), params = filters))

    /** Iconos cuadrados. */
    suspend fun icons(gameId: Long): List<SgdbImage> = images(url("icons", "game", gameId.toString(), params = filters))

    private suspend fun images(u: HttpUrl): List<SgdbImage> = try {
        SgdbParser.images(get(u)).sortedByDescending { it.score }
    } catch (e: ApiException.NotFound) {
        emptyList()
    }

    companion object {
        const val BASE = "https://www.steamgriddb.com/api/v2"
        val PORTRAIT = listOf("600x900", "342x482", "660x930")
        val SQUARE = listOf("512x512", "1024x1024")
    }
}
