package com.elyndra.launcher.domain.relationship

import java.time.Instant
import java.time.ZoneId

/**
 * Lo cerca que se siente Lucy del usuario: decide lo cálida que suena, nunca
 * lo que puede hacer. Cada nivel cubre un tramo de la puntuación (0…100).
 */
enum class AffinityLevel(val max: Int) {
    /** Algo apagada, breve, callada. */
    Distant(5),

    /** Contenta, curiosa, intrigada por el usuario. */
    Curious(35),

    /** Suave, desenfadada, empieza a abrirse. */
    Friendly(60),

    /** Cálida, aterciopelada, coqueta: quiere que se fijen en ella. */
    Close(75),

    /** Muy cariñosa, juguetona, apegada sin disimulo: aquí los celos se notan más. */
    Devoted(100),
    ;

    /** Nombre interno (el que lee el prompt en RELATIONSHIP_LEVEL). */
    val id: String get() = name.lowercase()

    companion object {
        fun of(score: Int): AffinityLevel {
            val s = score.coerceIn(Affinity.MIN, Affinity.MAX)
            return entries.first { s <= it.max }
        }
    }
}

/** Por qué subió la relación (lo que enseña el historial del panel "Lucy y tú"). */
enum class GainReason {
    Compliment,
    Kindness,
    CuriousAboutHer,
    Sharing,
    Humour,
    Greeting,

    /** Lo puntuó la IA: sin categoría concreta. */
    Chat,
    ;

    val id: String get() = name.lowercase()

    companion object {
        fun byId(id: String?): GainReason? = entries.firstOrNull { it.id == id }
    }
}

/** Una subida: [points] ya recortados (tope diario incluido), [at] epoch ms. */
data class AffinityGain(val points: Int, val reason: GainReason, val at: Long)

/** Lo que [Affinity] guarda entre aperturas. */
interface AffinityStore {
    var score: Int

    /** Día local (epoch day) al que se refiere [gainedToday]. */
    var day: Long

    /** Puntos ganados ese día: el tope diario sale de aquí. */
    var gainedToday: Int

    /** Las últimas subidas, de la más vieja a la más nueva (nunca bajadas). */
    var gains: List<AffinityGain>

    /** El nivel más alto ya celebrado (ordinal), o −1 si aún no se sabe. */
    var celebrated: Int
}

/** El de los tests (y el de partida si no se inyecta otro). */
class MemoryAffinityStore(
    override var score: Int = Affinity.START,
    override var day: Long = -1,
    override var gainedToday: Int = 0,
    override var gains: List<AffinityGain> = emptyList(),
    override var celebrated: Int = -1,
) : AffinityStore

/**
 * La puntuación de la relación con Lucy y sus reglas, sin Android.
 *
 * - Empieza en [START] y vive en [MIN]…[MAX].
 * - Cada cambio se recorta a ±[MAX_DELTA], venga de la IA o de las reglas
 *   locales: ni un mensaje ni una respuesta manipulada mueven más que eso.
 * - Las subidas tienen un tope de [DAILY_GAIN_CAP] por día local, para que el
 *   nivel no se pueda inflar en unos minutos. Las bajadas no tienen tope.
 * - Nada baja con el tiempo: no abrir la app o estar fuera no se castiga.
 * - Guarda las últimas [MAX_GAINS] subidas con su motivo (las bajadas no se
 *   enseñan, solo cuentan en el total) y qué nivel se celebró ya: cada nivel,
 *   una sola vez.
 */
class Affinity(
    private val store: AffinityStore = MemoryAffinityStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    data class Change(val before: Int, val after: Int, val celebrate: Boolean = false) {
        val oldLevel: AffinityLevel get() = AffinityLevel.of(before)
        val newLevel: AffinityLevel get() = AffinityLevel.of(after)
        val levelUp: Boolean get() = newLevel > oldLevel

        /** Lo que subió de verdad (0 si bajó o no se movió). */
        val gained: Int get() = (after - before).coerceAtLeast(0)
    }

    val score: Int get() = store.score.coerceIn(MIN, MAX)

    val level: AffinityLevel get() = AffinityLevel.of(score)

    /** Las últimas subidas, la más nueva primero. */
    val recentGains: List<AffinityGain> get() = store.gains.asReversed()

    /** Cómo va hacia el siguiente nivel. */
    val progress: AffinityProgress get() = AffinityProgress.of(score)

    /**
     * Aplica [delta] con el recorte por mensaje y el tope diario. Si sube a un
     * nivel que nunca se había celebrado, [Change.celebrate] lo dice (y queda apuntado).
     */
    @Synchronized
    fun apply(delta: Int, reason: GainReason = GainReason.Chat): Change {
        val before = score
        val d = delta.coerceIn(-MAX_DELTA, MAX_DELTA)
        val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().toEpochDay()
        if (store.day != today) {
            store.day = today
            store.gainedToday = 0
        }
        val allowed = if (d > 0) minOf(d, (DAILY_GAIN_CAP - store.gainedToday).coerceAtLeast(0)) else d
        val after = (before + allowed).coerceIn(MIN, MAX)
        if (after > before) {
            store.gainedToday += after - before
            store.gains = (store.gains + AffinityGain(after - before, reason, clock())).takeLast(MAX_GAINS)
        }
        if (after != before) store.score = after
        val level = AffinityLevel.of(after)
        val celebrated = store.celebrated.takeIf { it >= 0 } ?: AffinityLevel.of(before).ordinal
        val celebrate = level.ordinal > celebrated
        if (celebrate || store.celebrated < 0) store.celebrated = maxOf(level.ordinal, celebrated)
        return Change(before, after, celebrate)
    }

    /** "Olvidar todo lo que Lucy sabe": vuelve al principio. */
    @Synchronized
    fun reset() {
        store.score = START
        store.gainedToday = 0
        store.gains = emptyList()
        store.celebrated = AffinityLevel.of(START).ordinal
    }

    companion object {
        const val MIN = 0
        const val MAX = 100
        const val START = 10
        const val MAX_DELTA = 10
        const val DAILY_GAIN_CAP = 15
        const val MAX_GAINS = 8
    }
}
