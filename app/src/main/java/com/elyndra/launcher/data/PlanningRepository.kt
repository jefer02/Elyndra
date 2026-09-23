package com.elyndra.launcher.data

import androidx.room.withTransaction
import com.elyndra.launcher.data.db.ArcDao
import com.elyndra.launcher.data.db.ArcEntity
import com.elyndra.launcher.data.db.ArcStepEntity
import com.elyndra.launcher.data.db.ElyndraDatabase
import com.elyndra.launcher.data.db.SmartListDao
import com.elyndra.launcher.data.db.SmartListEntity
import com.elyndra.launcher.data.db.SmartListItemEntity
import com.elyndra.launcher.domain.lists.ListRule
import com.elyndra.launcher.domain.session.Arc
import com.elyndra.launcher.domain.session.ArcStatus
import com.elyndra.launcher.domain.session.ArcStep
import com.elyndra.launcher.domain.session.Arcs
import com.elyndra.launcher.library.Names
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Una lista guardada. */
data class SavedList(
    val id: String,
    val name: String,
    val rule: ListRule,
    /** "user", "masha" o "system". */
    val createdBy: String,
    val createdAt: Long,
    val pinned: Boolean,
)

/**
 * Listas inteligentes guardadas. Las dinámicas guardan solo su regla y se
 * recalculan al abrirlas; las manuales guardan además sus juegos, en orden.
 */
@Singleton
class SmartListRepository @Inject constructor(private val db: ElyndraDatabase) {

    private val dao: SmartListDao get() = db.smartListDao()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    suspend fun all(): List<SavedList> = runCatching { dao.lists() }.getOrDefault(emptyList()).map { load(it) }

    /** Por nombre, exacto o parecido. */
    suspend fun byName(name: String): SavedList? {
        val lists = all()
        return lists.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            ?: lists.maxByOrNull { Names.similarity(name, it.name) }?.takeIf { Names.similarity(name, it.name) >= 0.7 }
    }

    suspend fun save(name: String, rule: ListRule, createdBy: String, now: Long = System.currentTimeMillis()): SavedList {
        val existing = all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        db.withTransaction {
            dao.upsertList(
                SmartListEntity(
                    id = id,
                    name = name.trim(),
                    rule = json.encodeToString(ListRule.serializer(), rule.copy(keys = emptyList())),
                    createdBy = createdBy,
                    createdAt = existing?.createdAt ?: now,
                    pinned = existing?.pinned ?: false,
                ),
            )
            dao.clearItems(id)
            if (rule.keys.isNotEmpty()) {
                dao.upsertItems(rule.keys.distinct().mapIndexed { i, key -> SmartListItemEntity(id, key, i) })
            }
        }
        return SavedList(id, name.trim(), rule, createdBy, existing?.createdAt ?: now, existing?.pinned ?: false)
    }

    suspend fun delete(id: String) = dao.deleteList(id)

    private suspend fun load(e: SmartListEntity): SavedList {
        val rule = runCatching { json.decodeFromString(ListRule.serializer(), e.rule) }.getOrDefault(ListRule())
        val items = runCatching { dao.items(e.id) }.getOrDefault(emptyList()).map { it.gameKey }
        return SavedList(e.id, e.name, if (items.isEmpty()) rule else rule.copy(keys = items), e.createdBy, e.createdAt, e.pinned)
    }
}

/** Arcos: se guardan, se consultan y se van cerrando según se juega. */
@Singleton
class ArcRepository @Inject constructor(private val db: ElyndraDatabase) {

    private val dao: ArcDao get() = db.arcDao()

    suspend fun active(): List<Arc> = byStatus(ArcStatus.Active)

    suspend fun byStatus(status: ArcStatus): List<Arc> =
        runCatching { dao.arcs(status.id) }.getOrDefault(emptyList()).map { load(it) }

    suspend fun save(arc: Arc) {
        db.withTransaction {
            dao.upsertArc(ArcEntity(arc.id, arc.title, arc.theme, arc.description, arc.status.id, arc.createdAt, arc.updatedAt))
            dao.upsertSteps(
                arc.steps.map {
                    ArcStepEntity(arc.id, it.position, it.gameKey, it.goal, it.targetMinutes, it.baselineMinutes, it.completedAt)
                },
            )
        }
    }

    suspend fun setStatus(arcId: String, status: ArcStatus, now: Long = System.currentTimeMillis()) =
        dao.setStatus(arcId, status.id, now)

    /**
     * Cierra los pasos que ya llegaron a su tiempo y los arcos que se
     * terminaron. Devuelve los pasos recién cerrados (para que Masha lo diga).
     */
    suspend fun sync(minutesOf: (String) -> Int, now: Long = System.currentTimeMillis()): List<Pair<Arc, ArcStep>> {
        val closed = ArrayList<Pair<Arc, ArcStep>>()
        for (arc in active()) {
            val reached = Arcs.reachedSteps(arc, minutesOf)
            if (reached.isEmpty()) continue
            reached.forEach { dao.completeStep(arc.id, it.position, now) }
            closed += reached.map { arc to it }
            if (arc.steps.count { it.completedAt == null } == reached.size) dao.setStatus(arc.id, ArcStatus.Completed.id, now)
        }
        return closed
    }

    fun newId(): String = UUID.randomUUID().toString()

    private suspend fun load(e: ArcEntity): Arc {
        val steps = runCatching { dao.steps(e.id) }.getOrDefault(emptyList()).map {
            ArcStep(it.position, it.gameKey, it.goal, it.targetMinutes, it.baselineMinutes, it.completedAt)
        }
        return Arc(e.id, e.title, e.theme, e.description, ArcStatus.byId(e.status), steps, e.createdAt, e.updatedAt)
    }
}
