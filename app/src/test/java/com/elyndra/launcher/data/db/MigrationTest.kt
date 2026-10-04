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
            val upper = s.uppercase()
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
