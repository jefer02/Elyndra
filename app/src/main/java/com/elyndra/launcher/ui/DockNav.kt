package com.elyndra.launcher.ui

/**
 * Las paradas del dock con L1/R1: las secciones de la biblioteca y, tras
 * ellas, la tienda de ports (el destino del final, ver
 * [DotTabsLayout.DEST_SLOT]). Da la vuelta: R1 en la tienda vuelve a la
 * primera sección y L1 a la última. Lógica pura (DockNavTest).
 */
object DockNav {

    sealed interface Stop<out F> {
        data class Filter<F>(val filter: F) : Stop<F>
        data object Ports : Stop<Nothing>
    }

    fun <F> stops(filters: List<F>): List<Stop<F>> = filters.map { Stop.Filter(it) } + Stop.Ports

    /** La parada a [delta] pasos de [current] (si [current] no está, como si estuviera en la primera). */
    fun <F> next(filters: List<F>, current: Stop<F>, delta: Int): Stop<F> {
        val all = stops(filters)
        val from = all.indexOf(current).coerceAtLeast(0)
        return all[Math.floorMod(from + delta, all.size)]
    }
}
