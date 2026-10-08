package com.elyndra.launcher.ui

/**
 * El orden del foco del mando en la parte de arriba de la biblioteca, sin
 * Compose: la barra del hero y el dock de secciones con el botón de orden.
 *
 * Con el dock en la barra (ventana ancha) todo es una fila, en el orden en
 * que se ve. Con el dock en la costura hero/estante son dos: la barra arriba
 * y el dock debajo, justo encima del carrusel. Desde el carrusel, arriba
 * lleva al dock; desde la barra, abajo baja al dock y luego al carrusel.
 */
object LibraryFocus {

    fun rows(dockInBar: Boolean, searchOpen: Boolean): List<List<BarItem>> {
        val open = BarItem.Open.takeIf { !searchOpen }
        return if (dockInBar) {
            listOf(listOfNotNull(BarItem.Masha, BarItem.Sections, BarItem.Sort, open, BarItem.Search, BarItem.Settings))
        } else {
            listOf(
                listOfNotNull(BarItem.Masha, open, BarItem.Search, BarItem.Settings),
                listOf(BarItem.Sections, BarItem.Sort),
            )
        }
    }

    /** Arriba desde el carrusel: el dock (o el buscador, si está abierto: es donde se escribe). */
    fun fromCarousel(searchOpen: Boolean): BarItem = if (searchOpen) BarItem.Search else BarItem.Sections

    private fun rowOf(rows: List<List<BarItem>>, item: BarItem): Int = rows.indexOfFirst { item in it }

    /** Arriba desde [item]: la fila de encima (en "Abrir", o en el buscador abierto); en la de arriba, quieto. */
    fun up(rows: List<List<BarItem>>, item: BarItem): BarItem {
        val r = rowOf(rows, item)
        if (r <= 0) return item
        val above = rows[r - 1]
        return above.firstOrNull { it == BarItem.Open } ?: above.firstOrNull { it == BarItem.Search } ?: above.first()
    }

    /** Abajo desde [item]: la fila de debajo o, desde la última, el carrusel (null). */
    fun down(rows: List<List<BarItem>>, item: BarItem): BarItem? {
        val r = rowOf(rows, item)
        if (r < 0 || r >= rows.lastIndex) return null
        return rows[r + 1].first()
    }

    /** Izquierda / derecha dentro de la fila, sin dar la vuelta. */
    fun side(rows: List<List<BarItem>>, item: BarItem, delta: Int): BarItem {
        val row = rows.getOrNull(rowOf(rows, item)) ?: return item
        val i = row.indexOf(item)
        return row[(i + delta).coerceIn(0, row.lastIndex)]
    }

    /**
     * Paso por los puntos del dock: el siguiente punto, o null si se sale por
     * un extremo (entonces el foco pasa a la pieza de al lado).
     */
    fun stepDot(focus: Int, delta: Int, count: Int): Int? {
        val next = focus + delta
        return if (next in 0 until count) next else null
    }

    /** Punto que se señala al entrar en el dock: por un lado, el del extremo; por arriba o abajo, la sección actual. */
    fun entryDot(selected: Int, count: Int, fromLeft: Boolean?): Int = when (fromLeft) {
        true -> 0
        false -> count - 1
        null -> selected
    }.coerceIn(0, (count - 1).coerceAtLeast(0))
}
