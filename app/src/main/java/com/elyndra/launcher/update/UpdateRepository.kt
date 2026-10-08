package com.elyndra.launcher.update

import com.elyndra.launcher.di.UpdateHttp
import com.elyndra.launcher.di.UpdateReleasesUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** De dónde salen las actualizaciones y los enlaces de "Acerca de". */
object UpdateSource {
    const val OWNER = "jefer02"
    const val REPO = "Elyndra"
    const val AUTHOR_URL = "https://github.com/$OWNER"
    const val REPO_URL = "https://github.com/$OWNER/$REPO"
    const val RELEASES_URL = "$REPO_URL/releases"

    /** Autor y titular del copyright (LICENSE): un nombre propio, no se traduce. */
    const val AUTHOR_NAME = "Jeferson Manuel Morillo Vallejo"

    /** La API pública, sin token: el repositorio es público y nunca va una credencial en el APK. */
    const val API_RELEASES = "https://api.github.com/repos/$OWNER/$REPO/releases?per_page=20"
}

/** Por qué no se pudo comprobar o descargar. */
sealed class UpdateError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable) : UpdateError(cause.message ?: "network", cause)
    /** GitHub limita las peticiones sin autenticar (60 por hora y por IP). */
    class RateLimited : UpdateError("rate limited")
    class Server(val code: Int) : UpdateError("HTTP $code")
    class BadResponse(message: String) : UpdateError(message)
    /** El APK bajado no coincide con el SHA-256 publicado. */
    class Checksum : UpdateError("checksum mismatch")
}

/**
 * La última lista de releases y su ETag, para las peticiones condicionales:
 * si nada cambió, GitHub contesta 304 (que además no gasta cupo) y se usa la
 * copia guardada.
 */
interface ReleaseCache {
    val etag: String?
    val body: String?
    fun store(etag: String?, body: String)
}

/**
 * Las releases de GitHub del repositorio de Elyndra.
 *
 * Solo pide la lista pública de releases y los archivos que se van a
 * instalar: ninguna petición lleva datos del usuario (ni identificadores, ni
 * la biblioteca, ni la versión de Android), solo un User-Agent genérico.
 */
@Singleton
class UpdateRepository @Inject constructor(
    @UpdateHttp private val client: OkHttpClient,
    @UpdateReleasesUrl private val releasesUrl: String,
    private val cache: ReleaseCache,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Las releases publicadas, de la red o, si no cambiaron (304), de la copia guardada. */
    suspend fun releases(): List<Release> = withContext(Dispatchers.IO) {
        val cachedBody = cache.body
        val request = Request.Builder()
            .url(releasesUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
            .apply { cache.etag?.takeIf { cachedBody != null }?.let { header("If-None-Match", it) } }
            .build()
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw UpdateError.Network(e)
        }
        response.use { r ->
            when {
                r.code == 304 && cachedBody != null -> parse(cachedBody)
                r.isSuccessful -> {
                    val body = try {
                        r.body?.string().orEmpty()
                    } catch (e: IOException) {
                        throw UpdateError.Network(e)
                    }
                    val list = parse(body)
                    cache.store(r.header("ETag"), body)
                    list
                }
                r.code == 429 || (r.code == 403 && r.header("X-RateLimit-Remaining") == "0") -> throw UpdateError.RateLimited()
                else -> throw UpdateError.Server(r.code)
            }
        }
    }

    internal fun parse(body: String): List<Release> = try {
        json.decodeFromString<List<GhRelease>>(body).map { r ->
            Release(
                tag = r.tag,
                title = r.name?.takeIf { it.isNotBlank() } ?: r.tag,
                notes = r.body.orEmpty(),
                pageUrl = r.htmlUrl,
                prerelease = r.prerelease,
                draft = r.draft,
                assets = r.assets.map { a -> ReleaseAsset(a.name, a.url, a.size, UpdateLogic.parseDigest(a.digest)) },
            )
        }
    } catch (e: Exception) {
        throw UpdateError.BadResponse(e.message ?: "bad json")
    }

    /**
     * El SHA-256 con el que comprobar [apk]: el `digest` de la API o, si no
     * viene, el `.sha256` publicado al lado. Null = no hay con qué comprobarlo.
     */
    suspend fun expectedSha256(apk: ReleaseAsset, release: Release): String? {
        apk.sha256?.let { return it }
        val sidecar = UpdateLogic.checksumAsset(apk, release.assets) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(sidecar.url).header("User-Agent", USER_AGENT).build()).await().use { r ->
                    if (r.isSuccessful) UpdateLogic.parseChecksumFile(r.body?.string().orEmpty()) else null
                }
            }.getOrNull()
        }
    }

    /**
     * Descarga [asset] en [dir] e informa del avance (0..1) por [onProgress].
     *
     * Con [expectedSha256], el archivo se comprueba mientras se escribe y, si
     * no coincide, se borra y falla con [UpdateError.Checksum]. Cancelar la
     * corrutina corta la descarga y borra lo bajado.
     */
    suspend fun download(asset: ReleaseAsset, dir: File, expectedSha256: String?, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val out = File(dir, asset.name.replace(Regex("""[^A-Za-z0-9._-]"""), "_"))
            val request = Request.Builder()
                .url(asset.url)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .build()
            var ok = false
            try {
                val response = try {
                    client.newCall(request).await()
                } catch (e: IOException) {
                    throw UpdateError.Network(e)
                }
                response.use { r ->
                    if (!r.isSuccessful) throw UpdateError.Server(r.code)
                    val body = r.body ?: throw UpdateError.BadResponse("empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: asset.size
                    val digest = MessageDigest.getInstance("SHA-256")
                    try {
                        body.byteStream().use { input ->
                            out.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var done = 0L
                                var lastReported = -1
                                while (true) {
                                    ensureActive()
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                    done += n
                                    if (total > 0) {
                                        // Un aviso por cada 1 %: más solo repintaría lo mismo.
                                        val pct = (done * 100 / total).toInt()
                                        if (pct != lastReported) {
                                            lastReported = pct
                                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: IOException) {
                        throw UpdateError.Network(e)
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) throw UpdateError.Checksum()
                }
                ok = true
                out
            } finally {
                if (!ok) out.delete()
            }
        }

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val name: String? = null,
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String,
        @SerialName("browser_download_url") val url: String,
        val size: Long = 0,
        val digest: String? = null,
    )

    private companion object {
        /** Genérico a propósito: GitHub exige User-Agent, pero no hace falta que diga nada del dispositivo. */
        const val USER_AGENT = "Elyndra-Updater"
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
