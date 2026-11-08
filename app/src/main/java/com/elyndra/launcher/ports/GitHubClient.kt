package com.elyndra.launcher.ports

import com.elyndra.launcher.di.PortsHttpClient
import com.elyndra.launcher.domain.ports.GhRepo
import com.elyndra.launcher.domain.ports.PortRelease
import com.elyndra.launcher.domain.ports.RepoInfo
import com.elyndra.launcher.update.ReleaseAsset
import com.elyndra.launcher.update.UpdateError
import com.elyndra.launcher.update.UpdateLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Una petición GET condicional con copia guardada.
 *
 * - Copia de menos de [ttlMs] (y sin [force]): se usa sin ir a la red.
 * - Si no, se pide con `If-None-Match`; un 304 renueva la copia.
 * - Si GitHub limita, la red falla o llega algo que no vale ([accept]) y hay
 *   copia (aunque sea vieja), se usa la copia. Lo que no vale no se guarda nunca.
 */
internal class ConditionalGet(
    private val client: OkHttpClient,
    private val cache: PortHttpCache,
    private val clock: () -> Long,
) {
    /** El cupo que quedaba en la última respuesta de la API (null = no se sabe). */
    @Volatile var rateRemaining: Int? = null
        private set

    suspend fun get(
        url: String,
        allow: HostAllowlist,
        ttlMs: Long,
        force: Boolean,
        headers: Map<String, String> = emptyMap(),
        accept: (String) -> Boolean = { true },
    ): String = withContext(Dispatchers.IO) {
        val cached = cache.get(url)
        val now = clock()
        if (cached != null && !force && now - cached.fetchedAt in 0 until ttlMs) return@withContext cached.body
        val request = Request.Builder().url(url.toHttpUrl())
            .header("User-Agent", PortsHttp.USER_AGENT)
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
                cached?.etag?.let { header("If-None-Match", it) }
            }
            .build()
        val response = try {
            PortsHttp.execute(client, request, allow)
        } catch (e: UpdateError.Network) {
            return@withContext cached?.body ?: throw e
        }
        response.use { r ->
            r.header("X-RateLimit-Remaining")?.toIntOrNull()?.let { rateRemaining = it }
            when {
                r.code == 304 && cached != null -> {
                    cache.put(url, cached.copy(fetchedAt = now))
                    cached.body
                }
                r.isSuccessful -> {
                    val body = try {
                        r.body?.string().orEmpty()
                    } catch (e: IOException) {
                        return@withContext cached?.body ?: throw UpdateError.Network(e)
                    }
                    if (!accept(body)) return@withContext cached?.body ?: throw UpdateError.BadResponse("unexpected body")
                    cache.put(url, PortHttpCache.Entry(r.header("ETag"), body, now))
                    body
                }
                PortsHttp.isRateLimited(r) -> cached?.body ?: throw UpdateError.RateLimited()
                else -> cached?.body ?: throw UpdateError.Server(r.code)
            }
        }
    }
}

/**
 * Lo que la tienda lee de la API de GitHub: las releases y los datos de
 * cada repositorio. Sin token valen 60 peticiones por hora; con el token
 * opcional de Ajustes → APIs, 5 000. El token solo viaja a la API, nunca a
 * las descargas ni al catálogo.
 */
@Singleton
class GitHubClient(
    client: OkHttpClient,
    cache: PortHttpCache,
    private val apiBase: String,
    private val allow: HostAllowlist,
    private val token: GitHubToken,
    clock: () -> Long = System::currentTimeMillis,
) {
    @Inject constructor(@PortsHttpClient client: OkHttpClient, cache: PortHttpCache, token: GitHubToken) :
        this(client, cache, API_BASE, HostAllowlist.API, token)

    private val http = ConditionalGet(client, cache, clock)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** El cupo restante de la API, según la última respuesta. */
    val rateRemaining: Int? get() = http.rateRemaining

    /** Las últimas releases de [repo] (las más nuevas primero). */
    suspend fun releases(repo: GhRepo, force: Boolean = false): List<PortRelease> {
        val url = "${apiBase.trimEnd('/')}/repos/${repo.owner}/${repo.name}/releases?per_page=$PER_PAGE"
        return parseReleases(http.get(url, allow, RELEASES_TTL, force, headers()) { runCatching { parseReleases(it) }.isSuccess })
    }

    /** Estrellas, licencia y descripción de [repo]. */
    suspend fun repoInfo(repo: GhRepo, force: Boolean = false): RepoInfo {
        val url = "${apiBase.trimEnd('/')}/repos/${repo.owner}/${repo.name}"
        val body = http.get(url, allow, REPO_TTL, force, headers()) { runCatching { json.decodeFromString<GhRepoJson>(it) }.isSuccess }
        val r = try {
            json.decodeFromString<GhRepoJson>(body)
        } catch (e: Exception) {
            throw UpdateError.BadResponse(e.message ?: "bad json")
        }
        return RepoInfo(r.stars, r.license?.spdx?.takeIf { it != "NOASSERTION" } ?: r.license?.name, r.description, instant(r.pushedAt))
    }

    private fun headers(): Map<String, String> = buildMap {
        put("Accept", "application/vnd.github+json")
        put("X-GitHub-Api-Version", "2022-11-28")
        token.value()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
    }

    internal fun parseReleases(body: String): List<PortRelease> = try {
        json.decodeFromString<List<GhReleaseJson>>(body).map { r ->
            PortRelease(
                tag = r.tag,
                title = r.name?.takeIf { it.isNotBlank() } ?: r.tag,
                notes = r.body.orEmpty(),
                pageUrl = r.htmlUrl,
                prerelease = r.prerelease,
                draft = r.draft,
                publishedAt = instant(r.publishedAt),
                assets = r.assets.map { a -> ReleaseAsset(a.name, a.url, a.size, UpdateLogic.parseDigest(a.digest)) },
            )
        }
    } catch (e: Exception) {
        throw UpdateError.BadResponse(e.message ?: "bad json")
    }

    private fun instant(iso: String?): Long = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    @Serializable
    private data class GhReleaseJson(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<GhAssetJson> = emptyList(),
    )

    @Serializable
    private data class GhAssetJson(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
        val digest: String? = null,
    )

    @Serializable
    private data class GhRepoJson(
        @SerialName("stargazers_count") val stars: Int = 0,
        val description: String? = null,
        @SerialName("pushed_at") val pushedAt: String? = null,
        val license: GhLicenseJson? = null,
    )

    @Serializable
    private data class GhLicenseJson(@SerialName("spdx_id") val spdx: String? = null, val name: String? = null)

    companion object {
        const val API_BASE = "https://api.github.com"
        const val PER_PAGE = 5

        /** Las releases de un port cambian poco: 6 h sin preguntar (y luego, con ETag). */
        const val RELEASES_TTL = 6L * 60 * 60 * 1000
        const val REPO_TTL = 24L * 60 * 60 * 1000

        /** Con menos cupo que esto, la portada deja de pedir releases por adelantado (la ficha sí pide). */
        const val PREFETCH_FLOOR = 10
    }
}

/** El token de GitHub opcional (Ajustes → APIs), leído en cada petición. */
fun interface GitHubToken {
    fun value(): String?
}
