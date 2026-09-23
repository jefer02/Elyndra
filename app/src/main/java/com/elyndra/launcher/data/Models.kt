package com.elyndra.launcher.data

import kotlinx.serialization.Serializable

/* ─────────────────────────────────────────────────────────────
   Modelo de la biblioteca: la instantánea en memoria que pinta la UI.

   Todo lo que el usuario añade vive aquí: carpetas de ROMs (una por
   sistema y emulador), las ROMs encontradas en cada carpeta, los
   juegos Android elegidos, los metadatos descargados y el tiempo de
   juego. Las rutas de imágenes son relativas a filesDir.

   Se persiste en Room (ver data/db): cada cambio de la instantánea se
   traduce en las filas que cambian. Las clases siguen siendo
   @Serializable porque la primera vez se importa el antiguo
   files/library.json (ver LegacyLibraryJson).
   ───────────────────────────────────────────────────────────── */

@Serializable
data class Library(
    val version: Int = 1,
    val folders: List<RomFolder> = emptyList(),
    val roms: List<RomEntry> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val sessions: List<PlaySession> = emptyList(),
    val lastAutoScan: Long = 0,
)

/** Carpeta de ROMs dada de alta: un árbol SAF concedido + la subcarpeta raíz. */
@Serializable
data class RomFolder(
    val id: String,
    val systemId: String,
    /** URI del árbol concedido con ACTION_OPEN_DOCUMENT_TREE (permiso persistente). */
    val treeUri: String,
    /** documentId de la carpeta que contiene las ROMs (puede ser una subcarpeta del árbol). */
    val rootDocId: String,
    val displayPath: String,
    /** Perfil de emulador; "custom:<paquete>" para una app elegida a mano. */
    val emulatorId: String? = null,
    val addedAt: Long = 0,
    val lastScan: Long = 0,
    /**
     * Imágenes elegidas a mano para la carpeta. Si están, mandan sobre las
     * heredadas del último juego jugado (ver `Derived.folders` en el ViewModel).
     */
    val cover: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val icon: String? = null,
    /**
     * Juegos que el usuario ha quitado de la biblioteca, por su documento.
     *
     * Se guardan porque el archivo sigue en el disco: sin esta lista, el
     * siguiente análisis los volvería a dar de alta y "quitar" no querría
     * decir nada. Se vacía con "restaurar" (ver `LibraryRepository`).
     */
    val excluded: Set<String> = emptySet(),
) {
    val key: String get() = "f:$id"
}

@Serializable
data class RomEntry(
    val id: String,
    val folderId: String,
    val systemId: String,
    val docId: String,
    val fileName: String,
    val relPath: String,
    val size: Long = 0,
    val modified: Long = 0,
    val isDirectory: Boolean = false,
    /** Título limpio derivado del nombre de archivo. */
    val title: String,
    val meta: GameMeta = GameMeta(),
    val stats: PlayStats = PlayStats(),
    /** Emulador solo para esta ROM (si no, el de la carpeta). */
    val emulatorId: String? = null,
    val hashes: FileHashes? = null,
    /**
     * Juegos que son una carpeta (PC): el documento del ejecutable que los
     * arranca y su ruta dentro de la carpeta ("bin/Hades.exe"). Null en todo
     * lo demás, donde el juego ya es el propio [docId].
     */
    val mainDocId: String? = null,
    val mainFile: String? = null,
    /**
     * Id de este juego dentro de la biblioteca de un runtime de Windows
     * (BannerHub / GameHub / GameNative), puesto a mano por el usuario.
     *
     * Manda sobre lo que se pueda leer del disco: si está, es el id que se
     * manda en el intent (ver [BannerHub]).
     */
    val pcGameId: String? = null,
    /** Cuándo entró en la biblioteca (0 en las ROMs de antes de guardarlo: vale la fecha de su carpeta). */
    val addedAt: Long = 0,
) {
    val key: String get() = "r:$id"
    val displayTitle: String get() = meta.name?.takeIf { it.isNotBlank() } ?: title
    val extension: String get() = fileName.substringAfterLast('.', "").uppercase()
}

@Serializable
data class FileHashes(
    val size: Long,
    val modified: Long,
    val crc: String? = null,
    val md5: String? = null,
    val sha1: String? = null,
    /** Hash de RetroAchievements (rcheevos). Null si el formato no permite calcularlo. */
    val ra: String? = null,
    val raComputed: Boolean = false,
)

@Serializable
data class AppEntry(
    val packageName: String,
    val label: String,
    val addedAt: Long = 0,
    val meta: GameMeta = GameMeta(),
    val stats: PlayStats = PlayStats(),
) {
    val key: String get() = "a:$packageName"
    val displayTitle: String get() = meta.name?.takeIf { it.isNotBlank() } ?: label
}

@Serializable
data class PlayStats(
    val minutes: Int = 0,
    val lastPlayed: Long = 0,
    val launches: Int = 0,
)

@Serializable
data class GameMeta(
    /** Momento del último intento de metadatos (0 = nunca). */
    val scrapedAt: Long = 0,
    /** Algún servicio reconoció el juego. */
    val matched: Boolean = false,
    val sources: List<String> = emptyList(),
    val name: String? = null,
    val description: String? = null,
    /** "AAAA-MM-DD" o "AAAA". */
    val releaseDate: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    val genre: String? = null,
    val players: String? = null,
    /** Nota normalizada 0…1. */
    val rating: Float? = null,
    val cover: String? = null,
    val hero: String? = null,
    val logo: String? = null,
    val screenshot: String? = null,
    /** Icono elegido a mano (sustituye al icono de la app o acompaña a la ROM sin carátula). */
    val icon: String? = null,
    /** Imágenes elegidas a mano ("cover", "hero", "icon"): "Actualizar metadatos" no las pisa. */
    val pinned: List<String> = emptyList(),
    val ssGameId: String? = null,
    val igdbId: Long? = null,
    val sgdbId: Long? = null,
    val ra: RaInfo? = null,
    /**
     * De dónde salió cada imagen, por su clase ("cover", "hero", "logo",
     * "icon", "shot"): el servicio y la URL original. Permite volver a
     * descargarla sin preguntar a nadie y que Masha sepa qué falta y de dónde.
     */
    val artOrigins: Map<String, ArtOrigin> = emptyMap(),
    /** Cómo se identificó el juego (ver [MatchMethod]); null = sin identificar. */
    val matchedBy: String? = null,
    /** Confianza de esa identificación, 0…1. */
    val matchConfidence: Float? = null,
)

/** Origen de una imagen: [source] es el id del servicio (`ss`, `igdb`…) o `local` (galería). */
@Serializable
data class ArtOrigin(val source: String, val url: String? = null)

/** Cómo se reconoció un juego, de más a menos fiable. */
object MatchMethod {
    /** Hash del archivo (CRC/MD5/SHA-1 o rcheevos): es ese volcado y no otro. */
    const val HASH = "hash"
    /** Nombre casi idéntico al del servicio. */
    const val NAME = "name"
    /** Nombre parecido, por encima del umbral pero lejos de ser exacto. */
    const val FUZZY = "fuzzy"
    /** Elegido o corregido por el usuario. */
    const val MANUAL = "manual"
}

@Serializable
data class RaInfo(
    val gameId: Int,
    val title: String = "",
    val achievements: Int = 0,
    val earned: Int = 0,
    val earnedHardcore: Int = 0,
    val points: Int = 0,
    val earnedPoints: Int = 0,
    /** "hash" o "title": cómo se identificó el juego. */
    val matchedBy: String = "",
    val updatedAt: Long = 0,
)

/**
 * Una sesión de juego.
 *
 * Se mide con UsageStatsManager si el usuario lo ha permitido (tiempo real en
 * primer plano del emulador) y, si no, entre el lanzamiento y la vuelta a
 * Elyndra. Los campos nuevos tienen valor por omisión: las sesiones antiguas
 * solo sabían juego, inicio y minutos.
 */
@Serializable
data class PlaySession(
    val key: String,
    val start: Long,
    val minutes: Int,
    /** Emulador con el que se jugó; null en apps Android y en sesiones antiguas. */
    val emulatorId: String? = null,
    /** Fin de la sesión; 0 en las guardadas antes de medirlo. */
    val end: Long = 0,
    /** Cómo se midió: [SOURCE_USAGE] o [SOURCE_LIFECYCLE]. */
    val source: String = SOURCE_LIFECYCLE,
    /**
     * Se salió casi al entrar. Es la mejor pista que tiene un lanzador de que
     * algo fue mal —el emulador falló, rindió mal o no era el adecuado— o de
     * que el juego no enganchó. Pesa en la elección de emulador y en "abandonados".
     */
    val earlyExit: Boolean = false,
) {
    companion object {
        const val SOURCE_USAGE = "usage"
        const val SOURCE_LIFECYCLE = "lifecycle"
    }
}
