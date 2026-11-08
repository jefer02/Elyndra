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
        assertTrue(ports.count { it.installable } >= 9)
        // Cada port instalable tiene texto de requisito en inglés y español.
        ports.filter { it.requires.userRom }.forEach { p ->
            assertNotNull(p.id, p.requires.text["en"])
            assertNotNull(p.id, p.requires.text["es"])
        }
        // Nada de ROMs en el catálogo: ninguna URL apunta a otra cosa que GitHub.
        assertFalse(text.contains("http://"))
        assertFalse(Regex("""https://(?!github\.com|opengraph\.githubassets\.com|raw\.githubusercontent\.com)""").containsMatchIn(text))
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
