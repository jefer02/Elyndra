package com.elyndra.launcher.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Matemática de color sobre enteros ARGB, sin Android ni Compose: contraste
 * WCAG, HSL, mezclas y el ajuste que garantiza un contraste mínimo. Es lo que
 * usan la paleta (P, acentos), el arte de reserva y el widget, y se prueba en
 * la JVM.
 */
object ColorMath {

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    fun opaque(c: Int): Int = c or (0xFF shl 24)

    fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Luminancia relativa WCAG 2.x (0 negro … 1 blanco). */
    fun luminance(c: Int): Double = 0.2126 * channel(red(c)) + 0.7152 * channel(green(c)) + 0.0722 * channel(blue(c))

    /** Contraste WCAG entre dos colores opacos: 1 … 21. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [top] (con su alfa) sobre [bottom] opaco. */
    fun over(top: Int, bottom: Int): Int {
        val a = alpha(top) / 255f
        fun mix(t: Int, b: Int) = (t * a + b * (1f - a) + 0.5f).toInt()
        return argb(255, mix(red(top), red(bottom)), mix(green(top), green(bottom)), mix(blue(top), blue(bottom)))
    }

    /** Mezcla lineal en sRGB: [t] = 0 → [a], 1 → [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun m(x: Int, y: Int) = (x + (y - x) * t + 0.5f).toInt()
        return argb(m(alpha(a), alpha(b)), m(red(a), red(b)), m(green(a), green(b)), m(blue(a), blue(b)))
    }

    /** A HSL: h 0…360, s y l 0…1. */
    fun toHsl(c: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val r = red(c) / 255f
        val g = green(c) / 255f
        val b = blue(c) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        if (d < 1e-6f) {
            out[0] = 0f; out[1] = 0f; out[2] = l
            return out
        }
        val s = d / (1f - abs(2f * l - 1f))
        var h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = s.coerceIn(0f, 1f)
        out[2] = l
        return out
    }

    fun fromHsl(h: Float, s: Float, l: Float, alpha: Int = 255): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val ss = s.coerceIn(0f, 1f)
        val ll = l.coerceIn(0f, 1f)
        val c = (1f - abs(2f * ll - 1f)) * ss
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = ll - c / 2f
        val (r, g, b) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt()
        return argb(alpha, ch(r), ch(g), ch(b))
    }

    /**
     * [fg] con el mismo tono y saturación, y la luminosidad justa para alcanzar
     * [minRatio] contra [bg]: se oscurece si el fondo es claro y se aclara si es
     * oscuro. Si ya cumple, se devuelve tal cual.
     */
    fun ensureContrast(fg: Int, bg: Int, minRatio: Double): Int {
        val solid = opaque(fg)
        if (contrast(solid, bg) >= minRatio) return solid
        val hsl = toHsl(solid)
        val darken = luminance(bg) > 0.18
        var lo = if (darken) 0f else hsl[2]
        var hi = if (darken) hsl[2] else 1f
        var best = if (darken) fromHsl(hsl[0], hsl[1], 0f) else fromHsl(hsl[0], hsl[1], 1f)
        repeat(24) {
            val mid = (lo + hi) / 2f
            val c = fromHsl(hsl[0], hsl[1], mid)
            if (contrast(c, bg) >= minRatio) {
                best = c
                if (darken) lo = mid else hi = mid
            } else {
                if (darken) hi = mid else lo = mid
            }
        }
        return best
    }
}
