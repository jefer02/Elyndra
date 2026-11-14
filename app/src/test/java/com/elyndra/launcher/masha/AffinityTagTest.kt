package com.elyndra.launcher.masha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La marca oculta de puntuación: nunca asoma (ni partida entre trozos) y solo
 * cuenta en la última línea.
 */
class AffinityTagTest {

    /** Pasa [pieces] por el filtro como llegarían en streaming; devuelve lo emitido trozo a trozo y el valor. */
    private fun stream(vararg pieces: String): Pair<List<String>, Int?> {
        val s = AffinityTag.Stripper()
        val out = pieces.map { s.push(it) } + s.finish()
        return out to s.delta
    }

    private fun joined(vararg pieces: String) = stream(*pieces).let { it.first.joinToString("") to it.second }

    @Test
    fun aFinalLineMarkerIsRemovedAndRead() {
        assertEquals(AffinityTag.Stripped("Okay, I have one you'll love.\n", 3), AffinityTag.strip("Okay, I have one you'll love.\n<<A:+3>>"))
        assertEquals(-4, AffinityTag.strip("Wow, harsh.\n<<A:-4>>\n").delta)
        assertEquals(-2, AffinityTag.strip("Hm.\n<<A:−2>>").delta) // signo menos tipográfico
        assertEquals(5, AffinityTag.strip("Hi\n  << a : 5 >>  ").delta)
        assertEquals(0, AffinityTag.strip("<<A:0>>").delta)
    }

    @Test
    fun aMarkerSplitAcrossChunksNeverLeaks() {
        val (out, delta) = stream("Sure thing.\n<<A:", "+3>>")
        assertEquals(listOf("Sure thing.\n", "", ""), out)
        assertEquals(3, delta)
    }

    @Test
    fun everySplitPointIsSafe() {
        val text = "That's actually wild.\n<<A:+7>>"
        for (cut in 1 until text.length) {
            val (out, delta) = stream(text.substring(0, cut), text.substring(cut))
            out.forEach { assertFalse("trozo con marca en el corte $cut: $it", it.contains("<") || it.contains(">>") || it.contains("A:")) }
            assertEquals("That's actually wild.\n", out.joinToString(""))
            assertEquals(7, delta)
        }
    }

    @Test
    fun characterByCharacterStreamingNeverLeaks() {
        val text = "Okay.\n<<A:-10>>"
        val (out, delta) = stream(*text.map { it.toString() }.toTypedArray())
        assertEquals("Okay.\n", out.joinToString(""))
        assertFalse(out.any { it.contains('<') })
        assertEquals(-10, delta)
    }

    @Test
    fun aPossiblePrefixIsHeldUntilRuledOut() {
        val s = AffinityTag.Stripper()
        assertEquals("I ", s.push("I <"))
        assertEquals("", s.push("<"))
        // No era una marca: sale entero, en su orden.
        assertEquals("<<3 games", s.push("3 games"))
        assertEquals("", s.finish())
        assertNull(s.delta)
    }

    @Test
    fun aTruncatedMarkerAtTheEndIsDropped() {
        assertEquals("Bye.\n" to null, joined("Bye.\n<<A:+", "3"))
        assertEquals("Bye.\n" to null, joined("Bye.\n<<A"))
        // Un "<" suelto al final sí es texto.
        assertEquals("a <" to null, joined("a <"))
    }

    @Test
    fun aMarkerInTheMiddleIsRemovedButIgnored() {
        val r = AffinityTag.strip("Excuse me?! <<A:+9>> Who is she?!")
        assertEquals("Excuse me?!  Who is she?!", r.text)
        assertNull(r.delta)
        // Sola en su línea pero con texto detrás: tampoco es la última línea.
        assertNull(AffinityTag.strip("Hi.\n<<A:+9>>\nMore text.").delta)
    }

    @Test
    fun aMarkerAtTheEndOfASentenceLineDoesNotCount() {
        val r = AffinityTag.strip("Enjoy it! <<A:+4>>")
        assertEquals("Enjoy it! ", r.text)
        assertNull(r.delta)
    }

    @Test
    fun theLastFinalLineMarkerWins() {
        assertEquals(-1, AffinityTag.strip("<<A:+8>>\nok\n<<A:-1>>").delta)
    }

    @Test
    fun malformedMarkersStayAsText() {
        listOf("<<A:+>>", "<<B:+3>>", "<<A:+300>>", "<A:+3>", "<<A+3>>").forEach {
            assertEquals(it, AffinityTag.strip(it).text)
            assertNull(AffinityTag.strip(it).delta)
        }
    }

    @Test
    fun plainTextPassesThroughUntouched() {
        val text = "You left Okami at the second hour, three weeks ago. 2 < 3 and 5 > 4."
        assertEquals(AffinityTag.Stripped(text, null), AffinityTag.strip(text))
    }
}
