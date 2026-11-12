package com.elyndra.launcher.domain.relationship

/**
 * Lo que enseña la insignia de confianza de la cabecera de Lucy (pura): el
 * nivel como "Nivel N de 5", el aro del corazón que se llena hacia el
 * siguiente y los puntos que faltan. En el último nivel el aro está lleno.
 */
data class AffinityBadge(
    val level: AffinityLevel,
    val number: Int,
    val total: Int,
    /** Lo que llena el aro (0..1) dentro del nivel. */
    val ring: Float,
    val next: AffinityLevel?,
    val pointsToNext: Int,
) {
    companion object {
        fun of(progress: AffinityProgress): AffinityBadge = AffinityBadge(
            level = progress.level,
            number = progress.level.ordinal + 1,
            total = AffinityLevel.entries.size,
            ring = if (progress.next == null) 1f else progress.fraction.coerceIn(0f, 1f),
            next = progress.next,
            pointsToNext = progress.pointsToNext,
        )

        /**
         * Cómo animar el aro al cambiar la puntuación: si subió de nivel, se
         * llena hasta arriba y vuelve a empezar desde 0 (no retrocede).
         */
        fun ringSteps(before: AffinityBadge, after: AffinityBadge): List<Float> =
            if (after.number > before.number) listOf(1f, 0f, after.ring) else listOf(after.ring)
    }
}
