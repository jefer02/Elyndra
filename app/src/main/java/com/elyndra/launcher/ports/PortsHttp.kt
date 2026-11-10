package com.elyndra.launcher.ports

import com.elyndra.launcher.update.UpdateError
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* Lo común a las peticiones de la tienda de ports: a qué hosts se puede ir
   y cómo seguir las redirecciones comprobando cada salto. */

/**
 * Los hosts a los que puede ir la tienda.
 *
 * El cliente de ports no sigue redirecciones solo (`followRedirects(false)`):
 * las sigue [PortsHttp.execute] y comprueba aquí cada salto, así un APK
 * nunca se baja de un sitio que no sea GitHub aunque una redirección lo intente.
 */
class HostAllowlist(hosts: Set<String>, private val allowHttp: Boolean = false) {
    private val hosts = hosts.map { it.lowercase() }.toSet()

    /** Solo https (salvo en las pruebas, contra un MockWebServer) y solo estos hosts. */
    fun allows(url: HttpUrl): Boolean = url.host.lowercase() in hosts && (url.isHttps || allowHttp)

    companion object {
        /** API de GitHub. */
        val API = HostAllowlist(setOf("api.github.com"))

        /** El catálogo: archivos en bruto de un repositorio de GitHub. */
        val CATALOG = HostAllowlist(setOf("raw.githubusercontent.com", "gist.githubusercontent.com"))

        /**
         * Los APK de las releases: `github.com` redirige al almacén de archivos,
         * que hoy es `release-assets.githubusercontent.com` (antes
         * `objects.githubusercontent.com`).
         */
        val DOWNLOADS = HostAllowlist(
            setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"),
        )
    }
}

object PortsHttp {

    const val USER_AGENT = "Elyndra-Ports"
    private const val MAX_REDIRECTS = 5

    /**
     * Hace [request] siguiendo las redirecciones a mano, como mucho
     * [MAX_REDIRECTS], y solo hacia hosts de [allow]. Un salto fuera de la
     * lista falla con [UpdateError.BadResponse] sin llegar a conectar.
     * Las cabeceras de autorización no pasan de un host a otro.
     */
    suspend fun execute(client: OkHttpClient, request: Request, allow: HostAllowlist): Response {
        var req = request
        repeat(MAX_REDIRECTS + 1) {
            if (!allow.allows(req.url)) throw UpdateError.BadResponse("host not allowed: ${req.url.host}")
            val response = try {
                client.newCall(req).await()
            } catch (e: IOException) {
                throw UpdateError.Network(e)
            }
            if (response.code !in REDIRECTS) return response
            val location = response.header("Location")
            response.close()
            val next = location?.let { req.url.resolve(it) ?: it.toHttpUrlOrNull() }
                ?: throw UpdateError.BadResponse("redirect without location")
            req = req.newBuilder().url(next).apply {
                if (next.host != req.url.host) removeHeader("Authorization")
            }.build()
        }
        throw UpdateError.BadResponse("too many redirects")
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /** ¿Es un límite de peticiones de GitHub? (429, o 403 con el cupo a cero). */
    fun isRateLimited(r: Response): Boolean = r.code == 429 || (r.code == 403 && r.header("X-RateLimit-Remaining") == "0")
}

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
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
