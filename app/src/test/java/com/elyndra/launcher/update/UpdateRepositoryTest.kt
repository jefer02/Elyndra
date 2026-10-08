package com.elyndra.launcher.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class UpdateRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val cache = MemoryCache()
    private lateinit var repo: UpdateRepository

    private class MemoryCache : ReleaseCache {
        override var etag: String? = null
        override var body: String? = null
        override fun store(etag: String?, body: String) {
            this.etag = etag
            this.body = body
        }
    }

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        repo = UpdateRepository(OkHttpClient(), server.url("/repos/jefer02/Elyndra/releases").toString(), cache)
    }

    @After fun tearDown() = server.shutdown()

    private fun json(apkUrl: String = "https://example/a.apk", digest: String? = "sha256:" + "ab".repeat(32)) = """
        [
          {"tag_name":"v0.4.0-beta","name":"Elyndra v0.4.0-beta","body":"## New\n- Updater","html_url":"https://github.com/jefer02/Elyndra/releases/tag/v0.4.0-beta",
           "prerelease":true,"draft":false,"author":{"login":"jefer02"},
           "assets":[{"name":"Elyndra-v0.4.0-beta-arm64-v8a.apk","browser_download_url":"$apkUrl","size":12,${digest?.let { "\"digest\":\"$it\"" } ?: "\"digest\":null"}}]},
          {"tag_name":"v0.3.0-beta","name":"","body":null,"html_url":"https://github.com/x","prerelease":true,"draft":false,"assets":[]}
        ]
    """.trimIndent()

    @Test fun `reads releases and sends no user data`() = runTest {
        server.enqueue(MockResponse().setBody(json()).setHeader("ETag", "\"abc\""))
        val list = repo.releases()
        assertEquals(listOf("v0.4.0-beta", "v0.3.0-beta"), list.map { it.tag })
        val first = list[0]
        assertEquals("Elyndra v0.4.0-beta", first.title)
        assertTrue(first.prerelease)
        assertEquals("ab".repeat(32), first.assets.single().sha256)
        // Sin nombre, el título es la etiqueta; sin notas, cadena vacía.
        assertEquals("v0.3.0-beta", list[1].title)
        assertEquals("", list[1].notes)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("Elyndra-Updater", request.getHeader("User-Agent"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader("If-None-Match"))
        assertEquals(0L, request.bodySize)
        assertEquals("\"abc\"", cache.etag)
    }

    @Test fun `conditional request reuses the stored list on 304`() = runTest {
        server.enqueue(MockResponse().setBody(json()).setHeader("ETag", "\"abc\""))
        server.enqueue(MockResponse().setResponseCode(304))
        repo.releases()
        val again = repo.releases()
        assertEquals("v0.4.0-beta", again.first().tag)
        server.takeRequest()
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun `rate limit and server errors are typed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("{not json"))
        try { repo.releases(); fail() } catch (e: UpdateError.RateLimited) { }
        try { repo.releases(); fail() } catch (e: UpdateError.Server) { assertEquals(500, e.code) }
        try { repo.releases(); fail() } catch (e: UpdateError.BadResponse) { }
    }

    @Test fun `network failure is a Network error`() = runTest {
        server.shutdown()
        try { repo.releases(); fail() } catch (e: UpdateError.Network) { }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `download verifies the checksum and reports progress`() = runTest {
        val payload = ByteArray(300_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(payload)))
        val asset = ReleaseAsset("Elyndra x.apk", server.url("/a.apk").toString(), payload.size.toLong())
        val seen = mutableListOf<Float>()
        val file = repo.download(asset, tmp.root, sha(payload)) { seen += it }
        assertEquals("Elyndra_x.apk", file.name)
        assertTrue(file.readBytes().contentEquals(payload))
        assertEquals(1f, seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun `checksum mismatch deletes the file`() = runTest {
        server.enqueue(MockResponse().setBody("not the apk"))
        val asset = ReleaseAsset("a.apk", server.url("/a.apk").toString(), 11)
        try {
            repo.download(asset, tmp.root, "00".repeat(32)) {}
            fail()
        } catch (e: UpdateError.Checksum) { }
        assertFalse(tmp.root.resolve("a.apk").exists())
    }

    @Test fun `failed download leaves nothing behind`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val asset = ReleaseAsset("a.apk", server.url("/a.apk").toString(), 11)
        try {
            repo.download(asset, tmp.root, null) {}
            fail()
        } catch (e: UpdateError.Server) { }
        assertFalse(tmp.root.resolve("a.apk").exists())
    }

    @Test fun `expected sha comes from the digest or the sidecar`() = runTest {
        val withDigest = ReleaseAsset("a.apk", "https://x/a.apk", 1, "cd".repeat(32))
        val release = Release("v1", "v1", "", "", false, false, listOf(withDigest))
        assertEquals("cd".repeat(32), repo.expectedSha256(withDigest, release))

        server.enqueue(MockResponse().setBody("${"ef".repeat(32)}  a.apk\n"))
        val noDigest = ReleaseAsset("a.apk", "https://x/a.apk", 1)
        val sidecar = ReleaseAsset("a.apk.sha256", server.url("/a.apk.sha256").toString(), 80)
        val r2 = Release("v1", "v1", "", "", false, false, listOf(noDigest, sidecar))
        assertEquals("ef".repeat(32), repo.expectedSha256(noDigest, r2))

        assertNull(repo.expectedSha256(noDigest, Release("v1", "v1", "", "", false, false, listOf(noDigest))))
    }
}
