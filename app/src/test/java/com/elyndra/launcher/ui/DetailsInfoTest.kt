package com.elyndra.launcher.ui

import com.elyndra.launcher.R
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.PlayStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailsInfoTest {

    private fun info(
        meta: GameMeta = GameMeta(),
        stats: PlayStats = PlayStats(),
        file: String? = null,
        size: String? = null,
        pkg: String? = null,
    ) = DetailsInfo.of(meta, stats, file, size, pkg, formatDate = { "date:$it" }, formatDay = { "day:$it" })

    @Test
    fun `sin metadatos no hay nada que enseñar`() {
        val i = info()
        assertTrue(i.isEmpty)
        assertTrue(i.facts.isEmpty())
        assertTrue(i.genres.isEmpty())
    }

    @Test
    fun `los campos vacíos o de relleno no se enseñan`() {
        val i = info(GameMeta(developer = "  ", publisher = "-", players = "Unknown", genre = "n/a", releaseDate = ""))
        assertTrue(i.isEmpty)
    }

    @Test
    fun `desarrolladora y editora solo si las hay`() {
        val i = info(GameMeta(developer = "Nintendo EAD"))
        assertEquals(listOf(DetailsInfo.Fact(R.string.details_developer, "Nintendo EAD")), i.facts)
    }

    @Test
    fun `orden de la rejilla`() {
        val i = info(
            GameMeta(developer = "D", publisher = "P", releaseDate = "1998", players = "1-2", rating = 0.87f),
            PlayStats(lastPlayed = 42L),
            file = "roms/zelda.z64",
            size = "32 MB",
        )
        assertEquals(
            listOf(
                R.string.details_developer,
                R.string.details_publisher,
                R.string.details_release,
                R.string.details_players,
                R.string.details_rating,
                R.string.details_last_played,
                R.string.details_file,
                R.string.details_size,
            ),
            i.facts.map { it.label },
        )
        assertEquals("87 / 100", i.facts.first { it.label == R.string.details_rating }.value)
        assertEquals("date:42", i.facts.first { it.label == R.string.details_last_played }.value)
    }

    @Test
    fun `sin partidas no hay última partida`() {
        assertTrue(info(stats = PlayStats(minutes = 0, lastPlayed = 0)).facts.none { it.label == R.string.details_last_played })
    }

    @Test
    fun `géneros como etiquetas sin repetir`() {
        assertEquals(listOf("Acción", "Aventura", "Plataformas"), DetailsInfo.splitGenres("Acción, Aventura / Plataformas; acción"))
        assertTrue(DetailsInfo.splitGenres(null).isEmpty())
        assertTrue(DetailsInfo.splitGenres(" , / ").isEmpty())
    }

    @Test
    fun `nota cero o fuera de rango no se enseña`() {
        assertNull(DetailsInfo.ratingText(0f))
        assertNull(DetailsInfo.ratingText(1.5f))
        assertNull(DetailsInfo.ratingText(Float.NaN))
        assertEquals("100 / 100", DetailsInfo.ratingText(1f))
    }

    @Test
    fun `fecha de lanzamiento completa con formato, año tal cual`() {
        assertEquals("day:1998-11-21", DetailsInfo.releaseText("1998-11-21") { "day:$it" })
        assertEquals("1998", DetailsInfo.releaseText("1998") { "day:$it" })
        assertEquals("1998-13-45", DetailsInfo.releaseText("1998-13-45") { "day:$it" })
        assertNull(DetailsInfo.releaseText(" ") { "day:$it" })
    }

    @Test
    fun `fuentes sin repetir y paquete aparte`() {
        val i = info(GameMeta(sources = listOf("igdb", "igdb", " ", "steam")), pkg = "com.example.game")
        assertEquals(listOf("igdb", "steam"), i.sources)
        assertEquals("com.example.game", i.packageName)
        assertTrue(i.facts.none { it.label == R.string.details_package })
    }

    /* ── Reparto en dos columnas ── */

    private val about = DetailsLayout.Block.About
    private val memory = DetailsLayout.Block.Memory
    private val facts = DetailsLayout.Block.Facts
    private val achievements = DetailsLayout.Block.Achievements

    @Test
    fun `con sinopsis larga va sola a la izquierda y lo demas a la derecha`() {
        val (l, r) = DetailsLayout.columns(listOf(about to 12f, memory to 3f, facts to 6f, achievements to 0f))
        assertEquals(listOf(about), l)
        assertEquals(listOf(memory, facts), r)
    }

    @Test
    fun `sin sinopsis ninguna columna se queda vacia`() {
        val (l, r) = DetailsLayout.columns(listOf(about to 0f, memory to 3f, facts to 6f, achievements to 9f))
        assertTrue(l.isNotEmpty() && r.isNotEmpty())
        assertEquals(listOf(memory, achievements), l)
        assertEquals(listOf(facts), r)
    }

    @Test
    fun `un solo bloque va en una sola columna`() {
        val (l, r) = DetailsLayout.columns(listOf(about to 0f, memory to 3f, facts to 0f, achievements to 0f))
        assertEquals(listOf(memory), l)
        assertTrue(r.isEmpty())
    }

    @Test
    fun `el orden de lectura se respeta dentro de cada columna`() {
        val (l, r) = DetailsLayout.columns(listOf(about to 4f, memory to 3f, facts to 3f, achievements to 4f))
        assertEquals(listOf(about, achievements), l)
        assertEquals(listOf(memory, facts), r)
    }

    @Test
    fun `los pesos crecen con lo que hay que enseñar`() {
        assertTrue(DetailsLayout.aboutWeight(600) > DetailsLayout.aboutWeight(100))
        val few = info(GameMeta(developer = "A"))
        val many = info(GameMeta(developer = "A", publisher = "B", genre = "Acción", rating = 0.8f, sources = listOf("igdb")))
        assertTrue(DetailsLayout.factsWeight(many, false) > DetailsLayout.factsWeight(few, false))
        assertEquals(0f, DetailsLayout.factsWeight(info(), false), 0f)
        assertTrue(DetailsLayout.achievementsWeight(10) > DetailsLayout.achievementsWeight(0))
    }
}
