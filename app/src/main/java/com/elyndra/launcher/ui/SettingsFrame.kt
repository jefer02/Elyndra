package com.elyndra.launcher.ui

/**
 * Las medidas de Ajustes en ventana ancha (raíl + panel). Kotlin puro: se
 * prueba en la JVM.
 *
 * El raíl lleva arriba volver y el título, y debajo las categorías; el panel
 * ocupa todo el alto, desde la misma línea que el título. Así no queda una
 * franja vacía encima del panel.
 */
object SettingsFrame {

    /** Parte del ancho de la ventana para el raíl. */
    const val RAIL_FRACTION = 0.24f

    /** Topes del raíl (dp): por debajo no caben los nombres; por encima solo roba sitio al panel. */
    const val RAIL_MIN_DP = 196f
    const val RAIL_MAX_DP = 272f

    /** El raíl nunca pasa de esta parte de la ventana: el panel se queda siempre con la mayor. */
    const val RAIL_MAX_SHARE = 0.36f

    /** Zona táctil mínima de una fila del raíl (dp). */
    const val MIN_TOUCH_DP = 48f

    /** Hueco entre filas del raíl (dp). */
    const val RAIL_GAP_DP = 2f

    /**
     * Ancho del raíl (dp) para una ventana de [windowWidthDp]: un cuarto,
     * dentro de sus topes y sin pasar nunca de [RAIL_MAX_SHARE]. En una
     * ventana ancha de 600 dp se queda en 196 (un tercio); en la Legion
     * apaisada, en 272 (algo más de un quinto).
     */
    fun railWidth(windowWidthDp: Float): Float =
        (windowWidthDp * RAIL_FRACTION)
            .coerceIn(RAIL_MIN_DP, RAIL_MAX_DP)
            .coerceAtMost(windowWidthDp * RAIL_MAX_SHARE)

    /**
     * Alto de una fila del raíl (dp): 50 con la letra normal y más con la
     * letra grande del sistema (dos líneas de nombre a 1,5×), nunca menos de
     * la zona táctil.
     */
    fun railItemHeight(fontScale: Float): Float =
        (42f + 8f * fontScale).coerceAtLeast(MIN_TOUCH_DP)

    /** Dónde empieza (dp) el resalte de la fila [position] (puede ir entre dos filas mientras se desliza). */
    fun highlightTop(position: Float, itemHeight: Float, gap: Float = RAIL_GAP_DP): Float =
        position * (itemHeight + gap)

    /**
     * Filas algo más prietas: solo en la ventana ancha y apaisada, donde el
     * alto es lo que escasea. En vertical y en el móvil, el aire de siempre.
     */
    fun dense(widthDp: Float, heightDp: Float): Boolean =
        SettingsNav.isWide(widthDp) && widthDp > heightDp
}
