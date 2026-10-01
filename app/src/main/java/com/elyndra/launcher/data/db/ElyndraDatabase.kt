package com.elyndra.launcher.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        DescriptionTranslationEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ElyndraDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun emulatorDao(): EmulatorDao
    abstract fun mashaDao(): MashaDao
    abstract fun smartListDao(): SmartListDao
    abstract fun arcDao(): ArcDao
    abstract fun aiCacheDao(): AiCacheDao
    abstract fun translationDao(): TranslationDao

    companion object {
        const val NAME = "elyndra.db"

        /** 1 → 2: el TITLE_ID de los juegos de PS4 (RomEntry.serial). Nada se borra. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `roms` ADD COLUMN `serial` TEXT")
            }
        }

        /**
         * 2 → 3: el appid de Steam (fuente sin clave), el idioma de cada descripción, todas las descripciones por
         * idioma y las traducciones hechas en el dispositivo. Las
         * descripciones que ya había se quedan tal cual, con idioma desconocido
         * (NULL): la próxima pasada las etiqueta. Nada se borra.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `game_metadata` ADD COLUMN `description_lang` TEXT")
                db.execSQL("ALTER TABLE `game_metadata` ADD COLUMN `descriptions` TEXT")
                db.execSQL("ALTER TABLE `game_metadata` ADD COLUMN `description_checked_lang` TEXT")
                db.execSQL("ALTER TABLE `game_metadata` ADD COLUMN `steam_app_id` INTEGER")
                // Las apps Android solo recibían descripción de IGDB, que solo la da en inglés.
                db.execSQL("UPDATE `game_metadata` SET `description_lang` = 'en' WHERE `game_key` LIKE 'a:%' AND `description` IS NOT NULL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `description_translations` (" +
                        "`game_key` TEXT NOT NULL, `target_lang` TEXT NOT NULL, `source_lang` TEXT NOT NULL, " +
                        "`source_hash` TEXT NOT NULL, `text` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`game_key`, `target_lang`))",
                )
            }
        }

        /**
         * 3 → 4: el nombre puesto a mano (y si está fijado) y el nombre exacto
         * de libretro elegido a mano. Nada se borra.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `game_metadata` ADD COLUMN `user_name` TEXT")
                db.execSQL("ALTER TABLE `game_metadata` ADD COLUMN `name_locked` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `game_metadata` ADD COLUMN `libretro_name` TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
