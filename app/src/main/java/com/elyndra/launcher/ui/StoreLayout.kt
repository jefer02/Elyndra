package com.elyndra.launcher.ui

import com.elyndra.launcher.domain.ports.PortAction

/**
 * Geometría de la tienda de ports, sin Compose (StoreLayoutTest). Todo en dp.
 *
 * La tienda se lee como la de una consola de salón: fondo casi negro, mucho
 * aire a los lados (~9 % del ancho en apaisado), una fila de pestañas bajo el
 * título y rejillas de cuadrados con el foco como un marco blanco. En vertical
 * y en ventanas estrechas el aire se reduce y las columnas bajan (5 / 4 / 3 / 2).
 */
object StoreLayout {

    /* ── márgenes y cabecera ─────────────────────────────────── */

    /** El aire de cada lado: ~9 % del ancho en apaisado, 16–24 dp en vertical. */
    fun margin(width: Float, height: Float): Float =
        if (width > height && width >= 600f) (width * 0.09f).coerceIn(24f, 160f) else if (width < 600f) 16f else 24f

    /** La baldosa de la tienda (arriba a la izquierda) y la de descargas (arriba a la derecha). */
    const val HEADER_TILE = 56f
    const val HEADER_TILE_COMPACT = 44f

    fun headerTile(width: Float): Float = if (width < 600f) HEADER_TILE_COMPACT else HEADER_TILE

    /**
     * ¿Cabe la baldosa de la tienda junto al botón de volver, el título y los
     * iconos? En el móvil más estrecho no: el botón de volver hace de cabecera.
     */
    fun headerTileVisible(width: Float): Boolean = width >= 400f

    /** El título "Ports", en peso fino. */
    fun titleSize(width: Float): Float = if (width < 600f) 20f else 26f

    /** Las pestañas (sp) y su píldora de foco. */
    fun tabTextSize(width: Float): Float = if (width < 600f) 14f else 17f
    const val TAB_PILL_H = 40f
    const val TAB_GAP = 8f

    /** Los botones redondos de Buscar, Lista de deseos, Descargas y Más. */
    const val ICON_BUTTON = 44f

    /**
     * ¿Van los botones de icono a la derecha de las pestañas? Solo si caben
     * con ellas en una fila; si no, suben a la cabecera, junto a descargas.
     */
    fun iconsInTabRow(width: Float): Boolean = width >= 760f

    /* ── rejilla ─────────────────────────────────────────────── */

    const val GAP = 16f
    const val GAP_COMPACT = 10f
    const val TILE_RADIUS = 4f

    fun gap(width: Float): Float = if (width < 600f) GAP_COMPACT else GAP

    /** Lo que queda para la rejilla: el ancho menos los dos márgenes. */
    fun room(width: Float, height: Float): Float = (width - 2 * margin(width, height)).coerceAtLeast(0f)

    /** Columnas de la rejilla: 5 en apaisado ancho, luego 4, 3 y 2 en un móvil en vertical. */
    fun columns(width: Float, height: Float): Int {
        val room = room(width, height)
        return when {
            room >= 820f -> 5
            room >= 600f -> 4
            room >= 420f -> 3
            else -> 2
        }
    }

    /** Lado de cada baldosa cuadrada: las [columns] llenan la fila entera. */
    fun tileSide(width: Float, height: Float, columns: Int = columns(width, height)): Float {
        val cols = columns.coerceAtLeast(1)
        return ((room(width, height) - gap(width) * (cols - 1)) / cols).coerceAtLeast(0f)
    }

    /** La baldosa con el foco crece esto (con un muelle). */
    const val FOCUS_SCALE = 1.04f
    const val FOCUS_BORDER = 3f

    /** La rendija oscura entre el marco blanco y el arte. */
    const val FOCUS_GAP = 2f

    /** Duración (ms) de los cambios de foco y del cruce entre pestañas. */
    const val FOCUS_MS = 140
    const val TAB_MS = 160

    /** Lo que se desliza el contenido al cambiar de pestaña. */
    const val TAB_SLIDE = 24f

    /**
     * El botón redondo de orden y filtro: en el margen izquierdo, a la altura
     * de la primera fila, si el margen da para él; si no, en la cabecera de la sección.
     */
    const val SORT_BUTTON = 48f

    fun sortButtonInMargin(width: Float, height: Float): Boolean = margin(width, height) >= SORT_BUTTON + 16f

    /** El indicador de desplazamiento del borde derecho: [fraction] (0..1) de lo visto y su largo. */
    fun scrollThumb(trackHeight: Float, firstVisibleRow: Int, visibleRows: Int, totalRows: Int): Pair<Float, Float> {
        if (totalRows <= 0 || visibleRows >= totalRows) return 0f to trackHeight
        val length = (trackHeight * visibleRows / totalRows).coerceAtLeast(24f).coerceAtMost(trackHeight)
        val maxFirst = (totalRows - visibleRows).coerceAtLeast(1)
        val top = (trackHeight - length) * (firstVisibleRow.coerceIn(0, maxFirst).toFloat() / maxFirst)
        return top to length
    }

    /* ── portada (Novedades) ─────────────────────────────────── */

    /** Los dos carteles grandes, lado a lado (≈ 4:3); en vertical, uno debajo del otro. */
    fun heroColumns(width: Float, height: Float): Int = if (width > height && room(width, height) >= 520f) 2 else 1

    fun heroWidth(width: Float, height: Float): Float {
        val cols = heroColumns(width, height)
        return (room(width, height) - gap(width) * (cols - 1)) / cols
    }

    /** Alto de un cartel: 4:3, sin pasar de lo que deja ver la fila de debajo en apaisado. */
    fun heroHeight(width: Float, height: Float): Float {
        val byAspect = heroWidth(width, height) * 0.75f
        return if (width > height) byAspect.coerceAtMost(height * 0.62f) else byAspect.coerceAtMost(320f)
    }

    /** Las tarjetas apaisadas (16:9) de los carruseles: siempre asoma la siguiente. */
    fun cardWidth(width: Float, height: Float): Float {
        val room = room(width, height)
        val shown = when {
            room < 420f -> 1.6f
            room < 700f -> 2.4f
            room < 1000f -> 3.4f
            else -> 4.3f
        }
        return (room / shown - gap(width)).coerceIn(150f, 320f)
    }

    fun cardHeight(cardWidth: Float): Float = cardWidth * 9f / 16f

    /** Colecciones: tarjetas apaisadas en 3, 2 o 1 columnas. */
    fun collectionColumns(width: Float, height: Float): Int {
        val room = room(width, height)
        return when {
            room >= 820f -> 3
            room >= 420f -> 2
            else -> 1
        }
    }

    fun collectionWidth(width: Float, height: Float): Float {
        val cols = collectionColumns(width, height)
        return (room(width, height) - gap(width) * (cols - 1)) / cols
    }

    /* ── ficha ───────────────────────────────────────────────── */

    /** ¿Ficha a lo ancho (panel flotante a la izquierda sobre el arte) o apilada (vertical)? */
    fun detailWide(width: Float, height: Float): Boolean = width > height && width >= 640f

    /** El panel de la ficha: ~40 % del ancho en apaisado; todo el ancho en vertical. */
    fun detailPanelWidth(width: Float, height: Float): Float =
        if (detailWide(width, height)) (width * 0.4f).coerceIn(300f, 520f) else width - 2 * margin(width, height)

    /** El arte a sangre de la ficha: casi toda la pantalla en apaisado; 16:9 arriba en vertical. */
    fun detailHeroHeight(width: Float, height: Float): Float =
        if (detailWide(width, height)) height * 0.78f else width * 9f / 16f

    /** Dónde empieza el panel: abajo del arte en apaisado; montado sobre su borde en vertical. */
    fun detailPanelTop(width: Float, height: Float): Float =
        if (detailWide(width, height)) (height * 0.3f).coerceAtLeast(96f) else detailHeroHeight(width, height) - 40f
}

/** Las pestañas de la tienda, en el orden de la fila. (La lista de deseos no es una pestaña: es una hoja.) */
enum class StoreTab {
    Latest, Collections, Updates, Installed, Browse;

    companion object {
        val ROW = entries.toList()

        /** L1/R1: la de al lado en la fila, dando la vuelta. */
        fun cycle(current: StoreTab, delta: Int): StoreTab = ROW[Math.floorMod(ROW.indexOf(current) + delta, ROW.size)]
    }
}

/**
 * Lo que dice una baldosa abajo a la izquierda (como el precio en una tienda
 * de consola): un texto, una caja blanca opcional delante ("NUEVO",
 * "ACTUALIZADO", "v1.3") y, para una actualización, la versión vieja tachada.
 * NUEVO: el port entró en el catálogo hace menos de [RECENT_MS] (ver
 * PortFirstSeen); ACTUALIZADO: su última release es así de reciente. Si
 * cumple las dos, NUEVO.
 * Kotlin puro (StoreLayoutTest); la interfaz pone los textos.
 */
data class TileStatus(val text: Text, val badge: Badge? = null, val struck: String? = null, val size: Long = 0, val percent: Int = 0) {
    enum class Text { Free, Size, Installed, Update, Downloading, NotOnAndroid, VrOnly, Unavailable }

    sealed interface Badge {
        data object New : Badge
        data object Updated : Badge
        data class Version(val tag: String) : Badge
    }

    companion object {
        /** Lo que cuenta como "reciente" para NUEVO y ACTUALIZADO. */
        const val RECENT_MS = 14L * 24 * 60 * 60 * 1000

        fun of(
            action: PortAction,
            progress: Float? = null,
            installedTag: String? = null,
            latestTag: String? = null,
            sizeBytes: Long = 0,
            publishedAt: Long = 0,
            now: Long = 0,
            /** Cuándo entró en el catálogo (0: desde antes de que la app lo mirara). */
            addedAt: Long = 0,
        ): TileStatus {
            if (progress != null) return TileStatus(Text.Downloading, percent = (progress.coerceIn(0f, 1f) * 100).toInt())
            fun within(t: Long) = t > 0 && now - t in 0..RECENT_MS
            val added = within(addedAt)
            val recent = within(publishedAt)
            return when (action) {
                PortAction.Update -> TileStatus(
                    Text.Update,
                    badge = latestTag?.let { Badge.Version(shortTag(it)) },
                    struck = installedTag?.takeIf { it != latestTag }?.let { shortTag(it) },
                )
                PortAction.Installed -> TileStatus(Text.Installed)
                PortAction.NotOnAndroid -> TileStatus(Text.NotOnAndroid)
                PortAction.VrOnly -> TileStatus(Text.VrOnly)
                PortAction.Unavailable, PortAction.SignatureChanged -> TileStatus(Text.Unavailable)
                PortAction.Free, PortAction.Install -> {
                    val badge = when {
                        added -> Badge.New
                        recent -> Badge.Updated
                        else -> null
                    }
                    if (sizeBytes > 0) TileStatus(Text.Size, badge, size = sizeBytes) else TileStatus(Text.Free, badge)
                }
            }
        }

        /** "v9.3.1-ports.2" → "v9.3.1": lo que cabe en la caja. */
        fun shortTag(tag: String): String {
            val core = tag.trim().substringBefore('-').substringBefore('+').ifEmpty { tag.trim() }
            val clipped = if (core.length > 10) core.take(10) else core
            return if (clipped.firstOrNull()?.isDigit() == true) "v$clipped" else clipped
        }
    }
}
