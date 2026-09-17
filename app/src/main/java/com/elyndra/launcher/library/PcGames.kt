package com.elyndra.launcher.library

import com.elyndra.launcher.data.BannerHub

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

   Ese ejecutable identifica al juego dentro de su carpeta, pero **no es
   lo que se lanza**: ningún runtime de Windows acepta hoy que le pasen
   un .exe por intent. Lo que sí aceptan es el archivo lanzador que ellos
   mismos exportan ("Frontend Export" / "Export for frontends") — ver
   [Launcher].
   ───────────────────────────────────────────────────────────── */
object PcGames {

    const val SYSTEM_ID = "pc"

    /**
     * Archivos que exporta un runtime de Windows para que lo lance un frontend.
     *
     * Son la única forma de arrancar un juego de PC desde fuera, porque el
     * juego no vive suelto en el disco: vive dentro del runtime, en un
     * contenedor con su Wine, sus componentes y sus ajustes. El archivo es el
     * puente — o lleva dentro el id del juego dentro del runtime, o es el
     * propio acceso directo que el runtime sabe abrir.
     *
     * Las extensiones son las de la configuración Android de ES-DE
     * (`.amazon .desktop .epic .gog .pcgame .steam` en el sistema `windows`):
     *
     *   - `.desktop` → Winlator y sus forks. Se le pasa **la ruta** del archivo
     *     (`shortcut_path`); dentro están el contenedor y el ejecutable.
     *   - el resto → GameHub/BannerHub y GameNative. Se les pasa **el
     *     contenido**: el id del juego en la biblioteca del runtime.
     */
    enum class Launcher(val ext: String, val store: String?) {
        Desktop("desktop", null),
        Steam("steam", "STEAM"),
        Epic("epic", "EPIC"),
        Gog("gog", "GOG"),
        Amazon("amazon", "AMAZON"),
        /** Juego añadido a mano dentro del runtime, sin tienda detrás. */
        Local("pcgame", "CUSTOM_GAME"),
        ;

        /** El archivo lleva dentro el id; el `.desktop` es el acceso directo entero. */
        val carriesId: Boolean get() = this != Desktop

        /** Un juego de Steam viaja con su appid; el resto, con el id interno del runtime. */
        val isSteam: Boolean get() = store == "STEAM"
    }

    val LAUNCHER_EXTENSIONS: Set<String> = Launcher.entries.map { it.ext }.toSet()

    /** Tope de lectura del archivo lanzador: solo lleva un id (ES-DE usa 4 kB). */
    const val MAX_LAUNCHER_BYTES = 4096

    /** ¿Es este archivo un lanzador exportado por un runtime, y de cuál? */
    fun launcherOf(fileName: String): Launcher? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return Launcher.entries.firstOrNull { it.ext == ext }
    }

    /** Lo que cuenta como "esto arranca algo": el binario, o su lanzador. */
    val EXECUTABLE_EXTENSIONS = setOf("exe", "bat", "lnk", "msi") + LAUNCHER_EXTENSIONS

    /**
     * Archivo suelto cuyo contenido es el id del juego dentro del runtime.
     *
     * Es la otra forma de tener una biblioteca de PC, y para BannerHub la más
     * cómoda: en vez de la carpeta entera del juego —que en Android muchas
     * veces ni está, porque el juego vive dentro del runtime— se deja un
     * archivo por juego con su id dentro ("Hollow Knight.iso" → `367520`). El
     * nombre del archivo es el nombre del juego y su contenido, el id.
     *
     * Se exige que pese poco: así un `.iso` que sea un disco de verdad no se
     * confunde nunca con uno de estos (ver [com.elyndra.launcher.data.BannerHub.ID_FILE_EXTENSIONS]).
     */
    fun isIdFile(fileName: String, size: Long): Boolean =
        isIdFileName(fileName) && size in 1L..MAX_LAUNCHER_BYTES.toLong()

    /** Lo mismo mirando solo el nombre, para cuando el tamaño no viene a mano. */
    fun isIdFileName(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in BannerHub.ID_FILE_EXTENSIONS

    /**
     * ¿Hay que mirar dentro para dar este archivo por bueno?
     *
     * Los que exporta el propio runtime (.steam, .gog…) se aceptan por la
     * extensión: no hay otra cosa que puedan ser. Un `.iso` o un `.txt`, sí:
     * son extensiones de uso común y lo que los distingue es llevar un id
     * dentro y no un disco o un léeme.
     */
    fun idFileNeedsContent(fileName: String): Boolean = launcherOf(fileName) == null

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
     * Si dentro hay un archivo lanzador ([Launcher]) manda ese, por encima de
     * cualquier .exe: es lo único que el runtime de Windows sabe abrir desde
     * fuera. Si no lo hay, gana el ejecutable que más se parece al nombre de
     * la carpeta — que es como está nombrado en la inmensa mayoría de los
     * juegos —, y a igualdad manda el que esté menos enterrado y el más
     * grande, porque el binario del motor pesa órdenes de magnitud más que
     * cualquier utilidad que lo acompañe.
     */
    fun pickExecutable(folderName: String, candidates: List<Executable>): Executable? {
        if (candidates.isEmpty()) return null
        val pool = candidates.filter { launcherOf(it.name) != null }.ifEmpty { candidates }
        val useful = pool.filterNot { isJunk(it.name) }.ifEmpty { pool }
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
