package com.elyndra.launcher.ports

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PortCatalogRepositoryTest {

    private lateinit var server: MockWebServer
    private val cache = GitHubClientTest.MemoryCache()
    private var now = 0L
    private lateinit var repo: PortCatalogRepository

    private fun catalog(vararg ids: String) = """{"schema":1,"ports":[${
        ids.joinToString(",") { """{"id":"$it","name":"$it","repo":{"owner":"o","name":"$it"},"original":{"title":"G","platform":"n64"},"builds":[{"platform":"android"}]}""" }
    }]}"""

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        repo = PortCatalogRepository(
            OkHttpClient.Builder().followRedirects(false).build(),
            cache,
            HostAllowlist(setOf(server.hostName), allowHttp = true),
            { server.url("/jefer02/elyndra-ports/main/catalog.json").toString() },
            { catalog("bundled") },
            { now },
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `the remote catalog wins`() = runTest {
        server.enqueue(MockResponse().setBody(catalog("a", "b")).setHeader("ETag", "\"c1\""))
        val loaded = repo.load()
        assertEquals(PortCatalogRepository.Source.Remote, loaded.source)
        assertEquals(listOf("a", "b"), loaded.catalog.ports.map { it.id })
    }

    @Test fun `garbage falls back to the bundled copy and is not cached`() = runTest {
        server.enqueue(MockResponse().setBody("<html>Not found</html>"))
        val loaded = repo.load()
        assertEquals(PortCatalogRepository.Source.Bundled, loaded.source)
        assertEquals(listOf("bundled"), loaded.catalog.ports.map { it.id })
        assertEquals(0, cache.entries.size)
    }

    @Test fun `an empty catalog falls back too`() = runTest {
        server.enqueue(MockResponse().setBody(catalog()))
        assertEquals(PortCatalogRepository.Source.Bundled, repo.load().source)
    }

    @Test fun `without network the last good copy is used`() = runTest {
        server.enqueue(MockResponse().setBody(catalog("a")).setHeader("ETag", "\"c1\""))
        repo.load()
        server.shutdown()
        now += PortCatalogRepository.TTL + 1
        val loaded = repo.load()
        assertEquals(PortCatalogRepository.Source.Remote, loaded.source)
        assertEquals(listOf("a"), loaded.catalog.ports.map { it.id })
    }

    @Test fun `a 404 from a repo that doesn't exist yet is not offline`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("404: Not Found"))
        val loaded = repo.load()
        assertEquals(PortCatalogRepository.Source.Bundled, loaded.source)
        assertEquals(PortCatalogRepository.Fallback.Unavailable, loaded.fallback)
        assertEquals(listOf("bundled"), loaded.catalog.ports.map { it.id })
    }

    @Test fun `a server error or garbage is not offline either`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(PortCatalogRepository.Fallback.Unavailable, repo.load(force = true).fallback)
        server.enqueue(MockResponse().setBody("<html>Not found</html>"))
        assertEquals(PortCatalogRepository.Fallback.Unavailable, repo.load(force = true).fallback)
    }

    @Test fun `no network at all and nothing cached is offline`() = runTest {
        server.shutdown()
        val loaded = repo.load()
        assertEquals(PortCatalogRepository.Source.Bundled, loaded.source)
        assertEquals(PortCatalogRepository.Fallback.Offline, loaded.fallback)
    }

    @Test fun `the remote catalog has no fallback reason`() = runTest {
        server.enqueue(MockResponse().setBody(catalog("a")))
        assertEquals(null, repo.load().fallback)
        assertEquals(PortCatalogRepository.Fallback.Offline, PortCatalogRepository.fallbackFor(java.net.UnknownHostException("x")))
        assertEquals(PortCatalogRepository.Fallback.Unavailable, PortCatalogRepository.fallbackFor(com.elyndra.launcher.update.UpdateError.Server(404)))
        assertEquals(PortCatalogRepository.Fallback.Unavailable, PortCatalogRepository.fallbackFor(null))
    }

    @Test fun `a host outside the allowlist is never contacted`() = runTest {
        val strict = PortCatalogRepository(
            OkHttpClient(),
            cache,
            HostAllowlist.CATALOG,
            { server.url("/catalog.json").toString() },
            { catalog("bundled") },
        )
        assertEquals(PortCatalogRepository.Source.Bundled, strict.load().source)
        assertEquals(0, server.requestCount)
    }
}
