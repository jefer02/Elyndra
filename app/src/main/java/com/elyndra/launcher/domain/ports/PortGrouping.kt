package com.elyndra.launcher.domain.ports

/**
 * Varios ports del mismo juego (cuatro Wind Waker HD, dos Ocarina of Time…)
 * son una sola cápsula en la tienda: un [GameGroup]. La ficha del juego tiene
 * un selector con todos sus ports en el orden de [ORDER]. Kotlin puro
 * (PortGroupingTest).
 */
data class GameGroup(
    val key: String,
    /** Los ports del juego, el mejor primero ([PortGrouping.ORDER]). */
    val ports: List<PortEntry>,
    /** El que representa al grupo en la cápsula: el instalado si lo hay; si no, el primero. */
    val lead: PortEntry,
) {
    val game: PortGame get() = lead.game
    val ids: Set<String> get() = ports.mapTo(LinkedHashSet()) { it.id }
}

object PortGrouping {

    /** El mismo juego: misma plataforma original y mismo título reducido (ver [PortLookup.normalizeTitle]). */
    fun key(port: PortEntry): String =
        port.game.originalPlatform.lowercase() + ":" + PortLookup.normalizeTitle(port.game.name).ifEmpty { port.id }

    /**
     * Los ports de un juego, en el orden del selector: los que se instalan,
     * luego verificados, luego más estrellas, luego la release más reciente.
     */
    fun order(publishedAt: Map<String, Long> = emptyMap()): Comparator<PortEntry> =
        compareByDescending<PortEntry> { it.installable }
            .thenByDescending { it.trust.verified }
            .thenByDescending { it.trust.stars ?: -1 }
            .thenByDescending { publishedAt[it.id] ?: it.release.publishedAtMillis }
            .thenBy { it.id }

    val ORDER: Comparator<PortEntry> = order()

    /**
     * Agrupa [ports] por juego, en el orden en que aparece cada juego por
     * primera vez (así una fila ordenada sigue ordenada). [installedIds]
     * decide el [GameGroup.lead]; [publishedAt] (releases ya leídas) manda
     * sobre la fecha del catálogo.
     */
    fun group(
        ports: List<PortEntry>,
        installedIds: Set<String> = emptySet(),
        publishedAt: Map<String, Long> = emptyMap(),
    ): List<GameGroup> {
        val order = order(publishedAt)
        return ports.groupBy { key(it) }.map { (key, members) ->
            val sorted = members.sortedWith(order)
            GameGroup(key, sorted, sorted.firstOrNull { it.id in installedIds } ?: sorted.first())
        }
    }

    /** Los ports del mismo juego que [port] en [catalog] (él incluido), en el orden del selector. */
    fun siblings(port: PortEntry, catalog: List<PortEntry>, publishedAt: Map<String, Long> = emptyMap()): List<PortEntry> {
        val k = key(port)
        return catalog.filter { key(it) == k }.ifEmpty { listOf(port) }.sortedWith(order(publishedAt))
    }
}
