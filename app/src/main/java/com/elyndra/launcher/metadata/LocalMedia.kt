package com.elyndra.launcher.metadata

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Una imagen traída del dispositivo, lista para guardarse en [MediaCache]. */
data class LocalImage(val bytes: ByteArray, val extension: String) {
    // ByteArray no compara por contenido: data class con array necesita esto.
    override fun equals(other: Any?): Boolean =
        this === other || (other is LocalImage && extension == other.extension && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + extension.hashCode()
}

/**
 * "Elegir de la galería": lee la imagen que el usuario ha escogido y la deja
 * en un formato que Elyndra sabe guardar y volver a pintar.
 *
 * Una foto del carrete no se puede copiar tal cual:
 *
 *   · Puede venir en HEIC o AVIF, que [MediaCache.sniff] no reconoce y que
 *     Coil no siempre repinta. Se decodifica y se vuelve a codificar.
 *   · Puede pesar decenas de megas. Como fondo de una card no hace falta más
 *     de [MAX_EDGE] px de lado, así que se reduce al vuelo con `inSampleSize`
 *     (que además evita cargar el bitmap entero en memoria).
 *
 * Se conserva el archivo original cuando ya es pequeño y de un formato
 * conocido: así un PNG de logo con transparencia llega intacto.
 */
class LocalMedia(private val resolver: ContentResolver) {

    fun readImage(uri: Uri): LocalImage? {
        val bytes = read(uri) ?: return null
        val ext = MediaCache.sniff(bytes)
        if (ext != null && bytes.size <= KEEP_AS_IS_BYTES) return LocalImage(bytes, ext)
        return transcode(bytes) ?: ext?.let { LocalImage(bytes, it) }
    }

    private fun read(uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { it.readAtMost(MAX_INPUT_BYTES) }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Reduce y vuelve a codificar. PNG si la imagen tiene transparencia (logos, iconos). */
    private fun transcode(bytes: ByteArray): LocalImage? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val edge = maxOf(bounds.outWidth, bounds.outHeight)
        if (edge <= 0) return null

        var sample = 1
        while (edge / sample > MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        val alpha = bitmap.hasAlpha()
        val out = ByteArrayOutputStream()
        val ok = if (alpha) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }
        bitmap.recycle()
        if (!ok || out.size() == 0) return null
        LocalImage(out.toByteArray(), if (alpha) "png" else "jpg")
    }.getOrNull()

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() <= limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return if (out.size() > limit) ByteArray(0) else out.toByteArray()
    }

    private companion object {
        /** Lado máximo del resultado: de sobra para un fondo a pantalla completa. */
        const val MAX_EDGE = 2048

        /** Por debajo de esto y en un formato conocido, se copia el archivo tal cual. */
        const val KEEP_AS_IS_BYTES = 4 * 1024 * 1024

        /** Tope de lectura. Más allá, la foto se descarta antes de decodificarla. */
        const val MAX_INPUT_BYTES = 64 * 1024 * 1024

        const val JPEG_QUALITY = 92
    }
}
