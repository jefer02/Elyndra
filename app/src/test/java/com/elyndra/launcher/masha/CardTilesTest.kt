package com.elyndra.launcher.masha

import com.elyndra.launcher.masha.CardTiles.Kind
import com.elyndra.launcher.masha.CardTiles.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Con el chat oculto, solo las imágenes: una baldosa por juego o port, con el motivo fuera de la pantalla. */
class CardTilesTest {

    @Test
    fun picksBecomeTilesWithTheirReason() {
        val picks = MashaAttachment.Picks(
            listOf(
                MashaAttachment.Pick(MashaAttachment.Pick.GAME, "rom:okami", "You left it at the best part."),
                MashaAttachment.Pick(MashaAttachment.Pick.PORT, "gow3", "Runs natively here."),
            ),
        )
        assertEquals(
            listOf(Tile(Kind.Game, "rom:okami", "You left it at the best part."), Tile(Kind.Port, "gow3", "Runs natively here.")),
            CardTiles.from(picks),
        )
    }

    @Test
    fun everyVisualResultGivesTilesAndConfirmationsOrDoneGiveNone() {
        assertEquals(2, CardTiles.from(MashaAttachment.Games(null, listOf("a", "b"))).size)
        assertEquals(listOf(Tile(Kind.Port, "p1")), CardTiles.from(MashaAttachment.Ports(null, listOf("p1", "p1"))))
        assertEquals(1, CardTiles.from(MashaAttachment.Plan(30, 40, "any", listOf(MashaAttachment.PlanItem("a", 30, "short")))).size)
        assertTrue(CardTiles.from(MashaAttachment.Confirm("1", ConfirmKind.ADD_GAMES, listOf("x"))).isEmpty())
        assertTrue(CardTiles.from(MashaAttachment.Done("▶ Okami")).isEmpty())
        assertTrue(CardTiles.from(null).isEmpty())
        assertEquals(CardTiles.MAX, CardTiles.from(MashaAttachment.Games(null, List(40) { "g$it" })).size)
    }

    @Test
    fun theLabelCarriesTheTitleAndTheReason() {
        assertEquals("Okami. You left it at the best part.", CardTiles.label("Okami", " You left it at the best part. "))
        assertEquals("Okami", CardTiles.label("Okami", ""))
    }
}
