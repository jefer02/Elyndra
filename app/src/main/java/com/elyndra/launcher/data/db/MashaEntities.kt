package com.elyndra.launcher.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/* ─────────────────────────────────────────────────────────────
   Tablas de Masha y del orquestador de lanzamientos.

   Aquí vive lo que Elyndra aprende de *este* dispositivo y de *este*
   usuario: qué emuladores hay instalados, cómo acabó cada
   lanzamiento, qué recuerda Masha, las listas y los arcos que ha
   creado y las respuestas de la IA que se pueden reutilizar.
   ───────────────────────────────────────────────────────────── */

/**
 * Un emulador instalado en el dispositivo (un paquete que casa con algún
 * perfil de Emulators.kt). Se refresca al volver a la app; [lastSeen] viejo
 * quiere decir que se desinstaló.
 */
@Entity(tableName = "installed_emulators")
data class InstalledEmulatorEntity(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    val label: String,
    @ColumnInfo(name = "version_name") val versionName: String?,
    @ColumnInfo(name = "version_code") val versionCode: Long,
    /** Perfiles de Emulators.kt que usa este paquete, separados por comas. */
    @ColumnInfo(name = "profile_ids") val profileIds: String,
    @ColumnInfo(name = "first_seen") val firstSeen: Long,
    @ColumnInfo(name = "last_seen") val lastSeen: Long,
)

/**
 * Un intento de lanzamiento y cómo acabó.
 *
 * Junto con las sesiones, es lo que le permite al orquestador saber qué
 * emulador funciona mejor para cada juego en este dispositivo: un emulador
 * que falla al arrancar o del que se sale al minuto pierde puntos.
 */
@Entity(
    tableName = "launch_events",
    indices = [Index("game_key"), Index("emulator_id"), Index("at")],
)
data class LaunchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "game_key") val gameKey: String,
    @ColumnInfo(name = "system_id") val systemId: String?,
    @ColumnInfo(name = "emulator_id") val emulatorId: String?,
    @ColumnInfo(name = "package_name") val packageName: String?,
    val at: Long,
    /** Ver LaunchOutcome. */
    val outcome: String,
    @ColumnInfo(name = "battery_pct") val batteryPct: Int?,
    /** Estado térmico de PowerManager (0 = normal … 6 = apagado). */
    val thermal: Int?,
)

/**
 * Algo que Masha recuerda: una preferencia que el usuario dijo ("odio el
 * farmeo"), un dato ("juega en la tele los findes") o una observación suya.
 * [subject] es la clave del juego al que se refiere, si se refiere a uno.
 */
@Entity(tableName = "masha_memories", indices = [Index("subject"), Index("kind")])
data class MashaMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val subject: String?,
    val content: String,
    /** Importancia 0…1: decide qué recuerdos entran en el contexto si no caben todos. */
    val weight: Float,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** Caducidad (epoch ms); null = no caduca. */
    @ColumnInfo(name = "expires_at") val expiresAt: Long?,
)

/** Un mensaje de la conversación con Masha, para retomar el hilo al volver. */
@Entity(tableName = "masha_messages", indices = [Index("created_at")])
data class MashaMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "user" o "masha". */
    val role: String,
    val text: String,
    @ColumnInfo(name = "game_key") val gameKey: String?,
    /** Adjunto del mensaje (lista de juegos, plan de sesión…) en JSON. */
    val attachment: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/**
 * Lista inteligente guardada. [rule] es la regla en JSON (ver SmartListRule):
 * las dinámicas se recalculan cada vez; las manuales guardan sus juegos en
 * [SmartListItemEntity].
 */
@Entity(tableName = "smart_lists")
data class SmartListEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rule: String,
    /** "user", "masha" o "system". */
    @ColumnInfo(name = "created_by") val createdBy: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val pinned: Boolean,
)

@Entity(
    tableName = "smart_list_items",
    primaryKeys = ["list_id", "game_key"],
    foreignKeys = [
        ForeignKey(
            entity = SmartListEntity::class,
            parentColumns = ["id"],
            childColumns = ["list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SmartListItemEntity(
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "game_key") val gameKey: String,
    val position: Int,
)

/** Un arco: una serie de sesiones con hilo temático ("la saga Castlevania en orden"). */
@Entity(tableName = "arcs", indices = [Index("status")])
data class ArcEntity(
    @PrimaryKey val id: String,
    val title: String,
    val theme: String?,
    val description: String?,
    /** "active", "completed" o "abandoned". */
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Un paso del arco: un juego y cuánto jugarlo. El progreso se mide contra
 * los minutos que el juego ya tenía al crear el arco ([baselineMinutes]).
 */
@Entity(
    tableName = "arc_steps",
    primaryKeys = ["arc_id", "position"],
    foreignKeys = [
        ForeignKey(
            entity = ArcEntity::class,
            parentColumns = ["id"],
            childColumns = ["arc_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("game_key")],
)
data class ArcStepEntity(
    @ColumnInfo(name = "arc_id") val arcId: String,
    val position: Int,
    @ColumnInfo(name = "game_key") val gameKey: String,
    val goal: String?,
    @ColumnInfo(name = "target_minutes") val targetMinutes: Int,
    @ColumnInfo(name = "baseline_minutes") val baselineMinutes: Int,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
)

/**
 * Respuesta de la IA que se puede reutilizar mientras no cambie lo que la
 * produjo (ver MashaCache): la clave es el hash del modelo, el prompt y el
 * contexto que se mandó.
 */
@Entity(tableName = "ai_cache", indices = [Index("expires_at")])
data class AiCacheEntity(
    @PrimaryKey @ColumnInfo(name = "cache_key") val key: String,
    val response: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)
