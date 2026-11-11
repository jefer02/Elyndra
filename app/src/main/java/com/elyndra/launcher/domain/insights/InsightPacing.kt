package com.elyndra.launcher.domain.insights

/**
 * Lo que [InsightPacing] necesita recordar entre aperturas de la app. Antes
 * vivía solo en memoria y, como Android mata el proceso a menudo entre
 * partidas, cada vez que se abría Elyndra el bocadillo volvía a salir como
 * nuevo.
 */
interface InsightPacingStore {
    /** Cuándo se enseñó [id] por última vez; null = nunca. */
    fun shownAt(id: String): Long?
    fun markShown(id: String, now: Long)

    /** Última sugerencia de rutina enseñada; null = ninguna. */
    var lastRoutineAt: Long?

    /** Tras "Ahora no", hasta cuándo se calla. */
    var quietUntil: Long
}

/** El de los tests (y el de partida si no se inyecta otro). */
class MemoryInsightPacingStore : InsightPacingStore {
    private val shown = HashMap<String, Long>()
    override fun shownAt(id: String): Long? = shown[id]
    override fun markShown(id: String, now: Long) { shown[id] = now }
    override var lastRoutineAt: Long? = null
    override var quietUntil: Long = 0L
}

/**
 * Cuándo puede asomar el bocadillo de Lucy (Masha en el código), para que no canse.
 *
 * - Hechos ([Insight.isEvent]: dispositivo caliente, juegos nuevos, paso de
 *   arco, metadatos terminados, emulador que falta): se cuentan UNA vez. El
 *   calor es la excepción: puede volver a avisar pasado [repeatHotMs].
 * - Sugerencias de rutina (seguir un juego, uno abandonado, carátulas que
 *   faltan…): como mucho una cada [routineGapMs] (≈ una al día), y la misma no
 *   se repite en [repeatRoutineMs].
 * - Tras "Ahora no" se calla [quietAfterDismissMs].
 * - Entre dos bocadillos cualesquiera, [minGapMs] (salvo el calor).
 *
 * Todo se guarda en [store], así que abrir la app (o que Android la reinicie)
 * no basta para que salga nada: tiene que haber un hecho nuevo o haber pasado
 * el día.
 */
class InsightPacing(
    private val store: InsightPacingStore = MemoryInsightPacingStore(),
    private val quietAfterDismissMs: Long = 45L * 60 * 1000,
    private val routineGapMs: Long = 20L * 60 * 60 * 1000,
    private val repeatRoutineMs: Long = 3L * 24 * 60 * 60 * 1000,
    private val repeatHotMs: Long = 2L * 60 * 60 * 1000,
    private val minGapMs: Long = 10L * 60 * 1000,
) {
    /** Solo dentro de una misma ejecución: entre aperturas ya manda el cupo diario. */
    private var lastShown = 0L

    /** ¿Se puede enseñar [insight] ahora? No cuenta como enseñada. */
    fun canShow(insight: Insight, now: Long): Boolean {
        if (now < store.quietUntil) return false
        val hot = insight is Insight.DeviceHot
        // El hueco mínimo no frena lo urgente (el dispositivo caliente).
        if (!hot && lastShown > 0 && now - lastShown < minGapMs) return false
        val seen = store.shownAt(insight.id)
        return when {
            hot -> seen == null || now - seen >= repeatHotMs
            insight.isEvent -> seen == null
            else -> (seen == null || now - seen >= repeatRoutineMs) && routineSlotFree(now)
        }
    }

    /** ¿Queda hueco hoy para una sugerencia de rutina? */
    fun routineSlotFree(now: Long): Boolean {
        val last = store.lastRoutineAt ?: return true
        return now - last >= routineGapMs
    }

    /** Cuenta [insight] como enseñada. */
    fun markShown(insight: Insight, now: Long) {
        store.markShown(insight.id, now)
        if (!insight.isEvent) store.lastRoutineAt = now
        lastShown = now
    }

    /** [canShow] y, si sí, [markShown]. */
    fun admit(insight: Insight, now: Long): Boolean {
        if (!canShow(insight, now)) return false
        markShown(insight, now)
        return true
    }

    /**
     * La primera de [candidates] (ya ordenadas por peso) que se puede enseñar,
     * marcada como enseñada. O(n) en el número de sugerencias (una docena).
     */
    fun pick(candidates: List<Insight>, now: Long): Insight? =
        candidates.firstOrNull { canShow(it, now) }?.also { markShown(it, now) }

    fun dismissed(now: Long) {
        store.quietUntil = now + quietAfterDismissMs
    }
}
