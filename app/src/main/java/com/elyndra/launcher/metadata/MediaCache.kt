package com.elyndra.launcher.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Imágenes descargadas (carátulas, fondos, logos, capturas) en
 * files/media/<hash de la clave>/. En la biblioteca se guardan rutas
 * relativas a filesDir, y cada descarga lleva marca de tiempo en el nombre
 * para que el caché de imágenes de la UI nunca muestre una versión vieja.
 */
class MediaCache(private val filesDir: File, private val codec: ImageCodec = AndroidImageCodec) {

    private val root = File(filesDir, "media")

    fun file(relative: String?): File? = relative?.let { File(filesDir, it) }?.takeIf { it.exists() }

    private fun dirFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(root, digest.take(10).joinToString("") { "%02x".format(it) })
    }

    /** Descarga [url] como [kind] ("cover", "hero"…) de [key]. Devuelve la ruta relativa o null. */
    suspend fun download(url: String, key: String, kind: String): String? =
        (fetch(url, key, kind) as? MediaResult.Saved)?.path

    /**
     * Descarga, comprueba y guarda. Antes de tocar nada se decodifica la
     * imagen: si no sirve, la que había se queda y el motivo vuelve en
     * [MediaResult.Failed] (para decírselo al usuario, no fallar en silencio).
     * ICO y BMP —SteamGridDB sirve iconos .ico— se guardan convertidos a PNG.
     */
    suspend fun fetch(url: String, key: String, kind: String): MediaResult = withContext(Dispatchers.IO) {
        // `await()` solo espera las cabeceras: el cuerpo se lee aquí. En el hilo
        // principal Android lo corta con NetworkOnMainThreadException (no es
        // IOException) y la app se cierra; por eso todo va en IO, sea quien sea
        // el que llame.
        val bytes = try {
            Http.client.newCall(Request.Builder().url(url).get().build()).await().use { response ->
                if (!response.isSuccessful) return@withContext MediaResult.Failed(MediaFailure.Http)
                val body = response.body ?: return@withContext MediaResult.Failed(MediaFailure.Http)
                if (body.contentLength() > MAX_BYTES) return@withContext MediaResult.Failed(MediaFailure.TooBig)
                body.bytes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            return@withContext MediaResult.Failed(MediaFailure.Http)
        } catch (e: Exception) {
            return@withContext MediaResult.Failed(MediaFailure.Network)
        }
        when (val checked = ImageRules.check(bytes, codec)) {
            is ImageRules.Checked.Ok -> save(checked.bytes, key, kind, checked.ext)?.let { MediaResult.Saved(it) }
                ?: MediaResult.Failed(MediaFailure.Storage)
            is ImageRules.Checked.Bad -> MediaResult.Failed(checked.reason)
        }
    }

    /**
     * Guarda una imagen ya leída — la que el usuario elige en su galería — como
     * [kind] de [key]. Devuelve la ruta relativa, igual que [download].
     *
     * La marca de tiempo en el nombre es lo que evita que el caché de imágenes
     * de la interfaz siga enseñando la anterior.
     */
    fun save(bytes: ByteArray, key: String, kind: String, ext: String): String? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val dir = dirFor(key).apply { mkdirs() }
        val target = File(dir, "${kind}_${System.currentTimeMillis()}.$ext")
        val tmp = File(dir, target.name + ".tmp")
        val written = runCatching {
            tmp.writeBytes(bytes)
            tmp.renameTo(target)
        }.getOrDefault(false)
        if (!written) {
            tmp.delete()
            return null
        }
        // La anterior se borra solo cuando la nueva ya está en disco: si escribir
        // falla (sin espacio), la biblioteca sigue apuntando a una imagen que existe.
        dir.listFiles { f -> f.name.startsWith("${kind}_") && f.name != target.name }?.forEach { it.delete() }
        return target.relativeTo(filesDir).path.replace('\\', '/')
    }

    /** Lo mismo con bytes ya leídos (galería): se comprueban igual antes de guardar. */
    fun saveChecked(bytes: ByteArray, key: String, kind: String): MediaResult =
        when (val checked = ImageRules.check(bytes, codec)) {
            is ImageRules.Checked.Ok -> save(checked.bytes, key, kind, checked.ext)?.let { MediaResult.Saved(it) }
                ?: MediaResult.Failed(MediaFailure.Storage)
            is ImageRules.Checked.Bad -> MediaResult.Failed(checked.reason)
        }

    fun deleteFor(key: String) {
        dirFor(key).deleteRecursively()
    }

    /** Borra solo una clase de imagen ("cover", "hero", "logo") de [key]. */
    fun delete(key: String, kind: String) {
        dirFor(key).listFiles { f -> f.name.startsWith("${kind}_") }?.forEach { it.delete() }
    }

    fun clearAll() {
        root.deleteRecursively()
    }

    fun sizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        const val MAX_BYTES = 20L * 1024 * 1024

        /** Formato real por los primeros bytes (ver [ImageRules.sniff]). */
        fun sniff(b: ByteArray): String? = ImageRules.sniff(b)
    }
}

/** Lo que pasó al bajar una imagen. */
sealed interface MediaResult {
    data class Saved(val path: String) : MediaResult
    data class Failed(val reason: MediaFailure) : MediaResult
}

enum class MediaFailure { Network, Http, TooBig, Unsupported, Undecodable, TooSmall, Storage }

/** Decodificar sin Compose: el de Android en la app, uno falso en las pruebas. */
interface ImageCodec {
    /** Ancho y alto si se puede decodificar; null si no. */
    fun bounds(bytes: ByteArray): Pair<Int, Int>?

    /** La imagen re-codificada como PNG (para ICO y BMP); null si no se puede. */
    fun toPng(bytes: ByteArray): ByteArray?
}

object AndroidImageCodec : ImageCodec {
    override fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
    }

    override fun toPng(bytes: ByteArray): ByteArray? = runCatching {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        java.io.ByteArrayOutputStream().use { out ->
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }.getOrNull()
}

/** Qué imagen se acepta (Kotlin puro: se prueba en la JVM con un decodificador falso). */
object ImageRules {

    /** Más pequeño que esto no sirve ni de icono. */
    const val MIN_SIDE = 32

    /** Más píxeles que esto no se decodifica sin riesgo de quedarse sin memoria. */
    const val MAX_PIXELS = 50_000_000L

    /** Formato real por los primeros bytes (ScreenScraper responde "NOMEDIA" en texto si no hay imagen). */
    fun sniff(b: ByteArray): String? = when {
        b.size < 12 -> null
        b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "png"
        b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
        String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        // Iconos de Windows (.ico): SteamGridDB los sirve entre sus iconos.
        b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte() && b[3] == 0.toByte() -> "ico"
        b[0] == 'B'.code.toByte() && b[1] == 'M'.code.toByte() -> "bmp"
        else -> null
    }

    sealed interface Checked {
        /** Lista para guardar: [bytes] puede ser la versión PNG del original. */
        class Ok(val bytes: ByteArray, val ext: String) : Checked
        data class Bad(val reason: MediaFailure) : Checked
    }

    fun check(bytes: ByteArray, codec: ImageCodec): Checked {
        if (bytes.size > MediaCache.MAX_BYTES) return Checked.Bad(MediaFailure.TooBig)
        val ext = sniff(bytes) ?: return Checked.Bad(MediaFailure.Unsupported)
        val (w, h) = runCatching { codec.bounds(bytes) }.getOrNull() ?: return Checked.Bad(MediaFailure.Undecodable)
        if (w.toLong() * h > MAX_PIXELS) return Checked.Bad(MediaFailure.TooBig)
        if (w < MIN_SIDE || h < MIN_SIDE) return Checked.Bad(MediaFailure.TooSmall)
        if (ext == "ico" || ext == "bmp") {
            val png = runCatching { codec.toPng(bytes) }.getOrNull() ?: return Checked.Bad(MediaFailure.Undecodable)
            return Checked.Ok(png, "png")
        }
        return Checked.Ok(bytes, ext)
    }
}
