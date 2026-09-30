package com.elyndra.launcher.ui.masha.voice

import com.elyndra.launcher.ui.masha.lipsync.CmuDict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

class LanguageSpansTest {

    private val dict: CmuDict? by lazy {
        listOf("src/main/assets/lipsync/cmudict.txt", "app/src/main/assets/lipsync/cmudict.txt")
            .map { File(it) }.firstOrNull { it.exists() }?.inputStream()?.let { CmuDict.load(it) }
    }

    private fun english(w: String): Boolean = dict!!.lookup(w.lowercase()) != null

    /** Tramos en inglés entre corchetes: "prueba [Hollow Knight] o …". */
    private fun mark(text: String, lang: String): String {
        assertNotNull("falta assets/lipsync/cmudict.txt", dict)
        val spans = LanguageSpans.split(text, lang, ::english)
        assertEquals("sin pérdida: $text", text, spans.joinToString("") { it.text })
        assertTrue("sin tramos vacíos: $text", spans.all { it.text.isNotEmpty() } || text.isEmpty())
        assertTrue("sin tramos seguidos del mismo idioma: $text", spans.zipWithNext().none { (a, b) -> a.lang == b.lang })
        return spans.joinToString("") { if (it.lang == "en") "[${it.text}]" else it.text }
    }

    private fun check(lang: String, table: List<String>) {
        val bad = table.mapNotNull { want ->
            val input = want.replace("[", "").replace("]", "")
            val got = mark(input, lang)
            if (got != want) "  [$lang] quiere: $want\n       sale:   $got" else null
        }
        assertTrue("${bad.size} diferencias:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `espanol titulos de juegos`() = check(
        "es",
        listOf(
            "¡Hola! Soy Masha. Hoy te recomiendo [The Legend of Zelda: Tears of the Kingdom]; si prefieres algo más oscuro, prueba [Hollow Knight] o [Call of Duty].",
            "Juega [Elden Ring] esta noche.",
            "Te recomiendo [Final Fantasy] siete.",
            "[Final Fantasy] es mi saga favorita.",
            "Tu [backlog] tiene [Red Dead Redemption] dos.",
            "[Dead Space] da miedo.",
            "¿Has jugado a [God of War]?",
            "En [Hollow Knight] hay mucho lore.",
            "El gameplay de [Street Fighter] es brutal.",
            "Tienes una partida de [Assassin's Creed] pendiente.",
            "Juega [League of Legends] con tus amigos, o [Among Us].",
            "Llevas doce horas jugando a [Stardew Valley].",
            "Me encanta [The Witcher] tres.",
            "Prueba [Hollow Knight: Silksong] cuando salga.",
            "[Resident Evil] cuatro y [Dead by Daylight].",
            "Hoy juegas a [It Takes Two] con tu pareja.",
        ),
    )

    @Test
    fun `espanol sin ingles se queda en un tramo`() {
        val texts = listOf(
            "Vivo en Buenos Aires y viajo a Los Angeles.",
            "No me gusta el pan, pero la red es total y el final es normal.",
            "Hot, red, fin, sin, son, come, me, no, real, general.",
            "Hola Masha, ¿qué tal estás hoy?",
            "Nueva York es enorme.",
            "Tengo un bug en el juego.",
            "Juega a Fortnite, que es gratis.",
            "Llevas doce horas y cuarenta y cinco minutos jugando; tu batería está al veintitrés por ciento.",
            "El gameplay online es genial, bro.",
        )
        for (t in texts) {
            val spans = LanguageSpans.split(t, "es", ::english)
            assertEquals(t, listOf(Span(t, "es")), spans)
        }
    }

    @Test
    fun `portugues frances y aleman`() {
        check(
            "pt",
            listOf(
                "Hoje eu recomendo [Hollow Knight] ou [The Legend of Zelda: Tears of the Kingdom] para você.",
                "Você jogou [Elden Ring] por doze horas.",
                "Não gosto de pão, mas a rede é boa e o final é normal.",
                "Vamos jogar [Call of Duty] hoje?",
            ),
        )
        check(
            "fr",
            listOf(
                "Aujourd'hui je te recommande [Hollow Knight] ou [Call of Duty], c'est génial.",
                "La station de jeu est prête et la nation entière joue à [Elden Ring].",
                "Tu as joué pendant douze heures et quarante-cinq minutes.",
            ),
        )
        check(
            "de",
            listOf(
                "Heute empfehle ich dir [Hollow Knight] oder [The Legend of Zelda: Tears of the Kingdom].",
                "Der Wald ist dunkel und der Berg ist hoch, die Kinder spielen im Garten.",
                "Du hast [Call of Duty] zwölf Stunden gespielt.",
            ),
        )
    }

    @Test
    fun `ingles y japones devuelven un solo tramo`() {
        val t = "Today I recommend Hollow Knight, a great game."
        assertEquals(listOf(Span(t, "en")), LanguageSpans.split(t, "en", ::english))
        val j = "今日はHollow Knightをおすすめします。"
        assertEquals(listOf(Span(j, "ja")), LanguageSpans.split(j, "ja", ::english))
        assertEquals(listOf(Span("", "es")), LanguageSpans.split("", "es", ::english))
    }

    @Test
    fun `la puntuacion se queda en el tramo base`() {
        val spans = LanguageSpans.split("Prueba Hollow Knight, ¿vale?", "es", ::english)
        assertEquals(listOf(Span("Prueba ", "es"), Span("Hollow Knight", "en"), Span(", ¿vale?", "es")), spans)
    }

    @Test
    fun `diccionario falso determinista`() {
        val en = setOf("hollow", "knight", "the", "of", "call", "duty", "red", "final", "no", "me")
        val spans = LanguageSpans.split("No me gusta Call of Duty, prefiero Hollow Knight.", "es") { it in en }
        assertEquals(
            listOf(Span("No me gusta ", "es"), Span("Call of Duty", "en"), Span(", prefiero ", "es"), Span("Hollow Knight", "en"), Span(".", "es")),
            spans,
        )
    }

    @Test
    fun `nunca pierde texto ni lanza`() {
        val r = Random(11)
        val atoms = listOf(
            "Hollow", "Knight", "of", "the", "The", "Call", "Duty", "hola", "juego", "Final", "Fantasy", " ", " ", ":", "-", ",", ".",
            "¿", "?", "¡", "!", "é", "ñ", "'s", "’", "12", "Zelda", "Elden", "Ring", "\n", "red", "no", "El", "Buenos", "Aires",
            "\u00A0", "&", "x", "Stardew", "Valley", "\uD83C\uDFAE",
        )
        repeat(3000) {
            val sb = StringBuilder()
            repeat(r.nextInt(12)) {
                sb.append(atoms[r.nextInt(atoms.size)])
                if (r.nextBoolean()) sb.append(' ')
            }
            val s = sb.toString()
            for (lang in listOf("es", "pt", "fr", "de")) {
                val spans = LanguageSpans.split(s, lang) { w -> w.length > 3 && w.hashCode() % 3 == 0 || english(w) }
                assertEquals(s, spans.joinToString("") { it.text })
            }
        }
    }
}
