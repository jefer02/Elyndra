package com.elyndra.launcher.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * La biblioteca se lee entera al arrancar (ver RoomLibraryStore) y se escribe
 * por diferencias. Los borrados van por entidad y no con `IN (:ids)`: Room los
 * ejecuta fila a fila por clave primaria, así que quitar una carpeta con miles
 * de ROMs no choca con el límite de 999 variables de SQLite.
 */
@Dao
interface LibraryDao {

    @Query("SELECT * FROM rom_folders")
    suspend fun folders(): List<FolderEntity>

    @Query("SELECT * FROM folder_exclusions")
    suspend fun exclusions(): List<FolderExclusionEntity>

    @Query("SELECT * FROM roms")
    suspend fun roms(): List<RomEntity>

    @Query("SELECT * FROM android_apps")
    suspend fun apps(): List<AppEntity>

    @Query("SELECT * FROM game_metadata")
    suspend fun metadata(): List<GameMetadataEntity>

    @Query("SELECT * FROM artwork")
    suspend fun artwork(): List<ArtworkEntity>

    @Query("SELECT * FROM play_stats")
    suspend fun stats(): List<PlayStatsEntity>

    @Query("SELECT * FROM play_sessions ORDER BY started_at")
    suspend fun sessions(): List<PlaySessionEntity>

    @Query("SELECT * FROM library_state WHERE id = 0")
    suspend fun state(): LibraryStateEntity?

    @Upsert suspend fun upsertFolders(items: List<FolderEntity>)
    @Delete suspend fun deleteFolders(items: List<FolderEntity>)

    @Upsert suspend fun upsertExclusions(items: List<FolderExclusionEntity>)
    @Delete suspend fun deleteExclusions(items: List<FolderExclusionEntity>)

    @Upsert suspend fun upsertRoms(items: List<RomEntity>)
    @Delete suspend fun deleteRoms(items: List<RomEntity>)

    @Upsert suspend fun upsertApps(items: List<AppEntity>)
    @Delete suspend fun deleteApps(items: List<AppEntity>)

    @Upsert suspend fun upsertMetadata(items: List<GameMetadataEntity>)
    @Delete suspend fun deleteMetadata(items: List<GameMetadataEntity>)

    @Upsert suspend fun upsertArtwork(items: List<ArtworkEntity>)
    @Delete suspend fun deleteArtwork(items: List<ArtworkEntity>)

    @Upsert suspend fun upsertStats(items: List<PlayStatsEntity>)
    @Delete suspend fun deleteStats(items: List<PlayStatsEntity>)

    @Upsert suspend fun upsertSessions(items: List<PlaySessionEntity>)
    @Delete suspend fun deleteSessions(items: List<PlaySessionEntity>)

    @Upsert suspend fun upsertState(state: LibraryStateEntity)

    @Query("UPDATE library_state SET last_auto_scan = :value WHERE id = 0")
    suspend fun setLastAutoScan(value: Long)

    /* ── consultas para Masha (no pasan por la instantánea) ────── */

    /** Juegos con carátula (o icono, las apps) frente a los que no la tienen. */
    @Query("SELECT COUNT(DISTINCT game_key) FROM artwork WHERE kind IN ('cover', 'icon') AND local_path IS NOT NULL")
    suspend fun gamesWithPrimaryArt(): Int

    /** Imágenes por servicio de origen, para contar de dónde sale el arte. */
    @Query("SELECT source, COUNT(*) AS count FROM artwork WHERE local_path IS NOT NULL GROUP BY source")
    suspend fun artworkBySource(): List<SourceCount>
}

data class SourceCount(val source: String?, val count: Int)

@Dao
interface EmulatorDao {

    @Query("SELECT * FROM installed_emulators")
    suspend fun installed(): List<InstalledEmulatorEntity>

    @Query("SELECT * FROM installed_emulators")
    fun observeInstalled(): Flow<List<InstalledEmulatorEntity>>

    @Upsert suspend fun upsertInstalled(items: List<InstalledEmulatorEntity>)

    @Query("DELETE FROM installed_emulators WHERE last_seen < :before")
    suspend fun forgetUninstalled(before: Long)

    @Insert
    suspend fun insertLaunch(event: LaunchEventEntity): Long

    @Query("SELECT * FROM launch_events WHERE game_key = :gameKey ORDER BY at DESC LIMIT :limit")
    suspend fun launchesFor(gameKey: String, limit: Int = 200): List<LaunchEventEntity>

    @Query("SELECT * FROM launch_events WHERE system_id = :systemId ORDER BY at DESC LIMIT :limit")
    suspend fun launchesForSystem(systemId: String, limit: Int = 500): List<LaunchEventEntity>

    @Query("SELECT * FROM launch_events ORDER BY at DESC LIMIT :limit")
    suspend fun recentLaunches(limit: Int): List<LaunchEventEntity>

    @Query("DELETE FROM launch_events WHERE game_key = :gameKey")
    suspend fun deleteLaunchesFor(gameKey: String)
}

@Dao
interface MashaDao {

    @Query("SELECT * FROM masha_memories WHERE expires_at IS NULL OR expires_at > :now ORDER BY weight DESC, updated_at DESC LIMIT :limit")
    suspend fun memories(now: Long, limit: Int): List<MashaMemoryEntity>

    @Query("SELECT * FROM masha_memories WHERE subject = :subject AND (expires_at IS NULL OR expires_at > :now) ORDER BY updated_at DESC")
    suspend fun memoriesAbout(subject: String, now: Long): List<MashaMemoryEntity>

    @Query("SELECT * FROM masha_memories WHERE content = :content LIMIT 1")
    suspend fun memoryWithContent(content: String): MashaMemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MashaMemoryEntity): Long

    @Upsert
    suspend fun upsertMemory(memory: MashaMemoryEntity)

    @Query("DELETE FROM masha_memories WHERE id = :id")
    suspend fun deleteMemory(id: Long)

    @Query("DELETE FROM masha_memories")
    suspend fun clearMemories()

    @Query("SELECT * FROM (SELECT * FROM masha_messages ORDER BY created_at DESC, id DESC LIMIT :limit) ORDER BY created_at, id")
    suspend fun recentMessages(limit: Int): List<MashaMessageEntity>

    @Insert
    suspend fun insertMessage(message: MashaMessageEntity): Long

    /** Deja solo los [keep] mensajes más recientes. */
    @Query("DELETE FROM masha_messages WHERE id NOT IN (SELECT id FROM masha_messages ORDER BY created_at DESC, id DESC LIMIT :keep)")
    suspend fun trimMessages(keep: Int)

    @Query("DELETE FROM masha_messages")
    suspend fun clearMessages()
}

@Dao
interface SmartListDao {

    @Query("SELECT * FROM smart_lists ORDER BY pinned DESC, created_at DESC")
    suspend fun lists(): List<SmartListEntity>

    @Query("SELECT * FROM smart_lists ORDER BY pinned DESC, created_at DESC")
    fun observeLists(): Flow<List<SmartListEntity>>

    @Query("SELECT * FROM smart_list_items WHERE list_id = :listId ORDER BY position")
    suspend fun items(listId: String): List<SmartListItemEntity>

    @Upsert suspend fun upsertList(list: SmartListEntity)

    @Upsert suspend fun upsertItems(items: List<SmartListItemEntity>)

    @Query("DELETE FROM smart_list_items WHERE list_id = :listId")
    suspend fun clearItems(listId: String)

    @Query("DELETE FROM smart_lists WHERE id = :listId")
    suspend fun deleteList(listId: String)
}

@Dao
interface ArcDao {

    @Query("SELECT * FROM arcs WHERE status = :status ORDER BY updated_at DESC")
    suspend fun arcs(status: String): List<ArcEntity>

    @Query("SELECT * FROM arcs ORDER BY updated_at DESC")
    fun observeArcs(): Flow<List<ArcEntity>>

    @Query("SELECT * FROM arc_steps WHERE arc_id = :arcId ORDER BY position")
    suspend fun steps(arcId: String): List<ArcStepEntity>

    @Upsert suspend fun upsertArc(arc: ArcEntity)

    @Upsert suspend fun upsertSteps(steps: List<ArcStepEntity>)

    @Query("UPDATE arc_steps SET completed_at = :at WHERE arc_id = :arcId AND position = :position")
    suspend fun completeStep(arcId: String, position: Int, at: Long)

    @Query("UPDATE arcs SET status = :status, updated_at = :at WHERE id = :arcId")
    suspend fun setStatus(arcId: String, status: String, at: Long)

    @Query("DELETE FROM arcs WHERE id = :arcId")
    suspend fun deleteArc(arcId: String)
}

@Dao
interface AiCacheDao {

    @Query("SELECT * FROM ai_cache WHERE cache_key = :key AND expires_at > :now")
    suspend fun get(key: String, now: Long): AiCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: AiCacheEntity)

    @Query("DELETE FROM ai_cache WHERE expires_at <= :now")
    suspend fun evictExpired(now: Long)

    @Query("DELETE FROM ai_cache")
    suspend fun clear()
}
