package com.elyndra.launcher.ui

import com.elyndra.launcher.data.ColorMath

/**
 * El carrusel dentro del estante de Biblioteca y Carpeta, sin Compose (dp).
 *
 * El hero no cambia de alto (lo sigue mandando `metrics()`): el estante es lo
 * que queda debajo y aquí se decide cuánto crece la card y a qué altura va.
 * La card se centra en el hueco que hay entre el hero (o el dock, si va en la
 * costura) y la barra de pistas, con el nombre debajo; arriba se guarda sitio
 * para lo que la seleccionada sube, crece y alumbra.
 *
 * La barra de pistas del mando se reserva solo cuando se ve. `metrics()` ya
 * le cede ese alto al estante (el hero encoge lo mismo), así que el hueco
 * libre para la fila apenas cambia: la card no cambia de tamaño y la pantalla
 * anima la posición de la fila para que no salte.
 */
object ShelfLayout {

    /** Lo que sube la card seleccionada (`SelectionLift`). */
    const val LIFT = 10f

    /** Crecimiento de la seleccionada (`SelectionScale` − 1). */
    const val SCALE_GROW = 0.05f

    /** Aire sobre la seleccionada para su halo. */
    const val GLOW_TOP = 10f

    /** Aire entre el nombre y el pie (o la barra de pistas). */
    const val BOTTOM_MARGIN = 8f

    /** Barra de pistas del mando con su margen inferior. */
    const val HINTS = 32f

    /** Cuánto puede crecer la card sobre su tamaño de partida, como mucho. */
    const val GROW = 1.10f

    /** El nombre tiene que quedar ya sobre el color liso del estante. */
    const val LABEL_CLEAR = 6f

    class Result(
        /** Alto de la card (sin escala de selección). */
        val tileHeight: Float,
        val tileWidth: Float,
        /** Desde arriba del estante hasta el canto superior de la card en reposo. */
        val tileTop: Float,
        /** Desde arriba del estante hasta el nombre bajo la card. */
        val labelTop: Float,
    )

    /**
     * @param available alto del estante (dp).
     * @param aspect ancho / alto de la card (1 cuadrada, 2/3 carátula).
     * @param base alto de partida de la card (el de `metrics()`).
     * @param topInset lo que ocupa arriba del estante lo que va encima (el dock en la costura, las fichas de la carpeta).
     * @param labelBlock hueco y línea del nombre bajo la card.
     */
    fun compute(
        available: Float,
        hintsVisible: Boolean,
        aspect: Float,
        base: Float,
        topInset: Float,
        labelBlock: Float,
        minTile: Float,
        maxTile: Float,
    ): Result {
        val grow = SCALE_GROW / 2f
        val hints = if (hintsVisible) HINTS else 0f
        val fixed = topInset + LIFT + GLOW_TOP + labelBlock + BOTTOM_MARGIN + hints
        val fit = (available - fixed) / (1f + grow)
        val tile = minOf(base * GROW, fit, maxTile).coerceAtLeast(minTile)

        val bottom = available - hints
        val centered = topInset + (bottom - topInset - tile) / 2f
        val lo = topInset + LIFT + GLOW_TOP + tile * grow
        val hi = bottom - BOTTOM_MARGIN - labelBlock - tile
        val top = if (hi < lo) lo else centered.coerceIn(lo, hi)
        return Result(
            tileHeight = tile,
            tileWidth = tile * aspect,
            tileTop = top,
            labelTop = top + tile + LABEL_GAP,
        )
    }

    /** Hueco entre la card y su nombre (el resto de `labelBlock` es la línea). */
    const val LABEL_GAP = 6f

    /* ── Extensión del arte del hero ──────────────────────────── */

    /** Fracción del estante que cubre el arte en una ventana baja y en una alta. */
    const val EXTENSION_SHORT = 0.45f
    const val EXTENSION_TALL = 0.58f
    const val SHORT_WINDOW = 480f
    const val TALL_WINDOW = 900f

    /**
     * Cuánto baja el arte del hero por detrás del estante (dp): una fracción
     * del estante que encoge en ventanas bajas, y que nunca llega al nombre
     * de las cards ([labelTop]): ahí el estante ya es liso.
     */
    fun artExtension(shelfHeight: Float, windowHeight: Float, labelTop: Float): Float {
        val t = smoothstep(SHORT_WINDOW, TALL_WINDOW, windowHeight)
        val fraction = EXTENSION_SHORT + (EXTENSION_TALL - EXTENSION_SHORT) * t
        return minOf(shelfHeight * fraction, labelTop - LABEL_CLEAR).coerceAtLeast(0f)
    }

    /**
     * El velo del estante sobre el arte extendido, del canto del hero (0) al
     * final de la extensión (1): parte transparente y acaba en el color liso
     * del estante. Pares (posición, alfa), en curva suave.
     */
    val SHELF_RAMP = floatArrayOf(
        0f, 0f,
        0.3f, 0.12f,
        0.6f, 0.5f,
        0.85f, 0.88f,
        1f, 1f,
    )

    /**
     * La franja oscura del titular del hero, que no acaba de golpe en su canto:
     * se apaga en la primera mitad de la extensión. Factor sobre el alfa que
     * tenía el velo del hero en su borde inferior.
     */
    val TITLE_BAND = floatArrayOf(
        0f, 1f,
        0.25f, 0.62f,
        0.55f, 0.18f,
        0.8f, 0f,
    )

    /** Valor de una rampa de pares (posición, alfa) en [x], interpolando en línea recta. */
    fun ramp(stops: FloatArray, x: Float): Float {
        if (x <= stops[0]) return stops[1]
        var i = 0
        while (i + 2 < stops.size) {
            val x0 = stops[i]
            val x1 = stops[i + 2]
            if (x <= x1) {
                val t = if (x1 > x0) (x - x0) / (x1 - x0) else 1f
                return stops[i + 1] + (stops[i + 3] - stops[i + 1]) * t
            }
            i += 2
        }
        return stops[stops.size - 1]
    }

    /** Alfa del tinte del acento sobre el papel del estante. */
    fun shelfTintAlpha(dark: Boolean): Float = if (dark) 0.05f else 0.035f

    /** Alfa del estante en su pie: un poco translúcido, deja adivinar el fondo vivo. */
    fun shelfBottomAlpha(dark: Boolean): Float = if (dark) 0.9f else 0.94f

    /** El color liso del estante (ARGB): el papel con un velo del acento. Tinta honda en oscuro, perla en claro. */
    fun shelfColor(paper: Int, accent: Int, dark: Boolean): Int =
        ColorMath.over(ColorMath.withAlpha(accent, shelfTintAlpha(dark)), ColorMath.opaque(paper))

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
