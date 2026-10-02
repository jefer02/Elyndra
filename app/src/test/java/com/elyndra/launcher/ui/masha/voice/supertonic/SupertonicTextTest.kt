package com.elyndra.launcher.ui.masha.voice.supertonic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Preprocesado de texto de Supertonic. Las pruebas de limpieza/etiquetas no necesitan ficheros;
 * las de ids comparan con los de la referencia (sherpa-onnx 1.13.8, misma duración predicha) y
 * solo corren si hay `unicode_indexer.bin` (variable de entorno MASHA_SUPERTONIC_DIR).
 */
class SupertonicTextTest {

    @Test fun limpiaSimbolosYEspacios() {
        assertEquals("Hi, I'm Masha test for example, you me 1 at home",
            SupertonicText.clean("Hi,  I'm   Masha [test] e.g., you/me #1 @home"))
        assertEquals("Ação, coração e pão - \"ótimo\"", SupertonicText.clean("Ação, coração e pão — “ótimo”"))
        assertEquals("Hola, ¿qué tal?", SupertonicText.clean("Hola , ¿qué tal ?"))
        assertEquals("dijo \"sí\"", SupertonicText.clean("dijo \"\"sí\"\""))
        assertEquals("guay", SupertonicText.clean("  guay 😀 "))
        assertEquals("", SupertonicText.clean(" \n\t "))
    }

    @Test fun etiquetaYPuntoFinal() {
        assertEquals("<es>Vale.</es>", SupertonicText.tag("Vale", "es"))
        assertEquals("<en>Okay, done.</en>", SupertonicText.tag("Okay, done.", "en"))
        assertEquals("<fr>C'est « magique »!</fr>", SupertonicText.tag("C'est « magique » !", "fr"))
        assertEquals("<de>Straße…</de>", SupertonicText.tag("Straße…", "de"))
        assertEquals("<es>Hollow Knight</es>", SupertonicText.tag("Hollow Knight", "es", addPeriod = false))
        assertEquals("<en>Ha <laugh></en>", SupertonicText.tag("Ha <laugh>", "en"))
        assertEquals("", SupertonicText.tag("   ", "es"))
        assertTrue(SupertonicText.endsWithPunct("¿qué?"))
        assertFalse(SupertonicText.endsWithPunct("hola"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun idiomaDesconocido() {
        SupertonicText.tag("hola", "xx")
    }

    @Test fun nfkdConIndiceFalso() {
        // Índice de juguete: cada código BMP → su propio valor, salvo U+00F3 (ó precompuesta) = -1.
        val ix = IntArray(65536) { it }.also { it[0xF3] = -1 }
        // "ó" se descompone en 'o' + U+0301; lo desconocido (☀ = -1 aquí) se quita.
        ix[0x2600] = -1
        assertArrayEquals(longArrayOf('o'.code.toLong(), 0x301), SupertonicText.toIds("ó☀", ix))
        assertArrayEquals(longArrayOf('.'.code.toLong(), '.'.code.toLong(), '.'.code.toLong()), SupertonicText.toIds("…", ix))
    }

    @Test fun trocea() {
        assertEquals(listOf("Hola. Adiós."), SupertonicText.chunk("Hola. Adiós.", 300))
        val long = (1..40).joinToString(" ") { "Frase número $it." }
        val parts = SupertonicText.chunk(long, 120)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 120 })
        assertEquals(long, parts.joinToString(" "))
        val noSpaces = "a".repeat(250)
        assertEquals(listOf("a".repeat(120), "a".repeat(120), "a".repeat(10)), SupertonicText.chunk(noSpaces, 120))
        assertTrue(SupertonicText.chunk("  ", 300).isEmpty())
    }

    @Test fun tramosMezclados() {
        val segs = listOf(
            "Si te gustó " to "es", "Hollow Knight" to "en", ", tienes que probar " to "es",
            "The Legend of Zelda: Tears of the Kingdom" to "en", "." to "es",
        )
        assertEquals(
            listOf("Si te gustó" to "es", "Hollow Knight" to "en", ", tienes que probar" to "es",
                "The Legend of Zelda: Tears of the Kingdom." to "en"),
            SupertonicModel.mergeSegments(segs),
        )
        assertEquals(listOf("uno dos" to "es"), SupertonicModel.mergeSegments(listOf("uno " to "es", "dos" to "es", " " to "en")))
    }

    @Test fun frasesCortasMasDespacio() {
        assertEquals(0.9f, SupertonicModel.shortSpeed("Okay, done.", 1f), 1e-6f)
        assertEquals(1f, SupertonicModel.shortSpeed("Hola, soy Masha.", 1f), 1e-6f)
    }

    @Test fun recorteDeJuntas() {
        val w = FloatArray(10000).also { for (i in 3000 until 7000) it[i] = if (i % 2 == 0) 0.5f else -0.5f }
        val keep = 100
        val both = SupertonicModel.trimEdges(w, keep, lead = true, tail = true)
        assertEquals(4000 + 2 * keep, both.size)
        assertEquals(w.size - 3000 + keep, SupertonicModel.trimEdges(w, keep, lead = true, tail = false).size)
        assertEquals(7000 + keep, SupertonicModel.trimEdges(w, keep, lead = false, tail = true).size)
    }

    @Test fun idsIgualesQueSherpa() {
        val dir = modelDir()
        assumeTrue("MASHA_SUPERTONIC_DIR sin modelo", dir != null)
        val ix = SupertonicText.loadIndexer(File(dir, "unicode_indexer.bin"))
        for ((text, lang, ids) in GOLDEN) {
            assertArrayEquals("$lang: $text", ids, SupertonicText.encode(text, lang, ix))
        }
    }

    companion object {
        fun modelDir(): File? {
            val p = System.getenv("MASHA_SUPERTONIC_DIR") ?: System.getProperty("MASHA_SUPERTONIC_DIR") ?: return null
            return File(p).takeIf { File(it, "unicode_indexer.bin").isFile }
        }

        private fun ids(s: String) = s.split(',').map { it.trim().toLong() }.toLongArray()

        /** Ids de la referencia (Python con el mismo preprocesado que sherpa; validados por duración). */
        val GOLDEN = listOf(
            Triple("¡Hola! Soy Masha, ¿qué tal?", "es",
                ids("29,64,78,31,98,40,74,71,60,3,2,51,74,84,2,45,60,78,67,60,13,2,111,76,80,64,146,2,79,60,71,32,29,16,64,78,31")),
            Triple("Si te gustó Hollow Knight, tienes que probar The Legend of Zelda: Tears of the Kingdom.", "es",
                ids("29,64,78,31,51,68,2,79,64,2,66,80,78,79,74,146,2,40,74,71,71,74,82,2,43,73,68,66,67,79,13,2,79,68,64,73,64,78,2,76,80,64,2,75,77,74,61,60,77,2,52,67,64,2,44,64,66,64,73,63,2,74,65,2,58,64,71,63,60,27,2,52,64,60,77,78,2,74,65,2,79,67,64,2,43,68,73,66,63,74,72,15,29,16,64,78,31")),
            Triple("Ação, coração e pão — “ótimo”", "pt",
                ids("29,75,79,31,33,62,162,60,148,74,13,2,62,74,77,60,62,162,60,148,74,2,64,2,75,60,148,74,2,14,2,4,74,146,79,68,72,74,4,29,16,75,79,31")),
            Triple("Größe über Straße… naïve café", "de",
                ids("29,63,64,31,39,77,74,152,116,64,2,80,152,61,64,77,2,51,79,77,60,116,64,15,15,15,2,73,60,68,152,81,64,2,62,60,65,64,146,15,29,16,63,64,31")),
            Triple("L'été à Noël, c'est « magique » !", "fr",
                ids("29,65,77,31,44,8,64,146,79,64,146,2,60,145,2,46,74,64,152,71,13,2,62,8,64,78,79,2,104,2,72,60,66,68,76,80,64,2,110,3,29,16,65,77,31")),
            Triple("こんにちは、マーシャです。", "ja",
                ids("29,69,60,31,693,735,706,700,710,661,775,799,755,780,703,736,696,662,29,16,69,60,31")),
            Triple("Hi,  I'm   Masha [test] e.g., you/me #1 @home", "en",
                ids("29,64,73,31,40,68,13,2,41,8,72,2,45,60,78,67,60,2,79,64,78,79,2,65,74,77,2,64,83,60,72,75,71,64,13,2,84,74,80,2,72,64,2,18,2,60,79,2,67,74,72,64,15,29,16,64,73,31")),
            Triple("Vale", "es", ids("29,64,78,31,54,60,71,64,15,29,16,64,78,31")),
            Triple("Okay, done.", "en", ids("29,64,73,31,47,70,60,84,13,2,63,74,73,64,15,29,16,64,73,31")),
        )
    }
}
