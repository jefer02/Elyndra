package com.elyndra.launcher.ports

import com.elyndra.launcher.domain.ports.GhRepo
import com.elyndra.launcher.update.UpdateError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class GitHubClientTest {

    class MemoryCache : PortHttpCache {
        val entries = HashMap<String, PortHttpCache.Entry>()
        override fun get(key: String) = entries[key]
        override fun put(key: String, entry: PortHttpCache.Entry) {
            entries[key] = entry
        }
    }

    private lateinit var server: MockWebServer
    private val cache = MemoryCache()
    private var now = 1_000_000L
    private var token: String? = null
    private lateinit var client: GitHubClient
    private val repo = GhRepo("linkzenic", "Shipwright-Ports")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString()
        client = GitHubClient(
            OkHttpClient.Builder().followRedirects(false).build(),
            cache,
            base,
            HostAllowlist(setOf(server.hostName), allowHttp = true),
            { token },
            { now },
        )
    }

    @After fun tearDown() = server.shutdown()

    private val releasesJson = """
        [{"tag_name":"v9.3.1-ports.2","name":"","body":"Notes","html_url":"https://github.com/linkzenic/Shipwright-Ports/releases/tag/v9.3.1-ports.2",
          "prerelease":false,"draft":false,"published_at":"2026-10-30T03:13:30Z",
          "assets":[{"name":"soh_release_v9.3.1-android.4.apk","browser_download_url":"https://github.com/x.apk","size":58792077,
                     "digest":"sha256:${"ab".repeat(32)}"},
                    {"name":"Ship-iOS.ipa","browser_download_url":"https://github.com/y.ipa","size":1,"digest":null}]}]
    """.trimIndent()

    @Test fun `reads releases with dates and digests`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson).setHeader("ETag", "\"e1\"").setHeader("X-RateLimit-Remaining", "57"))
        val list = client.releases(repo)
        val r = list.single()
        assertEquals("v9.3.1-ports.2", r.title)
        assertEquals(1_793_330_010_000L, r.publishedAt)
        assertEquals("ab".repeat(32), r.assets[0].sha256)
        assertNull(r.assets[1].sha256)
        assertEquals(57, client.rateRemaining)
        val req = server.takeRequest()
        assertEquals("/repos/linkzenic/Shipwright-Ports/releases?per_page=5", req.path)
        assertEquals("Elyndra-Ports", req.getHeader("User-Agent"))
        assertNull(req.getHeader("Authorization"))
    }

    @Test fun `a fresh copy is used without asking the network`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson).setHeader("ETag", "\"e1\""))
        client.releases(repo)
        now += GitHubClient.RELEASES_TTL - 1
        assertEquals(1, client.releases(repo).size)
        assertEquals(1, server.requestCount)
    }

    @Test fun `a stale copy is revalidated with its etag`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson).setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(304))
        client.releases(repo)
        now += GitHubClient.RELEASES_TTL + 1
        assertEquals("v9.3.1-ports.2", client.releases(repo).single().tag)
        server.takeRequest()
        assertEquals("\"e1\"", server.takeRequest().getHeader("If-None-Match"))
        // El 304 renueva la copia: la siguiente no va a la red.
        client.releases(repo)
        assertEquals(2, server.requestCount)
    }

    @Test fun `force skips the freshness window`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson).setHeader("ETag", "\"e1\""))
        server.enqueue(MockResponse().setResponseCode(304))
        client.releases(repo)
        client.releases(repo, force = true)
        assertEquals(2, server.requestCount)
    }

    @Test fun `rate limits fall back to the stale copy or fail without one`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"))
        try {
            client.releases(repo)
            fail("expected RateLimited")
        } catch (e: UpdateError.RateLimited) {
            assertEquals(0, client.rateRemaining)
        }
        server.enqueue(MockResponse().setBody(releasesJson))
        client.releases(repo, force = true)
        server.enqueue(MockResponse().setResponseCode(429))
        now += GitHubClient.RELEASES_TTL + 1
        assertEquals(1, client.releases(repo).size)
    }

    @Test fun `server errors without a copy surface`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        try {
            client.releases(repo)
            fail("expected Server")
        } catch (e: UpdateError.Server) {
            assertEquals(500, e.code)
        }
    }

    @Test fun `the token only goes to the api host`() = runTest {
        token = "ghp_secret"
        server.enqueue(MockResponse().setBody(releasesJson))
        client.releases(repo)
        assertEquals("Bearer ghp_secret", server.takeRequest().getHeader("Authorization"))
        // Una redirección a otro host pierde la cabecera… y aquí ni siquiera se sigue: el host no está en la lista.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.example/steal"))
        try {
            client.repoInfo(repo)
            fail("expected BadResponse")
        } catch (e: UpdateError.BadResponse) {
            assertTrue(e.message!!.contains("evil.example"))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun `repo info reads stars and license`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"stargazers_count":101,"description":"SoH Android","pushed_at":"2026-10-30T03:13:30Z","license":{"spdx_id":"NOASSERTION","name":"Other"}}""",
            ),
        )
        val info = client.repoInfo(repo)
        assertEquals(101, info.stars)
        assertEquals("Other", info.license)
        assertEquals("/repos/linkzenic/Shipwright-Ports", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test fun `bad json is a bad response`() = runTest {
        server.enqueue(MockResponse().setBody("<html>"))
        try {
            client.releases(repo)
            fail("expected BadResponse")
        } catch (e: UpdateError.BadResponse) {
            // ok
        }
    }
}
