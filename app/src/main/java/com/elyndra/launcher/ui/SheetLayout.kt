package com.elyndra.launcher.ui

/**
 * Cómo se reparte en pantalla un menú de acciones y cómo lo recorre el mando.
 *
 * El panel lo usa para pintar y el [InputController] para moverse, así el
 * orden del foco es siempre el que se ve. Kotlin puro (se prueba en la JVM).
 *
 * Un menú de juego ([ActionSheetSpec.hero]) se reparte en cuatro partes:
 * - [primary]: el botón grande ("Jugar").
 * - [tiles]: el resto de acciones, como piezas compactas.
 * - [art]: las imágenes del juego, como tarjetas con la imagen de verdad.
 * - [danger]: lo que borra (quitar una imagen, quitar el juego), aparte y al final.
 *
 * Cualquier otro menú (ordenar, emuladores, menú de la app) es una lista:
 * cada acción es una fila, en el orden de sus bloques.
 *
 * En horizontal, el menú de juego va en dos columnas: a la izquierda arte,
 * "Jugar" y las piezas; a la derecha las imágenes. La zona de borrado es un
 * pie a lo ancho de las dos, aparte de todo lo demás.
 */
class SheetLayout private constructor(
    val primary: SheetAction?,
    val tiles: List<SheetAction>,
    val art: List<SheetAction>,
    val danger: List<SheetAction>,
    /** Bloques del menú en forma de lista (vacío en un menú de juego). */
    val list: List<SheetGroup>,
    val tileColumns: Int,
    val twoColumns: Boolean,
) {
    /** Todas las acciones en el orden del foco: el índice del foco es un índice de aquí. */
    val actions: List<SheetAction> =
        if (list.isNotEmpty()) list.flatMap { it.actions } else listOfNotNull(primary) + tiles + art + danger

    /** Filas de navegación: índices en [actions] y la columna (0/1, o [SPAN] si ocupa las dos). */
    val rows: List<Row> = buildRows()

    class Row(val column: Int, val items: IntArray)

    enum class Move { Up, Down, Left, Right }

    val isHero: Boolean get() = list.isEmpty()

    fun indexOf(action: SheetAction): Int = actions.indexOfFirst { it === action }

    /** Filas de las piezas: [tiles] partidas en [tileColumns]. */
    fun tileRows(): List<List<SheetAction>> = tiles.chunked(tileColumns.coerceAtLeast(1))

    private fun buildRows(): List<Row> {
        if (list.isNotEmpty()) return actions.indices.map { Row(0, intArrayOf(it)) }
        val out = ArrayList<Row>()
        var i = 0
        val right = if (twoColumns) 1 else 0
        primary?.let { out += Row(0, intArrayOf(i)); i++ }
        for (row in tileRows()) {
            out += Row(0, IntArray(row.size) { i + it })
            i += row.size
        }
        if (art.isNotEmpty()) {
            out += Row(right, IntArray(art.size) { i + it })
            i += art.size
        }
        for (d in danger) {
            out += Row(if (twoColumns) SPAN else 0, intArrayOf(i))
            i++
        }
        return out
    }

    /**
     * El foco tras mover el mando. Nada señalado (-1): la primera acción. No da
     * la vuelta en los extremos: en un menú corto, saltar del final al principio
     * se lee como un error.
     */
    fun move(focus: Int, move: Move): Int {
        if (actions.isEmpty()) return -1
        if (focus !in actions.indices) return 0
        val r = rows.indexOfFirst { row -> row.items.contains(focus) }
        if (r < 0) return 0
        val row = rows[r]
        val p = row.items.indexOf(focus)
        return when (move) {
            Move.Left -> if (p > 0) row.items[p - 1] else jumpColumn(r, 0, toStart = false) ?: focus
            Move.Right -> if (p < row.items.lastIndex) row.items[p + 1] else jumpColumn(r, 1, toStart = true) ?: focus
            Move.Up -> vertical(r, p, -1) ?: focus
            Move.Down -> vertical(r, p, 1) ?: focus
        }
    }

    /** Fila de arriba o de abajo en la misma columna (o en el pie), en la posición más parecida. */
    private fun vertical(r: Int, p: Int, delta: Int): Int? {
        val column = rows[r].column
        var k = r + delta
        while (k in rows.indices && !sameColumn(rows[k].column, column)) k += delta
        if (k !in rows.indices) return null
        return rows[k].items[nearest(p, rows[r].items.size, rows[k].items.size)]
    }

    private fun sameColumn(a: Int, b: Int): Boolean = a == b || a == SPAN || b == SPAN

    /** Salto a la otra columna (horizontal), a la fila de la misma altura relativa. El pie no salta. */
    private fun jumpColumn(r: Int, target: Int, toStart: Boolean): Int? {
        val from = rows[r].column
        if (from == target || from == SPAN) return null
        val mine = rows.filter { it.column == from }
        val theirs = rows.filter { it.column == target }
        if (theirs.isEmpty()) return null
        val at = mine.indexOf(rows[r]).coerceAtLeast(0)
        val dest = theirs[nearest(at, mine.size, theirs.size)]
        return if (toStart) dest.items.first() else dest.items.last()
    }

    private fun nearest(p: Int, from: Int, to: Int): Int =
        if (to <= 1 || from <= 1) (if (to <= 1) 0 else p.coerceAtMost(to - 1))
        else Math.round(p * (to - 1) / (from - 1).toFloat()).coerceIn(0, to - 1)

    companion object {
        /** Columna de una fila que ocupa el ancho entero (el pie de borrado). */
        const val SPAN = -1

        /**
         * Piezas por fila: hasta tres en una; cuatro, en dos filas de dos; más,
         * de tres en tres. Así ningún rótulo se queda con menos de un tercio
         * del ancho y caben en dos líneas en cualquier idioma.
         */
        fun columnsFor(count: Int): Int = when {
            count <= 3 -> count.coerceAtLeast(1)
            count == 4 -> 2
            else -> 3
        }

        fun of(spec: ActionSheetSpec, landscape: Boolean): SheetLayout {
            if (spec.hero == null) {
                return SheetLayout(null, emptyList(), emptyList(), emptyList(), spec.groups.filter { it.actions.isNotEmpty() }, 1, false)
            }
            val all = spec.groups.flatMap { g -> g.actions.map { g.style to it } }
            val primary = all.firstOrNull { it.second.primary }?.second
            val tiles = all.filter { (style, a) -> a !== primary && !a.destructive && style == GroupStyle.Rows }.map { it.second }
            val art = all.filter { (style, a) -> !a.destructive && style == GroupStyle.Thumbnails }.map { it.second }
            // Lo que borra: primero las imágenes (se hacen al momento), al final el juego.
            val danger = all.filter { it.second.destructive }.map { it.second }.sortedBy { if (it.holdToConfirm) 0 else 1 }
            return SheetLayout(primary, tiles, art, danger, emptyList(), columnsFor(tiles.size), landscape)
        }
    }
}
