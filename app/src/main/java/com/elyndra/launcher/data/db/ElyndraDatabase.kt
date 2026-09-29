package com.elyndra.launcher.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base de datos de Elyndra (files/../databases/elyndra.db).
 *
 * Sin `fallbackToDestructiveMigration`: un cambio de esquema sin migración
 * tiene que fallar en desarrollo, no borrar en silencio la biblioteca de
 * nadie. Cada versión deja su esquema en app/schemas para escribir y probar
 * la migración correspondiente.
 */
@Database(
    entities = [
        FolderEntity::class,
        FolderExclusionEntity::class,
        RomEntity::class,
        AppEntity::class,
        GameMetadataEntity::class,
        ArtworkEntity::class,
        PlayStatsEntity::class,
        PlaySessionEntity::class,
        LibraryStateEntity::class,
        InstalledEmulatorEntity::class,
        LaunchEventEntity::class,
        MashaMemoryEntity::class,
        MashaMessageEntity::class,
        SmartListEntity::class,
        SmartListItemEntity::class,
        ArcEntity::class,
        ArcStepEntity::class,
        AiCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ElyndraDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun emulatorDao(): EmulatorDao
    abstract fun mashaDao(): MashaDao
    abstract fun smartListDao(): SmartListDao
    abstract fun arcDao(): ArcDao
    abstract fun aiCacheDao(): AiCacheDao

    companion object {
        const val NAME = "elyndra.db"
    }
}
