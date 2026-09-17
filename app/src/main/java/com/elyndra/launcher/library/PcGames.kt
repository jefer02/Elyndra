package com.elyndra.launcher.library

/* ─────────────────────────────────────────────────────────────
   Juegos de PC (Windows) corriendo en Android.

   Un juego de PC no es un archivo: es una carpeta con su ejecutable,
   sus DLL y sus datos. Por eso la biblioteca de PC se da de alta
   distinto al resto — el usuario elige **una carpeta raíz** y dentro
   hay una carpeta por juego:

       Juegos PC/
         Hollow Knight/      → Hollow Knight.exe
         Celeste/            → Celeste.exe
         Hades/  bin/        → Hades.exe

   El análisis por extensión que usan las consolas aquí no sirve: daría
   de alta el desinstalador, el instalador de DirectX y cada
   herramienta suelta como si fuesen juegos distintos. Lo que se hace
   es recorrer carpeta a carpeta y, dentro de cada una, elegir **un**
   ejecutable: el que de verdad arranca el juego.

   Ese ejecutable es además lo que se le pasa al runtime de Windows
   (Winlator y compañía) cuando acepta una ruta.
   ───────────────────────────────────────────────────────────── */
object PcGames {

    const val SYSTEM_ID = "pc"

    /** Lo que cuenta como "esto arranca algo". */
    val EXECUTABLE_EXTENSIONS = setOf("exe", "bat", "lnk", "msi")

    /** Hasta dónde se baja dentro de la carpeta de un juego buscando su .exe. */
    const val MAX_DEPTH = 3

    /**
     * Un ejecutable encontrado dentro de la carpeta de un juego.
     *
     * [relPath] es relativo a esa carpeta ("bin/Hades.exe"), así que su número
     * de barras es la profundidad a la que estaba.
     */
    data class Executable(val docId: String, val relPath: String, val size: Long) {
        val name: String get() = relPath.substringAfterLast('/')
        val depth: Int get() = relPath.count { it == '/' }
    }

    /**
     * Ejecutables que nunca son el juego: desinstaladores, instaladores de
     * dependencias y los volcadores de fallos que dejan Unity y Unreal.
     *
     * Solo se descartan si hay alguna otra opción — una carpeta cuyo único
     * .exe se llame "setup.exe" seguirá dando de alta ese, porque es mejor
     * que dejar el juego fuera de la biblioteca.
     */
    private val NEVER_THE_GAME = listOf(
        "unins", "uninstall", "setup", "install", "vcredist", "vc_redist",
        "dxsetup", "dxwebsetup", "directx", "dotnetfx", "oalinst", "openal",
        "prereq", "redist", "crashreport", "crashhandler", "crashpad",
        "cleanup", "activation", "notification_helper",
    )

    private fun isJunk(name: String): Boolean {
        val lower = name.lowercase()
        return NEVER_THE_GAME.any { lower.contains(it) }
    }

    /**
     * ¿Qué ejecutable arranca este juego?
     *
     * Gana el que más se parece al nombre de la carpeta — que es como está
     * nombrado en la inmensa mayoría de los juegos —, y a igualdad manda el
     * que esté menos enterrado y el más grande, porque el binario del motor
     * pesa órdenes de magnitud más que cualquier utilidad que lo acompañe.
     */
    fun pickExecutable(folderName: String, candidates: List<Executable>): Executable? {
        if (candidates.isEmpty()) return null
        val useful = candidates.filterNot { isJunk(it.name) }.ifEmpty { candidates }
        val folder = Names.normalize(folderName)
        return useful.maxByOrNull { exe ->
            val stem = Names.normalize(exe.name.substringBeforeLast('.'))
            var score = 0.0
            when {
                stem.isNotEmpty() && stem == folder -> score += 1000.0
                stem.length >= 3 && folder.contains(stem) -> score += 600.0
                folder.isNotEmpty() && stem.contains(folder) -> score += 600.0
                else -> score += 200.0 * Names.similarity(folderName, exe.name.substringBeforeLast('.'))
            }
            // Menos enterrado, mejor: el .exe de la raíz manda sobre el de `tools/`.
            score -= exe.depth * 40.0
            // Tamaño, con techo: distingue el motor de un ayudante de 200 kB
            // sin que un juego enorme desbanque a uno que sí coincide de nombre.
            score += minOf(exe.size / (8L * 1024 * 1024), 20L).toDouble()
            score
        }
    }

    /**
     * ¿Merece esta carpeta estar en la biblioteca?
     *
     * Sin ningún ejecutable dentro no es un juego: es la carpeta de guardados,
     * la de mods o la que dejó un descompresor a medias.
     */
    fun looksLikeGame(candidates: List<Executable>): Boolean = candidates.isNotEmpty()

    /** Título de partida de un juego de PC: el nombre de su carpeta, limpio. */
    fun titleOf(folderName: String): String = Names.cleanTitle(folderName, stripExtension = false)
}
