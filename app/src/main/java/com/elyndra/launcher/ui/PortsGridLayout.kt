package com.elyndra.launcher.ui

/**
 * La cuadrícula de la tienda con un filtro o una búsqueda puestos, sin
 * Compose (PortsGridLayoutTest). Todo en dp.
 *
 * Como las páginas de categoría de las tiendas de escritorio: todos los
 * juegos que cumplen el filtro, en columnas que bajan. Cuántas columnas
 * depende del ancho (dos en un móvil en vertical, cinco en una tableta en
 * apaisado) y la cápsula reparte lo que sobra: no hay columna a medias.
 */
object PortsGridLayout {

    /** Cápsula más estrecha que se acepta: en estrecho y en ancho (≥ [WIDE]). */
    const val MIN_CAPSULE = 150f
    const val MIN_CAPSULE_WIDE = 200f
    const val WIDE = 600f
    const val MIN_COLUMNS = 2
    const val MAX_COLUMNS = 6

    /** Aire entre cápsulas, en las dos direcciones. */
    const val GAP = PortsLayout.ROW_GAP

    fun minCapsule(width: Float): Float = if (width >= WIDE) MIN_CAPSULE_WIDE else MIN_CAPSULE

    /** Lo que queda para las cápsulas: el ancho menos los lados de la tienda. */
    fun room(width: Float): Float = (width - 2 * PortsLayout.pad(width)).coerceAtLeast(0f)

    fun columns(width: Float): Int {
        val fit = ((room(width) + GAP) / (minCapsule(width) + GAP)).toInt()
        return fit.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    /** Ancho de cada cápsula con [columns] columnas: llenan la fila entera. */
    fun capsuleWidth(width: Float, columns: Int = columns(width)): Float {
        val cols = columns.coerceAtLeast(1)
        return ((room(width) - GAP * (cols - 1)) / cols).coerceAtLeast(0f)
    }
}
