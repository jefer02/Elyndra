package com.elyndra.launcher.domain

import com.elyndra.launcher.domain.notify.InstalledGame
import com.elyndra.launcher.domain.notify.NewGameDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class NewGameDetectorTest {

    private val installed = listOf(
        InstalledGame("com.old", "Old", installedAt = 100),
        InstalledGame("com.added.in.app", "Added", installedAt = 500),
        InstalledGame("com.fresh.a", "Fresh A", installedAt = 300),
        InstalledGame("com.fresh.b", "Fresh B", installedAt = 400),
    )

    @Test
    fun onlyWhatWasInstalledSinceTheLastLookAndIsNotInTheLibrary() {
        val fresh = NewGameDetector.fresh(installed, inLibrary = setOf("com.added.in.app"), since = 200)
        assertEquals(listOf("com.fresh.b", "com.fresh.a"), fresh.map { it.packageName })
    }

    @Test
    fun nothingNewIsNothing() {
        assertEquals(emptyList<InstalledGame>(), NewGameDetector.fresh(installed, emptySet(), since = 500))
    }
}
