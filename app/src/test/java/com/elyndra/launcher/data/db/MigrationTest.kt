package com.elyndra.launcher.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * Las migraciones, contra los esquemas exportados en app/schemas: el esquema
 * de la versión N más lo que ejecuta la migración N → N+1 tiene que dar
 * exactamente el de N+1 (si no, Room tiraría la app al abrir la base). Y
 * ninguna migración borra nada.
 */
class MigrationTest {

    private val dir = File("schemas/com.elyndra.launcher.data.db.ElyndraDatabase")

    private fun schema(version: Int): Map<String, JsonObject> {
        val root = Json.parseToJsonElement(File(dir, "$version.json").readText()).jsonObject
        return root["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject }
            .associateBy { it["tableName"]!!.jsonPrimitive.content }
    }

    private fun columns(table: JsonObject): Set<String> =
        table["fields"]!!.jsonArray.map { it.jsonObject["columnName"]!!.jsonPrimitive.content }.toSet()

    /** Lo que ejecuta una migración, sin base de datos de verdad. */
    private fun sqlOf(migration: androidx.room.migration.Migration): List<String> {
        val sql = ArrayList<String>()
        val db = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
            if (method.name == "execSQL") sql += args!![0] as String
            null
        } as SupportSQLiteDatabase
        migration.migrate(db)
        return sql
    }

    private fun addedColumns(sql: List<String>, table: String): Set<String> =
        sql.mapNotNull { Regex("ALTER TABLE `$table` ADD COLUMN `([a-z_]+)`").find(it)?.groupValues?.get(1) }.toSet()

    @Test
    fun migration2to3MatchesTheExportedSchema() {
        val v2 = schema(2)
        val v3 = schema(3)
        val sql = sqlOf(ElyndraDatabase.MIGRATION_2_3)

        // game_metadata: las tres columnas nuevas, y nada más.
        val added = addedColumns(sql, "game_metadata")
        assertEquals(setOf("description_lang", "descriptions", "description_checked_lang", "steam_app_id"), added)
        assertEquals(columns(v3.getValue("game_metadata")), columns(v2.getValue("game_metadata")) + added)

        // La tabla nueva se crea igual que la declara Room.
        val expected = v3.getValue("description_translations")["createSql"]!!.jsonPrimitive.content
            .replace("\${TABLE_NAME}", "description_translations")
        assertTrue(sql.any { it == expected })

        // El resto de tablas no cambia.
        assertEquals(v2.keys + "description_translations", v3.keys)
        for (name in v2.keys - "game_metadata") assertEquals(name, columns(v2.getValue(name)), columns(v3.getValue(name)))
    }

    @Test
    fun migration3to4MatchesTheExportedSchema() {
        val v3 = schema(3)
        val v4 = schema(4)
        val added = addedColumns(sqlOf(ElyndraDatabase.MIGRATION_3_4), "game_metadata")
        assertEquals(setOf("user_name", "name_locked", "libretro_name"), added)
        assertEquals(columns(v4.getValue("game_metadata")), columns(v3.getValue("game_metadata")) + added)
        assertEquals(v3.keys, v4.keys)
        // name_locked: NOT NULL con valor por defecto, como lo declara Room.
        val locked = v4.getValue("game_metadata")["fields"]!!.jsonArray.map { it.jsonObject }
            .first { it["columnName"]!!.jsonPrimitive.content == "name_locked" }
        assertEquals("0", locked["defaultValue"]!!.jsonPrimitive.content)
        assertTrue(locked["notNull"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun migration4to5MatchesTheExportedSchema() {
        val v4 = schema(4)
        val v5 = schema(5)
        val sql = sqlOf(ElyndraDatabase.MIGRATION_4_5)
        val added = addedColumns(sql, "game_metadata")
        assertEquals(setOf("description_sources"), added)
        assertEquals(columns(v5.getValue("game_metadata")), columns(v4.getValue("game_metadata")) + added)
        // Las traducciones se quedan; ninguna tabla aparece ni desaparece.
        assertEquals(v4.keys, v5.keys)
        assertTrue("description_translations" in v5.keys)
    }

    @Test
    fun migration5to6MatchesTheExportedSchema() {
        val v5 = schema(5)
        val v6 = schema(6)
        val sql = sqlOf(ElyndraDatabase.MIGRATION_5_6)
        // Una tabla nueva y su índice, creados igual que los declara Room; nada más cambia.
        val table = v6.getValue("installed_ports")
        val create = table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "installed_ports")
        assertTrue(sql.contains(create))
        table["indices"]!!.jsonArray.forEach { index ->
            val createIndex = index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "installed_ports")
            assertTrue(createIndex, sql.contains(createIndex))
        }
        assertEquals(2, sql.size)
        assertEquals(v5.keys + "installed_ports", v6.keys)
        for (name in v5.keys) assertEquals(name, columns(v5.getValue(name)), columns(v6.getValue(name)))
        assertEquals(setOf("catalog_id", "package_name", "installed_tag", "asset_name", "installed_at"), columns(table))
    }

    @Test
    fun migration6to7MatchesTheExportedSchema() {
        val v6 = schema(6)
        val v7 = schema(7)
        val sql = sqlOf(ElyndraDatabase.MIGRATION_6_7)
        val expected = mapOf(
            "rom_folders" to setOf("is_virtual"),
            "android_apps" to setOf("folder_id", "folder_manual"),
            "installed_ports" to setOf("system_id", "latest_tag", "latest_published_at", "checked_at", "notified_tag", "blocked_tag"),
        )
        for ((table, cols) in expected) {
            assertEquals(table, cols, addedColumns(sql, table))
            assertEquals(table, columns(v7.getValue(table)), columns(v6.getValue(table)) + cols)
        }
        // Las NOT NULL nuevas llevan el mismo valor por defecto que declara Room (las filas que ya hay lo toman).
        for ((table, col) in listOf("rom_folders" to "is_virtual", "android_apps" to "folder_manual", "installed_ports" to "checked_at")) {
            val field = v7.getValue(table)["fields"]!!.jsonArray.map { it.jsonObject }.first { it["columnName"]!!.jsonPrimitive.content == col }
            assertEquals("$table.$col", "0", field["defaultValue"]!!.jsonPrimitive.content)
            assertTrue(sql.any { it.contains("`$col` INTEGER NOT NULL DEFAULT 0") })
        }
        // El índice de la carpeta de cada app, igual que lo crea Room; sin clave ajena (borrar una carpeta no borra apps).
        val apps = v7.getValue("android_apps")
        val index = apps["indices"]!!.jsonArray.single().jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "android_apps")
        assertTrue(index, sql.contains(index))
        assertTrue(apps["foreignKeys"]?.jsonArray.isNullOrEmpty())
        // Ninguna tabla aparece ni desaparece; las demás no cambian.
        assertEquals(v6.keys, v7.keys)
        for (name in v6.keys - expected.keys) assertEquals(name, columns(v6.getValue(name)), columns(v7.getValue(name)))
    }

    @Test
    fun migration7to8AddsOnlyThePortAvailabilityTable() {
        val v7 = schema(7)
        val v8 = schema(8)
        val sql = sqlOf(ElyndraDatabase.MIGRATION_7_8)
        val table = v8.getValue("port_availability")
        val create = table["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "port_availability")
        assertTrue(create, sql.contains(create))
        assertEquals(1, sql.size)
        assertEquals(v7.keys + "port_availability", v8.keys)
        for (name in v7.keys) assertEquals(name, columns(v7.getValue(name)), columns(v8.getValue(name)))
        assertEquals(setOf("catalog_id", "tag", "asset_name", "found_at"), columns(table))
    }

    @Test
    fun migration8to9AddsOnlyTheUserFolderTables() {
        val v8 = schema(8)
        val v9 = schema(9)
        val sql = sqlOf(ElyndraDatabase.MIGRATION_8_9)
        val added = setOf("user_folders", "user_folder_items")
        for (name in added) {
            val create = v9.getValue(name)["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name)
            assertTrue(create, sql.contains(create))
        }
        val items = v9.getValue("user_folder_items")
        val index = items["indices"]!!.jsonArray.single().jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "user_folder_items")
        assertTrue(index, sql.contains(index))
        assertEquals(3, sql.size)
        assertEquals(v8.keys + added, v9.keys)
        for (name in v8.keys) assertEquals(name, columns(v8.getValue(name)), columns(v9.getValue(name)))
        assertEquals(setOf("id", "name", "description", "position", "created_at"), columns(v9.getValue("user_folders")))
        assertEquals(setOf("folder_id", "game_key", "position"), columns(items))
    }

    @Test
    fun migration9to10AddsOnlyTheInterruptedFlagToLucysMessages() {
        val v9 = schema(9)
        val v10 = schema(10)
        val sql = sqlOf(ElyndraDatabase.MIGRATION_9_10)
        assertEquals(1, sql.size)
        assertEquals(setOf("interrupted"), addedColumns(sql, "masha_messages"))
        assertEquals(v9.keys, v10.keys)
        assertEquals(columns(v9.getValue("masha_messages")) + "interrupted", columns(v10.getValue("masha_messages")))
        for (name in v9.keys - "masha_messages") assertEquals(name, columns(v9.getValue(name)), columns(v10.getValue(name)))
        // Como lo declara la entidad: entero, no nulo, 0 por defecto (los mensajes viejos no estaban cortados).
        val field = v10.getValue("masha_messages")["fields"]!!.jsonArray.map { it.jsonObject }.single { it["columnName"]!!.jsonPrimitive.content == "interrupted" }
        assertEquals("INTEGER", field["affinity"]!!.jsonPrimitive.content)
        assertEquals("0", field["defaultValue"]!!.jsonPrimitive.content)
        assertTrue(sql.single().contains("NOT NULL DEFAULT 0"))
        assertEquals(10, ElyndraDatabase.MIGRATIONS.last().endVersion)
    }

    @Test
    fun portPlacementRoundTripsThroughTheMapper() {
        val folder = com.elyndra.launcher.data.RomFolder("v-switch", "switch", "", "", "", isVirtual = true)
        assertTrue(LibraryMapper.folder(folder).isVirtual)
        val app = com.elyndra.launcher.data.AppEntry("com.port", "Port", folderId = "v-switch", folderManual = true)
        val row = LibraryMapper.app(app)
        assertEquals("v-switch", row.folderId)
        assertTrue(row.folderManual)
    }

    @Test
    fun androidDescriptionsNotFromPlayAreForgottenAndRomsAreUntouched() {
        val update = sqlOf(ElyndraDatabase.MIGRATION_4_5).single { it.startsWith("UPDATE") }
        assertTrue(update.contains("`game_key` LIKE 'a:%'"))
        assertTrue(update.contains("NOT LIKE '%gplay%'"))
        // Solo se vacían las columnas de descripción: la fila (y lo demás) se queda.
        assertTrue(update.contains("`descriptions` = NULL"))
        assertTrue("DELETE" !in update.uppercase())
    }

    @Test
    fun migrationsNeverDropOrDelete() {
        for (m in ElyndraDatabase.MIGRATIONS) for (s in sqlOf(m)) {
            // Una clave ajena en cascada declara una regla: no borra nada al migrar.
            val upper = s.uppercase().replace("ON DELETE CASCADE", "")
            assertTrue(s, "DROP " !in upper && "DELETE " !in upper)
        }
    }

    @Test
    fun legacyAppDescriptionsAreTaggedEnglish() {
        val sql = sqlOf(ElyndraDatabase.MIGRATION_2_3)
        assertTrue(sql.any { it.contains("SET `description_lang` = 'en'") && it.contains("'a:%'") })
    }

    @Test
    fun descriptionsRoundTripThroughTheMapper() {
        val map = mapOf("es" to "Hola \"mundo\"", "en" to "Hi")
        assertEquals(map, LibraryMapper.decodeDescriptions(LibraryMapper.encodeDescriptions(map)))
        assertEquals(emptyMap<String, String>(), LibraryMapper.decodeDescriptions("{roto"))
        assertEquals(null, LibraryMapper.encodeDescriptions(emptyMap()))
    }

    @Test
    fun descriptionSourcesRoundTripThroughTheMapper() {
        fun roundTrip(m: com.elyndra.launcher.data.GameMeta) =
            LibraryMapper.gameMeta(LibraryMapper.metadata("r:1", m), emptyList()).descriptionSources
        val base = com.elyndra.launcher.data.GameMeta(scrapedAt = 1, name = "X")
        assertEquals(setOf("gplay", "steam"), roundTrip(base.copy(descriptionSources = setOf("steam", "gplay"))))
        // "Ya preguntado y nadie respondió" no es lo mismo que "sin saber".
        assertEquals(emptySet<String>(), roundTrip(base.copy(descriptionSources = emptySet())))
        assertEquals(null, roundTrip(base.copy(descriptionSources = null)))
    }
}
