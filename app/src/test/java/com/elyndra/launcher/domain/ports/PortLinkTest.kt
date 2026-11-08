package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.MatchMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortLinkTest {

    private val port = PortEntry(
        id = "soh",
        name = "Ship of Harkinian",
        repo = GhRepo("linkzenic", "Shipwright-Ports"),
        original = OriginalGame("The Legend of Zelda: Ocarina of Time ", "n64", igdbId = 1029),
        long = mapOf("en" to "Native port.", "es" to "Port nativo."),
    )

    @Test fun `the app takes the original title with a port suffix and keeps it`() {
        val meta = PortLink.meta(GameMeta(name = "SoH", cover = "media/x.png", pinned = listOf("cover")), port, "es")
        assertEquals("The Legend of Zelda: Ocarina of Time (Port)", meta.lockedName)
        assertTrue(meta.nameLocked)
        assertEquals(MatchMethod.MANUAL, meta.matchedBy)
        assertEquals(1029L, meta.igdbId)
        assertEquals("Port nativo.", meta.description)
        assertEquals("es", meta.descriptionLang)
        // Lo que ya tenía (arte fijado) no se pierde.
        assertEquals("media/x.png", meta.cover)
        assertEquals(listOf("cover"), meta.pinned)
    }

    @Test fun `without catalog ids the previous ones stay`() {
        val meta = PortLink.meta(GameMeta(sgdbId = 7, igdbId = 9), port.copy(original = port.original.copy(igdbId = null)), "en")
        assertEquals(9L, meta.igdbId)
        assertEquals(7L, meta.sgdbId)
    }

    @Test fun `the library key is the android app key`() {
        assertEquals("a:com.dishii.soh", PortLink.key("com.dishii.soh"))
    }
}
