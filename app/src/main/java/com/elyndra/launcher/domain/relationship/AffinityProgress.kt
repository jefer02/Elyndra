package com.elyndra.launcher.domain.relationship

/**
 * Cuánto falta para el siguiente nivel (pura): lo que dibuja la barra del
 * panel "Lucy y tú" y la fila de Ajustes.
 *
 * Cada nivel cubre de `max` del anterior + 1 hasta su `max` ([AffinityLevel]).
 * [pointsToNext] es lo que falta para entrar en [next]; [fraction], lo
 * recorrido dentro del tramo actual (0 al entrar). En el último nivel no hay
 * siguiente y la barra está llena.
 */
data class AffinityProgress(
    val score: Int,
    val level: AffinityLevel,
    val next: AffinityLevel?,
    val pointsToNext: Int,
    val fraction: Float,
) {
    companion object {
        fun of(score: Int): AffinityProgress {
            val s = score.coerceIn(Affinity.MIN, Affinity.MAX)
            val level = AffinityLevel.of(s)
            val next = AffinityLevel.entries.getOrNull(level.ordinal + 1)
                ?: return AffinityProgress(s, level, null, 0, 1f)
            val min = firstScore(level)
            val width = level.max - min + 1
            return AffinityProgress(s, level, next, firstScore(next) - s, (s - min).toFloat() / width)
        }

        /** La primera puntuación de [level]. */
        fun firstScore(level: AffinityLevel): Int =
            AffinityLevel.entries.getOrNull(level.ordinal - 1)?.let { it.max + 1 } ?: Affinity.MIN
    }
}
