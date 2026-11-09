package com.elyndra.launcher.domain.ports

import com.elyndra.launcher.update.UpdateLogic

/**
 * Lo que de verdad se puede instalar en este dispositivo (Kotlin puro,
 * PortAvailabilityTest). El catálogo dice lo que vio el generador; aquí se le
 * suman dos cosas que solo sabe la app:
 *
 *  · un port "aún no en Android" al que le ha salido un APK desde entonces
 *    (lo apunta PortUpdateWorker en `port_availability`): pasa a instalable,
 *    pero sin verificar, porque nadie lo ha revisado;
 *  · un APK solo para gafas de RV se instala únicamente en unas gafas.
 */
object PortAvailability {

    /** Un APK que encontró la app en la release [tag] de un port que no tenía ([asset]: su nombre). */
    data class Found(val tag: String, val asset: String)

    /** [entry] tal como se enseña en este dispositivo. */
    fun effective(entry: PortEntry, isVrHeadset: Boolean, found: Found?): PortEntry = when {
        entry.vrOnly && isVrHeadset -> entry.copy(availability = PortEntry.AVAILABLE)
        entry.notOnAndroid && found != null -> entry.copy(
            availability = PortEntry.AVAILABLE,
            builds = entry.builds.filterNot { it.platform.equals(PortBuild.ANDROID, ignoreCase = true) } +
                PortBuild(PortBuild.ANDROID, asset = "^" + Regex.escape(found.asset) + "$"),
            trust = entry.trust.copy(
                verified = false,
                flags = (entry.trust.flags + PortTrust.DISCOVERED).distinct(),
            ),
        )
        else -> entry
    }

    fun effective(catalog: List<PortEntry>, isVrHeadset: Boolean, found: Map<String, Found>): List<PortEntry> =
        catalog.map { effective(it, isVrHeadset, found[it.id]) }

    fun installable(entry: PortEntry, isVrHeadset: Boolean, found: Found? = null): Boolean =
        effective(entry, isVrHeadset, found).installable

    /** Cuántos ports "aún no en Android" se miran cada día (una petición a GitHub cada uno). */
    const val BATCH = 30

    /**
     * Los [BATCH] de [ids] que tocan hoy a partir de [cursor], dando la
     * vuelta, y el cursor de mañana. Así, en unos días, se han mirado todos.
     */
    fun batch(ids: List<String>, cursor: Int, size: Int = BATCH): Pair<List<String>, Int> {
        if (ids.isEmpty()) return emptyList<String>() to 0
        val sorted = ids.distinct().sorted()
        val start = Math.floorMod(cursor, sorted.size)
        val take = minOf(size, sorted.size)
        val out = (0 until take).map { sorted[(start + it) % sorted.size] }
        return out to (start + take) % sorted.size
    }

    /** Si [assets] (nombres de una release) traen un APK que valga para un port que no tenía. */
    fun apkIn(assets: List<String>): String? {
        // Sin revisar a nadie, tampoco uno de depuración.
        val apks = assets.filter { it.endsWith(".apk", ignoreCase = true) && !PortAssetPicker.isDebug(it) }
        return apks.firstOrNull { UpdateLogic.namesAbi(it, "arm64-v8a") }
            ?: apks.firstOrNull { !UpdateLogic.mentionsAnyAbi(it) }
            ?: apks.firstOrNull()
    }
}

/** Qué dice el aviso antes de instalar un port sin verificar (UnverifiedDialogTest). */
object UnverifiedDialog {

    enum class Line { Unverified }

    /** Vacío = no hace falta aviso. */
    fun lines(port: PortEntry): List<Line> = if (port.verified) emptyList() else listOf(Line.Unverified)
}
