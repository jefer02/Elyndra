package com.elyndra.launcher.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/* ─────────────────────────────────────────────────────────────
   Tablas de la biblioteca.

   Un juego se identifica en toda la app por su "clave de juego":
   "r:<id de ROM>" o "a:<paquete>". Metadatos, imágenes, tiempo y
   sesiones cuelgan de esa clave y no de una clave foránea, porque
   un juego puede ser de dos tablas distintas (ROM o app Android).
   El borrado en cascada de esas filas lo hace LibraryDiff: quitar
   un juego de la instantánea borra también todo lo suyo.
   ───────────────────────────────────────────────────────────── */

/** Carpeta de ROMs: un árbol SAF concedido + la subcarpeta raíz de un sistema. */
@Entity(tableName = "rom_folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "system_id") val systemId: String,
    @ColumnInfo(name = "tree_uri") val treeUri: String,
    @ColumnInfo(name = "root_doc_id") val rootDocId: String,
    @ColumnInfo(name = "display_path") val displayPath: String,
    @ColumnInfo(name = "emulator_id") val emulatorId: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "last_scan") val lastScan: Long,
    val cover: String?,
    val hero: String?,
    val logo: String?,
    val icon: String?,
)

/** Juego quitado a mano de una carpeta: el siguiente análisis no lo devuelve. */
@Entity(
    tableName = "folder_exclusions",
    primaryKeys = ["folder_id", "doc_id"],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FolderExclusionEntity(
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "doc_id") val docId: String,
)

/**
 * Una ROM (o un juego-carpeta) encontrada al analizar una carpeta.
 *
 * Los hashes van en columnas y no en una tabla aparte: se calculan una vez por
 * archivo y valen mientras no cambien [size]/[modified] del propio archivo
 * (`hash_size` y `hash_modified` guardan con qué versión se calcularon).
 */
@Entity(
    tableName = "roms",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("folder_id"), Index("system_id"), Index("md5"), Index("crc")],
)
data class RomEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "folder_id") val folderId: String,
    @ColumnInfo(name = "system_id") val systemId: String,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "rel_path") val relPath: String,
    val size: Long,
    val modified: Long,
    @ColumnInfo(name = "is_directory") val isDirectory: Boolean,
    val title: String,
    @ColumnInfo(name = "emulator_id") val emulatorId: String?,
    @ColumnInfo(name = "main_doc_id") val mainDocId: String?,
    @ColumnInfo(name = "main_file") val mainFile: String?,
    @ColumnInfo(name = "pc_game_id") val pcGameId: String?,
    @ColumnInfo(name = "hash_size") val hashSize: Long?,
    @ColumnInfo(name = "hash_modified") val hashModified: Long?,
    val crc: String?,
    val md5: String?,
    val sha1: String?,
    @ColumnInfo(name = "ra_hash") val raHash: String?,
    @ColumnInfo(name = "ra_computed") val raComputed: Boolean,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** Juego Android elegido para la biblioteca. */
@Entity(tableName = "android_apps")
data class AppEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    val label: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/**
 * Metadatos de texto de un juego y cómo se identificó.
 *
 * Solo hay fila para los juegos que alguna vez pasaron por el motor de
 * metadatos: la ausencia de fila es "nunca consultado".
 */
@Entity(tableName = "game_metadata")
data class GameMetadataEntity(
    @PrimaryKey @ColumnInfo(name = "game_key") val gameKey: String,
    val name: String?,
    val description: String?,
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    val players: String?,
    val rating: Float?,
    @ColumnInfo(name = "scraped_at") val scrapedAt: Long,
    val matched: Boolean,
    /** Ids de los servicios que reconocieron el juego, separados por comas. */
    val sources: String,
    @ColumnInfo(name = "matched_by") val matchedBy: String?,
    @ColumnInfo(name = "match_confidence") val matchConfidence: Float?,
    @ColumnInfo(name = "ss_game_id") val ssGameId: String?,
    @ColumnInfo(name = "igdb_id") val igdbId: Long?,
    @ColumnInfo(name = "sgdb_id") val sgdbId: Long?,
    @ColumnInfo(name = "ra_game_id") val raGameId: Int?,
    @ColumnInfo(name = "ra_title") val raTitle: String?,
    @ColumnInfo(name = "ra_achievements") val raAchievements: Int?,
    @ColumnInfo(name = "ra_earned") val raEarned: Int?,
    @ColumnInfo(name = "ra_earned_hardcore") val raEarnedHardcore: Int?,
    @ColumnInfo(name = "ra_points") val raPoints: Int?,
    @ColumnInfo(name = "ra_earned_points") val raEarnedPoints: Int?,
    @ColumnInfo(name = "ra_matched_by") val raMatchedBy: String?,
    @ColumnInfo(name = "ra_updated_at") val raUpdatedAt: Long?,
)

/**
 * Una imagen de un juego: carátula, fondo, logo, icono o captura.
 *
 * [kind] es el nombre de archivo de MediaCache ("cover", "hero", "logo",
 * "icon", "shot"). [localPath] es relativa a filesDir y puede ser null en una
 * imagen fijada a mano cuyo archivo se borró (Ajustes → "Borrar imágenes"):
 * la marca sigue ahí para que "Actualizar metadatos" no la sustituya.
 */
@Entity(
    tableName = "artwork",
    primaryKeys = ["game_key", "kind"],
    indices = [Index("source")],
)
data class ArtworkEntity(
    @ColumnInfo(name = "game_key") val gameKey: String,
    val kind: String,
    @ColumnInfo(name = "local_path") val localPath: String?,
    /** Servicio de origen (`ss`, `igdb`, `sgdb`, `ra`) o `local` si vino de la galería. */
    val source: String?,
    @ColumnInfo(name = "remote_url") val remoteUrl: String?,
    /** Elegida a mano: el motor de metadatos no la pisa. */
    val pinned: Boolean,
)

/** Contadores de juego por juego (lo que se enseña sin recorrer las sesiones). */
@Entity(tableName = "play_stats")
data class PlayStatsEntity(
    @PrimaryKey @ColumnInfo(name = "game_key") val gameKey: String,
    val minutes: Int,
    @ColumnInfo(name = "last_played") val lastPlayed: Long,
    val launches: Int,
)

/** Una sesión de juego (ver PlaySession). */
@Entity(
    tableName = "play_sessions",
    primaryKeys = ["game_key", "started_at"],
    indices = [Index("started_at"), Index("emulator_id")],
)
data class PlaySessionEntity(
    @ColumnInfo(name = "game_key") val gameKey: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    val minutes: Int,
    @ColumnInfo(name = "emulator_id") val emulatorId: String?,
    val source: String,
    @ColumnInfo(name = "early_exit") val earlyExit: Boolean,
)

/** Estado suelto de la biblioteca: una sola fila (id 0). */
@Entity(tableName = "library_state")
data class LibraryStateEntity(
    @PrimaryKey val id: Int = 0,
    @ColumnInfo(name = "last_auto_scan") val lastAutoScan: Long,
    /** Cuándo se importó el antiguo library.json (0 = no había). */
    @ColumnInfo(name = "imported_at") val importedAt: Long,
)
