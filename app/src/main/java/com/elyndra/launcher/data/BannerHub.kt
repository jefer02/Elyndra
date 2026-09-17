package com.elyndra.launcher.data

/* ─────────────────────────────────────────────────────────────
   GameHub Lite y sus forks (BannerHub, EggGame…).

   Este runtime no abre archivos: abre juegos **de su propia
   biblioteca**, cada uno con su id. Lanzarlo sin más deja al
   usuario en la portada del runtime — que es justo lo que pasaba
   antes de que existiera este módulo.

   Lo que sí entiende es un intent explícito con el id dentro:

       Intent()
         .setClassName("banner.hub", "com.xiaoji.egggame.DeepLinkActivity")
         .setAction("banner.hub.LAUNCH_GAME")
         .putExtra("localGameId", id)
         .putExtra("steamAppId", id)
         .putExtra("autoStartGame", true)

   Aquí vive todo lo que cambia entre builds: el paquete, la
   actividad y de dónde sale el id. El intent en sí lo arma
   [com.elyndra.launcher.launch.LaunchPlanner] como el de cualquier
   otro emulador.
   ───────────────────────────────────────────────────────────── */
object BannerHub {

    /**
     * Paquetes con los que se instala el runtime, en orden de preferencia.
     *
     * Los tres primeros son los propios; el resto son paquetes "de reemplazo"
     * con los que algunos forks se publican haciéndose pasar por otra app
     * (ver [Emulators.SPOOFED_PACKAGES]). Se ofrecen al usuario en Ajustes
     * por si tiene más de una build instalada.
     */
    val PACKAGES = listOf(
        "banner.hub",
        "gamehub.lite",
        "com.xiaoji.egggame",
        "emuready.gamehub.lite",
    )

    /** Paquetes de reemplazo bajo los que también aparece alguna build. */
    val DECOY_PACKAGES = listOf(
        "com.tencent.ig",
        "com.antutu.ABenchMark",
        "com.tencent.tmgp.cf",
    )

    /**
     * Actividades que aceptan el intent de lanzamiento, en orden.
     *
     * `DeepLinkActivity` es la puerta de entrada de las builds recientes;
     * `GameDetailActivity` la de las antiguas. Solo hay una instalada en cada
     * build, así que se listan las dos y gana la que exista de verdad (ver
     * `GameLauncher.installedComponent`).
     */
    val ACTIVITIES = listOf(
        "com.xiaoji.egggame.DeepLinkActivity",
        "com.xj.landscape.launcher.ui.gamedetail.GameDetailActivity",
    )

    /** Acción declarada por cada build con su propio paquete delante. */
    const val ACTION_SUFFIX = ".LAUNCH_GAME"

    /** Id de un juego de Steam dentro del runtime (el appid). */
    const val EXTRA_STEAM_ID = "steamAppId"

    /** Id de un juego que no viene de Steam (GOG, Epic, añadido a mano…). */
    const val EXTRA_LOCAL_ID = "localGameId"

    /** Arrancar el juego en vez de quedarse en su ficha. */
    const val EXTRA_AUTOSTART = "autoStartGame"

    /**
     * Extensiones de las que se saca el id cuando no está asignado a mano.
     *
     * Las de [com.elyndra.launcher.library.PcGames.Launcher] las exporta el
     * propio runtime. `.txt` e `.iso` están porque mucha gente ya tiene el id
     * escrito a mano en un archivo junto al juego: no lleva nada dentro más
     * que el número, y obligar a re-exportar cada juego por eso no tiene
     * sentido.
     */
    val ID_FILE_EXTENSIONS = setOf("txt", "iso", "steam", "pcgame", "gog", "epic", "amazon")

    /**
     * Id leído del contenido de uno de esos archivos.
     *
     * Se queda con la primera línea que no esté vacía ni sea un comentario, y
     * la descarta si no parece un id: un `.iso` de verdad —un disco, no un id
     * escrito a mano— daría binario, y mandarlo como id abriría el runtime sin
     * juego.
     */
    fun parseId(text: String?): String? = text
        ?.removePrefix("\uFEFF")
        ?.lineSequence()
        ?.map(String::trim)
        ?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?.takeIf(::looksLikeId)

    /**
     * ¿Esto puede ser un id de la biblioteca del runtime?
     *
     * Los de Steam son el appid (268910, 2551…) y los demás, el id interno del
     * runtime; ninguno lleva espacios ni ocupa una línea de texto entera.
     */
    fun looksLikeId(raw: String): Boolean = raw.length in 1..64 &&
        raw.none(Char::isWhitespace) &&
        raw.any(Char::isLetterOrDigit)

    /** Id escrito a mano por el usuario; null si no vale como tal. */
    fun normalizeId(raw: String): String? = raw.trim().takeIf(::looksLikeId)
}
