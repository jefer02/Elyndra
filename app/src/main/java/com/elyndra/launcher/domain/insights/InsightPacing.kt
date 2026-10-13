package com.elyndra.launcher.domain.insights

/**
 * Cuándo puede asomar el bocadillo de Masha, para que no canse.
 *
 * - Tras "Ahora no" se calla un rato: no salta enseguida la siguiente sugerencia.
 * - Una misma sugerencia, ya vista, no vuelve a asomar en unas horas aunque se
 *   vuelva a la app.
 * - Entre dos bocadillos cualesquiera pasa un mínimo de tiempo.
 */
class InsightPacing(
    private val quietAfterDismissMs: Long = 45L * 60 * 1000,
    private val repeatSameMs: Long = 6L * 60 * 60 * 1000,
    private val minGapMs: Long = 10L * 60 * 1000,
) {
    private val shownAt = HashMap<String, Long>()
    private var lastShown = 0L
    private var quietUntil = 0L

    /** ¿Se puede enseñar [insight] ahora? Si sí, cuenta como enseñada. */
    fun admit(insight: Insight, now: Long): Boolean {
        if (now < quietUntil) return false
        val seen = shownAt[insight.id]
        if (seen != null && now - seen < repeatSameMs) return false
        // El hueco mínimo no frena lo urgente (el dispositivo caliente).
        if (insight !is Insight.DeviceHot && lastShown > 0 && now - lastShown < minGapMs) return false
        shownAt[insight.id] = now
        lastShown = now
        return true
    }

    fun dismissed(now: Long) {
        quietUntil = now + quietAfterDismissMs
    }
}
