package com.elyndra.launcher.ui

import kotlin.math.abs

/**
 * Cómo se reparte en pantalla un menú de acciones y cómo lo recorre el mando.
 *
 * El panel lo usa para pintar y el [InputController] para moverse, así el
 * orden del foco es siempre el que se ve. Kotlin puro (se prueba en la JVM).
 *
 * Un menú de juego ([ActionSheetSpec.hero]) se reparte en cinco partes:
 * - [primary]: el botón grande ("Jugar").
 * - [tiles]: el resto de acciones, como piezas compactas.
 * - [art]: las imágenes del juego, como tarjetas con la imagen de verdad.
 * - [artClears]: el "Quitar" de cada imagen, justo debajo de su tarjeta: se
 *   ve qué se borra sin leer el rótulo entero.
 * - [danger]: lo demás que borra (una imagen sin tarjeta, quitar el juego),
 *   aparte, tras un filo rojo y al final.
 *
 * Cualquier otro menú (ordenar, emuladores, menú de la app) es una lista:
 * cada acción es una fila, en el orden de sus bloques.
 *
 * En horizontal, el menú de juego va en dos columnas: a la izquierda arte,
 * "Jugar" y las piezas; a la derecha las imágenes, sus "Quitar" y la zona de
 * borrado (antes era un pie a lo ancho y dejaba media columna vacía).
 */
class SheetLayout private constructor(
    val primary: SheetAction?,
    val tiles: List<SheetAction>,
    val art: List<SheetAction>,
    /**
     * Del mismo tamaño que [art]: el "Quitar" de cada imagen (emparejados por
     * [SheetAction.artSlot]) o null si esa imagen no tiene nada que quitar.
     * Vacía si ninguna lo tiene.
     */
    val artClears: List<SheetAction?>,
    val danger: List<SheetAction>,
    /** Bloques del menú en forma de lista (vacío en un menú de juego). */
    val list: List<SheetGroup>,
    val tileColumns: Int,
    val twoColumns: Boolean,
) {
    /** Todas las acciones en el orden del foco: el índice del foco es un índice de aquí. */
    val actions: List<SheetAction> =
        if (list.isNotEmpty()) list.flatMap { it.actions }
        else listOfNotNull(primary) + tiles + art + artClears.filterNotNull() + danger

    /** Filas de navegación: índices en [actions] y la columna (0 o 1). */
    val rows: List<Row> = buildRows()

    /**
     * Una fila de navegación. [slots] es el hueco visual de cada pieza (la
     * columna de imagen a la que pertenece), en las filas alineadas con las
     * imágenes; null en las demás.
     */
    class Row(val column: Int, val items: IntArray, val slots: IntArray? = null)

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
            out += Row(right, IntArray(art.size) { i + it }, IntArray(art.size) { it })
            i += art.size
        }
        // Solo los huecos que tienen "Quitar": bajar desde una imagen lleva al suyo.
        val clearSlots = artClears.indices.filter { artClears[it] != null }
        if (clearSlots.isNotEmpty()) {
            out += Row(right, IntArray(clearSlots.size) { i + it }, clearSlots.toIntArray())
            i += clearSlots.size
        }
        for (d in danger) {
            out += Row(right, intArrayOf(i))
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

    /**
     * Fila de arriba o de abajo en la misma columna, en la posición más
     * parecida. Entre dos filas con huecos (las imágenes y sus "Quitar"), al
     * hueco más cercano: de "Icono" se baja a SU "Quitar", no al de al lado.
     */
    private fun vertical(r: Int, p: Int, delta: Int): Int? {
        val from = rows[r]
        var k = r + delta
        while (k in rows.indices && rows[k].column != from.column) k += delta
        if (k !in rows.indices) return null
        val to = rows[k]
        val fromSlots = from.slots
        val toSlots = to.slots
        if (fromSlots != null && toSlots != null) return to.items[nearestSlot(fromSlots[p], toSlots)]
        return to.items[nearest(p, from.items.size, to.items.size)]
    }

    /**
     * Salto a la otra columna (horizontal), a la fila de la misma altura
     * relativa. Nunca cae en la zona de borrado: a ella se llega bajando por su
     * columna, no por inercia desde la de al lado (salvo que no haya otra cosa).
     */
    private fun jumpColumn(r: Int, target: Int, toStart: Boolean): Int? {
        val from = rows[r].column
        if (from == target) return null
        val mine = rows.filter { it.column == from }
        val theirs = rows.filter { it.column == target && !isDanger(it) }.ifEmpty { rows.filter { it.column == target } }
        if (theirs.isEmpty()) return null
        val at = mine.indexOf(rows[r]).coerceAtLeast(0)
        val dest = theirs[nearest(at, mine.size, theirs.size)]
        return if (toStart) dest.items.first() else dest.items.last()
    }

    /** La zona de borrado va la última en el orden del foco. */
    private fun isDanger(row: Row): Boolean = row.items.first() >= actions.size - danger.size

    private fun nearest(p: Int, from: Int, to: Int): Int =
        if (to <= 1 || from <= 1) (if (to <= 1) 0 else p.coerceAtMost(to - 1))
        else Math.round(p * (to - 1) / (from - 1).toFloat()).coerceIn(0, to - 1)

    /** Posición en [slots] (ascendentes) del hueco más cercano a [slot]; a igual distancia, el de la izquierda. */
    private fun nearestSlot(slot: Int, slots: IntArray): Int {
        var best = 0
        for (k in slots.indices) if (abs(slots[k] - slot) < abs(slots[best] - slot)) best = k
        return best
    }

    companion object {
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
                return SheetLayout(null, emptyList(), emptyList(), emptyList(), emptyList(), spec.groups.filter { it.actions.isNotEmpty() }, 1, false)
            }
            val all = spec.groups.flatMap { g -> g.actions.map { g.style to it } }
            val primary = all.firstOrNull { it.second.primary }?.second
            val tiles = all.filter { (style, a) -> a !== primary && !a.destructive && style == GroupStyle.Rows }.map { it.second }
            val art = all.filter { (style, a) -> !a.destructive && style == GroupStyle.Thumbnails }.map { it.second }
            val destructive = all.filter { it.second.destructive }.map { it.second }
            // Cada "Quitar" bajo la tarjeta de la imagen que borra.
            val paired = art.map { a -> a.artSlot?.let { slot -> destructive.firstOrNull { it.artSlot == slot } } }
            val artClears = if (paired.any { it != null }) paired else emptyList()
            // Lo demás que borra: primero las imágenes sin tarjeta (se hacen al momento), al final el juego.
            val danger = destructive.filter { d -> artClears.none { it === d } }.sortedBy { if (it.holdToConfirm) 0 else 1 }
            return SheetLayout(primary, tiles, art, artClears, danger, emptyList(), columnsFor(tiles.size), landscape)
        }
    }
}
