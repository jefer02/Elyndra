package com.elyndra.launcher.data

import kotlin.math.abs

/**
 * Los colores del arte de reserva, la carátula que se pinta cuando un juego
 * no tiene arte: una base, un tono más profundo y uno más claro y análogo
 * para el degradado en malla. Kotlin puro sobre ARGB (se prueba en la JVM).
 *
 * De dónde sale el color:
 *   · del icono del juego, si lo hay ([dominant]);
 *   · si no, del primario de marca, girado unos grados según el nombre para
 *     que una fila de juegos sin arte no sea un muro del mismo color.
 *
 * Los extremos se recortan: un icono casi blanco, casi negro o gris no da
 * una carátula blanca, negra o de cemento. Los colores vivos se quedan en
 * una saturación y luminosidad medias, y los grises pasan a un grafito frío
 * con el tono de la marca. La base siempre deja el texto blanco en AA.
 */
object FallbackPalette {

    class Tones(val base: Int, val deep: Int, val light: Int) {
        override fun equals(other: Any?) = other is Tones && other.base == base && other.deep == deep && other.light == light
        override fun hashCode() = (base * 31 + deep) * 31 + light
    }

    /** Por debajo de esta saturación el color se trata como gris. */
    const val GRAY_SATURATION = 0.12f

    const val MIN_SATURATION = 0.38f
    const val MAX_SATURATION = 0.72f
    const val MIN_LIGHTNESS = 0.30f
    const val MAX_LIGHTNESS = 0.44f

    /** Saturación del grafito que sustituye a los grises. */
    const val NEUTRAL_SATURATION = 0.16f
    const val NEUTRAL_LIGHTNESS = 0.30f

    /** Giro máximo de tono (grados) cuando no hay color del icono. */
    const val SEED_SPREAD = 16f

    private const val WHITE = 0xFFFFFFFF.toInt()

    /**
     * Los tonos para un juego. [extracted] es el color del icono (null si no
     * hay icono o no se pudo leer); [seed], lo que identifica al juego.
     */
    fun of(extracted: Int?, seed: String): Tones {
        val source = extracted ?: BrandTokens.PRIMARY
        val hsl = ColorMath.toHsl(ColorMath.opaque(source))
        var h = hsl[0]
        val s: Float
        val l: Float
        if (hsl[1] < GRAY_SATURATION) {
            // Gris, blanco o negro: grafito frío con el tono de la marca.
            h = ColorMath.toHsl(BrandTokens.PRIMARY)[0]
            s = NEUTRAL_SATURATION
            l = NEUTRAL_LIGHTNESS
        } else {
            if (extracted == null) h += seedShift(seed)
            s = hsl[1].coerceIn(MIN_SATURATION, MAX_SATURATION)
            l = hsl[2].coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
        }
        val base = ColorMath.ensureContrast(ColorMath.fromHsl(h, s, l), WHITE, 4.5)
        val bl = ColorMath.toHsl(base)[2]
        val deep = ColorMath.fromHsl(h + 10f, (s * 1.05f).coerceAtMost(1f), bl * 0.52f)
        val light = ColorMath.fromHsl(h - 24f, s * 0.9f, (bl + 0.20f).coerceAtMost(0.62f))
        return Tones(base, deep, light)
    }

    /** Giro de tono estable para [seed], en −[SEED_SPREAD]…+[SEED_SPREAD]. */
    fun seedShift(seed: String): Float {
        if (seed.isEmpty()) return 0f
        val u = (hash(seed) ushr 8) / 16_777_215f
        return (u * 2f - 1f) * SEED_SPREAD
    }

    /** Hash estable (FNV-1a) para colocar los blobs y girar el tono. */
    fun hash(seed: String): Int {
        var h = 0x811C9DC5.toInt()
        for (ch in seed) {
            h = h xor ch.code
            h *= 0x01000193
        }
        return h
    }

    /** Fracción 0…1 estable de [seed] para el canal [slot] (posición de los blobs). */
    fun unit(seed: String, slot: Int): Float {
        val h = hash(seed) xor (slot * 0x9E3779B1.toInt())
        val mixed = (h xor (h ushr 15)) * 0x2C1B3C6D
        return ((mixed ushr 8) and 0xFFFF) / 65_535f
    }

    /**
     * El color que manda en un icono, para [of]. Primero el tono vivo que más
     * pesa (lo mismo que el acento del panel); si el icono es gris, su gris
     * medio —que [of] convierte en grafito—; null si no tiene píxeles opacos.
     */
    fun dominant(pixels: IntArray): Int? {
        vivid(pixels)?.let { return it }
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
        }
        if (n == 0) return null
        return ColorMath.argb(255, (r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** El tono vivo con más peso (cubetas de tono pesadas por saturación); null si no hay color. */
    private fun vivid(pixels: IntArray): Int? {
        val buckets = 24
        val weight = FloatArray(buckets)
        val rs = FloatArray(buckets)
        val gs = FloatArray(buckets)
        val bs = FloatArray(buckets)
        val hsl = FloatArray(3)
        var counted = 0
        for (p in pixels) {
            if ((p ushr 24) < 128) continue
            counted++
            ColorMath.toHsl(p, hsl)
            if (hsl[1] < 0.18f || hsl[2] < 0.08f || hsl[2] > 0.95f) continue
            val w = hsl[1] * (1f - abs(hsl[2] - 0.5f) * 1.4f).coerceAtLeast(0.1f)
            val k = ((hsl[0] / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
            weight[k] += w
            rs[k] += ((p shr 16) and 0xFF) * w
            gs[k] += ((p shr 8) and 0xFF) * w
            bs[k] += (p and 0xFF) * w
        }
        if (counted == 0 || weight.sum() / counted < 0.06f) return null
        var best = 0
        for (k in 1 until buckets) if (weight[k] > weight[best]) best = k
        val w = weight[best]
        if (w <= 0f) return null
        return ColorMath.argb(255, (rs[best] / w).toInt(), (gs[best] / w).toInt(), (bs[best] / w).toInt())
    }
}

/**
 * Desenfoque de caja sobre píxeles ARGB (sin premultiplicar), en su sitio:
 * [passes] pasadas de radio [radius] se acercan a un gaussiano. Es lo que
 * convierte el icono reducido en la base borrosa del arte de reserva; se hace
 * una vez por juego y se guarda.
 */
object PixelBlur {

    fun blur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int = 3): IntArray {
        if (w <= 0 || h <= 0 || radius <= 0) return px
        val tmp = IntArray(px.size)
        repeat(passes) {
            pass(px, tmp, w, h, radius, horizontal = true)
            pass(tmp, px, w, h, radius, horizontal = false)
        }
        return px
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = r * 2 + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = at(i)
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until len) {
                val out = ((a / div) shl 24) or ((rr / div) shl 16) or ((g / div) shl 8) or (b / div)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val add = at(i + r + 1)
                val sub = at(i - r)
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
