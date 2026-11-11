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

/** Lo que [Affinity] guarda entre aperturas. */
interface AffinityStore {
    var score: Int

    /** Día local (epoch day) al que se refiere [gainedToday]. */
    var day: Long

    /** Puntos ganados ese día: el tope diario sale de aquí. */
    var gainedToday: Int
}

/** El de los tests (y el de partida si no se inyecta otro). */
class MemoryAffinityStore(
    override var score: Int = Affinity.START,
    override var day: Long = -1,
    override var gainedToday: Int = 0,
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
 */
class Affinity(
    private val store: AffinityStore = MemoryAffinityStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    data class Change(val before: Int, val after: Int) {
        val oldLevel: AffinityLevel get() = AffinityLevel.of(before)
        val newLevel: AffinityLevel get() = AffinityLevel.of(after)
        val levelUp: Boolean get() = newLevel > oldLevel
    }

    val score: Int get() = store.score.coerceIn(MIN, MAX)

    val level: AffinityLevel get() = AffinityLevel.of(score)

    /** Aplica [delta] con el recorte por mensaje y el tope diario. */
    @Synchronized
    fun apply(delta: Int): Change {
        val before = score
        val d = delta.coerceIn(-MAX_DELTA, MAX_DELTA)
        val today = Instant.ofEpochMilli(clock()).atZone(zone()).toLocalDate().toEpochDay()
        if (store.day != today) {
            store.day = today
            store.gainedToday = 0
        }
        val allowed = if (d > 0) minOf(d, (DAILY_GAIN_CAP - store.gainedToday).coerceAtLeast(0)) else d
        val after = (before + allowed).coerceIn(MIN, MAX)
        if (after > before) store.gainedToday += after - before
        if (after != before) store.score = after
        return Change(before, after)
    }

    /** "Olvidar todo lo que Lucy sabe": vuelve al principio. */
    @Synchronized
    fun reset() {
        store.score = START
        store.gainedToday = 0
    }

    companion object {
        const val MIN = 0
        const val MAX = 100
        const val START = 10
        const val MAX_DELTA = 10
        const val DAILY_GAIN_CAP = 15
    }
}
