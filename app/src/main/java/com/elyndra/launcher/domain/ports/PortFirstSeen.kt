package com.elyndra.launcher.domain.ports

/**
 * Cuándo entró cada port en el catálogo, visto desde la app (Kotlin puro,
 * PortFirstSeenTest): el catálogo no trae fecha de alta, así que se anota la
 * primera vez que un id aparece en un catálogo leído de la red. Es lo que
 * decide la caja NUEVO de la tienda.
 *
 * La primera vez (nada anotado) todo entra con 0, "desde siempre": si no,
 * al instalar la app el catálogo entero saldría como nuevo.
 */
object PortFirstSeen {

    /** [known] con los ids de [ids] que faltaban anotados en [now]; los que ya no están, fuera. */
    fun update(known: Map<String, Long>, ids: Collection<String>, now: Long): Map<String, Long> {
        if (ids.isEmpty()) return known
        val seed = known.isEmpty()
        return ids.associateWith { id -> known[id] ?: if (seed) 0L else now }
    }

    /** "id=ms" por línea, como se guarda en los ajustes. */
    fun encode(map: Map<String, Long>): String = map.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun decode(text: String?): Map<String, Long> = text.orEmpty().lineSequence()
        .mapNotNull { line ->
            val i = line.lastIndexOf('=')
            if (i <= 0) null else line.substring(i + 1).toLongOrNull()?.let { line.substring(0, i) to it }
        }
        .toMap()
}
