package com.elyndra.launcher.domain.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortDisplayTest {

    private val soh = PortEntry(
        id = "soh",
        name = " Ship of Harkinian ",
        developer = "HarbourMasters",
        repo = GhRepo("linkzenic", "Shipwright-Ports"),
        game = PortGame(" The Legend of Zelda: Ocarina of Time ", "n64"),
    )

    @Test fun `the game name comes first and the port is the secondary line`() {
        assertEquals("The Legend of Zelda: Ocarina of Time", PortDisplay.title(soh))
        assertEquals(PortDisplay.PortLine("Ship of Harkinian", "HarbourMasters"), PortDisplay.portLine(soh))
    }

    @Test fun `without a developer the line has only the port name`() {
        assertNull(PortDisplay.portLine(soh.copy(developer = " ")).developer)
    }

    @Test fun `a blank game name falls back to the port name`() {
        assertEquals("Ship of Harkinian", PortDisplay.title(soh.copy(game = soh.game.copy(name = "  "))))
    }

    @Test fun `platform badges use the short system name`() {
        assertEquals("N64", PortDisplay.platformBadge("n64"))
        assertEquals("SWITCH", PortDisplay.platformBadge("switch"))
        assertEquals("XBOX 360", PortDisplay.platformBadge("xbox360"))
        assertEquals("PC", PortDisplay.platformBadge("pc"))
        assertEquals("ZX81", PortDisplay.platformBadge("zx81"))
    }
}
