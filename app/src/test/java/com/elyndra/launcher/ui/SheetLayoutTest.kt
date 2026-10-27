package com.elyndra.launcher.ui

import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.SheetLayout.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetLayoutTest {

    private fun a(name: String, primary: Boolean = false, destructive: Boolean = false, hold: Boolean = false, slot: ArtKind? = null) =
        SheetAction(UiText.Raw(name), primary = primary, destructive = destructive, holdToConfirm = hold, artSlot = slot) {}

    private val play = a("play", primary = true)
    private val details = a("details")
    private val refresh = a("refresh")
    private val bg = a("bg", slot = ArtKind.Background)
    private val logo = a("logo", slot = ArtKind.Logo)
    private val icon = a("icon", slot = ArtKind.Icon)
    private val clearBg = a("clearBg", destructive = true, hold = true, slot = ArtKind.Background)
    private val clearLogo = a("clearLogo", destructive = true, hold = true, slot = ArtKind.Logo)
    private val clearIcon = a("clearIcon", destructive = true, hold = true, slot = ArtKind.Icon)
    private val remove = a("remove", destructive = true)

    private fun spec(hero: Boolean, clears: List<SheetAction> = listOf(clearBg)) = ActionSheetSpec(
        UiText.Raw("Juego"),
        null,
        listOf(
            SheetGroup(null, listOf(play, details)),
            SheetGroup(null, listOf(bg, logo, icon) + clears, GroupStyle.Thumbnails),
            SheetGroup(null, listOf(refresh)),
            SheetGroup(null, listOf(remove)),
        ),
        hero = if (hero) SheetHero(fallback = ArtFallback("game", "Game")) else null,
    )

    @Test
    fun heroSplitsIntoPlayTilesArtClearsAndDanger() {
        val l = SheetLayout.of(spec(true), landscape = false)
        assertSame(play, l.primary)
        assertEquals(listOf(details, refresh), l.tiles)
        assertEquals(listOf(bg, logo, icon), l.art)
        // Cada "Quitar" bajo su imagen; lo que no tiene, un hueco.
        assertEquals(listOf(clearBg, null, null), l.artClears)
        assertEquals(listOf(remove), l.danger)
        assertEquals(listOf(play, details, refresh, bg, logo, icon, clearBg, remove), l.actions)
    }

    @Test
    fun withoutAnythingToRemoveThereAreNoClears() {
        val l = SheetLayout.of(spec(true, clears = emptyList()), landscape = false)
        assertEquals(emptyList<SheetAction?>(), l.artClears)
        assertEquals(listOf(remove), l.danger)
        assertEquals(listOf(play, details, refresh, bg, logo, icon, remove), l.actions)
    }

    @Test
    fun aRemoveWithoutItsTileGoesToTheDangerZone() {
        // La carátula se puede quitar pero no tiene tarjeta en este menú: va con lo que borra, delante del juego.
        val clearCover = a("clearCover", destructive = true, hold = true, slot = ArtKind.Cover)
        val l = SheetLayout.of(spec(true, clears = listOf(clearCover, clearIcon)), landscape = false)
        assertEquals(listOf(null, null, clearIcon), l.artClears)
        assertEquals(listOf(clearCover, remove), l.danger)
        assertEquals(listOf(play, details, refresh, bg, logo, icon, clearIcon, clearCover, remove), l.actions)
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
        assertEquals(6, l.move(3, Move.Down)) // fondo → su "Quitar"
        assertEquals(6, l.move(4, Move.Down)) // el logo no tiene: al "Quitar" que hay
        assertEquals(3, l.move(6, Move.Up)) // "Quitar" → su imagen
        assertEquals(7, l.move(6, Move.Down)) // → zona de borrado
        assertEquals(7, l.move(7, Move.Down))
        assertEquals(6, l.move(7, Move.Up))
        assertEquals(0, l.move(1, Move.Up))
        assertEquals(0, l.move(0, Move.Left))
    }

    @Test
    fun movingDownFromEachImageReachesItsOwnRemove() {
        val l = SheetLayout.of(spec(true, clears = listOf(clearBg, clearLogo, clearIcon)), landscape = false)
        assertEquals(listOf(clearBg, clearLogo, clearIcon), l.artClears)
        for ((image, clear) in listOf(bg to clearBg, logo to clearLogo, icon to clearIcon)) {
            assertEquals(image.label.toString(), l.indexOf(clear), l.move(l.indexOf(image), Move.Down))
            assertEquals(image.label.toString(), l.indexOf(image), l.move(l.indexOf(clear), Move.Up))
        }
        // Entre los "Quitar", de lado a lado.
        assertEquals(l.indexOf(clearLogo), l.move(l.indexOf(clearBg), Move.Right))
    }

    @Test
    fun withGapsTheRemoveRowKeepsItsSlots() {
        // [fondo, —, icono]: cada imagen baja a su "Quitar"; el logo, a igual distancia, al de la izquierda.
        val l = SheetLayout.of(spec(true, clears = listOf(clearBg, clearIcon)), landscape = false)
        assertEquals(listOf(clearBg, null, clearIcon), l.artClears)
        assertEquals(l.indexOf(clearBg), l.move(l.indexOf(bg), Move.Down))
        assertEquals(l.indexOf(clearIcon), l.move(l.indexOf(icon), Move.Down))
        assertEquals(l.indexOf(clearBg), l.move(l.indexOf(logo), Move.Down))
        assertEquals(l.indexOf(icon), l.move(l.indexOf(clearIcon), Move.Up))
        // Solo el icono tiene: desde el fondo se llega a él igual.
        val single = SheetLayout.of(spec(true, clears = listOf(clearIcon)), landscape = false)
        assertEquals(listOf(null, null, clearIcon), single.artClears)
        assertEquals(single.indexOf(clearIcon), single.move(single.indexOf(bg), Move.Down))
        assertEquals(single.indexOf(icon), single.move(single.indexOf(clearIcon), Move.Up))
    }

    @Test
    fun landscapeJumpsBetweenColumns() {
        val l = SheetLayout.of(spec(true), landscape = true)
        assertTrue(l.twoColumns)
        // play 0 · details 1, refresh 2 | bg 3, logo 4, icon 5 · clearBg 6 · remove 7
        // Derecha desde Jugar: a la columna de las imágenes.
        assertEquals(3, l.move(0, Move.Right))
        // Izquierda desde la primera imagen: de vuelta a la columna de Jugar.
        assertEquals(0, l.move(3, Move.Left))
        // Abajo en la columna izquierda no baja a la derecha: ya no hay pie a lo ancho.
        assertEquals(1, l.move(0, Move.Down))
        assertEquals(1, l.move(1, Move.Down))
        // La columna derecha baja de las imágenes a su "Quitar" y de ahí a quitar el juego.
        assertEquals(6, l.move(3, Move.Down))
        assertEquals(7, l.move(6, Move.Down))
        assertEquals(7, l.move(7, Move.Down))
        assertEquals(6, l.move(7, Move.Up))
        assertEquals(3, l.move(6, Move.Up))
        // Derecha desde la última pieza: a la fila de su altura, nunca a quitar el juego por inercia.
        assertEquals(6, l.move(2, Move.Right))
        // Desde la zona de borrado se vuelve a la izquierda, a la fila de su altura.
        assertEquals(2, l.move(6, Move.Left))
        assertEquals(2, l.move(7, Move.Left))
        assertEquals(7, l.move(7, Move.Right))
    }

    @Test
    fun portraitKeepsEverythingInOneColumn() {
        val l = SheetLayout.of(spec(true), landscape = false)
        assertTrue(l.rows.all { it.column == 0 })
        // Las imágenes y sus "Quitar" llevan su hueco; el resto no.
        val art = l.rows.first { l.indexOf(bg) in it.items }
        assertEquals(listOf(0, 1, 2), art.slots?.toList())
        val clears = l.rows.first { l.indexOf(clearBg) in it.items }
        assertEquals(listOf(0), clears.slots?.toList())
        assertEquals(null, l.rows.last().slots)
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
