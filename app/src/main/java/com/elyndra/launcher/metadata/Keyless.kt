package com.elyndra.launcher.metadata

import com.elyndra.launcher.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.File

/* ─────────────────────────────────────────────────────────────
   Fuentes sin clave (libretro, tienda de Steam): lo común.

   Son servicios públicos que no piden cuenta, así que hay que ser
   buen vecino: User-Agent que dice quién es, una petición cada poco
   (no ráfagas), reintentos con espera creciente si el servidor se
   queja, y caché de aciertos (MediaCache) y de fallos ([MissCache]):
   un juego que no está no se vuelve a preguntar en días.

   Nada de esto puede tumbar una pasada: un error es "no hay".
   ───────────────────────────────────────────────────────────── */

object KeylessHttp {

    /** Quién pregunta: la app y su versión, sin nada del dispositivo. */
    val USER_AGENT = "Elyndra/${BuildConfig.VERSION_NAME} (Android game launcher; keyless metadata)"

    /** Reintentos ante 429, 5xx o red caída: espera 1 s, luego 3 s. */
    val BACKOFF_MS = longArrayOf(1_000, 3_000)

    fun request(url: String): Request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

    /**
     * GET con reintentos. Devuelve el resultado (también un 404, que es "no
     * está") o null si tras los reintentos no hubo respuesta útil.
     */
    suspend fun get(url: String, limiter: RateLimiter, backoff: LongArray = BACKOFF_MS): HttpResult? {
        var attempt = 0
        while (true) {
            limiter.acquire()
            val result = try {
                Http.text(request(url))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val retryable = result == null || result.code == 429 || result.code >= 500
            if (!retryable) return result
            if (attempt >= backoff.size) return null
            delay(backoff[attempt])
            attempt++
        }
    }

    /** HEAD: ¿existe [url]? null = no se pudo saber (red). */
    suspend fun exists(url: String, limiter: RateLimiter): Boolean? {
        limiter.acquire()
        return try {
            Http.client.newCall(Request.Builder().url(url).head().header("User-Agent", USER_AGENT).build()).await().use {
                when {
                    it.isSuccessful -> true
                    it.code == 404 -> false
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}

/** Una petición cada [minIntervalMs] como mucho, entre todas las corrutinas. */
class RateLimiter(private val minIntervalMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun acquire() = mutex.withLock {
        val wait = last + minIntervalMs - clock()
        if (wait > 0) delay(wait)
        last = clock()
    }
}

/**
 * Caché de fallos: "esto no estaba" con fecha. Mientras no caduque
 * ([ttlMs]), no se vuelve a preguntar. Un archivo de texto (clave\tfecha).
 */
class MissCache(private val file: File?, private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {

    private val entries = HashMap<String, Long>()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        runCatching {
            if (!f.exists()) return
            f.forEachLine { line ->
                val tab = line.lastIndexOf('\t')
                if (tab > 0) line.substring(tab + 1).toLongOrNull()?.let { entries[line.substring(0, tab)] = it }
            }
        }
    }

    @Synchronized
    fun isMiss(key: String): Boolean {
        load()
        val at = entries[key] ?: return false
        if (clock() - at < ttlMs) return true
        entries.remove(key)
        return false
    }

    @Synchronized
    fun markMiss(key: String) {
        load()
        entries[key] = clock()
        save()
    }

    @Synchronized
    fun clearMiss(key: String) {
        load()
        if (entries.remove(key) != null) save()
    }

    private fun save() {
        val f = file ?: return
        val now = clock()
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText(entries.filter { now - it.value < ttlMs }.entries.joinToString("\n") { "${it.key}\t${it.value}" })
        }
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
