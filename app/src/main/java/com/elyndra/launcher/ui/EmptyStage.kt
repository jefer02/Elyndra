package com.elyndra.launcher.ui

import com.elyndra.launcher.data.ColorMath
import java.text.Normalizer
import kotlin.math.min

/* ─────────────────────────────────────────────────────────────
   El estado vacío de la biblioteca, sin Compose: los colores del
   escenario que sustituye al arte cuando no hay juego (Meridian y el
   hero clásico, en horizontal y en vertical) y la búsqueda del cuerpo
   del titular que cabe en su caja. Todo son números para probarlo en
   la JVM; el dibujo vive en ui/components/EmptyLibrary.kt.
   ───────────────────────────────────────────────────────────── */

/**
 * Los colores del escenario vacío (ARGB opacos salvo donde se dice):
 * el fondo de arriba abajo, el resplandor del primario de la paleta de
 * firma ([glow], con su opacidad máxima [glowAlpha]), el halo del
 * secundario ([halo], [haloAlpha]) y las órbitas finas del destello ([ring]).
 */
data class StageColors(
    val top: Int,
    val bottom: Int,
    val glow: Int,
    val glowAlpha: Float,
    val halo: Int,
    val haloAlpha: Float,
    val ring: Int,
)

/**
 * Dónde caen las luces del escenario, en fracciones del lienzo ([radius]: del
 * lado mayor), y el ángulo (grados) del nodo de luz sobre la órbita del medio:
 * [nodeAngle] en general y [nodeAngleWide] en un lienzo muy apaisado (el hero
 * clásico en horizontal), donde arriba queda la barra.
 */
data class StageSpot(
    val glowX: Float,
    val glowY: Float,
    val glowRadius: Float,
    val haloX: Float,
    val haloY: Float,
    val haloRadius: Float,
    val nodeAngle: Float = -150f,
    val nodeAngleWide: Float = nodeAngle,
) {
    /** El ángulo del nodo para un lienzo de [width]×[height]. */
    fun nodeAngleFor(width: Float, height: Float): Float = if (width > height * WIDE) nodeAngleWide else nodeAngle

    companion object {
        /** Desde esta proporción el lienzo cuenta como muy apaisado. */
        const val WIDE = 1.8f
    }
}

/**
 * El escenario de una biblioteca sin arte: una consola apagada con su luz de
 * espera. Un fondo hondo teñido con el tono del primario de la paleta de
 * firma (más hondo de noche, un violeta crepuscular de día), un resplandor
 * del primario, un halo del secundario y órbitas finas del destello.
 *
 * El titular, la línea y los botones van en blanco: el punto más claro del
 * escenario (el fondo de arriba con las dos luces encima a la vez) deja el
 * blanco a [MIN_TEXT] como poco. Si una paleta muy clara no lo cumple, se
 * baja la luz, nunca el contraste.
 */
object EmptyStage {

    const val MIN_TEXT = 4.5

    /** Luminosidad (HSL) del fondo arriba y abajo: de noche y de día. */
    const val DARK_TOP_L = 0.14f
    const val DARK_BOTTOM_L = 0.06f
    const val LIGHT_TOP_L = 0.30f
    const val LIGHT_BOTTOM_L = 0.18f

    /** Saturación máxima del fondo: teñido, nunca chillón. */
    const val MAX_SAT = 0.58f

    /** Opacidad máxima del resplandor y la parte que lleva el halo. */
    const val GLOW_MAX = 0.55f
    const val HALO_RATIO = 0.6f

    /** El paso con que se baja la luz hasta dar el contraste. */
    private const val STEP = 0.01f

    private const val WHITE = 0xFFFFFFFF.toInt()

    /** Las luces en Meridian: el resplandor sobre la zona del hero (arriba a la derecha) y el halo bajo la rueda. */
    val MERIDIAN = StageSpot(glowX = 0.74f, glowY = 0.34f, glowRadius = 0.62f, haloX = 0.18f, haloY = 1.05f, haloRadius = 0.55f)

    /** Las luces en el hero clásico: el resplandor arriba a la derecha y el halo abajo a la izquierda, junto al titular. */
    val CLASSIC = StageSpot(glowX = 0.80f, glowY = 0.30f, glowRadius = 0.70f, haloX = 0.08f, haloY = 1.10f, haloRadius = 0.65f, nodeAngleWide = 35f)

    fun colors(primary: Int, secondary: Int, spark: Int, dark: Boolean): StageColors {
        val p = ColorMath.opaque(primary)
        val hsl = ColorMath.toHsl(p)
        val sat = min(hsl[1], MAX_SAT)
        val top = ColorMath.fromHsl(hsl[0], sat, if (dark) DARK_TOP_L else LIGHT_TOP_L)
        val bottom = ColorMath.fromHsl(hsl[0], sat, if (dark) DARK_BOTTOM_L else LIGHT_BOTTOM_L)
        val s = ColorMath.opaque(secondary)
        var a = GLOW_MAX
        while (a > 0f && ColorMath.contrast(WHITE, brightest(top, p, a, s, a * HALO_RATIO)) < MIN_TEXT) a -= STEP
        a = a.coerceAtLeast(0f)
        return StageColors(top, bottom, p, a, s, a * HALO_RATIO, ColorMath.opaque(spark))
    }

    /** El punto más claro posible: el fondo de arriba con el resplandor y el halo en su máximo, uno sobre otro. */
    fun brightest(top: Int, glow: Int, glowAlpha: Float, halo: Int, haloAlpha: Float): Int =
        ColorMath.over(ColorMath.withAlpha(halo, haloAlpha), ColorMath.over(ColorMath.withAlpha(glow, glowAlpha), top))

    fun brightest(c: StageColors): Int = brightest(c.top, c.glow, c.glowAlpha, c.halo, c.haloAlpha)
}

/**
 * El cuerpo del titular sin logo: el mayor que cabe en su caja (en dos
 * líneas), por bisección entre [min] y [max]. Si ni el mínimo cabe, null:
 * entonces se pinta con el mínimo y una línea más, nunca con puntos suspensivos.
 */
object TitleFit {

    const val ITERATIONS = 10

    fun largest(min: Float, max: Float, iterations: Int = ITERATIONS, fits: (Float) -> Boolean): Float? {
        if (max < min) return if (fits(min)) min else null
        if (!fits(min)) return null
        if (fits(max)) return max
        var lo = min
        var hi = max
        repeat(iterations) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }
        return lo
    }

    /** Interlineado del titular en versalitas: apretado, salvo si lleva tildes o virgulillas (la de la Í no toca la línea de arriba). */
    const val LINE_HEIGHT = 0.92f
    const val LINE_HEIGHT_ACCENTED = 1.04f

    fun lineHeight(text: String): Float =
        if (Normalizer.normalize(text.uppercase(), Normalizer.Form.NFD).any { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }) LINE_HEIGHT_ACCENTED else LINE_HEIGHT

    /** Las líneas del titular: dos si cabe así; si no, una más con el cuerpo mínimo. */
    fun lines(fitted: Float?): Int = if (fitted != null) 2 else 3
}
