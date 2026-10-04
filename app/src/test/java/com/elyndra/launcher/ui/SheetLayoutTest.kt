package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.SheetLayout.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetLayoutTest {

    private fun a(name: String, primary: Boolean = false, destructive: Boolean = false, hold: Boolean = false) =
        SheetAction(UiText.Raw(name), primary = primary, destructive = destructive, holdToConfirm = hold) {}

    private val play = a("play", primary = true)
    private val details = a("details")
    private val refresh = a("refresh")
    private val bg = a("bg")
    private val logo = a("logo")
    private val icon = a("icon")
    private val clearBg = a("clearBg", destructive = true, hold = true)
    private val remove = a("remove", destructive = true)

    private fun spec(hero: Boolean) = ActionSheetSpec(
        UiText.Raw("Juego"),
        null,
        listOf(
            SheetGroup(null, listOf(play, details)),
            SheetGroup(null, listOf(bg, logo, icon, clearBg), GroupStyle.Thumbnails),
            SheetGroup(null, listOf(refresh)),
            SheetGroup(null, listOf(remove)),
        ),
        hero = if (hero) SheetHero(fallback = ArtFallback("game", "Game")) else null,
    )

    @Test
    fun heroSplitsIntoPlayTilesArtAndDanger() {
        val l = SheetLayout.of(spec(true), landscape = false)
        assertSame(play, l.primary)
        assertEquals(listOf(details, refresh), l.tiles)
        assertEquals(listOf(bg, logo, icon), l.art)
        // Primero lo que borra al momento (imágenes), al final el juego.
        assertEquals(listOf(clearBg, remove), l.danger)
        assertEquals(listOf(play, details, refresh, bg, logo, icon, clearBg, remove), l.actions)
    }

    @Test
    fun portraitNavigationFollowsWhatIsOnScreen() {
        val l = SheetLayout.of(spec(true), landscape = false)
        assertEquals(0, l.move(-1, Move.Down))
        assertEquals(1, l.move(0, Move.Down)) // Jugar → primera pieza
        assertEquals(2, l.move(1, Move.Right))
        assertEquals(2, l.move(2, Move.Right)) // sin dar la vuelta
        assertEquals(5, l.move(2, Move.Down)) // última pieza → última imagen
        assertEquals(4, l.move(5, Move.Left))
        assertEquals(6, l.move(4, Move.Down)) // imágenes → zona de borrado
        assertEquals(7, l.move(6, Move.Down))
        assertEquals(7, l.move(7, Move.Down))
        assertEquals(0, l.move(1, Move.Up))
        assertEquals(0, l.move(0, Move.Left))
    }

    @Test
    fun landscapeJumpsBetweenColumns() {
        val l = SheetLayout.of(spec(true), landscape = true)
        assertTrue(l.twoColumns)
        // Derecha desde Jugar: a la columna de las imágenes.
        assertEquals(3, l.move(0, Move.Right))
        // Izquierda desde la primera imagen: de vuelta a la columna de Jugar.
        assertEquals(0, l.move(3, Move.Left))
        // Abajo en la columna izquierda no baja a la derecha…
        assertEquals(1, l.move(0, Move.Down))
        // …pero sí al pie de borrado, que ocupa el ancho de las dos.
        assertEquals(6, l.move(1, Move.Down))
        assertEquals(6, l.move(3, Move.Down))
        // Del pie no se salta de columna, y arriba vuelve a la última fila de encima.
        assertEquals(6, l.move(6, Move.Left))
        assertEquals(6, l.move(6, Move.Right))
        assertEquals(3, l.move(6, Move.Up))
    }

    @Test
    fun plainMenusStayALinearList() {
        val l = SheetLayout.of(spec(false), landscape = true)
        assertTrue(!l.isHero)
        assertEquals(listOf(play, details, bg, logo, icon, clearBg, refresh, remove), l.actions)
        assertEquals(1, l.move(0, Move.Down))
        assertEquals(0, l.move(1, Move.Up))
        assertEquals(1, l.move(1, Move.Right))
    }

    @Test
    fun tileColumnsStayBalanced() {
        assertEquals(1, SheetLayout.columnsFor(1))
        assertEquals(3, SheetLayout.columnsFor(3))
        // Cuatro, en dos filas de dos: en una sola los rótulos no caben a dos líneas.
        assertEquals(2, SheetLayout.columnsFor(4))
        assertEquals(3, SheetLayout.columnsFor(5))
        assertEquals(3, SheetLayout.columnsFor(6))
    }
}
