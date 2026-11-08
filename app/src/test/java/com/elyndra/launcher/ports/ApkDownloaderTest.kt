package com.elyndra.launcher.ports

import com.elyndra.launcher.update.ApkDownloader
import com.elyndra.launcher.update.ReleaseAsset
import com.elyndra.launcher.update.UpdateError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class ApkDownloaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var allow: HostAllowlist
    private val client = OkHttpClient.Builder().followRedirects(false).build()
    private val downloader = ApkDownloader(client, PortsHttp.USER_AGENT)
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        allow = HostAllowlist(setOf(server.hostName), allowHttp = true)
    }

    @After fun tearDown() = server.shutdown()

    private fun asset(path: String = "/download/soh.apk") = ReleaseAsset("soh release.apk", server.url(path).toString(), bytes.size.toLong())

    @Test fun `follows redirects inside the allowlist and verifies the checksum`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/objects/blob").toString()))
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val progress = mutableListOf<Float>()
        val file = downloader.download(asset(), tmp.root, sha, { progress += it }, allow, resume = true)
        assertEquals("soh_release.apk", file.name)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1f, progress.last())
        assertFalse(File(tmp.root, "soh_release.apk.part").exists())
        server.takeRequest()
        val second = server.takeRequest()
        assertEquals("/objects/blob", second.path)
        assertNull(second.getHeader("Authorization"))
    }

    @Test fun `a redirect off the allowlist is refused before connecting`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/soh.apk"))
        try {
            downloader.download(asset(), tmp.root, sha, {}, allow, resume = true)
            fail("expected BadResponse")
        } catch (e: UpdateError.BadResponse) {
            assertTrue(e.message!!.contains("evil.example"))
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `the release hosts are the only download hosts`() {
        fun ok(url: String) = HostAllowlist.DOWNLOADS.allows(okhttp3.HttpUrl.Builder().scheme(url.substringBefore("://")).host(url.substringAfter("://")).build())
        assertTrue(ok("https://github.com"))
        assertTrue(ok("https://objects.githubusercontent.com"))
        assertTrue(ok("https://release-assets.githubusercontent.com"))
        assertFalse(ok("http://github.com"))
        assertFalse(ok("https://raw.githubusercontent.com"))
        assertFalse(ok("https://github.com.evil.example"))
    }

    @Test fun `an interrupted download resumes with a range request`() = runTest {
        val half = bytes.size / 2
        File(tmp.root, "soh_release.apk.part").writeBytes(bytes.copyOfRange(0, half))
        server.enqueue(MockResponse().setResponseCode(206).setBody(Buffer().write(bytes, half, bytes.size - half)))
        val progress = mutableListOf<Float>()
        val file = downloader.download(asset(), tmp.root, sha, { progress += it }, allow, resume = true)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("bytes=$half-", server.takeRequest().getHeader("Range"))
        assertTrue(progress.first() >= 0.5f)
    }

    @Test fun `a server that ignores ranges restarts from zero`() = runTest {
        File(tmp.root, "soh_release.apk.part").writeBytes(ByteArray(10) { 9 })
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        val file = downloader.download(asset(), tmp.root, sha, {}, allow, resume = true)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun `a checksum mismatch deletes everything`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
        try {
            downloader.download(asset(), tmp.root, "00".repeat(32), {}, allow, resume = true)
            fail("expected Checksum")
        } catch (e: UpdateError.Checksum) {
            // ok
        }
        assertEquals(0, tmp.root.listFiles()!!.size)
    }

    @Test fun `a failed download keeps the partial file for later`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            downloader.download(asset(), tmp.root, sha, {}, allow, resume = true)
            fail("expected Server")
        } catch (e: UpdateError.Server) {
            assertEquals(503, e.code)
        }
        File(tmp.root, "soh_release.apk.part").writeBytes(bytes.copyOfRange(0, 10))
        server.enqueue(MockResponse().setResponseCode(416))
        try {
            downloader.download(asset(), tmp.root, sha, {}, allow, resume = true)
            fail("expected Server")
        } catch (e: UpdateError.Server) {
            assertEquals(416, e.code)
        }
        // Un 416 quiere decir que lo guardado ya no sirve.
        assertFalse(File(tmp.root, "soh_release.apk.part").exists())
    }
}
