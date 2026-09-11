package com.elyndra.launcher.metadata

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/* ─────────────────────────────────────────────────────────────
   IGDB (Twitch) — https://api-docs.igdb.com

   Credenciales: Client ID + Client Secret de una aplicación creada en
   https://dev.twitch.tv/console/apps. Con ellas se pide un token de
   aplicación (client_credentials) que dura ~60 días; se guarda y se
   renueva solo cuando caduca o la API responde 401.
   Límite: 4 peticiones por segundo.
   ───────────────────────────────────────────────────────────── */

data class IgdbCredentials(val clientId: String, val clientSecret: String) {
    val isComplete: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}

data class IgdbToken(val accessToken: String, val expiresAt: Long)

/** Dónde se guarda el token entre sesiones. */
interface IgdbTokenStore {
    fun load(): IgdbToken?
    fun save(token: IgdbToken?)
}

data class IgdbGame(
    val id: Long,
    val name: String,
    val summary: String?,
    val firstRelease: Long?,
    val genres: List<String>,
    val developers: List<String>,
    val publishers: List<String>,
    val coverId: String?,
    val artworkIds: List<String>,
    val screenshotIds: List<String>,
    val rating: Double?,
    val modes: List<String>,
)

object IgdbParser {

    fun token(root: JsonElement, now: Long): IgdbToken? {
        val o = root.asObject() ?: return null
        val token = o.str("access_token") ?: return null
        val expiresIn = o["expires_in"].asLong() ?: 3600L
        // Se renueva un día antes de que caduque.
        return IgdbToken(token, now + (expiresIn - 86_400L).coerceAtLeast(600L) * 1000L)
    }

    fun games(root: JsonElement): List<IgdbGame> = root.asArray()?.mapNotNull { it.asObject()?.let(::game) }.orEmpty()

    private fun game(o: JsonObject): IgdbGame? {
        val id = o["id"].asLong() ?: return null
        val name = o.str("name") ?: return null
        val companies = o["involved_companies"].asArray()?.mapNotNull { it.asObject() }.orEmpty()
        fun companiesWhere(flag: String) = companies.filter { it[flag].asBool() == true }
            .mapNotNull { it["company"].asObject()?.str("name") }
        return IgdbGame(
            id = id,
            name = name,
            summary = o.str("summary"),
            firstRelease = o["first_release_date"].asLong(),
            genres = names(o["genres"]),
            developers = companiesWhere("developer"),
            publishers = companiesWhere("publisher"),
            coverId = o["cover"].asObject()?.str("image_id"),
            artworkIds = imageIds(o["artworks"]),
            screenshotIds = imageIds(o["screenshots"]),
            rating = o["total_rating"].asDouble() ?: o["rating"].asDouble(),
            modes = names(o["game_modes"]),
        )
    }

    private fun names(e: JsonElement?) = e.asArray()?.mapNotNull { it.asObject()?.str("name") }.orEmpty()

    private fun imageIds(e: JsonElement?) = e.asArray()?.mapNotNull { it.asObject()?.str("image_id") }.orEmpty()

    /** Escapa comillas y barras para la sintaxis Apicalypse. */
    fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun searchQuery(name: String, platforms: List<Int>?, limit: Int = 10): String = buildString {
        append("search \"").append(escape(name)).append("\";\n")
        append(
            "fields name,summary,first_release_date,genres.name,involved_companies.developer," +
                "involved_companies.publisher,involved_companies.company.name,cover.image_id," +
                "artworks.image_id,screenshots.image_id,total_rating,rating,game_modes.name;\n",
        )
        if (!platforms.isNullOrEmpty()) append("where platforms = (").append(platforms.joinToString(",")).append(");\n")
        append("limit ").append(limit).append(";")
    }
}

class IgdbClient(
    private val credentials: () -> IgdbCredentials,
    private val tokens: IgdbTokenStore,
) {
    private val gate = RateGate(280)

    /** Pide un token nuevo (también sirve para comprobar las credenciales). */
    suspend fun authenticate(): IgdbToken {
        val creds = credentials()
        if (!creds.isComplete) throw ApiException.Unauthorized("missing credentials")
        val body = FormBody.Builder()
            .add("client_id", creds.clientId.trim())
            .add("client_secret", creds.clientSecret.trim())
            .add("grant_type", "client_credentials")
            .build()
        val r = Http.text(Request.Builder().url(TOKEN_URL).post(body).build())
        when (r.code) {
            200 -> Unit
            400, 401, 403 -> throw ApiException.Unauthorized(messageOf(r.body))
            else -> throw ApiException.Server(r.code, messageOf(r.body))
        }
        val token = IgdbParser.token(Http.parse(r.body), System.currentTimeMillis())
            ?: throw ApiException.BadResponse(r.body.take(160))
        tokens.save(token)
        return token
    }

    private suspend fun validToken(): IgdbToken {
        val t = tokens.load()
        return if (t != null && t.expiresAt > System.currentTimeMillis()) t else authenticate()
    }

    private suspend fun post(endpoint: String, query: String): JsonElement {
        var token = validToken()
        repeat(2) { attempt ->
            val r = withRateRetry(attempts = 4, baseDelayMs = 1_000) {
                gate.run {
                    Http.text(
                        Request.Builder()
                            .url("$API/$endpoint")
                            .header("Client-ID", credentials().clientId.trim())
                            .header("Authorization", "Bearer ${token.accessToken}")
                            .header("Accept", "application/json")
                            .post(query.toRequestBody(TEXT))
                            .build(),
                    )
                }.also { if (it.code == 429) throw ApiException.RateLimited("IGDB 429") }
            }
            when (r.code) {
                200 -> return Http.parse(r.body)
                401 -> if (attempt == 0) {
                    tokens.save(null)
                    token = authenticate()
                } else throw ApiException.Unauthorized(messageOf(r.body))
                403 -> throw ApiException.Unauthorized(messageOf(r.body))
                else -> throw ApiException.Server(r.code, messageOf(r.body))
            }
        }
        throw ApiException.Unauthorized("IGDB")
    }

    suspend fun search(name: String, platforms: List<Int>?): List<IgdbGame> =
        IgdbParser.games(post("games", IgdbParser.searchQuery(name, platforms)))

    private fun messageOf(body: String): String = runCatching {
        Http.parse(body).asObject()?.str("message")
    }.getOrNull() ?: body.take(160)

    companion object {
        const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"
        const val API = "https://api.igdb.com/v4"
        private val TEXT = "text/plain".toMediaType()

        /** https://images.igdb.com/igdb/image/upload/t_{size}/{hash}.jpg */
        fun imageUrl(imageId: String, size: String): String =
            "https://images.igdb.com/igdb/image/upload/t_$size/$imageId.jpg"
    }
}
