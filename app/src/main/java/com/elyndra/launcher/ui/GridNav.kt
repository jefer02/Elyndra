package com.elyndra.launcher.ui

import kotlin.math.max

/**
 * Cómo recorre el mando una rejilla de N elementos en C columnas.
 *
 * Kotlin puro (se prueba en la JVM): el selector de arte lo usa para moverse
 * y cualquier otra rejilla puede hacerlo igual, sin repetir la aritmética de
 * filas y columnas — que es donde se cuelan los errores de "fila incompleta".
 *
 * Reglas, las de una consola:
 *  - Izquierda/derecha no dan la vuelta: en el canto de la fila se queda. Saltar
 *    del final de una fila al principio de la siguiente se lee como un error.
 *  - Arriba desde la primera fila va a la cabecera (pestañas, cerrar…) si la
 *    hay; si no, se queda.
 *  - Abajo hacia una última fila incompleta cae en el elemento más cercano
 *    (el último), no se pierde en un hueco vacío.
 *  - Abajo desde la última fila pide más resultados si los hay.
 */
object GridNav {

    enum class Dir { Up, Down, Left, Right }

    /** Qué pasa tras mover. */
    sealed interface Result {
        /** El foco pasa a [index]. */
        data class Move(val index: Int) : Result

        /** Canto de la rejilla: no se mueve. */
        data object Stay : Result

        /** Arriba desde la primera fila: a la cabecera. */
        data object ToHeader : Result

        /** Abajo desde la última fila: a cargar la página siguiente. */
        data object LoadMore : Result
    }

    /**
     * Columnas de una rejilla adaptable, con la misma cuenta que
     * `GridCells.Adaptive` de Compose (en píxeles enteros). La pantalla la
     * pinta con este número fijo y el mando se mueve con él: así las columnas
     * del mando son siempre las que se ven, en móvil o tableta y en cada clase
     * de imagen.
     */
    fun columns(availablePx: Int, minCellPx: Int, spacingPx: Int): Int {
        if (availablePx <= 0 || minCellPx <= 0) return 1
        return max((availablePx + spacingPx) / (minCellPx + spacingPx), 1)
    }

    /**
     * Filas enteras que caben en el hueco a la vista: lo que salta una página
     * (LB/RB). Al menos una, para que el salto siempre avance.
     */
    fun pageRows(viewportPx: Int, rowPx: Int, spacingPx: Int): Int {
        if (viewportPx <= 0 || rowPx <= 0) return 1
        return max((viewportPx + spacingPx) / (rowPx + spacingPx), 1)
    }

    /**
     * El foco tras mover en [dir] desde [index]. Con [index] fuera de la
     * rejilla (nada señalado, se abrió con el dedo) la pulsación solo coloca
     * el foco en [initial], igual que en los menús.
     */
    fun move(
        index: Int,
        count: Int,
        columns: Int,
        dir: Dir,
        hasHeader: Boolean = false,
        canLoadMore: Boolean = false,
        initial: Int = 0,
    ): Result {
        if (count <= 0) return if (dir == Dir.Up && hasHeader) Result.ToHeader else Result.Stay
        if (index !in 0 until count) return Result.Move(initial.coerceIn(0, count - 1))
        val cols = columns.coerceAtLeast(1)
        val row = index / cols
        val col = index % cols
        val lastRow = (count - 1) / cols
        return when (dir) {
            Dir.Left -> if (col > 0) Result.Move(index - 1) else Result.Stay
            Dir.Right -> if (col < cols - 1 && index + 1 < count) Result.Move(index + 1) else Result.Stay
            Dir.Up -> when {
                row > 0 -> Result.Move(index - cols)
                hasHeader -> Result.ToHeader
                else -> Result.Stay
            }
            Dir.Down -> when {
                // En la fila de abajo, la misma columna o, si está incompleta, su último elemento.
                row < lastRow -> Result.Move((index + cols).coerceAtMost(count - 1))
                canLoadMore -> Result.LoadMore
                else -> Result.Stay
            }
        }
    }

    /**
     * Salto de [rows] filas (LB/RB) en la misma columna, sin pasar de la
     * primera ni de la última fila. Sobre una última fila incompleta, al
     * elemento más cercano.
     */
    fun page(index: Int, count: Int, columns: Int, rows: Int, delta: Int): Int {
        if (count <= 0) return -1
        if (index !in 0 until count) return 0
        val cols = columns.coerceAtLeast(1)
        val lastRow = (count - 1) / cols
        val row = (index / cols + delta * rows.coerceAtLeast(1)).coerceIn(0, lastRow)
        return (row * cols + index % cols).coerceAtMost(count - 1)
    }

    /**
     * Cuánto desplazar para que un elemento ([top]–[bottom], en las mismas
     * coordenadas que el hueco [start]–[end]) se vea entero con [margin] de
     * aire. 0 si ya se ve. Si no cabe, manda su parte de arriba.
     */
    fun revealDelta(top: Int, bottom: Int, start: Int, end: Int, margin: Int): Int {
        val from = start + margin
        val to = end - margin
        return when {
            top < from -> top - from
            bottom > to -> minOf(bottom - to, top - from)
            else -> 0
        }
    }
}
