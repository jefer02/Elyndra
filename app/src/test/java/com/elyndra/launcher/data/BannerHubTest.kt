package com.elyndra.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BannerHubTest {

    @Test
    fun readsTheIdFromAFileWithJustTheNumber() {
        assertEquals("268910", BannerHub.parseId("268910"))
        assertEquals("268910", BannerHub.parseId("\uFEFF268910\r\n"))
        // Un comentario delante no estorba: ES-DE exporta algunos así.
        assertEquals("2551", BannerHub.parseId("# Hades\n2551\n"))
    }

    @Test
    fun ignoresWhatIsNotAnId() {
        assertNull(BannerHub.parseId(null))
        assertNull(BannerHub.parseId(""))
        assertNull(BannerHub.parseId("\n\n"))
        // Un .iso de verdad: una línea de texto, no un id anotado a mano.
        assertNull(BannerHub.parseId("CD001 Hollow Knight disc image"))
    }

    @Test
    fun manualIdsAreTrimmedAndChecked() {
        assertEquals("10521", BannerHub.normalizeId("  10521 "))
        assertNull(BannerHub.normalizeId(""))
        assertNull(BannerHub.normalizeId("   "))
        assertNull(BannerHub.normalizeId("268910 Hollow Knight"))
    }

    @Test
    fun theRuntimesThatLaunchByGameIdAreKnown() {
        assertTrue(Emulators.usesGameId("bannerhub"))
        assertTrue(Emulators.usesGameId("gamehub"))
        assertTrue(Emulators.usesGameId("gamenative"))
        // Winlator recibe la ruta de su acceso directo, no un id.
        assertFalse(Emulators.usesGameId("winlator_cmod"))
        assertFalse(Emulators.usesGameId("ppsspp"))
        assertFalse(Emulators.usesGameId(null))
    }

    @Test
    fun theProfileIsBuiltFromTheseVariants() {
        val profile = Emulators.byId("bannerhub")!!
        assertTrue(profile.packages.containsAll(BannerHub.PACKAGES))
        assertEquals(BannerHub.PACKAGES.first(), profile.packages.first())
        assertEquals("%PKG%.LAUNCH_GAME", profile.action)
        assertFalse(profile.launchOnly)
    }
}
