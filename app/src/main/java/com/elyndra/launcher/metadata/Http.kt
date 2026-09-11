package com.elyndra.launcher.metadata

import com.elyndra.launcher.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Errores comunes de los servicios de metadatos. */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Credenciales incorrectas o sin permiso. */
    class Unauthorized(message: String) : ApiException(message)
    class NotFound(message: String = "not found") : ApiException(message)
    /** Cupo diario agotado (ScreenScraper 430/431). */
    class QuotaExceeded(message: String) : ApiException(message)
    class RateLimited(message: String) : ApiException(message)
    /** El servicio rechaza este software (ScreenScraper 426). */
    class Blocked(message: String) : ApiException(message)
    class Server(val code: Int, message: String) : ApiException(message)
    class Network(cause: Throwable) : ApiException(cause.message ?: cause.javaClass.simpleName, cause)
    class BadResponse(message: String) : ApiException(message)
}

data class HttpResult(val code: Int, val body: String, val contentType: String?)

object Http {

    val USER_AGENT = "Elyndra/${BuildConfig.VERSION_NAME} (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(if (req.header("User-Agent") == null) req.newBuilder().header("User-Agent", USER_AGENT).build() else req)
            }
            .build()
    }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Ejecuta la petición de forma cancelable y devuelve el cuerpo como texto. */
    suspend fun text(request: Request): HttpResult {
        val response = try {
            client.newCall(request).await()
        } catch (e: IOException) {
            throw ApiException.Network(e)
        }
        return response.use {
            val body = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw ApiException.Network(e)
            }
            HttpResult(it.code, body, it.header("Content-Type"))
        }
    }

    fun parse(body: String): JsonElement = try {
        json.parseToJsonElement(body)
    } catch (e: Exception) {
        throw ApiException.BadResponse(body.take(160))
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * Espaciado mínimo entre peticiones a un mismo servicio y una sola petición a
 * la vez (ScreenScraper da 1 hilo a las cuentas normales; IGDB permite 4/s).
 */
class RateGate(private val minIntervalMs: Long) {
    private val mutex = Mutex()
    private var lastAt = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = lastAt + minIntervalMs - now()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastAt = now()
        }
    }

    private fun now() = System.nanoTime() / 1_000_000
}

/** Reintenta ante límites de ritmo con espera creciente. */
suspend fun <T> withRateRetry(attempts: Int = 3, baseDelayMs: Long = 2_000, block: suspend () -> T): T {
    var n = 0
    while (true) {
        try {
            return block()
        } catch (e: ApiException.RateLimited) {
            n++
            if (n >= attempts) throw e
            delay(baseDelayMs * n)
        }
    }
}

/* ── JSON tolerante: los servicios mezclan números y cadenas ── */

fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

fun JsonElement?.asArray(): JsonArray? = when (this) {
    is JsonArray -> this
    is JsonObject -> JsonArray(this.values.toList())
    else -> null
}

fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

fun JsonElement?.asLong(): Long? = asString()?.trim()?.toLongOrNull() ?: asString()?.trim()?.toDoubleOrNull()?.toLong()

fun JsonElement?.asInt(): Int? = asLong()?.toInt()

fun JsonElement?.asDouble(): Double? = asString()?.trim()?.toDoubleOrNull()

fun JsonElement?.asBool(): Boolean? = when (val s = asString()?.lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

fun JsonObject.str(key: String): String? = this[key].asString()?.takeIf { it.isNotBlank() }
