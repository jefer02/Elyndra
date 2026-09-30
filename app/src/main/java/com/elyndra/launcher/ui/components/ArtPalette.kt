package com.elyndra.launcher.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * El color de acento de un arte: el tono vivo que más pesa en la imagen, ya
 * llevado a una luminosidad que se lee como acento (ni casi negro ni casi
 * blanco). Kotlin puro sobre píxeles ARGB (se prueba en la JVM).
 *
 * No es la media: la media de una carátula llena de colores es un marrón
 * grisáceo. Se agrupan los píxeles por tono (24 cubetas) pesando cada uno por
 * su saturación, se elige la cubeta más pesada y de ella el color medio.
 * Si la imagen es gris (o casi), devuelve null y quien llama usa el acento
 * del tema.
 */
object ArtPalette {

    private const val BUCKETS = 24

    /** Saturación mínima para que un píxel cuente como color. */
    private const val MIN_SATURATION = 0.18f

    /** Por debajo de este peso (fracción de píxeles "de color") la imagen es gris. */
    private const val MIN_COLORFUL = 0.06f

    fun accentOf(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val weight = FloatArray(BUCKETS)
        val r = FloatArray(BUCKETS)
        val g = FloatArray(BUCKETS)
        val b = FloatArray(BUCKETS)
        val hsl = FloatArray(3)
        var counted = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            counted++
            val pr = (p shr 16) and 0xFF
            val pg = (p shr 8) and 0xFF
            val pb = p and 0xFF
            rgbToHsl(pr, pg, pb, hsl)
            val s = hsl[1]
            val l = hsl[2]
            if (s < MIN_SATURATION || l < 0.08f || l > 0.95f) continue
            // Los medios tonos pesan más que los casi negros o casi blancos.
            val w = s * (1f - abs(l - 0.5f) * 1.4f).coerceAtLeast(0.1f)
            val k = ((hsl[0] / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            weight[k] += w
            r[k] += pr * w
            g[k] += pg * w
            b[k] += pb * w
        }
        if (counted == 0) return null
        // Cubetas vecinas juntas: un degradado de un mismo tono no se reparte en dos.
        var best = -1
        var bestW = 0f
        for (k in 0 until BUCKETS) {
            val w = weight[k] + 0.5f * (weight[(k + 1) % BUCKETS] + weight[(k + BUCKETS - 1) % BUCKETS])
            if (w > bestW) { bestW = w; best = k }
        }
        if (best < 0 || weight[best] <= 0f || weight.sum() / counted < MIN_COLORFUL) return null
        val w = weight[best]
        rgbToHsl((r[best] / w).toInt(), (g[best] / w).toInt(), (b[best] / w).toInt(), hsl)
        // Acento legible: saturación viva y luminosidad media.
        return hslToArgb(hsl[0], hsl[1].coerceIn(0.45f, 0.9f), hsl[2].coerceIn(0.45f, 0.62f))
    }

    /** Un color más oscuro del mismo tono: el segundo extremo del degradado del botón. */
    fun deeper(argb: Int): Int {
        val hsl = FloatArray(3)
        rgbToHsl((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, hsl)
        return hslToArgb((hsl[0] + 12f) % 360f, (hsl[1] * 1.05f).coerceAtMost(1f), (hsl[2] - 0.16f).coerceAtLeast(0.22f))
    }

    internal fun rgbToHsl(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val mx = max(rf, max(gf, bf))
        val mn = min(rf, min(gf, bf))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d < 1e-6f) {
            out[0] = 0f; out[1] = 0f; out[2] = l
            return
        }
        val s = d / (1f - abs(2f * l - 1f))
        val h = when (mx) {
            rf -> 60f * (((gf - bf) / d) % 6f)
            gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        out[0] = if (h < 0f) h + 360f else h
        out[1] = s.coerceIn(0f, 1f)
        out[2] = l
    }

    internal fun hslToArgb(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /**
     * El acento de la primera imagen de [paths] que tenga color (relativas a
     * `filesDir`, como todas las de la biblioteca). Se decodifica a 32 px con
     * Coil, que ya las tiene en caché la mayoría de las veces. Null = ninguna.
     */
    suspend fun load(context: Context, paths: List<String>): Int? {
        for (path in paths) {
            val request = ImageRequest.Builder(context).data(File(context.filesDir, path)).size(32).allowHardware(false).build()
            val result = context.imageLoader.execute(request) as? SuccessResult ?: continue
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: continue
            val accent = withContext(Dispatchers.Default) { accentOf(pixelsOf(bitmap)) }
            if (accent != null) return accent
        }
        return null
    }

    private fun pixelsOf(bitmap: Bitmap): IntArray {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return px
    }
}
