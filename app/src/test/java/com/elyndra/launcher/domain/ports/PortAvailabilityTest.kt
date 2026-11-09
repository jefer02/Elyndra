package com.elyndra.launcher.domain.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortAvailabilityTest {

    private val source = PortEntry(
        id = "cnc", name = "cnc-port", repo = GhRepo("dk8827", "cnc-port"),
        game = PortGame("Command & Conquer", "pc"), availability = PortEntry.SOURCE,
        trust = PortTrust(verified = true, stars = 500),
    )
    private val vr = PortEntry(
        id = "rerac", name = "Rerac-VR", repo = GhRepo("m", "RatchetAndClank-Quest-VR"),
        game = PortGame("Ratchet & Clank", "ps2"), builds = listOf(PortBuild(PortBuild.ANDROID)),
        availability = PortEntry.VR_ONLY,
    )

    @Test fun `a view-only entry is never installable and says so`() {
        assertFalse(source.installable)
        assertTrue(source.notOnAndroid)
        assertFalse(PortAvailability.installable(source, isVrHeadset = true))
        assertEquals(PortAction.NotOnAndroid, PortStatus.action(source, null, null, releasesKnown = true))
        // Aunque el catálogo traiga un build de Android, "source" manda.
        assertFalse(source.copy(builds = listOf(PortBuild(PortBuild.ANDROID))).installable)
    }

    @Test fun `vr-only builds install only on a headset`() {
        assertFalse(PortAvailability.installable(vr, isVrHeadset = false))
        assertTrue(PortAvailability.installable(vr, isVrHeadset = true))
        assertEquals(PortAction.VrOnly, PortStatus.action(vr, null, null, releasesKnown = true))
        val onHeadset = PortAvailability.effective(vr, isVrHeadset = true, found = null)
        assertEquals(PortAction.Free, PortStatus.action(onHeadset, null, null, releasesKnown = false))
    }

    @Test fun `an apk found after the catalog makes the entry installable but unverified`() {
        val found = PortAvailability.Found("v1.0", "cnc-android-arm64.apk")
        val e = PortAvailability.effective(source, isVrHeadset = false, found = found)
        assertTrue(e.installable)
        assertFalse(e.verified)
        assertTrue(e.trust.discovered)
        assertTrue(Regex(e.android!!.asset).containsMatchIn("cnc-android-arm64.apk"))
        assertFalse(Regex(e.android!!.asset).containsMatchIn("other.apk"))
        // Sin hallazgo, nada cambia.
        assertEquals(source, PortAvailability.effective(source, false, null))
    }

    @Test fun `the found apk prefers arm64 and never a debug build`() {
        assertEquals("g-arm64-v8a.apk", PortAvailability.apkIn(listOf("g-x86.apk", "g-arm64-v8a.apk", "g.zip")))
        assertEquals("g.apk", PortAvailability.apkIn(listOf("g-debug.apk", "g.apk")))
        assertEquals(null, PortAvailability.apkIn(listOf("g-debug.apk", "notes.txt")))
    }

    @Test fun `the daily check walks every view-only port in turns`() {
        val ids = (1..70).map { "p%02d".format(it) }
        val (first, c1) = PortAvailability.batch(ids, 0)
        assertEquals(30, first.size)
        val (second, c2) = PortAvailability.batch(ids, c1)
        val (third, _) = PortAvailability.batch(ids, c2)
        assertEquals(ids.toSet(), (first + second + third).toSet())
        assertEquals(emptyList<String>() to 0, PortAvailability.batch(emptyList(), 5))
        assertEquals(listOf("a", "b"), PortAvailability.batch(listOf("b", "a"), 99).first.sorted())
    }

    @Test fun `zip builds pick the android zip and debug builds only when the catalog allows them`() {
        fun a(n: String) = com.elyndra.launcher.update.ReleaseAsset(n, "https://x/$n", 1)
        val zipBuild = PortBuild(PortBuild.ANDROID, asset = """(?i)(android|apk).*\.zip$""", archive = """(?i)\.apk$""")
        assertEquals("g-android-arm64.zip", PortAssetPicker.pick(listOf(a("g-windows.zip"), a("g-android-arm64.zip")), zipBuild, listOf("arm64-v8a"))!!.name)
        val apk = PortBuild(PortBuild.ANDROID)
        assertEquals(null, PortAssetPicker.pick(listOf(a("app-debug.apk")), apk, listOf("arm64-v8a")))
        assertEquals("app-debug.apk", PortAssetPicker.pick(listOf(a("app-debug.apk")), apk, listOf("arm64-v8a"), allowDebug = true)!!.name)
    }

    @Test fun `the unverified dialog has only the unverified text, never a malware-scan line`() {
        val found = PortAvailability.effective(source, false, PortAvailability.Found("v1", "a.apk"))
        assertEquals(listOf(UnverifiedDialog.Line.Unverified), UnverifiedDialog.lines(found))
        assertEquals(listOf("Unverified"), UnverifiedDialog.Line.entries.map { it.name })
        assertTrue(UnverifiedDialog.lines(source.copy(trust = PortTrust(verified = true))).isEmpty())
    }
}
