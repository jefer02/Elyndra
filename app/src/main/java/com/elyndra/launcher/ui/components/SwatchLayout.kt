package com.elyndra.launcher.ui.components

/**
 * Cómo se reparten las muestras de color ([ColorSwatch]) en una fila que se
 * parte. Kotlin puro: se prueba en la JVM.
 *
 * Todas en una línea si caben; si no, en filas parejas (10 muestras donde
 * caben 7 van 5 + 5, no 7 + 3).
 */
object SwatchLayout {

    /** Lado de la muestra que se ve (dp). */
    const val VISUAL_DP = 40f

    /** Lado de la zona táctil (dp): la muestra con su aro y aire alrededor, ≥ 48. */
    const val SLOT_DP = 50f

    /** Hueco entre zonas táctiles (dp). */
    const val GAP_DP = 6f

    /** Cuántas muestras van por fila en [availableDp] para [count] muestras. */
    fun perRow(availableDp: Float, count: Int, slotDp: Float = SLOT_DP, gapDp: Float = GAP_DP): Int {
        if (count <= 0) return 0
        val fit = ((availableDp + gapDp) / (slotDp + gapDp)).toInt().coerceAtLeast(1)
        if (fit >= count) return count
        val rows = (count + fit - 1) / fit
        return (count + rows - 1) / rows
    }

    /** Filas que ocupan [count] muestras en [availableDp]. */
    fun rows(availableDp: Float, count: Int, slotDp: Float = SLOT_DP, gapDp: Float = GAP_DP): Int {
        val per = perRow(availableDp, count, slotDp, gapDp)
        return if (per == 0) 0 else (count + per - 1) / per
    }
}
