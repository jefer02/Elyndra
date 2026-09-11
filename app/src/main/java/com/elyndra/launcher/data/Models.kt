package com.elyndra.launcher.data

import kotlinx.serialization.Serializable

/* ─────────────────────────────────────────────────────────────
   Modelo persistido de la biblioteca (files/library.json).

   Todo lo que el usuario añade vive aquí: carpetas de ROMs (una por
   sistema y emulador), las ROMs encontradas en cada carpeta, los
   juegos Android elegidos, los metadatos descargados y el tiempo de
   juego. Las rutas de imágenes son relativas a filesDir.
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
    val ssGameId: String? = null,
    val igdbId: Long? = null,
    val sgdbId: Long? = null,
    val ra: RaInfo? = null,
)

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

/** Una sesión medida entre el lanzamiento y la vuelta a Elyndra. */
@Serializable
data class PlaySession(val key: String, val start: Long, val minutes: Int)
