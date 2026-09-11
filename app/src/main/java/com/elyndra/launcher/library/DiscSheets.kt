package com.elyndra.launcher.library

/**
 * Hojas de disco (.cue, .gdi, .m3u, .toc, .ccd): un juego en CD son varios
 * archivos, pero en la biblioteca debe aparecer una sola vez. Aquí se leen
 * los archivos que cada hoja referencia para ocultarlos al escanear.
 */
object DiscSheets {

    val EXTENSIONS = setOf("cue", "gdi", "m3u", "toc", "ccd")

    private val CUE_FILE = Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))""", RegexOption.IGNORE_CASE)
    private val TOC_FILE = Regex("""^\s*(?:FILE|DATAFILE)\s+"([^"]+)"""", RegexOption.IGNORE_CASE)

    /** Nombres de archivo (sin ruta) que referencia la hoja. */
    fun referencedFiles(extension: String, sheetName: String, content: String): List<String> {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        return when (extension.lowercase()) {
            "cue" -> lines.mapNotNull { line ->
                CUE_FILE.find(line)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }
            }.map(::baseName).toList()

            "toc" -> lines.mapNotNull { TOC_FILE.find(it)?.groupValues?.get(1) }.map(::baseName).toList()

            // "3\n1 0 4 2352 track01.bin 0\n2 600 0 2352 \"track 02.raw\" 0"
            "gdi" -> lines.drop(1).mapNotNull { gdiTrackName(it) }.map(::baseName).toList()

            "m3u" -> lines.filterNot { it.startsWith("#") }.map(::baseName).toList()

            // CloneCD: la imagen y los subcanales comparten nombre con la hoja.
            "ccd" -> sheetName.substringBeforeLast('.').let { base -> listOf("$base.img", "$base.sub") }

            else -> emptyList()
        }
    }

    private fun gdiTrackName(line: String): String? {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            when {
                line[i].isWhitespace() -> i++
                line[i] == '"' -> {
                    val end = line.indexOf('"', i + 1).let { if (it < 0) line.length else it }
                    tokens += line.substring(i + 1, end)
                    i = end + 1
                }
                else -> {
                    var end = i
                    while (end < line.length && !line[end].isWhitespace()) end++
                    tokens += line.substring(i, end)
                    i = end
                }
            }
        }
        return tokens.getOrNull(4)
    }

    private fun baseName(path: String): String = path.trim().substringAfterLast('/').substringAfterLast('\\')
}
