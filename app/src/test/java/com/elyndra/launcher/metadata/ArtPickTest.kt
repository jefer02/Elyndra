package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.MatchMethod
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Un decodificador falso: "sabe" el tamaño de cada imagen de prueba. */
private class FakeCodec(var size: Pair<Int, Int>? = 512 to 512, var png: ByteArray? = PNG) : ImageCodec {
    var conversions = 0
    override fun bounds(bytes: ByteArray) = size
    override fun toPng(bytes: ByteArray): ByteArray? {
        conversions++
        return png
    }
}

private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13, 1, 2)
private val JPG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1)
private val ICO = byteArrayOf(0, 0, 1, 0, 1, 0, 32, 32, 0, 0, 1, 0, 32, 0)
private val WEBP = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
private val HTML = "<html><body>not found</body></html>".toByteArray()

class ImageRulesTest {

    @Test
    fun sniffsRealFormatsByTheirBytes() {
        assertEquals("png", ImageRules.sniff(PNG))
        assertEquals("jpg", ImageRules.sniff(JPG))
        assertEquals("webp", ImageRules.sniff(WEBP))
        // Lo que rompía los iconos de SteamGridDB: ICO no se reconocía.
        assertEquals("ico", ImageRules.sniff(ICO))
        assertEquals(null, ImageRules.sniff(HTML))
        assertEquals(null, ImageRules.sniff("NOMEDIA".toByteArray()))
    }

    @Test
    fun validationOutcomes() {
        val codec = FakeCodec()
        assertTrue(ImageRules.check(PNG, codec) is ImageRules.Checked.Ok)
        assertEquals(ImageRules.Checked.Bad(MediaFailure.Unsupported), ImageRules.check(HTML, codec))
        // Cabecera de PNG pero no se puede decodificar.
        assertEquals(ImageRules.Checked.Bad(MediaFailure.Undecodable), ImageRules.check(PNG, FakeCodec(size = null)))
        assertEquals(ImageRules.Checked.Bad(MediaFailure.TooSmall), ImageRules.check(PNG, FakeCodec(size = 16 to 16)))
        assertEquals(ImageRules.Checked.Bad(MediaFailure.TooBig), ImageRules.check(PNG, FakeCodec(size = 20_000 to 20_000)))
    }

    @Test
    fun icoIsConvertedToPng() {
        val codec = FakeCodec()
        val ok = ImageRules.check(ICO, codec) as ImageRules.Checked.Ok
        assertEquals("png", ok.ext)
        assertEquals(1, codec.conversions)
        assertEquals(ImageRules.Checked.Bad(MediaFailure.Undecodable), ImageRules.check(ICO, FakeCodec(png = null)))
    }
}

class MediaCacheTest {

    private val dir: File = Files.createTempDirectory("media").toFile()
    private val server = MockWebServer()

    @After
    fun cleanup() {
        server.shutdown()
        dir.deleteRecursively()
    }

    @Test
    fun eachChoiceGetsANewPathSoNoCacheShowsTheOldOne() {
        val media = MediaCache(dir, FakeCodec())
        val first = (media.saveChecked(PNG, "a:x", "icon") as MediaResult.Saved).path
        Thread.sleep(2)
        val second = (media.saveChecked(PNG, "a:x", "icon") as MediaResult.Saved).path
        assertNotEquals(first, second)
        // Solo queda la última: la anterior se borra.
        assertFalse(File(dir, first).exists())
        assertTrue(File(dir, second).exists())
    }

    @Test
    fun failedDownloadsKeepThePreviousImageAndSayWhy() = runBlocking {
        val media = MediaCache(dir, FakeCodec())
        val kept = (media.saveChecked(PNG, "a:x", "icon") as MediaResult.Saved).path
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(Buffer().write(HTML)))
        server.enqueue(MockResponse().setBody(Buffer().write(ICO)))
        assertEquals(MediaResult.Failed(MediaFailure.Http), media.fetch(server.url("/a.png").toString(), "a:x", "icon"))
        assertEquals(MediaResult.Failed(MediaFailure.Unsupported), media.fetch(server.url("/b.png").toString(), "a:x", "icon"))
        assertTrue("la anterior sigue ahí", File(dir, kept).exists())
        // Un .ico de verdad entra, convertido a PNG.
        val saved = media.fetch(server.url("/c.ico").toString(), "a:x", "icon") as MediaResult.Saved
        assertTrue(saved.path.endsWith(".png"))
    }

    @Test
    fun noConnectionIsANetworkFailure() = runBlocking {
        val media = MediaCache(dir, FakeCodec())
        server.shutdown()
        assertEquals(MediaResult.Failed(MediaFailure.Network), media.fetch("http://127.0.0.1:1/x.png", "a:x", "icon"))
    }
}

class UserArtWinsTest {

    private val merged = MergedMetadata(
        text = emptyMap(), rating = null, art = emptyMap(), sources = listOf(Service.SteamGridDb),
        ssId = null, igdbId = null, sgdbId = 5, ra = null, matchedBy = MatchMethod.NAME, confidence = 0.9f,
    )

    @Test
    fun aPassNeverReplacesAPinnedImage() {
        val old = GameMeta(icon = "media/a/icon_1.png", pinned = listOf("icon"), artOrigins = mapOf("icon" to com.elyndra.launcher.data.ArtOrigin("sgdb", "u1")))
        // Aunque la pasada (que empezó antes de elegir) trajera otro icono, manda el elegido.
        val after = ScrapeApply.apply(old, merged, "es", ScrapeApply.Art(icon = "media/a/icon_2.png", origins = mapOf("icon" to com.elyndra.launcher.data.ArtOrigin("igdb", "u2"))), 1)
        assertEquals("media/a/icon_1.png", after.icon)
        assertEquals("u1", after.artOrigins["icon"]?.url)
        // Sin fijar, sí se actualiza.
        val free = ScrapeApply.apply(old.copy(pinned = emptyList()), merged, "es", ScrapeApply.Art(icon = "media/a/icon_2.png"), 1)
        assertEquals("media/a/icon_2.png", free.icon)
    }
}
