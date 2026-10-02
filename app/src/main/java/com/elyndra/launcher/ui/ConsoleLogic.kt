package com.elyndra.launcher.ui

import androidx.annotation.StringRes
import com.elyndra.launcher.R
import com.elyndra.launcher.data.GameSystem
import java.text.Normalizer
import kotlin.math.roundToInt

/**
 * Reordenar la prioridad de fuentes: con flechas o LB/RB (un paso) y
 * arrastrando por el asa (a la fila sobre la que se suelta). Genérico para
 * poder probarlo sin servicios de verdad.
 */
object PriorityOrder {

    /** [item] un paso arriba (−1) o abajo (+1); en los extremos no se mueve. */
    fun <T> move(order: List<T>, item: T, delta: Int): List<T> {
        val from = order.indexOf(item)
        if (from < 0) return order
        return moveTo(order, from, (from + delta).coerceIn(0, order.lastIndex))
    }

    fun <T> moveTo(order: List<T>, from: Int, to: Int): List<T> {
        if (from !in order.indices || to !in order.indices || from == to) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * La posición a la que va la fila [from] arrastrada [offset] px, con filas
     * de [rowHeight] px: cambia de sitio en cuanto cruza la mitad de la vecina.
     */
    fun dragTarget(from: Int, offset: Float, rowHeight: Float, size: Int): Int {
        if (size <= 0 || rowHeight <= 0f) return from
        return (from + (offset / rowHeight).roundToInt()).coerceIn(0, size - 1)
    }

    /** "ScreenScraper › IGDB …": las primeras [take] fuentes, para el resumen de una línea. */
    fun <T> summary(order: List<T>, name: (T) -> String, take: Int = 2): String =
        order.take(take).joinToString(" › ") { name(it) } + if (order.size > take) " …" else ""
}

/** El resumen de la sección plegada de sonidos propios: "9 eventos · 2 propios". */
object SoundSummary {
    fun text(events: Int, custom: Int, eventsLabel: (Int) -> String, customLabel: (Int) -> String): String =
        if (custom <= 0) eventsLabel(events) else eventsLabel(events) + " · " + customLabel(custom)
}

/** Fabricantes con los que se agrupan los sistemas en Añadir, en el orden en que se enseñan. */
enum class SystemMaker(@StringRes val label: Int) {
    Nintendo(R.string.maker_nintendo),
    Sony(R.string.maker_sony),
    Microsoft(R.string.maker_microsoft),
    Sega(R.string.maker_sega),
    Others(R.string.maker_others),
    Pc(R.string.maker_pc),
}

/** Agrupar y buscar sistemas en "Añadir → Carpeta de ROMs". Kotlin puro. */
object SystemGroups {

    private val NINTENDO = setOf("switch", "n64", "nds", "n3ds", "gc", "wii", "wiiu", "gba", "gbc", "gb", "nes", "fds", "snes", "virtualboy")
    private val SONY = setOf("ps2", "ps3", "ps4", "psp", "psvita", "psx")
    private val MICROSOFT = setOf("xbox360", "xbox")
    private val SEGA = setOf("megadrive", "mastersystem", "gamegear", "segacd", "sega32x", "saturn", "dreamcast", "sg1000")
    private val PC = setOf("pc", "dos")

    fun makerOf(systemId: String): SystemMaker = when (systemId) {
        in NINTENDO -> SystemMaker.Nintendo
        in SONY -> SystemMaker.Sony
        in MICROSOFT -> SystemMaker.Microsoft
        in SEGA -> SystemMaker.Sega
        in PC -> SystemMaker.Pc
        else -> SystemMaker.Others
    }

    /**
     * ¿Encaja [system] con lo escrito? Cada palabra tiene que aparecer en su
     * nombre, rótulo, siglas, id o alias, sin distinguir mayúsculas ni acentos
     * ("play 2" encuentra PlayStation 2; "megadrive", Mega Drive).
     */
    fun matches(system: GameSystem, query: String): Boolean {
        val words = normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = normalize(listOf(system.name, system.short, system.abbr, system.id).joinToString(" ") + " " + system.aliases.joinToString(" "))
        val compact = hay.replace(" ", "")
        return words.all { it in hay || it in compact }
    }

    /** Los sistemas que encajan con [query], por fabricante y sin grupos vacíos. */
    fun group(systems: List<GameSystem>, query: String): List<Pair<SystemMaker, List<GameSystem>>> {
        val shown = systems.filter { matches(it, query) }
        return SystemMaker.entries.mapNotNull { maker ->
            shown.filter { makerOf(it.id) == maker }.takeIf { it.isNotEmpty() }?.let { maker to it }
        }
    }

    internal fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex(" +"), " ")
            .trim()
}

/** Buscar entre las apps instaladas de "Añadir → Juegos Android". */
object AppSearch {
    fun matches(label: String, packageName: String, query: String): Boolean {
        val words = SystemGroups.normalize(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = SystemGroups.normalize("$label $packageName")
        return words.all { it in hay }
    }
}
