package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.update.ReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Test

class PortStatusTest {

    private val android = PortEntry("p", "P", repo = GhRepo("o", "r"), game = PortGame("G", "n64"), builds = listOf(PortBuild("android")))
    private val pc = android.copy(builds = listOf(PortBuild("windows")))

    private fun latest(tag: String, at: Long = 100) =
        Installable(PortRelease(tag, tag, "", "", false, false, at, emptyList()), ReleaseAsset("p.apk", "https://github.com/x", 1))

    private fun installed(tag: String, at: Long = 50) = InstalledPort("p", "com.p", tag, at)

    @Test fun `before the releases are known a port is just free`() {
        assertEquals(PortAction.Free, PortStatus.action(android, null, null, releasesKnown = false))
    }

    @Test fun `an installable release offers install and none says unavailable`() {
        assertEquals(PortAction.Install, PortStatus.action(android, null, latest("v1"), releasesKnown = true))
        assertEquals(PortAction.Unavailable, PortStatus.action(android, null, null, releasesKnown = true))
    }

    @Test fun `pc only ports are never installable`() {
        assertEquals(PortAction.NotOnAndroid, PortStatus.action(pc, null, null, releasesKnown = true))
    }

    @Test fun `installed ports offer an update only when the release is newer`() {
        assertEquals(PortAction.Installed, PortStatus.action(android, installed("v1"), latest("v1"), releasesKnown = true))
        assertEquals(PortAction.Update, PortStatus.action(android, installed("v1"), latest("v2"), releasesKnown = true))
        assertEquals(PortAction.Installed, PortStatus.action(android, installed("v2"), latest("v1"), releasesKnown = true))
        // Sin saber aún la última release, lo instalado se ve instalado.
        assertEquals(PortAction.Installed, PortStatus.action(android, installed("v1"), null, releasesKnown = false))
    }
}
