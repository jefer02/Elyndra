package com.elyndra.launcher.masha

import com.elyndra.launcher.data.db.MashaDao
import com.elyndra.launcher.data.db.MashaMemoryEntity
import com.elyndra.launcher.data.db.MashaMessageEntity
import com.elyndra.launcher.library.Names
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Algo que Masha recuerda del usuario. */
data class Memory(
    val id: Long,
    val kind: String,
    val subject: String?,
    val content: String,
    val weight: Float,
    val updatedAt: Long,
)

/** Un mensaje guardado del hilo con Masha. */
data class StoredMessage(
    val role: MashaTurn.Role,
    val text: String,
    val gameKey: String?,
    val attachment: MashaAttachment?,
    val createdAt: Long,
)

/**
 * La memoria de Masha: preferencias y datos que el usuario le ha contado
 * ("odio farmear", "juego en la tele los findes") y el hilo de la
 * conversación, para retomarlo al volver.
 *
 * Todo se queda en el dispositivo. A la IA solo viajan, con cada pregunta, los
 * recuerdos más importantes (ver MashaContextBuilder), y el usuario puede
 * borrarlo todo desde Ajustes.
 */
@Singleton
class MashaMemory @Inject constructor(private val dao: MashaDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    suspend fun remember(
        content: String,
        kind: String = KIND_PREFERENCE,
        subject: String? = null,
        weight: Float = 0.7f,
        now: Long = System.currentTimeMillis(),
    ): Memory {
        val text = content.trim().take(MAX_MEMORY_CHARS)
        // Lo mismo dicho otra vez no se duplica: se refresca y gana peso.
        val existing = dao.memoryWithContent(text)
            ?: dao.memories(now, MAX_MEMORIES).firstOrNull { Names.similarity(it.content, text) >= 0.92 }
        val row = if (existing != null) {
            existing.copy(
                kind = kind,
                subject = subject ?: existing.subject,
                weight = maxOf(existing.weight, weight).plus(0.05f).coerceAtMost(1f),
                updatedAt = now,
            ).also { dao.upsertMemory(it) }
        } else {
            val entity = MashaMemoryEntity(
                kind = kind,
                subject = subject,
                content = text,
                weight = weight.coerceIn(0f, 1f),
                createdAt = now,
                updatedAt = now,
                expiresAt = null,
            )
            entity.copy(id = dao.insertMemory(entity))
        }
        return row.toMemory()
    }

    /** Olvida lo que se parezca a [query]. Devuelve cuántos recuerdos borró. */
    suspend fun forget(query: String, now: Long = System.currentTimeMillis()): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0
        val matches = dao.memories(now, MAX_MEMORIES).filter {
            it.content.contains(q, ignoreCase = true) || Names.similarity(it.content, q) >= 0.6
        }
        matches.forEach { dao.deleteMemory(it.id) }
        return matches.size
    }

    suspend fun all(limit: Int = MAX_MEMORIES, now: Long = System.currentTimeMillis()): List<Memory> =
        runCatching { dao.memories(now, limit) }.getOrDefault(emptyList()).map { it.toMemory() }

    suspend fun about(subject: String, now: Long = System.currentTimeMillis()): List<Memory> =
        runCatching { dao.memoriesAbout(subject, now) }.getOrDefault(emptyList()).map { it.toMemory() }

    suspend fun clearMemories() = dao.clearMemories()

    /* ── hilo de conversación ─────────────────────────────────── */

    suspend fun history(limit: Int = MAX_MESSAGES): List<StoredMessage> =
        runCatching { dao.recentMessages(limit) }.getOrDefault(emptyList()).map { m ->
            StoredMessage(
                role = if (m.role == ROLE_USER) MashaTurn.Role.User else MashaTurn.Role.Masha,
                text = m.text,
                gameKey = m.gameKey,
                attachment = m.attachment?.let { a -> runCatching { json.decodeFromString(MashaAttachment.serializer(), a) }.getOrNull() },
                createdAt = m.createdAt,
            )
        }

    suspend fun saveMessage(message: StoredMessage) {
        runCatching {
            dao.insertMessage(
                MashaMessageEntity(
                    role = if (message.role == MashaTurn.Role.User) ROLE_USER else ROLE_MASHA,
                    text = message.text,
                    gameKey = message.gameKey,
                    attachment = message.attachment?.let { json.encodeToString(MashaAttachment.serializer(), it) },
                    createdAt = message.createdAt,
                ),
            )
            dao.trimMessages(MAX_MESSAGES)
        }
    }

    suspend fun clearHistory() = dao.clearMessages()

    private fun MashaMemoryEntity.toMemory() = Memory(id, kind, subject, content, weight, updatedAt)

    companion object {
        const val KIND_PREFERENCE = "preference"
        const val KIND_FACT = "fact"
        const val KIND_OBSERVATION = "observation"

        private const val ROLE_USER = "user"
        private const val ROLE_MASHA = "masha"
        private const val MAX_MEMORIES = 60
        private const val MAX_MEMORY_CHARS = 280
        const val MAX_MESSAGES = 60
    }
}
