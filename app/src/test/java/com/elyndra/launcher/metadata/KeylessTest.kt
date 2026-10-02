package com.elyndra.launcher.metadata

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LibretroNamesTest {

    private val psx = listOf(
        "Crash Bandicoot (Europe)", "Crash Bandicoot (USA)", "Crash Bandicoot (Japan, Asia)",
        "Crash Bandicoot 2 - Cortex Strikes Back (USA)", "Crash Bandicoot - Warped (USA)",
        "Final Fantasy VII (USA) (Disc 1)", "Final Fantasy VIII (USA) (Disc 1)",
        "Legend of Dragoon, The (USA) (Disc 1)",
        "Asterix _ Obelix Take On Caesar (Europe)",
        "Metal Gear Solid (USA) (Disc 1)", "Metal Gear Solid - VR Missions (USA)",
        "Spyro the Dragon (USA)", "Spyro 2 - Ripto's Rage! (USA)",
        "Tekken 3 (USA)", "Tekken 2 (USA)",
        "Ridge Racer (USA)", "Ridge Racer Revolution (USA)",
    )

    @Test
    fun filenameEscapingFollowsTheLibretroRule() {
        assertEquals("Asterix _ Obelix", LibretroNames.fileName("Asterix & Obelix"))
        assertEquals("Mega Man X_ Command Mission", LibretroNames.fileName("Mega Man X: Command Mission"))
        assertEquals("a_b_c_d_e_f_g_h_i_j_k", LibretroNames.fileName("a&b*c/d:e`f<g>h?i|j\\k"))
        val url = LibretroNames.url("Sony - PlayStation", LibretroNames.Kind.Boxart, "Asterix & Obelix Take On Caesar (Europe)")
        assertEquals("https://thumbnails.libretro.com/Sony%20-%20PlayStation/Named_Boxarts/Asterix%20_%20Obelix%20Take%20On%20Caesar%20%28Europe%29.png", url)
    }

    @Test
    fun parsesTheApacheListing() {
        val html = """<a href="?C=N;O=D">Name</a> <a href="Crash%20Bandicoot%20%28USA%29.png">Crash…</a>
            <a href="Asterix%20_%20Obelix%20Take%20On%20Caesar%20%28Europe%29.png">x</a> <a href="../">Parent</a>
            <a href="Dr.%20Mario%20%28World%29.png">y</a>"""
        assertEquals(
            listOf("Crash Bandicoot (USA)", "Asterix _ Obelix Take On Caesar (Europe)", "Dr. Mario (World)"),
            LibretroNames.parseListing(html),
        )
    }

    @Test
    fun exactNoIntroNameWinsAndExtensionOnlyWhenReal() {
        assertEquals("Crash Bandicoot (Europe)", LibretroNames.match("Crash Bandicoot (Europe).cue", "Crash Bandicoot", psx))
        assertEquals("Super Mario Bros.", LibretroNames.exactCandidate("Super Mario Bros."))
        assertEquals("Super Mario Bros. (World)", LibretroNames.exactCandidate("Super Mario Bros. (World).nes"))
    }

    @Test
    fun sameTitleWithoutTagsPicksThePreferredRegion() {
        assertEquals("Crash Bandicoot (USA)", LibretroNames.match("crash_bandicoot.chd", "Crash Bandicoot", psx))
        assertEquals("Crash Bandicoot (Europe)", LibretroNames.match("crash.chd", "Crash Bandicoot", psx, LibretroNames.regionsFor("es")))
        // Artículo al final y "&" en el nombre.
        assertEquals("Legend of Dragoon, The (USA) (Disc 1)", LibretroNames.match("The Legend of Dragoon.chd", "The Legend of Dragoon", psx))
        assertEquals("Asterix _ Obelix Take On Caesar (Europe)", LibretroNames.match("x.bin", "Asterix & Obelix Take On Caesar", psx))
    }

    @Test
    fun sequelsAndSubtitlesAreNotConfused() {
        // VII no es VIII, ni 2 es 3.
        assertEquals("Final Fantasy VII (USA) (Disc 1)", LibretroNames.match("ff7.bin", "Final Fantasy VII", psx))
        assertEquals("Tekken 3 (USA)", LibretroNames.match("tekken3.bin", "Tekken 3", psx))
        assertNull(LibretroNames.match("tekken4.bin", "Tekken 4", psx))
        // "Crash Bandicoot 3" no existe en la lista: no se coge la 2 ni la 1.
        assertNull(LibretroNames.match("crash3.bin", "Crash Bandicoot 3", psx))
        // Un subtítulo no es el juego base.
        assertEquals("Metal Gear Solid (USA) (Disc 1)", LibretroNames.match("mgs.bin", "Metal Gear Solid", psx))
        assertNull(LibretroNames.match("x.bin", "Ridge Racer Type 4", psx))
    }

    @Test
    fun weakOrAmbiguousMatchesGiveNothing() {
        assertNull(LibretroNames.match("unknown.bin", "Some Totally Different Game", psx))
        assertNull(LibretroNames.match("x.bin", "", psx))
        assertNull(LibretroNames.match("x.bin", "Crash", emptyList()))
    }

    @Test
    fun pickerCandidatesAreTheClosestTitles() {
        val c = LibretroNames.candidates("Crash Bandicoot", psx)
        assertTrue(c.first().startsWith("Crash Bandicoot ("))
        assertTrue(c.none { it.startsWith("Tekken") })
    }

    @Test
    fun systemsMapToLibretroFolders() {
        assertEquals("Sony - PlayStation", LibretroNames.folderFor("psx"))
        assertEquals("Nintendo - Super Nintendo Entertainment System", LibretroNames.folderFor("snes"))
        // Los romsets de arcade no casan por título; PC no está en libretro.
        assertNull(LibretroNames.folderFor("arcade"))
        assertNull(LibretroNames.folderFor("pc"))
    }
}

class SteamParserTest {

    private val searchBody = """{"total":3,"items":[
        {"type":"app","name":"Hollow Knight","id":367520,"tiny_image":"x"},
        {"type":"app","name":"Hollow Knight: Silksong","id":1030300},
        {"type":"app","name":"Hollow Knight - Official Soundtrack","id":598190}]}"""

    @Test
    fun parsesSearchAndPicksOnlyAConfidentGame() {
        val items = SteamParser.search(searchBody)
        assertEquals(3, items.size)
        assertEquals(367520L to "Hollow Knight", items.first().id to items.first().name)
        assertEquals(367520L, SteamParser.bestMatch("Hollow Knight", items)!!.first.id)
        assertEquals(1030300L, SteamParser.bestMatch("Hollow Knight Silksong", items)!!.first.id)
        // La banda sonora nunca es el juego, ni "Hollow" a secas.
        assertNull(SteamParser.bestMatch("Hollow", items))
        assertNull(SteamParser.bestMatch("Hollow Knight 2", items))
    }

    @Test
    fun parsesDetailsAndCleansHtml() {
        val body = """{"367520":{"success":true,"data":{"type":"game","name":"Hollow Knight","steam_appid":367520,
            "short_description":"&iexcl;Forja tu camino!<br>Una aventura &quot;épica&quot; &amp; más.",
            "developers":["Team Cherry"],"publishers":["Team Cherry"],
            "genres":[{"id":"1","description":"Acción"},{"id":"25","description":"Aventura"}]}}}"""
        val d = SteamParser.details(body, 367520)!!
        assertEquals("game", d.type)
        assertEquals("¡Forja tu camino! Una aventura \"épica\" & más.", d.shortDescription)
        assertEquals(listOf("Acción", "Aventura"), d.genres)
        assertNull(SteamParser.details("""{"1":{"success":false}}""", 1))
        assertNull(SteamParser.details("""{"367520":{"success":true}}""", 999))
    }

    @Test
    fun untranslatedSteamTextIsTaggedEnglish() {
        // Ficha traducida: las dos, cada una con su idioma.
        assertEquals(mapOf("es" to "Hola", "en" to "Hello"), SteamDescriptions.tag("es", "Hola", "Hello"))
        // Sin traducción, Steam devuelve el inglés aunque se pida español: solo cuenta como inglés.
        assertEquals(mapOf("en" to "Hello"), SteamDescriptions.tag("es", "Hello", "Hello"))
        assertEquals(mapOf("en" to "Hello"), SteamDescriptions.tag("en", "Hello", "Hello"))
        assertEquals(emptyMap<String, String>(), SteamDescriptions.tag("es", null, null))
    }

    @Test
    fun cdnArtByAppId() {
        assertEquals("https://cdn.akamai.steamstatic.com/steam/apps/367520/library_600x900_2x.jpg", SteamParser.cover(367520))
        assertEquals("https://cdn.akamai.steamstatic.com/steam/apps/367520/library_hero.jpg", SteamParser.hero(367520))
        assertEquals("https://cdn.akamai.steamstatic.com/steam/apps/367520/logo.png", SteamParser.logo(367520))
    }
}

class KeylessCacheTest {

    private val dir: File = Files.createTempDirectory("keyless").toFile()

    @After
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun missesExpireAndSurviveRestarts() {
        var now = 1_000L
        val file = File(dir, "misses.txt")
        val cache = MissCache(file, ttlMs = 100, clock = { now })
        assertFalse(cache.isMiss("psx|crash.bin"))
        cache.markMiss("psx|crash.bin")
        assertTrue(cache.isMiss("psx|crash.bin"))
        // Otra instancia (la app se reinició) lee el archivo.
        assertTrue(MissCache(file, ttlMs = 100, clock = { now }).isMiss("psx|crash.bin"))
        now += 101
        assertFalse(cache.isMiss("psx|crash.bin"))
        cache.markMiss("a")
        cache.clearMiss("a")
        assertFalse(cache.isMiss("a"))
    }

    @Test
    fun corruptCacheFileIsIgnored() {
        val file = File(dir, "bad.txt").apply { writeText("garbage\nkey\tnot-a-number\n\t\t") }
        assertFalse(MissCache(file, ttlMs = 1000).isMiss("key"))
    }
}

class SteamClientTest {

    private val server = MockWebServer()

    @After
    fun stop() {
        server.shutdown()
    }

    private fun client(dir: File?) = SteamStoreClient(dir, server.url("").toString().trimEnd('/'), RateLimiter(0))

    @Test
    fun findsLocalizedGameAndTagsLanguagesFromTheFakeServer() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/storesearch") -> MockResponse().setBody("""{"items":[{"name":"Celeste","id":504230}]}""")
                    path.contains("l=spanish") -> MockResponse().setBody("""{"504230":{"success":true,"data":{"type":"game","name":"Celeste","short_description":"Ayuda a Madeline."}}}""")
                    else -> MockResponse().setBody("""{"504230":{"success":true,"data":{"type":"game","name":"Celeste","short_description":"Help Madeline."}}}""")
                }
            }
        }
        val game = client(null).find("Celeste", "es")!!
        assertEquals(504230L, game.details.id)
        assertEquals(mapOf("es" to "Ayuda a Madeline.", "en" to "Help Madeline."), game.descriptions)
        // Solo el nombre del juego viaja en la búsqueda.
        val search = server.takeRequest()
        assertTrue(search.path!!.contains("term=Celeste"))
        assertTrue(search.getHeader("User-Agent")!!.startsWith("Elyndra/"))
    }

    @Test
    fun noMatchIsCachedButNetworkErrorsAreNot() = runBlocking {
        val dir = Files.createTempDirectory("steam").toFile()
        try {
            server.enqueue(MockResponse().setBody("""{"items":[{"name":"Something Else","id":1}]}"""))
            val c = client(dir)
            assertNull(c.find("Mobile Only Game", "es"))
            assertEquals(1, server.requestCount)
            // Fallo cacheado: la segunda vez ni se pregunta.
            assertNull(c.find("Mobile Only Game", "es"))
            assertEquals(1, server.requestCount)

            // Un 500 tras los reintentos no se apunta como "no está".
            repeat(3) { server.enqueue(MockResponse().setResponseCode(500)) }
            assertNull(c.find("Another Game", "es"))
            server.enqueue(MockResponse().setBody("""{"items":[]}"""))
            assertNull(c.find("Another Game", "es"))
            assertEquals(5, server.requestCount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedResponsesDoNotCrash() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        assertNull(client(null).find("Celeste", "en"))
        server.enqueue(MockResponse().setBody("""{"items":[{"name":"Celeste","id":5}]}"""))
        server.enqueue(MockResponse().setBody("{"))
        assertNull(client(null).find("Celeste", "en"))
    }

    @Test
    fun dlcWithTheSameNameIsRejected() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[{"name":"Celeste","id":5}]}"""))
        server.enqueue(MockResponse().setBody("""{"5":{"success":true,"data":{"type":"dlc","name":"Celeste"}}}"""))
        assertNull(client(null).find("Celeste", "en"))
    }
}

class KeylessPriorityTest {

    @Test
    fun keylessFillsGapsAndAuthenticatedSourcesKeepPriority() {
        val p = MetadataPriority.DEFAULT
        // Con cuenta van delante en texto y en imágenes.
        assertTrue(p.rank(Service.ScreenScraper, MetaField.Cover) < p.rank(Service.Libretro, MetaField.Cover))
        assertTrue(p.rank(Service.SteamGridDb, MetaField.Hero) < p.rank(Service.Steam, MetaField.Hero))
        assertTrue(p.rank(Service.Igdb, MetaField.Name) < p.rank(Service.Steam, MetaField.Name))

        val ss = SourceData(Service.ScreenScraper).apply { setArt(MetaField.Cover, "ss-cover") }
        val lr = SourceData(Service.Libretro).apply {
            setArt(MetaField.Cover, "lr-cover")
            setArt(MetaField.Screenshot, "lr-snap")
        }
        val merged = MetadataMerge.merge(mapOf(Service.ScreenScraper to ss, Service.Libretro to lr), p)
        assertEquals(Service.ScreenScraper to "ss-cover", merged.art[MetaField.Cover])
        // Lo que falta, lo pone libretro.
        assertEquals(Service.Libretro to "lr-snap", merged.art[MetaField.Screenshot])

        // Sin cuenta configurada, libretro es la fuente principal.
        val alone = MetadataMerge.merge(mapOf(Service.Libretro to lr), p)
        assertEquals(Service.Libretro to "lr-cover", alone.art[MetaField.Cover])
    }

    @Test
    fun savedPriorityFromOlderVersionsGetsTheNewSourcesAtTheEnd() {
        val parsed = MetadataPriority.parse("ss,igdb,sgdb,ra", MetadataPriority.DEFAULT.art)
        assertEquals(listOf(Service.ScreenScraper, Service.Igdb, Service.SteamGridDb, Service.RetroAchievements), parsed.take(4))
        assertTrue(parsed.containsAll(listOf(Service.Libretro, Service.Steam)))
    }

    @Test
    fun steamNeverProvidesTheIconOfAnAndroidApp() {
        assertFalse(MetaField.Icon in MetadataMerge.provides(Service.Steam))
        assertNotNull(MetadataMerge.provides(Service.Libretro).firstOrNull { it == MetaField.Cover })
    }
}
