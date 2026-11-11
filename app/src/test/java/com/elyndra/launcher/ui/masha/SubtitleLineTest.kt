package com.elyndra.launcher.ui.masha

import org.junit.Assert.assertEquals
import org.junit.Test

/** Sin voz y con el chat oculto, la respuesta se queda lo justo para leerla. */
class SubtitleLineTest {

    @Test
    fun aShortReplyStaysThreeSeconds() {
        assertEquals(3_000L, captionMs("Okay!"))
        assertEquals(3_000L, captionMs(""))
    }

    @Test
    fun aLongerOneStaysSixtyMillisecondsPerCharacter() {
        assertEquals(6_000L, captionMs("x".repeat(100)))
    }
}
