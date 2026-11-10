package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.update.ReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortReleasesTest {

    private fun a(name: String, sha: String? = null) = ReleaseAsset(name, "https://github.com/o/r/releases/download/t/$name", 10, sha)
    private val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val x86 = listOf("x86_64", "x86")
    private val build = PortBuild(platform = "android")

    private fun rel(tag: String, at: Long, vararg assets: ReleaseAsset, pre: Boolean = false, draft: Boolean = false) =
        PortRelease(tag, tag, "", "https://github.com/o/r/releases/tag/$tag", pre, draft, at, assets.toList())

    @Test fun `prefers the abi order of the catalog that the device runs`() {
        val assets = listOf(a("game-universal.apk"), a("game-arm64-v8a.apk"), a("game-x86_64.apk"))
        assertEquals("game-arm64-v8a.apk", PortAssetPicker.pick(assets, build, arm64)!!.name)
        assertEquals("game-universal.apk", PortAssetPicker.pick(assets, build, x86)!!.name)
        val x86First = build.copy(abis = listOf("x86_64", "universal"))
        assertEquals("game-x86_64.apk", PortAssetPicker.pick(assets, x86First, x86)!!.name)
    }

    @Test fun `an apk without abi in its name counts as universal`() {
        assertEquals("soh.apk", PortAssetPicker.pick(listOf(a("soh.apk")), build, x86)!!.name)
        assertEquals(
            "NSMBW-0.4.1-android-arm64.apk",
            PortAssetPicker.pick(listOf(a("NSMBW-0.4.1-android-arm64.apk"), a("NSMBW-0.4.1-windows-x64.zip")), build, arm64)!!.name,
        )
    }

    @Test fun `debug unsigned zips and ios builds are never picked`() {
        val assets = listOf(a("app-debug.apk"), a("game-unsigned.apk"), a("v5-android.zip"), a("Ship-iOS-unsigned.ipa"))
        assertNull(PortAssetPicker.pick(assets, build, arm64))
        assertEquals("app-release.apk", PortAssetPicker.pick(assets + a("app-release.apk"), build, arm64)!!.name)
        // "debugger" o "undebug" no son "debug".
        assertEquals(listOf("debugger-tools.apk"), PortAssetPicker.candidates(listOf(a("debugger-tools.apk")), build).map { it.name })
    }

    @Test fun `the catalog pattern narrows the assets`() {
        val soh = build.copy(asset = "(?i)android.*\\.apk$")
        val assets = listOf(a("Ship-of-Harkinian-9.3.1-iOS-unsigned.ipa"), a("other.apk"), a("soh_release_v9.3.1-android.4.apk"))
        assertEquals("soh_release_v9.3.1-android.4.apk", PortAssetPicker.pick(assets, soh, arm64)!!.name)
        assertNull(PortAssetPicker.pick(assets, build.copy(asset = "(["), arm64))
    }

    @Test fun `latest skips drafts and prefers stable releases`() {
        val releases = listOf(
            rel("v3", 300, a("g.apk"), draft = true),
            rel("v2-beta", 200, a("g.apk"), pre = true),
            rel("v1", 100, a("g.apk")),
            rel("v0", 50, a("g.zip")),
        )
        assertEquals("v1", PortAssetPicker.latest(releases, build, arm64)!!.release.tag)
        val onlyPre = listOf(rel("v0.4.5", 10, a("FH2-Recompiled-0.4.5.apk"), pre = true))
        assertEquals("v0.4.5", PortAssetPicker.latest(onlyPre, build, arm64)!!.release.tag)
        assertNull(PortAssetPicker.latest(listOf(rel("v0", 50, a("g.zip"))), build, arm64))
    }

    @Test fun `the catalog checksum wins over the api digest`() {
        val asset = a("g.apk", sha = "aa".repeat(32))
        assertEquals("aa".repeat(32), PortAssetPicker.expectedSha256(asset, build))
        val pinned = build.copy(sha256 = mapOf("G.APK" to "BB".repeat(32)))
        assertEquals("bb".repeat(32), PortAssetPicker.expectedSha256(asset, pinned))
        assertNull(PortAssetPicker.expectedSha256(a("g.apk"), build))
    }

    @Test fun `semver tags compare as semver`() {
        assertTrue(PortVersions.isNewer("v1.3", 0, "v1.2", 0))
        assertFalse(PortVersions.isNewer("v1.2", 0, "v1.3", 0))
        assertTrue(PortVersions.isNewer("1.0.0", 0, "1.0.0-beta.2", 0))
        assertFalse(PortVersions.isNewer("v1.0.0-rc.1", 0, "1.0.0", 0))
        assertFalse(PortVersions.isNewer("V1.2", 0, "v1.2", 0))
    }

    @Test fun `fork revisions compare by their numbers`() {
        assertTrue(PortVersions.isNewer("v5.0.1-android.3", 0, "v5.0.1-android.2", 0))
        assertTrue(PortVersions.isNewer("v9.0.2P2", 0, "v9.0.2P1", 0))
        assertTrue(PortVersions.isNewer("v9.3.1-ports.2", 0, "v9.0.2P2", 0))
        assertFalse(PortVersions.isNewer("v9.3.1-ports.1", 0, "v9.3.1-ports.2", 0))
        assertTrue(PortVersions.isNewer("3.5.10", 0, "3.5.9", 0))
    }

    @Test fun `tags without numbers fall back to the publication date`() {
        assertTrue(PortVersions.isNewer("nightly-b", 2_000, "nightly-a", 1_000))
        assertFalse(PortVersions.isNewer("nightly-b", 500, "nightly-a", 1_000))
        assertFalse(PortVersions.isNewer("ci-dev-build", 2_000, "ci-dev-build", 1_000))
    }
}
