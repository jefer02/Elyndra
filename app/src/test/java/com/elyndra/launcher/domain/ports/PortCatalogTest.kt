package com.elyndra.launcher.domain.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PortCatalogTest {

    private fun entry(id: String = "soh", extra: String = "", repo: String = """{"owner":"o","name":"r"}""") = """
        {"id":"$id","name":"Port $id","repo":$repo,
         "original":{"title":"Game","platform":"n64"},
         "builds":[{"platform":"android"}] $extra}
    """.trimIndent()

    private fun catalog(vararg entries: String) = """{"schema":1,"updated":"2026-11-08","ports":[${entries.joinToString(",")}]}"""

    @Test fun `the bundled catalog parses without rejections`() {
        val text = File("src/main/assets/ports/catalog.json").readText()
        val parsed = PortCatalogParser.parse(text)
        assertNotNull(parsed)
        assertEquals(emptyList<String>(), parsed!!.rejected)
        val ports = parsed.catalog.ports
        assertEquals(ports.size, ports.map { it.id }.toSet().size)
        assertEquals(PortCatalogParser.SCHEMA, parsed.catalog.schema)
        assertTrue(ports.count { it.installable } >= 50)
        // Lo que no se instala es porque aún no tiene APK (solo se enseña) o es para gafas de RV.
        assertTrue(ports.filterNot { it.installable }.all { it.notOnAndroid || it.vrOnly })
        // Un "aún no en Android" no trae build de Android que instalar.
        assertTrue(ports.filter { it.availability == PortEntry.SOURCE }.all { it.android == null })
        // Un zip siempre dice qué APK sacar de dentro, con un patrón que compila.
        ports.mapNotNull { it.android?.archive }.forEach { Regex(it) }
        // Los ids de la primera versión siguen ahí (lo instalado se guarda por id).
        assertTrue(ports.any { it.id == "soh" })
        // Un texto de requisito siempre trae el inglés.
        ports.filter { it.requires.text.isNotEmpty() }.forEach { p -> assertNotNull(p.id, p.requires.text["en"]) }
        // Nada de ROMs en el catálogo: solo GitHub y las fuentes de arte del juego original.
        assertFalse(text.contains("http://"))
        val allowed = listOf(
            "github\\.com", "opengraph\\.githubassets\\.com", "raw\\.githubusercontent\\.com",
            "cdn2\\.steamgriddb\\.com", "images\\.igdb\\.com", "cdn\\.akamai\\.steamstatic\\.com", "thumbnails\\.libretro\\.com",
        )
        assertFalse(Regex("""https://(?!(${allowed.joinToString("|")})/)""").containsMatchIn(text))
    }

    @Test fun `defaults fill a minimal entry`() {
        val p = PortCatalogParser.parse(catalog(entry()))!!.catalog.ports.single()
        assertTrue(p.installable)
        assertEquals(PortBuild.DEFAULT_ABIS, p.android!!.abis)
        assertEquals("https://opengraph.githubassets.com/1/o/r", p.image)
        assertFalse(p.verified)
    }

    @Test fun `a bad entry is dropped and the rest survive`() {
        val parsed = PortCatalogParser.parse(
            catalog(
                entry("good"),
                entry("Bad Id"),
                entry("bad-regex", extra = ""","x":1""").replace(""""platform":"android"}""", """"platform":"android","asset":"(["}"""),
                entry("bad-repo", repo = """{"owner":"o/..","name":"r"}"""),
                entry("good"),
                """{"id":"broken"}""",
            ),
        )!!
        assertEquals(listOf("good"), parsed.catalog.ports.map { it.id })
        assertEquals(5, parsed.rejected.size)
        assertTrue(parsed.rejected.any { it.startsWith("good: duplicate") })
    }

    @Test fun `unknown fields and newer schemas are tolerated`() {
        val text = """{"schema":7,"newThing":{"a":1},"ports":[${entry(extra = ""","futureField":[1,2],"verified":true""")}]}"""
        val parsed = PortCatalogParser.parse(text)!!
        assertEquals(7, parsed.catalog.schema)
        assertTrue(parsed.catalog.ports.single().verified)
    }

    @Test fun `a schema 1 entry reads as schema 2`() {
        val p = PortCatalogParser.parse(
            catalog(entry(extra = ""","verified":true""").replace(""""original":{"title":"Game","platform":"n64"}""", """"original":{"title":"Game","platform":"n64","igdbId":5,"sgdbId":6}""")),
        )!!.catalog.ports.single()
        assertEquals(PortGame("Game", "n64", igdbId = 5, sgdbId = 6), p.game)
        assertTrue(p.trust.verified)
        // Sin datos del generador no hay insignias.
        assertEquals(emptyList<String>(), p.trust.flags)
        assertEquals(0L, p.release.publishedAtMillis)
    }

    @Test fun `a schema 2 entry keeps game art release and trust`() {
        val text = """{"schema":2,"ports":[{"id":"soh","name":"Ship of Harkinian","repo":{"owner":"o","name":"r"},
            "game":{"name":"The Legend of Zelda: Ocarina of Time","originalPlatform":"n64","igdbId":1029,"steamAppId":null,"releaseYear":1998,
              "art":{"cover":"https://cdn.example/c.jpg","hero":"https://cdn.example/h.jpg","screenshots":["https://cdn.example/s1.jpg"]}},
            "builds":[{"platform":"android"}],
            "release":{"tag":"v9.3.1","publishedAt":"2026-10-30T12:00:00Z","prerelease":true},
            "trust":{"verified":false,"reason":"repo < 1 year","stars":101,"contributors":4,
              "scan":{"status":"not_scanned"},"flags":["prerelease","not_scanned"]}}]}"""
        val parsed = PortCatalogParser.parse(text)!!
        assertEquals(emptyList<String>(), parsed.rejected)
        val p = parsed.catalog.ports.single()
        assertEquals(2, parsed.catalog.schema)
        assertEquals("The Legend of Zelda: Ocarina of Time", p.game.name)
        assertEquals("n64", p.platform)
        assertEquals(1998, p.game.releaseYear)
        assertEquals("https://cdn.example/c.jpg", p.game.art.cover)
        assertEquals(listOf("https://cdn.example/s1.jpg"), p.game.art.screenshots)
        assertEquals(1_793_361_600_000L, p.release.publishedAtMillis)
        assertTrue(p.trust.prerelease)
        // VirusTotal ya no se usa: `scan` y "not_scanned" de un catálogo viejo se leen y se ignoran.
        assertTrue("not_scanned" in p.trust.flags)
        assertEquals(listOf("Unverified"), com.elyndra.launcher.domain.ports.UnverifiedDialog.lines(p).map { it.name })
        assertFalse(p.trust.checkRequirements)
        assertFalse(p.verified)
        assertEquals(101, p.trust.stars)
    }

    @Test fun `http art is rejected`() {
        val text = """{"schema":2,"ports":[{"id":"x","name":"X","repo":{"owner":"o","name":"r"},
            "game":{"name":"G","originalPlatform":"pc","art":{"cover":"http://x/c.jpg"}}}]}"""
        val parsed = PortCatalogParser.parse(text)!!
        assertTrue(parsed.catalog.ports.isEmpty())
        assertTrue(parsed.rejected.single().contains("https"))
    }

    @Test fun `text that is not a catalog is null`() {
        assertNull(PortCatalogParser.parse("<html>404</html>"))
        assertNull(PortCatalogParser.parse("""{"items":[]}"""))
        assertNull(PortCatalogParser.parse("[]"))
    }

    @Test fun `http images are rejected`() {
        val parsed = PortCatalogParser.parse(catalog(entry(extra = ""","portImage":"http://x/y.png"""")))!!
        assertTrue(parsed.catalog.ports.isEmpty())
    }

    @Test fun `pc only entries are not installable and builds can override the repo`() {
        val p = PortCatalogParser.parse(
            catalog(
                """{"id":"pc","name":"Pc","repo":{"owner":"a","name":"b"},"original":{"title":"G","platform":"n64"},
                    "builds":[{"platform":"windows","repo":{"owner":"c","name":"d"},"asset":"win\\.zip$"}]}""",
            ),
        )!!.catalog.ports.single()
        assertFalse(p.installable)
        assertEquals("a/b", p.releaseRepo.slug)
        assertEquals("c/d", p.builds.single().repo!!.slug)
    }

    @Test fun `localized text falls back to english then anything`() {
        assertEquals("hola", Localized.pick(mapOf("en" to "hi", "es" to "hola"), "es-ES"))
        assertEquals("hi", Localized.pick(mapOf("en" to "hi", "es" to "hola"), "ja"))
        assertEquals("salut", Localized.pick(mapOf("fr" to "salut"), "de"))
        assertNull(Localized.pick(emptyMap(), "en"))
    }
}
