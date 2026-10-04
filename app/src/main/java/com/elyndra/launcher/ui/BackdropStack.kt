package com.elyndra.launcher.ui

/**
 * Las capas modales que apartan el fondo, apiladas (lógica pura: se prueba
 * en la JVM; la parte de Compose está en `OverlayBackdrop`).
 *
 * Reglas:
 *  - El fondo se desenfoca **una sola vez**, mientras haya alguna capa
 *    abierta: una capa encima de otra no vuelve a desenfocar nada.
 *  - La capa de más abajo pone el velo de siempre; cada capa de encima solo
 *    lo hace un poco más hondo ([BackdropLevels]). Al cerrarse, su velo se
 *    funde y el conjunto vuelve al nivel de antes.
 *  - El orden es el de dibujo ([z]: menú, ficha, selector de arte, editar
 *    nombre, diálogo), no el de apertura; a igual [z], la que llegó antes.
 *
 * [entries] es la lista donde se guardan: en la app, una lista de estado de
 * Compose (así quien la lee se recompone al cambiar); en las pruebas, una
 * lista normal.
 */
class BackdropStack(private val entries: MutableList<Entry> = ArrayList()) {

    /**
     * Una capa. [open]: abierta (false mientras se anima su salida: sigue
     * contando para el orden pero ya no pide desenfoque). [light]: diálogo
     * pequeño de confirmación (ver [BackdropLevels.BLUR_LIGHT_LAYERS]).
     */
    data class Entry(val id: Any, val z: Int, val open: Boolean, val light: Boolean = false)

    val size: Int get() = entries.size

    /** Entra una capa (o se actualiza si ya estaba, sin perder su sitio). */
    fun put(id: Any, z: Int, open: Boolean, light: Boolean = false) {
        val i = entries.indexOfFirst { it.id == id }
        val entry = Entry(id, z, open, light)
        if (i >= 0) {
            if (entries[i] != entry) entries[i] = entry
        } else {
            entries.add(entry)
        }
    }

    /** La capa ya terminó de salir. */
    fun remove(id: Any) {
        val i = entries.indexOfFirst { it.id == id }
        if (i >= 0) entries.removeAt(i)
    }

    /**
     * Cuántas capas **abiertas** hay por debajo de [id] (0 = es la de abajo);
     * -1 si no está. Las que están saliendo no cuentan: un diálogo que se
     * abre mientras el menú se cierra ya pone el velo de la capa de abajo,
     * sin bajar y volver a subir cuando el menú termine de irse.
     */
    fun depthOf(id: Any): Int {
        val me = entries.indexOfFirst { it.id == id }
        if (me < 0) return -1
        val z = entries[me].z
        return entries.withIndex().count { (i, e) -> e.open && (e.z < z || (e.z == z && i < me)) }
    }

    /**
     * La profundidad de [id], o la que tendrá al entrar con [z] (la última de
     * las de su mismo [z]): así su primer fotograma ya lleva el velo bueno.
     */
    fun depthOrNext(id: Any, z: Int): Int =
        depthOf(id).takeIf { it >= 0 } ?: entries.count { it.open && it.z <= z }

    /** ¿Hay alguna capa abierta? */
    val anyOpen: Boolean get() = entries.any { it.open }

    /**
     * ¿Se desenfoca el fondo? Con alguna capa abierta; si las únicas abiertas
     * son diálogos ligeros y [lightBlurs] es false, no (velo más hondo).
     */
    fun blurs(lightBlurs: Boolean = BackdropLevels.BLUR_LIGHT_LAYERS): Boolean =
        entries.any { it.open && (lightBlurs || !it.light) }
}

/** Lo oscuro que es el velo según cuántas capas hay apiladas. */
object BackdropLevels {

    /** Velo de la capa de abajo con desenfoque (el del menú de siempre). */
    const val BASE_BLUR = 0.5f

    /** Sin desenfoque (Android 11 o menos, o diálogo ligero sin él) el fondo sigue nítido: más hondo. */
    const val BASE_NO_BLUR = 0.68f

    /** Lo que se oscurece el conjunto por cada capa de encima: "un poco más". */
    const val STEP = 0.1f

    /** Nunca más oscuro que esto, por muchas capas que se apilen. */
    const val MAX = 0.82f

    /**
     * Los diálogos pequeños de confirmación también desenfocan (ya lo hacían
     * antes: el contenedor desenfocaba con cualquier diálogo, así que abrirlos
     * no cuesta más que antes). Si en algún dispositivo se notase un tirón,
     * con false pasan al velo más hondo sin desenfoque.
     */
    const val BLUR_LIGHT_LAYERS = true

    /** Lo oscuro que queda el fondo con [layers] capas apiladas (1 = una). */
    fun total(layers: Int, blur: Boolean): Float {
        if (layers <= 0) return 0f
        val base = if (blur) BASE_BLUR else BASE_NO_BLUR
        return (base + STEP * (layers - 1)).coerceAtMost(maxOf(MAX, base))
    }

    /**
     * El velo que pinta la capa a profundidad [depth] (0 = la de abajo) para
     * que, sumado a los de debajo, el conjunto quede en [total] de depth + 1.
     * Velos apilados se componen como 1 − (1 − a)(1 − b).
     */
    fun layerAlpha(depth: Int, blur: Boolean): Float {
        if (depth < 0) return 0f
        val below = total(depth, blur)
        val withMe = total(depth + 1, blur)
        if (below >= 1f) return 0f
        return (1f - (1f - withMe) / (1f - below)).coerceIn(0f, 1f)
    }
}
