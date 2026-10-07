package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.BarItem.Masha
import com.elyndra.launcher.ui.BarItem.Open
import com.elyndra.launcher.ui.BarItem.Search
import com.elyndra.launcher.ui.BarItem.Sections
import com.elyndra.launcher.ui.BarItem.Settings
import com.elyndra.launcher.ui.BarItem.Sort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryFocusTest {

    @Test
    fun `en la barra el orden es el que se ve`() {
        assertEquals(listOf(listOf(Masha, Sections, Sort, Open, Search, Settings)), LibraryFocus.rows(dockInBar = true, searchOpen = false))
        // Con el buscador abierto "Abrir" se va.
        assertEquals(listOf(listOf(Masha, Sections, Sort, Search, Settings)), LibraryFocus.rows(dockInBar = true, searchOpen = true))
    }

    @Test
    fun `en la costura son dos filas, la barra encima del dock`() {
        assertEquals(
            listOf(listOf(Masha, Open, Search, Settings), listOf(Sections, Sort)),
            LibraryFocus.rows(dockInBar = false, searchOpen = false),
        )
    }

    @Test
    fun `arriba desde el carrusel lleva al dock, o al buscador si esta abierto`() {
        assertEquals(Sections, LibraryFocus.fromCarousel(searchOpen = false))
        assertEquals(Search, LibraryFocus.fromCarousel(searchOpen = true))
    }

    @Test
    fun `en la costura arriba sube a Abrir y abajo baja al dock y luego al carrusel`() {
        val rows = LibraryFocus.rows(dockInBar = false, searchOpen = false)
        assertEquals(Open, LibraryFocus.up(rows, Sections))
        assertEquals(Open, LibraryFocus.up(rows, Sort))
        assertEquals(Open, LibraryFocus.up(rows, Open))
        assertEquals(Sections, LibraryFocus.down(rows, Settings))
        assertNull(LibraryFocus.down(rows, Sections))
        // Buscando, arriba vuelve al campo.
        val searching = LibraryFocus.rows(dockInBar = false, searchOpen = true)
        assertEquals(Search, LibraryFocus.up(searching, Sort))
    }

    @Test
    fun `en una sola fila arriba se queda y abajo vuelve al carrusel`() {
        val rows = LibraryFocus.rows(dockInBar = true, searchOpen = false)
        assertEquals(Sort, LibraryFocus.up(rows, Sort))
        assertNull(LibraryFocus.down(rows, Sections))
    }

    @Test
    fun `a los lados recorre la fila sin dar la vuelta`() {
        val rows = LibraryFocus.rows(dockInBar = true, searchOpen = false)
        assertEquals(Sort, LibraryFocus.side(rows, Sections, 1))
        assertEquals(Masha, LibraryFocus.side(rows, Sections, -1))
        assertEquals(Masha, LibraryFocus.side(rows, Masha, -1))
        assertEquals(Settings, LibraryFocus.side(rows, Settings, 1))
    }

    @Test
    fun `los puntos se recorren y por los extremos se sale`() {
        assertEquals(1, LibraryFocus.stepDot(0, 1, 3))
        assertEquals(2, LibraryFocus.stepDot(1, 1, 3))
        assertNull(LibraryFocus.stepDot(2, 1, 3))
        assertNull(LibraryFocus.stepDot(0, -1, 3))
    }

    @Test
    fun `al entrar en el dock se senala el extremo por el que se llega o la seccion actual`() {
        assertEquals(0, LibraryFocus.entryDot(selected = 2, count = 4, fromLeft = true))
        assertEquals(3, LibraryFocus.entryDot(selected = 0, count = 4, fromLeft = false))
        assertEquals(2, LibraryFocus.entryDot(selected = 2, count = 4, fromLeft = null))
        assertEquals(0, LibraryFocus.entryDot(selected = -1, count = 3, fromLeft = null))
    }
}
