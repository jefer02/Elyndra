package com.elyndra.launcher.metadata

import com.elyndra.launcher.data.MatchMethod
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Una ficha de Play recortada, con la misma forma que la de verdad. */
private fun playPage(shotBase: String = "https://play-lh.googleusercontent.com", description: String = "ALL FOR THE CROWN!<br><br>Build &amp; defend.") = """
    <html><head>
    <meta property="og:title" content="Clash Royale - Apps on Google Play">
    <script type="application/ld+json" nonce="x">{"@context":"https://schema.org","@type":"SoftwareApplication","name":"Clash Royale","description":"Battle to defend your tower","operatingSystem":"ANDROID","applicationCategory":"GAME_STRATEGY","image":"https://play-lh.googleusercontent.com/ICON","author":{"@type":"Person","name":"Supercell"},"aggregateRating":{"@type":"AggregateRating","ratingValue":"4.5","ratingCount":"100"}}</script>
    </head><body>
    <img src="https://play-lh.googleusercontent.com/ICON=w240-h480" alt="Icon image">
    <div class="chip" itemprop="genre"><span jsname="V67aGc">Strategy</span><a class="x" href="/store/apps/category/GAME_STRATEGY" aria-label="Strategy" jsname="hSRGPd"></a></div>
    <img src="$shotBase/SHOT1=w526-h296" srcset="$shotBase/SHOT1=w1052-h592 2x" alt="Screenshot image" itemprop="image" data-screenshot-index="0" />
    <img src="$shotBase/SHOT2=w526-h296" alt="Screenshot image" itemprop="image" data-screenshot-index="1" />
    <img src="$shotBase/SHOT1=w526-h296" alt="Screenshot image" itemprop="image" data-screenshot-index="0" />
    <div data-g-id="description" inert>$description</div>
    </body></html>
""".trimIndent()

class PlayParserTest {

    @Test
    fun readsTheListingFromJsonLdAndThePage() {
        val l = PlayParser.parse(playPage(), "com.supercell.clashroyale")!!
        assertEquals("Clash Royale", l.title)
        assertEquals("Supercell", l.developer)
        assertEquals("Strategy", l.genre)
        assertEquals(0.9f, l.rating!!, 0.001f)
        // La descripción completa, no la corta del JSON-LD, con sus saltos de línea.
        assertEquals("ALL FOR THE CROWN!\n\nBuild & defend.", l.description)
        // URLs base, sin sufijo de tamaño.
        assertEquals("https://play-lh.googleusercontent.com/ICON", l.icon)
        // En orden y sin repetir: la página trae el carrusel más de una vez.
        assertEquals(
            listOf("https://play-lh.googleusercontent.com/SHOT1", "https://play-lh.googleusercontent.com/SHOT2"),
            l.screenshots,
        )
    }

    @Test
    fun sizesAndCategories() {
        assertEquals("https://x/ICON=s512", PlayParser.icon("https://x/ICON"))
        assertEquals("https://x/S=w1920-h1080", PlayParser.sized("https://x/S", 1920, 1080))
        assertEquals("https://x/S", PlayParser.baseUrl("https://x/S=w526-h296"))
        assertEquals("Role Playing", PlayParser.categoryName("GAME_ROLE_PLAYING"))
        assertNull(PlayParser.categoryName("PRODUCTIVITY"))
    }

    @Test
    fun aPageThatIsNotAListingGivesNothing() {
        assertNull(PlayParser.parse("<html><body>consent</body></html>", "a.b"))
    }
}

class GooglePlayClientTest {

    private val server = MockWebServer()

    @After
    fun stop() {
        server.shutdown()
    }

    /** "Decodifica" las miniaturas de prueba: el cuerpo dice el tamaño ("1080x1920"). */
    private val codec = object : ImageCodec {
        override fun bounds(bytes: ByteArray): Pair<Int, Int>? =
            String(bytes).split('x').takeIf { it.size == 2 }?.let { (w, h) -> w.toInt() to h.toInt() }
        override fun toPng(bytes: ByteArray): ByteArray? = null
    }

    private fun base() = server.url("").toString().trimEnd('/')

    private fun client(dir: java.io.File? = null) = GooglePlayClient(dir, codec, base(), RateLimiter(0))

    @Test
    fun findsTheListingAndPicksTheFirstLandscapeScreenshot() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    // La primera captura es vertical; la segunda, apaisada.
                    path.startsWith("/SHOT1") -> MockResponse().setBody(Buffer().writeUtf8("1080x1920"))
                    path.startsWith("/SHOT2") -> MockResponse().setBody(Buffer().writeUtf8("1920x1080"))
                    path.contains("hl=es") -> MockResponse().setBody(playPage(base(), "Defiende tu torre."))
                    else -> MockResponse().setBody(playPage(base(), "Defend your tower."))
                }
            }
        }
        val game = client().find("com.supercell.clashroyale", "es")!!
        assertEquals("Clash Royale", game.listing.title)
        assertEquals("${base()}/SHOT2", game.landscape)
        assertEquals(mapOf("es" to "Defiende tu torre.", "en" to "Defend your tower."), game.descriptions)
        // Solo viaja el nombre de paquete.
        val first = server.takeRequest()
        assertTrue(first.path!!.startsWith("/store/apps/details?id=com.supercell.clashroyale&hl=es"))
    }

    @Test
    fun anUntranslatedDescriptionOnlyCountsAsEnglish() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/store") -> MockResponse().setBody(playPage(base(), "Defend your tower."))
                else -> MockResponse().setBody(Buffer().writeUtf8("1080x1920"))
            }
        }
        val game = client().find("com.supercell.clashroyale", "es")!!
        assertEquals(mapOf("en" to "Defend your tower."), game.descriptions)
        // Todas verticales: no hay fondo apaisado (lo pondrá otra fuente o la captura).
        assertNull(game.landscape)
    }

    @Test
    fun aPackageNotOnPlayIsRememberedAsAMiss() = runBlocking {
        val dir = Files.createTempDirectory("gplay").toFile()
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client(dir).find("com.example.sideloaded", "en"))
        // Ni con otro cliente (otro arranque) se vuelve a preguntar.
        assertNull(client(dir).find("com.example.sideloaded", "en"))
        assertEquals(1, server.requestCount)
        dir.deleteRecursively()
        Unit
    }
}

class GooglePlayPriorityTest {

    private fun play() = SourceData(Service.GooglePlay).apply {
        matched(MatchMethod.PACKAGE, 1.0)
        setText(MetaField.Name, "Clash Royale")
        setArt(MetaField.Icon, "play-icon")
        setArt(MetaField.Hero, "play-shot")
    }

    private fun steam() = SourceData(Service.Steam).apply {
        matched(MatchMethod.FUZZY, 0.9)
        setText(MetaField.Name, "Clash Royale™")
        setArt(MetaField.Cover, "steam-cover")
        setArt(MetaField.Hero, "steam-hero")
        setArt(MetaField.Logo, "steam-logo")
    }

    @Test
    fun playIsTheMainSourceAndSteamFillsTheRest() {
        val merged = MetadataMerge.merge(mapOf(Service.Steam to steam(), Service.GooglePlay to play()), MetadataPriority.DEFAULT)
        assertEquals("Clash Royale", merged.text[MetaField.Name])
        assertEquals(Service.GooglePlay to "play-icon", merged.art[MetaField.Icon])
        assertEquals(Service.GooglePlay to "play-shot", merged.art[MetaField.Hero])
        // Lo que Play no tiene lo pone Steam.
        assertEquals(Service.Steam to "steam-cover", merged.art[MetaField.Cover])
        assertEquals(Service.Steam to "steam-logo", merged.art[MetaField.Logo])
        assertEquals(MatchMethod.PACKAGE, merged.matchedBy)
        // Si la descarga del fondo de Play falla, el de Steam espera detrás.
        assertEquals(listOf(Service.GooglePlay to "play-shot", Service.Steam to "steam-hero"), merged.artChain[MetaField.Hero])
    }

    @Test
    fun withoutPlaySteamIsTheFallback() {
        val merged = MetadataMerge.merge(mapOf(Service.Steam to steam()), MetadataPriority.DEFAULT)
        assertEquals(Service.Steam to "steam-hero", merged.art[MetaField.Hero])
        assertNull(merged.art[MetaField.Icon])
    }

    @Test
    fun savedOrdersGetPlayInFrontNotBehindSteam() {
        val parsed = MetadataPriority.parse("ss,igdb,steam,ra,sgdb,libretro", MetadataPriority.DEFAULT.text)
        assertEquals(Service.GooglePlay, parsed.first())
        assertTrue(parsed.indexOf(Service.GooglePlay) < parsed.indexOf(Service.Steam))
        assertNotNull(MetadataMerge.provides(Service.GooglePlay).firstOrNull { it == MetaField.Icon })
    }
}
