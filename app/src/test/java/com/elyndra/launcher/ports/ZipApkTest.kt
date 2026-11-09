package com.elyndra.launcher.ports

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipApkTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(vararg files: Pair<String, ByteArray>): File {
        val f = tmp.newFile("r-${System.nanoTime()}.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, data) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return f
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun `the single apk comes out with its hash and the zip stays`() {
        val data = "APK".toByteArray()
        val z = zip("readme.txt" to "hi".toByteArray(), "build/game.apk" to data)
        val out = tmp.newFolder("out")
        val r = ZipApk.extract(z, out) as ZipApk.Result.Ok
        assertEquals("build/game.apk", r.name)
        assertEquals(sha(data), r.sha256)
        assertEquals(out, r.apk.parentFile)
        assertTrue(z.exists())
    }

    @Test fun `with several apks the arm64 or abi-less one wins, otherwise none`() {
        assertEquals("g-arm64-v8a.apk", ZipApk.pick(listOf("g-armeabi-v7a.apk", "g-arm64-v8a.apk")))
        assertEquals("g.apk", ZipApk.pick(listOf("g-x86.apk", "g.apk")))
        assertNull(ZipApk.pick(listOf("g-x86.apk", "g-armeabi-v7a.apk")))
        assertNull(ZipApk.pick(listOf("notes.txt")))
        val out = tmp.newFolder("two")
        assertEquals(ZipApk.Result.Bad("no single apk inside"), ZipApk.extract(zip("a-x86.apk" to byteArrayOf(1), "b-armv7.apk" to byteArrayOf(2)), out))
    }

    @Test fun `entries that leave the archive are never extracted`() {
        assertFalse(ZipApk.safeName("../evil.apk"))
        assertFalse(ZipApk.safeName("/abs/evil.apk"))
        assertFalse(ZipApk.safeName("C:/evil.apk"))
        assertFalse(ZipApk.safeName("a\\..\\evil.apk"))
        assertNull(ZipApk.pick(listOf("../evil.apk", "__MACOSX/._g.apk")))
        val out = tmp.newFolder("evil")
        assertTrue(ZipApk.extract(zip("../evil.apk" to byteArrayOf(1)), out) is ZipApk.Result.Bad)
        assertTrue(out.listFiles().isNullOrEmpty())
    }

    @Test fun `a wrong inner checksum is refused and the apk deleted`() {
        val out = tmp.newFolder("sum")
        val r = ZipApk.extract(zip("g.apk" to "x".toByteArray()), out, expectedSha256 = "00".repeat(32))
        assertEquals(ZipApk.Result.Bad("checksum"), r)
        assertTrue(out.listFiles().isNullOrEmpty())
        assertTrue(ZipApk.extract(zip("g.apk" to "x".toByteArray()), out, expectedSha256 = sha("x".toByteArray()).uppercase()) is ZipApk.Result.Ok)
    }

    @Test fun `not a zip is a bad archive`() {
        val f = tmp.newFile("bad.zip").apply { writeText("nope") }
        assertEquals(ZipApk.Result.Bad("not a zip"), ZipApk.extract(f, tmp.newFolder("bad")))
    }

    @Test fun `a vr headset is told by its system features`() {
        assertTrue(VrDevice.isHeadset(setOf(VrDevice.OCULUS_STANDALONE)))
        assertTrue(VrDevice.isHeadset(setOf(PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE)))
        assertTrue(VrDevice.isHeadset(setOf(VrDevice.HEAD_TRACKING)))
        assertFalse(VrDevice.isHeadset(setOf("android.hardware.touchscreen")))
        assertFalse(VrDevice.isHeadset(emptySet()))
    }
}
