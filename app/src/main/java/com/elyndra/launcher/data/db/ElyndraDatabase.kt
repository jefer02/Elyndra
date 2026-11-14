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
        InstalledPortEntity::class,
        PortAvailabilityEntity::class,
        UserFolderEntity::class,
        UserFolderItemEntity::class,
    ],
    version = 10,
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
    abstract fun portDao(): PortDao

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

        /**
         * 4 → 5: qué fuentes respondieron ya con la descripción (para volver a
         * preguntar cuando aparece una nueva). Y las apps Android solo toman
         * la descripción de Google Play: la que les vino de IGDB o de Steam
         * (a veces de otro juego con el mismo nombre) se olvida y se pide a
         * Play. No se borra ninguna fila; las ROMs no se tocan.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `game_metadata` ADD COLUMN `description_sources` TEXT")
                db.execSQL(
                    "UPDATE `game_metadata` SET `description` = NULL, `description_lang` = NULL, `descriptions` = NULL, " +
                        "`description_checked_lang` = NULL WHERE `game_key` LIKE 'a:%' AND (`sources` IS NULL OR `sources` NOT LIKE '%gplay%')",
                )
            }
        }

        /**
         * 5 → 6: los ports instalados desde la tienda (`installed_ports`).
         * Solo una tabla nueva: nada de lo que había cambia.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `installed_ports` (`catalog_id` TEXT NOT NULL, `package_name` TEXT NOT NULL, " +
                        "`installed_tag` TEXT NOT NULL, `asset_name` TEXT, `installed_at` INTEGER NOT NULL, PRIMARY KEY(`catalog_id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_installed_ports_package_name` ON `installed_ports` (`package_name`)")
            }
        }

        /**
         * Tienda de ports, segunda versión: carpetas virtuales, la carpeta de
         * cada app (los ports van a la de su sistema) y el seguimiento de
         * actualizaciones. Todo columnas nuevas con valor por defecto: nada se
         * pierde ni se recrea.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `rom_folders` ADD COLUMN `is_virtual` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `android_apps` ADD COLUMN `folder_id` TEXT")
                db.execSQL("ALTER TABLE `android_apps` ADD COLUMN `folder_manual` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_android_apps_folder_id` ON `android_apps` (`folder_id`)")
                db.execSQL("ALTER TABLE `installed_ports` ADD COLUMN `system_id` TEXT")
                db.execSQL("ALTER TABLE `installed_ports` ADD COLUMN `latest_tag` TEXT")
                db.execSQL("ALTER TABLE `installed_ports` ADD COLUMN `latest_published_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `installed_ports` ADD COLUMN `checked_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `installed_ports` ADD COLUMN `notified_tag` TEXT")
                db.execSQL("ALTER TABLE `installed_ports` ADD COLUMN `blocked_tag` TEXT")
            }
        }

        /**
         * Tienda de ports, tercera versión: los ports "aún no en Android" a los
         * que la comprobación diaria les encuentra un APK. Una tabla nueva.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `port_availability` (`catalog_id` TEXT NOT NULL, `tag` TEXT NOT NULL, " +
                        "`asset_name` TEXT NOT NULL, `found_at` INTEGER NOT NULL, PRIMARY KEY(`catalog_id`))",
                )
            }
        }

        /** Carpetas creadas por el usuario: dos tablas nuevas y nada más cambia. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, " +
                        "`position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_folder_items` (`folder_id` TEXT NOT NULL, `game_key` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, PRIMARY KEY(`folder_id`, `game_key`), " +
                        "FOREIGN KEY(`folder_id`) REFERENCES `user_folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_folder_items_game_key` ON `user_folder_items` (`game_key`)")
            }
        }

        /** Conversación en vivo: una respuesta de Lucy cortada por el usuario se marca (una columna nueva). */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `masha_messages` ADD COLUMN `interrupted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
    }
}
