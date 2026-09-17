package com.elyndra.launcher.library

import com.elyndra.launcher.data.Systems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WiiUTest {

    @Test
    fun wiiUSystemReadsEveryContainerFormat() {
        val wiiu = Systems.byId("wiiu")!!
        assertTrue(listOf("wua", "wud", "wux", "wuhb", "rpx").all { it in wiiu.extensions })
        // Y el juego desempaquetado, que es una carpeta y no una extensión.
        assertTrue(wiiu.dirGames)
    }

    @Test
    fun unpackedGameFolderIsOneGame() {
        assertTrue(WiiU.isGameDir(listOf("code", "content", "meta")))
        assertTrue(WiiU.isGameDir(listOf("Meta", "CODE", "Content", "aoc")))
    }

    @Test
    fun looseFolderIsNotAGame() {
        assertFalse(WiiU.isGameDir(listOf("content")))
        assertFalse(WiiU.isGameDir(listOf("code", "meta")))
        assertFalse(WiiU.isGameDir(emptyList()))
    }

    @Test
    fun innerFoldersAreNeverScannedOnTheirOwn() {
        assertTrue(WiiU.isInnerDir("code"))
        assertTrue(WiiU.isInnerDir("Meta"))
        assertFalse(WiiU.isInnerDir("Super Mario 3D World"))
    }

    @Test
    fun titleIdComesOutOfTheArchiveName() {
        assertEquals("0005000010145D00", WiiU.titleId("Super Mario 3D World [0005000010145d00] (v32).wua"))
        assertEquals("000500001010ED00", WiiU.titleId("Splatoon (000500001010ED00).wua"))
        assertNull(WiiU.titleId("Super Mario 3D World.wua"))
    }

    @Test
    fun archiveNameStillGivesACleanTitle() {
        assertEquals(
            "Super Mario 3D World",
            Names.cleanTitle("Super Mario 3D World [0005000010145D00] (v32).wua"),
        )
    }
}
