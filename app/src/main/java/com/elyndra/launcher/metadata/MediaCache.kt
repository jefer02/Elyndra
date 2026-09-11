package com.elyndra.launcher.metadata

import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Imágenes descargadas (carátulas, fondos, logos, capturas) en
 * files/media/<hash de la clave>/. En la biblioteca se guardan rutas
 * relativas a filesDir, y cada descarga lleva marca de tiempo en el nombre
 * para que el caché de imágenes de la UI nunca muestre una versión vieja.
 */
class MediaCache(private val filesDir: File) {

    private val root = File(filesDir, "media")

    fun file(relative: String?): File? = relative?.let { File(filesDir, it) }?.takeIf { it.exists() }

    private fun dirFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(root, digest.take(10).joinToString("") { "%02x".format(it) })
    }

    /** Descarga [url] como [kind] ("cover", "hero"…) de [key]. Devuelve la ruta relativa o null. */
    suspend fun download(url: String, key: String, kind: String): String? {
        val result = try {
            Http.client.newCall(Request.Builder().url(url).get().build()).await()
        } catch (e: IOException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        return result.use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_BYTES) return null
            val bytes = try {
                body.bytes()
            } catch (e: IOException) {
                return null
            }
            if (bytes.size > MAX_BYTES) return null
            val ext = sniff(bytes) ?: return null
            val dir = dirFor(key).apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("${kind}_") }?.forEach { it.delete() }
            val target = File(dir, "${kind}_${System.currentTimeMillis()}.$ext")
            val tmp = File(dir, target.name + ".tmp")
            runCatching {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) return null
            }.onFailure { return null }
            target.relativeTo(filesDir).path.replace('\\', '/')
        }
    }

    fun deleteFor(key: String) {
        dirFor(key).deleteRecursively()
    }

    fun clearAll() {
        root.deleteRecursively()
    }

    fun sizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val MAX_BYTES = 20L * 1024 * 1024

        /** Formato real por los primeros bytes (ScreenScraper responde "NOMEDIA" en texto si no hay imagen). */
        fun sniff(b: ByteArray): String? = when {
            b.size < 12 -> null
            b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "png"
            b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
            b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
            String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
            else -> null
        }
    }
}
