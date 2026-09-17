package com.elyndra.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Todo texto con huecos (`%1$s`) recibe sus argumentos allí donde se usa.
 *
 * `UiText.res(...)` acaba en `stringResource(id, *args)`, que formatea de
 * verdad: si faltan argumentos, `String.format` lanza
 * `MissingFormatArgumentException` y la app se cierra al pintar el diálogo, no
 * al compilar. El compilador no lo ve, y Lint tampoco — solo sabe leer un
 * `getString` directo, y aquí todo pasa por [UiText] —, así que se comprueba
 * sobre el propio código.
 *
 * Solo mira `<string>`: los `<plurals>` llevan el contador por defecto
 * ([UiText.plural]) y contarlos daría falsos positivos.
 */
class StringArgsTest {

    @Test
    fun everyFormattedStringGetsItsArguments() {
        val needed = requiredArgs()
        assertTrue("no se han leído los textos", needed.isNotEmpty())

        val missing = sortedSetOf<String>()
        main.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val code = scrub(file.readText())
            USE.findAll(code).forEach { use ->
                val name = use.groupValues[1]
                val need = needed[name] ?: return@forEach
                val given = argsAfter(code, use.range.last + 1)
                if (given < need) {
                    val line = code.take(use.range.first).count { it == '\n' } + 1
                    missing += "${file.name}:$line R.string.$name pide $need y recibe $given"
                }
            }
        }
        assertEquals(emptyList<String>(), missing.toList())
    }

    /* ── lectura de los textos ────────────────────────────────── */

    /** Cuántos argumentos pide cada texto: el mayor `%N$` que aparece dentro. */
    private fun requiredArgs(): Map<String, Int> =
        STRING.findAll(File(main, "res/values/strings.xml").readText())
            .associate { it.groupValues[1] to argsIn(it.groupValues[2]) }

    private fun argsIn(text: String): Int {
        val body = text.replace("%%", "")
        INDEXED.findAll(body).map { it.groupValues[1].toInt() }.maxOrNull()?.let { return it }
        return if (PLAIN.containsMatchIn(body)) 1 else 0
    }

    /* ── lectura del código ───────────────────────────────────── */

    /**
     * Argumentos que quedan en la llamada después del `R.string.X`: las comas
     * hasta que se cierra el paréntesis que lo envuelve.
     */
    private fun argsAfter(code: String, from: Int): Int {
        var depth = 0
        var args = 0
        for (i in from until code.length) {
            when (code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return args else depth--
                ',' -> if (depth == 0) args++
            }
        }
        return args
    }

    /**
     * El mismo código con los comentarios y los literales en blanco.
     *
     * Así un paréntesis o una coma dentro de un texto no descuadran la cuenta.
     * Se sustituyen por espacios en vez de borrarse para no mover las líneas.
     */
    private fun scrub(source: String): String {
        val out = StringBuilder(source)
        var i = 0
        fun blank(end: Int) {
            for (j in i until end) if (out[j] != '\n') out[j] = ' '
            i = end
        }

        fun endOf(open: String, close: String): Int {
            val at = source.indexOf(close, i + open.length)
            return if (at < 0) source.length else at + close.length
        }

        while (i < source.length) {
            when {
                source.startsWith("//", i) -> blank(source.indexOf('\n', i).let { if (it < 0) source.length else it })
                source.startsWith("/*", i) -> blank(endOf("/*", "*/"))
                source.startsWith("\"\"\"", i) -> blank(endOf("\"\"\"", "\"\"\""))
                source[i] == '"' || source[i] == '\'' -> {
                    val quote = source[i]
                    var j = i + 1
                    while (j < source.length && source[j] != quote) j += if (source[j] == '\\') 2 else 1
                    blank(minOf(j + 1, source.length))
                }
                else -> i++
            }
        }
        return out.toString()
    }

    private companion object {
        /** Los tests corren desde el módulo o desde la raíz, según quién los lance. */
        val main: File = listOf("src/main", "app/src/main").map(::File).firstOrNull { it.isDirectory }
            ?: error("no se encuentra src/main desde ${File("").absolutePath}")

        val STRING = Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val INDEXED = Regex("""%(\d+)\$""")
        val PLAIN = Regex("""%[-+ #0-9.]*[sdf]""")
        val USE = Regex("""R\.string\.(\w+)""")
    }
}
