package com.elyndra.launcher.library

/* ─────────────────────────────────────────────────────────────
   Nintendo Wii U: las dos formas en que se guarda un juego.

     · Un archivo suelto — .wua (Wii U Archive de Cemu), .wud/.wux
       (volcado de disco), .wuhb (homebrew) o .rpx (ejecutable).
       Lo reconoce la extensión, como en cualquier otro sistema.

     · Un juego **desempaquetado**: una carpeta con `code`,
       `content` y `meta` dentro. Es lo que sueltan los volcadores y
       lo que más se ve en una biblioteca de Cemu, y es justo lo que
       antes se escapaba: el análisis entraba en la carpeta y daba de
       alta cada .rpx y cada .tmd como si fuese un juego distinto,
       así que el juego de verdad nunca aparecía.

   Aquí vive lo que hace falta para distinguir las dos: el resto del
   análisis es el mismo que el de cualquier sistema.
   ───────────────────────────────────────────────────────────── */
object WiiU {

    const val SYSTEM_ID = "wiiu"

    /** Carpetas que tiene dentro todo juego desempaquetado. */
    private val GAME_DIRS = setOf("code", "content", "meta")

    /**
     * ¿Es esta carpeta un juego desempaquetado?
     *
     * [childDirNames] son los nombres de sus subcarpetas directas. Se pide que
     * estén las tres: una carpeta con solo `content` es un directorio suelto de
     * datos, no un juego que Cemu pueda abrir.
     */
    fun isGameDir(childDirNames: List<String>): Boolean {
        val lower = childDirNames.mapTo(HashSet()) { it.lowercase() }
        return GAME_DIRS.all { it in lower }
    }

    /** Las tres de arriba, para no bajar a ellas cuando ya se ha dado de alta el juego. */
    fun isInnerDir(name: String): Boolean = name.lowercase() in GAME_DIRS

    /**
     * Title ID de 16 dígitos que Cemu escribe en el nombre del archivo:
     * "Super Mario 3D World [0005000010145D00] (v32).wua" → "0005000010145D00".
     *
     * Sirve para no confundir dos volcados del mismo juego y para limpiar el
     * nombre que se enseña; el contenido del contenedor (ZArchive comprimido
     * con zstd) no se abre: Cemu lo lee entero al lanzarlo.
     */
    private val TITLE_ID = Regex("""[\[(]\s*([0-9a-fA-F]{16})\s*[\])]""")

    fun titleId(fileName: String): String? =
        TITLE_ID.find(fileName)?.groupValues?.get(1)?.uppercase()
}
