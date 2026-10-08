package com.elyndra.launcher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {

    private fun v(s: String) = SemVer.parse(s)!!

    private fun release(tag: String, pre: Boolean = false, draft: Boolean = false, assets: List<ReleaseAsset> = emptyList()) =
        Release(tag, tag, "", "https://example/$tag", pre, draft, assets)

    private fun apk(name: String) = ReleaseAsset(name, "https://example/$name", 1)

    /* ── SemVer ───────────────────────────────────────────────── */

    @Test fun `parses tags with v, short versions and build metadata`() {
        assertEquals(SemVer(0, 3, 0, listOf("beta")), SemVer.parse("v0.3.0-beta"))
        assertEquals(SemVer(2, 0, 0), SemVer.parse("2.0"))
        assertEquals(SemVer(1, 2, 3, listOf("rc", "1")), SemVer.parse("V1.2.3-RC.1+build.7"))
        assertEquals(SemVer(1, 0, 0), SemVer.parse(" 1 "))
    }

    @Test fun `rejects what is not semver`() {
        listOf(null, "", "v", "beta", "1.2.3.4", "1.x", "1.0-", "1.0.0-be ta", "latest").forEach {
            assertNull(it, SemVer.parse(it))
        }
    }

    @Test fun `orders like semver`() {
        val ordered = listOf(
            "0.2.0-beta", "0.3.0-alpha", "0.3.0-beta", "0.3.0-beta.2", "0.3.0-beta.10", "0.3.0-rc.1", "0.3.0", "0.3.1", "0.10.0", "1.0.0-beta", "1.0.0",
        ).map(::v)
        assertEquals(ordered, ordered.shuffled(java.util.Random(4)).sorted())
        assertTrue(v("1.0.0-1") < v("1.0.0-alpha"))
        assertEquals(0, v("v1.0.0+a").compareTo(v("1.0.0+b")))
    }

    @Test fun `a stable release beats its betas`() {
        assertTrue(v("0.3.0") > v("0.3.0-beta"))
        assertFalse(v("0.3.0-beta") > v("0.3.0"))
    }

    /* ── newest ───────────────────────────────────────────────── */

    @Test fun `offers the newest release above the installed one`() {
        val list = listOf(release("v0.2.0-beta", pre = true), release("v0.4.0-beta", pre = true), release("v0.3.0-beta", pre = true))
        assertEquals("v0.4.0-beta", UpdateLogic.newest(list, "0.3.0-beta", includePrereleases = true)?.tag)
    }

    @Test fun `nothing when installed is the newest or newer`() {
        val list = listOf(release("v0.3.0-beta", pre = true))
        assertNull(UpdateLogic.newest(list, "0.3.0-beta", true))
        assertNull(UpdateLogic.newest(list, "0.4.0", true))
        // El versionName de antes ("2.0") no recibe una "actualización" que sería un paso atrás.
        assertNull(UpdateLogic.newest(list, "2.0", true))
    }

    @Test fun `drafts never count`() {
        val list = listOf(release("v9.0.0", draft = true), release("v0.4.0"))
        assertEquals("v0.4.0", UpdateLogic.newest(list, "0.3.0", true)?.tag)
    }

    @Test fun `pre-releases only when included`() {
        val list = listOf(release("v0.5.0-beta", pre = true), release("v0.4.0"), release("v0.4.5-beta"))
        assertEquals("v0.5.0-beta", UpdateLogic.newest(list, "0.3.0", true)?.tag)
        // Sin betas: fuera la marcada como pre-release y la que lleva sufijo aunque no lo esté.
        assertEquals("v0.4.0", UpdateLogic.newest(list, "0.3.0", false)?.tag)
    }

    @Test fun `stable install is offered the stable that follows its betas`() {
        val list = listOf(release("v1.0.0-beta", pre = true), release("v1.0.0"))
        assertEquals("v1.0.0", UpdateLogic.newest(list, "1.0.0-beta", true)?.tag)
    }

    @Test fun `ignores non semver tags and unparsable installed versions`() {
        val list = listOf(release("nightly"), release("v0.4.0"))
        assertEquals("v0.4.0", UpdateLogic.newest(list, "0.3.0", true)?.tag)
        assertNull(UpdateLogic.newest(list, "dev-build", true))
    }

    /* ── throttling y "no volver a preguntar" ─────────────────── */

    @Test fun `auto check at most once a day`() {
        val day = UpdateLogic.AUTO_INTERVAL_MS
        assertTrue(UpdateLogic.shouldAutoCheck(true, now = 1_000, lastCheckAt = 0))
        assertFalse(UpdateLogic.shouldAutoCheck(true, now = 10 + day - 1, lastCheckAt = 10))
        assertTrue(UpdateLogic.shouldAutoCheck(true, now = 10 + day, lastCheckAt = 10))
        assertFalse(UpdateLogic.shouldAutoCheck(false, now = 10 + day * 5, lastCheckAt = 10))
        // Reloj hacia atrás: se vuelve a mirar en vez de esperar días.
        assertTrue(UpdateLogic.shouldAutoCheck(true, now = 5, lastCheckAt = 10_000))
    }

    @Test fun `cancelled version is not offered again automatically, but manually it is`() {
        val r = release("v0.4.0-beta")
        assertFalse(UpdateLogic.shouldPrompt(r, skippedTag = "v0.4.0-beta", manual = false))
        assertTrue(UpdateLogic.shouldPrompt(r, skippedTag = "v0.4.0-beta", manual = true))
        assertTrue(UpdateLogic.shouldPrompt(r, skippedTag = "v0.3.0-beta", manual = false))
        assertTrue(UpdateLogic.shouldPrompt(r, skippedTag = null, manual = false))
    }

    /* ── elegir el APK ────────────────────────────────────────── */

    private val split = listOf(
        apk("Elyndra-v0.4.0-beta-universal.apk"),
        apk("Elyndra-v0.4.0-beta-arm64-v8a.apk"),
        apk("Elyndra-v0.4.0-beta-arm64-v8a.apk.sha256"),
        apk("Elyndra-v0.4.0-beta-armeabi-v7a.apk"),
        apk("Elyndra-v0.4.0-beta-x86_64.apk"),
        apk("Source code.zip"),
    )

    @Test fun `picks the APK of the preferred ABI`() {
        assertEquals("Elyndra-v0.4.0-beta-arm64-v8a.apk", UpdateLogic.chooseAsset(split, listOf("arm64-v8a", "armeabi-v7a", "armeabi"))?.name)
        assertEquals("Elyndra-v0.4.0-beta-armeabi-v7a.apk", UpdateLogic.chooseAsset(split, listOf("armeabi-v7a", "armeabi"))?.name)
        assertEquals("Elyndra-v0.4.0-beta-x86_64.apk", UpdateLogic.chooseAsset(split, listOf("x86_64", "x86"))?.name)
    }

    @Test fun `x86 does not match the x86_64 APK`() {
        assertEquals("Elyndra-v0.4.0-beta-universal.apk", UpdateLogic.chooseAsset(split, listOf("x86"))?.name)
    }

    @Test fun `falls back to universal, then to a single APK without ABI`() {
        val onlyUniversal = listOf(apk("Elyndra-v0.3.0-beta-arm64-v8a.apk"), apk("Elyndra-v0.3.0-beta-universal.apk"))
        assertEquals("Elyndra-v0.3.0-beta-universal.apk", UpdateLogic.chooseAsset(onlyUniversal, listOf("x86_64"))?.name)
        // Como la v0.2.0-beta: un solo APK sin ABI en el nombre.
        assertEquals("Elyndra.v0.2.0-beta.apk", UpdateLogic.chooseAsset(listOf(apk("Elyndra.v0.2.0-beta.apk")), listOf("arm64-v8a"))?.name)
    }

    @Test fun `no safe APK means null`() {
        assertNull(UpdateLogic.chooseAsset(listOf(apk("Elyndra-arm64-v8a.apk")), listOf("x86_64")))
        assertNull(UpdateLogic.chooseAsset(listOf(apk("a.apk"), apk("b.apk")), listOf("arm64-v8a")))
        assertNull(UpdateLogic.chooseAsset(listOf(apk("Source code.zip")), listOf("arm64-v8a")))
    }

    @Test fun `finds the sha256 sidecar`() {
        val apk = split[1]
        assertEquals("Elyndra-v0.4.0-beta-arm64-v8a.apk.sha256", UpdateLogic.checksumAsset(apk, split)?.name)
        assertNull(UpdateLogic.checksumAsset(split[0], split))
    }

    /* ── resúmenes ────────────────────────────────────────────── */

    @Test fun `parses GitHub digests and sha256sum files`() {
        val hex = "D862A7A7CB0A529ABC7C640D0BD60A539C00E3F3E4BB18EA5CA1B84D37A6D1F4"
        assertEquals(hex.lowercase(), UpdateLogic.parseDigest("sha256:$hex"))
        assertNull(UpdateLogic.parseDigest("sha512:abcd"))
        assertNull(UpdateLogic.parseDigest("sha256:1234"))
        assertNull(UpdateLogic.parseDigest(null))
        assertEquals(hex.lowercase(), UpdateLogic.parseChecksumFile("$hex  Elyndra.apk\n"))
        assertNull(UpdateLogic.parseChecksumFile("not a hash"))
    }

    /* ── notas ────────────────────────────────────────────────── */

    @Test fun `notes lose markdown syntax and repeated blank lines`() {
        val body = "## What's new\r\n\r\n\r\n- **Masha** 3D\n* [Gamepad](https://x) support\n<!-- hidden -->\n![shot](a.png)\n`code`"
        assertEquals("What's new\n\n• Masha 3D\n• Gamepad support\n\ncode", UpdateLogic.trimNotes(body))
    }

    @Test fun `long notes are cut with an ellipsis`() {
        val lines = (1..30).joinToString("\n") { "line $it" }
        val cut = UpdateLogic.trimNotes(lines, maxLines = 3)
        assertEquals("line 1\nline 2\nline 3…", cut)
        val long = "word ".repeat(400)
        val trimmed = UpdateLogic.trimNotes(long, maxChars = 100)
        assertTrue(trimmed.endsWith("…"))
        assertTrue(trimmed.length <= 101)
        assertEquals("", UpdateLogic.trimNotes(null))
        assertEquals("", UpdateLogic.trimNotes("  \n "))
    }
}
