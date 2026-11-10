package com.elyndra.launcher.update

import com.elyndra.launcher.ports.HostAllowlist
import com.elyndra.launcher.ports.PortsHttp
import com.elyndra.launcher.ports.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Descarga un APK con avance y comprobación de SHA-256.
 *
 * Lo usan el actualizador de Elyndra ([UpdateRepository.download]) y la
 * tienda de ports. Para la tienda, además:
 * - [allow]: solo hosts de la lista, siguiendo cada redirección a mano (el
 *   cliente tiene que tener `followRedirects(false)`).
 * - [resume]: lo bajado queda en `<nombre>.part` si se corta (red o
 *   cancelación) y la siguiente vez se sigue con `Range`. Si el servidor no
 *   acepta rangos (200), se empieza de cero.
 */
class ApkDownloader(private val client: OkHttpClient, private val userAgent: String) {

    suspend fun download(
        asset: ReleaseAsset,
        dir: File,
        expectedSha256: String?,
        onProgress: (Float) -> Unit,
        allow: HostAllowlist? = null,
        resume: Boolean = false,
    ): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val out = File(dir, safeName(asset.name))
        val part = if (resume) File(dir, out.name + ".part") else out
        if (!resume) part.delete()
        val already = if (resume) part.length() else 0L
        val request = Request.Builder()
            .url(asset.url)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", userAgent)
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()
        var ok = false
        var keepPart = resume
        try {
            val response = if (allow != null) {
                PortsHttp.execute(client, request, allow)
            } else {
                try {
                    client.newCall(request).await()
                } catch (e: IOException) {
                    throw UpdateError.Network(e)
                }
            }
            response.use { r ->
                val append = already > 0 && r.code == 206
                if (r.code == 416) {
                    // Lo guardado ya no casa con el archivo (cambió en el servidor): de cero la próxima vez.
                    keepPart = false
                    throw UpdateError.Server(r.code)
                }
                if (!r.isSuccessful) throw UpdateError.Server(r.code)
                write(r, part, append, already, asset.size, expectedSha256, onProgress) { keepPart = it }
            }
            if (part != out) {
                out.delete()
                if (!part.renameTo(out)) throw UpdateError.BadResponse("rename failed")
            }
            ok = true
            out
        } finally {
            if (!ok && !keepPart) part.delete()
        }
    }

    private suspend fun write(
        r: Response,
        part: File,
        append: Boolean,
        already: Long,
        assetSize: Long,
        expectedSha256: String?,
        onProgress: (Float) -> Unit,
        keep: (Boolean) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = r.body ?: throw UpdateError.BadResponse("empty body")
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        if (append) {
            // El resumen se calcula sobre el archivo entero: primero lo que ya había.
            part.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            done = already
        }
        val total = (body.contentLength().takeIf { it > 0 }?.plus(done)) ?: assetSize
        try {
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
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
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            keep(false)
            throw UpdateError.Checksum()
        }
    }

    companion object {
        fun safeName(name: String): String = name.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
    }
}
