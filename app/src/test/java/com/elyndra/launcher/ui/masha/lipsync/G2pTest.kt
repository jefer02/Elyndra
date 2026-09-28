package com.elyndra.launcher.ui.masha.lipsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class G2pTest {

    private val es = SpanishG2p(distincion = true, sheismo = false)
    private val mx = SpanishG2p(distincion = false, sheismo = false)
    private val ar = SpanishG2p(distincion = false, sheismo = true)

    private fun G2p.ph(w: String): List<Phone> = ArrayList<Phone>().also { word(w, it) }
    private fun G2p.s(w: String): String = ph(w).joinToString(" ") { it.ph.name + if (it.stress) "'" else "" }

    /* ── español ── */

    @Test
    fun `espanol basico con acento llano`() {
        assertEquals("K A' S A", es.s("casa"))
        assertEquals("M A' P A", es.s("mapa"))
    }

    @Test
    fun `seseo y distincion segun el pais`() {
        assertEquals("TH E' N A", es.s("cena"))
        assertEquals("S E' N A", mx.s("cena"))
        assertEquals("TH A P A' T O", es.s("zapato"))
        assertEquals("S A P A' T O", mx.s("zapato"))
    }

    @Test
    fun `v es b y nunca labiodental`() {
        val vaca = es.ph("vaca")
        assertEquals(Ph.B, vaca[0].ph)
        assertTrue(vaca.none { it.ph.labiodental })
        // Entre vocales, aproximante [β] (sin cierre obligatorio).
        assertEquals(Ph.BH, es.ph("uva")[1].ph)
        assertEquals(Ph.BH, es.ph("haber")[1].ph)
        // Tras nasal, oclusiva y la n se vuelve [m].
        val invierno = es.ph("invierno")
        assertEquals(Ph.M, invierno[1].ph)
        assertEquals(Ph.B, invierno[2].ph)
    }

    @Test
    fun `h muda ch ll y`() {
        assertEquals("O' L A", es.s("hola"))
        assertEquals(Ph.CH, es.ph("chico")[0].ph)
        assertEquals(Ph.YC, mx.ph("llave")[0].ph)
        assertEquals(Ph.SH, ar.ph("llave")[0].ph)
        assertEquals(Ph.YC, mx.ph("yo")[0].ph)
        // "y" conjunción: vocal átona; "hoy", "muy": semivocal final.
        assertEquals("I", es.s("y"))
        assertEquals("O' JG", es.s("hoy"))
        assertEquals("M U' JG", es.s("muy"))
    }

    @Test
    fun `qu gu gue y gui con u muda, gu con dieresis suena`() {
        assertEquals("K E' S O", es.s("queso"))
        assertEquals("G E' RR A", es.s("guerra"))
        assertTrue(es.ph("pingüino").any { it.ph == Ph.WG })
        assertEquals(Ph.X, es.ph("gente")[0].ph)
        assertEquals(Ph.X, es.ph("jamón")[0].ph)
    }

    @Test
    fun `r simple y multiple`() {
        assertEquals("P E' RT O", es.s("pero"))
        assertEquals("P E' RR O", es.s("perro"))
        assertEquals(Ph.RR, es.ph("rosa")[0].ph)
        assertEquals(Ph.RR, es.ph("honra")[2].ph)
    }

    @Test
    fun `x y equis mexicana`() {
        val taxi = es.ph("taxi").map { it.ph }
        assertEquals(listOf(Ph.T, Ph.A, Ph.K, Ph.S, Ph.I), taxi)
        assertEquals(Ph.X, mx.ph("México")[2].ph)
    }

    @Test
    fun `acento prosodico`() {
        // Tilde.
        assertTrue(es.ph("canción").first { it.stress }.ph == Ph.O)
        // Llana por acabar en n / s / vocal.
        assertEquals(Ph.A, es.ph("cantan").first { it.stress }.ph)
        assertEquals(1, es.ph("cantan").count { it.stress })
        // Aguda por acabar en consonante.
        val hablar = es.ph("hablar")
        assertEquals(Ph.A, hablar.first { it.stress }.ph)
        assertTrue(hablar.indexOfFirst { it.stress } > 1)
        // "ciudad": iu → semivocal + u, aguda en la a; d final débil.
        val ciudad = es.ph("ciudad")
        assertEquals(Ph.A, ciudad.first { it.stress }.ph)
        assertEquals(Ph.DHW, ciudad.last().ph)
        // Monosílabos átonos.
        assertTrue(es.ph("el").none { it.stress })
        assertTrue(es.ph("sol").any { it.stress })
    }

    @Test
    fun `numeros en palabras`() {
        val n = mx.ph("23")
        assertTrue(n.size > 8)
        assertTrue(n.any { it.stress })
        assertEquals(listOf("mil", "doscientos"), NumberWords.words("1200", "es"))
        assertEquals(listOf("tres", "coma", "cinco"), NumberWords.words("3,5", "es"))
        assertEquals(listOf("twenty", "one"), NumberWords.words("21", "en"))
    }

    /* ── inglés ── */

    private val dict: CmuDict? by lazy {
        val f = listOf("src/main/assets/lipsync/cmudict.txt", "app/src/main/assets/lipsync/cmudict.txt").map { File(it) }.firstOrNull { it.exists() }
        f?.inputStream()?.let { CmuDict.load(it) }
    }

    @Test
    fun `cmudict se carga y busca por biseccion`() {
        val d = dict
        assertNotNull("falta assets/lipsync/cmudict.txt", d)
        d!!
        assertTrue(d.size > 100_000)
        assertEquals(listOf("HH", "AH0", "L", "OW1"), d.lookup("hello"))
        assertEquals(listOf("W", "ER1", "L", "D"), d.lookup("world"))
        assertNull(d.lookup("zzzzqqq"))
        assertEquals(listOf("AH0"), d.lookup("a"))
    }

    @Test
    fun `ingles con diccionario`() {
        val en = EnglishG2p(dict)
        val hello = en.ph("Hello").map { it.ph }
        assertEquals(listOf(Ph.HH, Ph.AX, Ph.L, Ph.O, Ph.WG), hello)
        val thought = en.ph("thought")
        assertEquals(Ph.TH, thought[0].ph)
        assertEquals(Ph.AO, thought[1].ph)
        assertTrue(thought[1].stress)
        // v inglesa sí es labiodental.
        assertTrue(en.ph("very")[0].ph.labiodental)
    }

    @Test
    fun `ingles fuera del diccionario por reglas`() {
        val en = EnglishG2p(null)
        val p = en.ph("zorblax").map { it.ph }
        assertTrue(p.isNotEmpty())
        assertEquals(Ph.Z, p[0])
        assertTrue(p.contains(Ph.B))
        // "make": a larga + e muda.
        assertEquals(listOf(Ph.M, Ph.E, Ph.JG, Ph.K), en.ph("make").map { it.ph })
        assertEquals(Ph.TH, en.ph("think")[0].ph)
    }

    /* ── otros idiomas ── */

    @Test
    fun `portugues frances aleman y japones`() {
        val pt = G2p.forLocale(Locale.forLanguageTag("pt-BR"), null)!!
        assertTrue(pt.ph("vida")[0].ph.labiodental)
        // -m final nasal: sin cierre de labios.
        assertFalse(pt.ph("bem").last().ph.closure)
        val fr = G2p.forLocale(Locale.FRENCH, null)!!
        // "petit": t final muda.
        assertEquals(Ph.I, fr.ph("petit").last().ph)
        assertEquals(Ph.U, fr.ph("vous")[1].ph)
        val de = G2p.forLocale(Locale.GERMAN, null)!!
        assertEquals(Ph.F, de.ph("Vater")[0].ph)
        assertEquals(Ph.V, de.ph("Wasser")[0].ph)
        assertEquals(Ph.SH, de.ph("Schule")[0].ph)
        assertNull(G2p.forLocale(Locale.JAPANESE, null))
    }

    @Test
    fun `tokens con rangos y pausas`() {
        val t = Tokenizer.tokens("Hola, ¿qué tal? Son 3,5 euros.")
        assertEquals(listOf("Hola", "qué", "tal", "Son", "3,5", "euros"), t.map { it.text })
        assertEquals(Pause.COMMA, t[0].pause)
        assertEquals(Pause.QUESTION, t[2].pause)
        assertEquals(Pause.STOP, t[5].pause)
        assertEquals(7, t[1].start)
        assertTrue(Tokenizer.isQuestion("¿Vienes mañana?"))
        assertFalse(Tokenizer.isQuestion("Vale."))
    }
}
