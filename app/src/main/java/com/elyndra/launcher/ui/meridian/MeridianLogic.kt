package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.ui.DotTabsLayout
import com.elyndra.launcher.ui.TopBarLayout
import com.elyndra.launcher.ui.theme.WheelGlass
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/* ─────────────────────────────────────────────────────────────
   Meridian, sin Compose: qué modo toca, cómo se deforma la rueda
   según la distancia a la línea de foco, el círculo que siguen las
   filas y el dial, dónde se para al soltarla, el muelle que la lleva,
   el dial y el reparto de la pantalla.
   Todo son números para probarlo en la JVM; el dibujo vive en los
   demás archivos del paquete.
   ───────────────────────────────────────────────────────────── */

/** Cómo se ve la lista de juegos con la ventana apaisada. Se guarda en SettingsStore por su [id]. */
enum class LayoutStyle(val id: String) {
    /** Rueda vertical con el dial luminoso y el arte a pantalla completa. */
    Meridian("meridian"),

    /** El carrusel horizontal de siempre. */
    Classic("classic"),
    ;

    companion object {
        val DEFAULT = Meridian

        /** Lo guardado; sin nada guardado (o algo que ya no existe), Meridian. */
        fun byId(id: String?): LayoutStyle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * El fondo detrás de la rueda de Meridian (Ajustes → Apariencia): cristal
 * curvo (el arte casi tal cual) o difuminado (el velo de siempre, que funde
 * el arte con el color del tema). Sin elegir nunca, cristal en el tema claro
 * y difuminado en el oscuro; elegido, el mismo en los dos.
 */
object WheelBackdrop {
    fun glass(choice: Boolean?, dark: Boolean): Boolean = choice ?: !dark
}

object MeridianMode {

    /** Ancho mínimo de ventana (dp) para la rueda: por debajo, el carrusel no se toca. */
    const val MIN_WIDTH_DP = 640f

    /** Por debajo de este alto (dp) la ventana es baja (móvil apaisado): todo va en su versión compacta. */
    const val COMPACT_HEIGHT_DP = 480f

    /**
     * Meridian solo con la ventana apaisada (más ancha que alta) y ancha de
     * verdad, y si el usuario no ha elegido el carrusel. Se decide por el
     * tamaño de la ventana, nunca por el tipo de aparato: una tableta en
     * vertical o una ventana estrecha en pantalla partida siguen con el carrusel.
     */
    fun active(widthDp: Float, heightDp: Float, style: LayoutStyle): Boolean =
        style == LayoutStyle.Meridian && widthDp > heightDp && widthDp >= MIN_WIDTH_DP

    /** Ventana baja: barra más justa, rueda al 40 %, bloque del hero compacto y el bocadillo de Masha en una línea. */
    fun compact(heightDp: Float): Boolean = heightDp < COMPACT_HEIGHT_DP
}

/**
 * El círculo de la rueda: su centro queda fuera de la pantalla, a la
 * izquierda, a la altura de la línea de foco. Las filas y el dial siguen el
 * mismo círculo (las dos llaman a [offset]), así que nunca se separan.
 */
object WheelArc {

    /** Radio del círculo en altos de la rueda: curva clara sin sacar las vecinas de la columna. */
    const val RADIUS = 1.6f

    fun radius(railHeight: Float): Float = railHeight * RADIUS

    /**
     * Cuánto se va a la izquierda (≤ 0) el punto del círculo que está a [dy]
     * de la línea de foco. Continua y simétrica; más allá del radio se queda
     * en el radio (ahí no queda nada visible).
     */
    fun offset(dy: Float, radius: Float): Float {
        if (radius <= 0f) return 0f
        val y = min(abs(dy), radius)
        return -(radius - sqrt(radius * radius - y * y))
    }

    /** La normal del círculo en ese punto (hacia su centro), en [out]: x, y unitarios. Sin objetos por fotograma. */
    fun inward(dy: Float, radius: Float, out: FloatArray) {
        if (radius <= 0f) {
            out[0] = -1f
            out[1] = 0f
            return
        }
        val y = dy.coerceIn(-radius, radius)
        val x = sqrt(radius * radius - y * y)
        out[0] = -x / radius
        out[1] = -y / radius
    }
}

/**
 * La forma de la rueda, en tablas ajustables (una entrada por fila de
 * distancia a la línea de foco: 0, ±1, ±2, ±3, ±4) que se interpolan en
 * línea para las distancias intermedias (al girar no hay saltos).
 *
 * La fila del centro es la más grande y la más cercana; las demás encogen,
 * se apagan, giran un poco en perspectiva, siguen el círculo de [WheelArc] y
 * se cubren de "niebla". Las lejanas quedan algo por detrás de las cercanas.
 * Se aplica en la capa de cada fila: al girar no se remide ni se recompone nada.
 *
 * Las longitudes van en altos de la tarjeta enfocada, que es también el alto
 * de cada hueco de la lista (así la fila `i` está en la línea cuando la
 * posición vale `i`).
 */
object WheelTransform {

    /** La línea de foco: el centro exacto de la columna. */
    const val FOCUS_FRACTION = 0.5f

    /** Alto de la tarjeta enfocada respecto al alto de la rueda: iconos cuadrados (y apaisadas) y carátulas verticales. */
    const val ICON_TILE = 0.38f
    const val COVER_TILE = 0.48f

    /** Ancho máximo de la tarjeta enfocada: respecto a la ventana y respecto a la rueda (al nombre le queda sitio). */
    const val MAX_TILE_WIDTH = 0.24f
    const val MAX_TILE_RAIL = 0.42f

    /** Topes absolutos del alto de la tarjeta enfocada (dp). */
    const val TILE_MIN = 88f
    const val TILE_MAX = 380f

    /** Escala por distancia (la enfocada mide 1). */
    val SCALE = floatArrayOf(1f, 0.64f, 0.46f, 0.32f, 0.24f)

    /** Opacidad por distancia: desde ±4, invisible. */
    val ALPHA = floatArrayOf(1f, 0.85f, 0.5f, 0.25f, 0f)

    /** Calidad ligera: solo ±2. */
    val ALPHA_LITE = floatArrayOf(1f, 0.85f, 0.5f, 0f, 0f)

    /** Giro en perspectiva por distancia (grados). */
    val ROTATION = floatArrayOf(0f, 6f, 12f, 18f, 18f)

    /** Niebla sobre el arte por distancia (alfa del color del fondo). */
    val FOG = floatArrayOf(0f, 0.12f, 0.26f, 0.42f, 0.55f)

    /** Cuánto texto conserva la fila: el nombre se lee en la enfocada y sus vecinas; desde ±2, solo el arte. */
    val DETAIL = floatArrayOf(1f, 1f, 0f, 0f, 0f)

    /**
     * Hueco entre una fila y la siguiente, en altos de tarjeta: entre la
     * enfocada y sus vecinas, un poco de aire; más allá, solape (las lejanas se
     * meten un poco por detrás de las cercanas).
     */
    val GAP = floatArrayOf(0.02f, -0.06f, -0.08f, -0.10f)

    /** Centro visual de cada fila (en altos de tarjeta), sumando mitades y huecos: los huecos quedan regulares. */
    val CENTER: FloatArray = FloatArray(SCALE.size).also { c ->
        for (n in 1 until c.size) c[n] = c[n - 1] + (SCALE[n - 1] + SCALE[n]) / 2f + GAP[n - 1]
    }

    /** El radio del círculo en altos de tarjeta para una rueda de iconos (la miniatura de Ajustes y las pruebas). */
    const val DEFAULT_RADIUS_TILES = WheelArc.RADIUS / ICON_TILE

    /** Cuerpo (sp) del nombre de las filas que no están en la línea (la escala de la capa lo deja en ~12–13 sp a una fila). */
    const val NEIGHBOR_SP = 20f
    const val NEIGHBOR_SP_COMPACT = 19f

    /**
     * Alto de la tarjeta enfocada (dp) para una rueda de [railHeight] × [railWidth] dp,
     * una ventana de [windowWidth] dp y una proporción ancho/alto [aspect].
     */
    fun tileHeight(railHeight: Float, windowWidth: Float, aspect: Float, railWidth: Float = Float.MAX_VALUE): Float {
        val byHeight = railHeight * if (aspect < 1f) COVER_TILE else ICON_TILE
        val byWidth = min(windowWidth * MAX_TILE_WIDTH, railWidth * MAX_TILE_RAIL) / aspect.coerceAtLeast(0.1f)
        return min(byHeight, byWidth).coerceIn(TILE_MIN, TILE_MAX).coerceAtMost(railHeight.coerceAtLeast(TILE_MIN))
    }

    /** Interpolación lineal de una tabla por la distancia (sin signo); más allá de la última entrada, la última. */
    fun lookup(table: FloatArray, distance: Float): Float {
        val x = abs(distance)
        val last = table.size - 1
        if (x >= last) return table[last]
        val i = x.toInt()
        val t = x - i
        return table[i] + (table[i + 1] - table[i]) * t
    }

    fun scale(distance: Float): Float = lookup(SCALE, distance)

    fun alpha(distance: Float, lite: Boolean = false): Float = lookup(if (lite) ALPHA_LITE else ALPHA, distance)

    fun detail(distance: Float): Float = lookup(DETAIL, distance)

    /** La niebla también con "reducir movimiento" (es profundidad, no movimiento); sin ella en calidad ligera. */
    fun fog(distance: Float, lite: Boolean = false): Float = if (lite) 0f else lookup(FOG, distance)

    /** La curva en altos de tarjeta (≤ 0): el círculo de [WheelArc] a la altura del centro visual de la fila. */
    fun arc(distance: Float, radiusTiles: Float = DEFAULT_RADIUS_TILES): Float = WheelArc.offset(center(distance), radiusTiles)

    /** Giro en perspectiva (grados): las filas se vuelven hacia el arte al alejarse. Sin giro con "reducir movimiento" ni en calidad ligera. */
    fun rotationY(distance: Float, flat: Boolean): Float = if (flat) 0f else -lookup(ROTATION, distance)

    /** Centro visual (altos de tarjeta, con signo). Más allá de la tabla sigue con el paso de la última fila. */
    fun center(distance: Float): Float {
        val x = abs(distance)
        val last = CENTER.size - 1
        val c = if (x >= last) {
            CENTER[last] + (x - last) * (SCALE[last] + GAP[GAP.size - 1])
        } else {
            val i = x.toInt()
            CENTER[i] + (CENTER[i + 1] - CENTER[i]) * (x - i)
        }
        return sign(distance) * c
    }

    /**
     * La compensación vertical (en altos de tarjeta = huecos de la lista): la
     * escala de la capa no cambia lo que mide la fila, así que cada una se
     * mueve de donde la pone la lista ([distance] huecos) a su centro visual.
     */
    fun shift(distance: Float): Float = center(distance) - distance

    /** Orden de dibujo: las cercanas por encima de las lejanas (se fija al cambiar la selección, no al girar). */
    fun zOrder(index: Int, focused: Int): Float = -abs(index - focused).toFloat()

    /** Cuerpo (sp) del nombre: grande en la enfocada (dos líneas como mucho); el de las demás, fijo (la capa lo escala). */
    fun titleSp(tileHeight: Float, focused: Boolean, compact: Boolean = false): Float = when {
        focused && compact -> (tileHeight * 0.14f).coerceIn(18f, 20f)
        focused -> (tileHeight * 0.12f).coerceIn(20f, 24f)
        compact -> NEIGHBOR_SP_COMPACT
        else -> NEIGHBOR_SP
    }

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Todo junto, para las pruebas y las vistas previas (la rueda llama a cada función por separado). */
    fun pose(distance: Float, reduced: Boolean, lite: Boolean = false, radiusTiles: Float = DEFAULT_RADIUS_TILES): WheelPose = WheelPose(
        scale = scale(distance),
        alpha = alpha(distance, lite),
        arc = arc(distance, radiusTiles),
        rotationY = rotationY(distance, reduced || lite),
        shift = shift(distance),
        detail = detail(distance),
        fog = fog(distance, lite),
    )
}

data class WheelPose(
    val scale: Float,
    val alpha: Float,
    val arc: Float,
    val rotationY: Float,
    val shift: Float,
    val detail: Float,
    val fog: Float,
)

/**
 * Listas cortas (1 a 4 filas): en vez de dejar un hueco grande encima de la
 * primera, la pila entera se centra en la rueda. La línea de foco se mueve
 * entre su sitio de siempre y la que centra la pila ([lift]); como depende de
 * la posición continua de la rueda, filas, nodo y dial se mueven juntos y
 * sin saltos. Desde 6 filas no se mueve nada.
 */
object ShortList {

    const val MAX_CENTERED = 4

    /** Cuánto se centra: del todo hasta 4 filas, a medias con 5, nada desde 6. */
    fun weight(count: Int): Float = when {
        count <= 0 -> 0f
        count <= MAX_CENTERED -> 1f
        count == MAX_CENTERED + 1 -> 0.5f
        else -> 0f
    }

    /**
     * Lo que baja (+) o sube (−) la línea de foco, en altos de tarjeta, con la
     * rueda en [position], [count] filas, una rueda de [railTiles] tarjetas de
     * alto y la línea de siempre a [focusTiles]. La enfocada nunca se sale.
     */
    fun lift(position: Float, count: Int, railTiles: Float, focusTiles: Float): Float {
        val k = weight(count)
        if (k == 0f) return 0f
        val first = -position
        val last = count - 1 - position
        val top = WheelTransform.center(first) - WheelTransform.scale(first) / 2f
        val bottom = WheelTransform.center(last) + WheelTransform.scale(last) / 2f
        val centered = railTiles / 2f - (top + bottom) / 2f
        val target = centered.coerceIn(0.5f, (railTiles - 0.5f).coerceAtLeast(0.5f))
        return k * (target - focusTiles)
    }
}

/**
 * Los cantos de la rueda: una fila que se mete bajo lo que tapa la parte de
 * arriba (la cabecera) se apaga antes
 * de tocarlo; lo mismo abajo. [visualHeight] es el alto de la fila ya escalada.
 */
object RailEdge {

    fun fade(centerY: Float, visualHeight: Float, top: Float, bottom: Float): Float {
        val h = visualHeight.coerceAtLeast(1f)
        val a = WheelTransform.smoothstep(top - h * 0.2f, top + h * 0.35f, centerY)
        val b = 1f - WheelTransform.smoothstep(bottom - h * 0.35f, bottom + h * 0.2f, centerY)
        return min(a, b)
    }
}

/**
 * La posición de la rueda y dónde se para. Todas las filas miden lo mismo
 * ([rowPx]) y el relleno de arriba pone la fila 0 en la línea de foco, así
 * que la fila `i` está en la línea cuando la posición vale exactamente `i`.
 */
object WheelMath {

    /** Posición en filas a partir del desplazamiento lógico de la lista. */
    fun position(firstIndex: Int, firstOffsetPx: Int, rowPx: Float): Float =
        if (rowPx <= 0f) firstIndex.toFloat() else firstIndex + firstOffsetPx / rowPx

    /** Distancia (con signo) de la fila [index] a la línea de foco. */
    fun distance(index: Int, position: Float): Float = index - position

    /**
     * Centro visual (px desde arriba de la rueda) de la fila [index]: la línea
     * de foco ([focusY], ya movida por [liftPx]) más su centro en la rueda. El
     * nodo del dial va en `focusY + liftPx`: con la rueda parada, el centro
     * exacto de la tarjeta enfocada.
     */
    fun visualCenter(index: Int, position: Float, focusY: Float, liftPx: Float, rowPx: Float): Float =
        focusY + liftPx + WheelTransform.center(distance(index, position)) * rowPx

    /**
     * Dónde para un lanzamiento: lo que daría la inercia menos una fila (la
     * última la pone el ajuste final, que así llega con muelle y no de golpe).
     */
    fun approachOffset(decayOffset: Float, rowPx: Float): Float =
        sign(decayOffset) * (abs(decayOffset) - rowPx).coerceAtLeast(0f)

    /**
     * La fila en la que se queda: la más cercana si se suelta despacio; si no,
     * la siguiente en el sentido del gesto. Siempre dentro de la lista.
     */
    fun snapTarget(position: Float, velocity: Float, minVelocity: Float, count: Int): Int {
        if (count <= 0) return 0
        val raw = when {
            abs(velocity) < minVelocity -> position.roundToInt()
            velocity > 0f -> ceil(position - EPS).toInt()
            else -> floor(position + EPS).toInt()
        }
        return raw.coerceIn(0, count - 1)
    }

    private const val EPS = 1e-3f

    /** Frecuencia del muelle que lleva la rueda a su fila (rad/s): llega en ~0,3 s sin rebotar. */
    const val OMEGA = 15f

    /**
     * Un paso del muelle críticamente amortiguado que lleva la rueda hacia
     * [target], resuelto de forma exacta (estable con cualquier [dt]). Escribe
     * la nueva posición y velocidad en [out] (sin crear objetos por fotograma).
     * Si el destino cambia a mitad, la velocidad se conserva: el mando puede
     * pedir filas nuevas sin que la rueda se frene a cada paso.
     */
    fun springStep(x: Float, v: Float, target: Float, dt: Float, out: FloatArray, omega: Float = OMEGA) {
        val d0 = x - target
        val e = exp(-omega * dt)
        val c = v + omega * d0
        out[0] = target + (d0 + c * dt) * e
        out[1] = (v - omega * c * dt) * e
    }

    /** Ya está en su sitio: cerca de la fila y casi quieta. */
    fun settled(x: Float, v: Float, target: Float): Boolean = abs(x - target) < 0.002f && abs(v) < 0.02f

    /** Más allá de este salto (en filas) la rueda se coloca cerca y solo recorre el final. */
    const val JUMP = 8

    /** Filas que recorre con muelle tras colocarse en un salto largo. */
    const val JUMP_TAIL = 3

    /**
     * La fila con la que arranca la rueda (al girar el aparato, al cambiar el
     * tamaño de la ventana o al pasar del carrusel a la rueda): la seleccionada,
     * o "Añadir" si es la que señala el mando. Sin selección, la primera (la que
     * ya enseña el hero).
     */
    fun restoreIndex(keys: List<String>, selectedKey: String?, addFocused: Boolean, hasAdd: Boolean): Int {
        if (addFocused && hasAdd) return keys.size
        val i = keys.indexOf(selectedKey)
        return if (i >= 0) i else 0
    }
}

/** El mando en la rueda: mantener la cruceta acelera. */
object MeridianInput {

    /**
     * Filas por paso según cuántas repeticiones lleva la cruceta mantenida
     * (0 = la pulsación). Las primeras van de una en una, como siempre; luego
     * de dos, tres y cinco, para cruzar listas largas sin soltar el botón.
     */
    fun stepFor(repeats: Int): Int = when {
        repeats < 6 -> 1
        repeats < 12 -> 2
        repeats < 20 -> 3
        else -> 5
    }
}

/**
 * La escala del dial: una marca por juego, a [spacing] dp una de otra a lo
 * largo del arco, que ruedan con la lista (la del juego `i` está a
 * `(i − posición) × spacing` de la muesca de foco). En listas largas las
 * marcas van más juntas y solo se pinta una de cada [stride] (las demás
 * existen, pero no se ven); cada [majorEvery] juegos, una marca mayor.
 */
data class DialScale(val count: Int, val spacing: Float, val stride: Int, val majorEvery: Int) {

    fun isMajor(index: Int): Boolean = majorEvery > 0 && index % majorEvery == 0

    /** Primera marca pintada a la vista, para un dial que llega [up] px por encima de la muesca, con marcas a [spacingPx]. */
    fun first(position: Float, up: Float, spacingPx: Float): Int {
        if (count <= 0 || spacingPx <= 0f) return 0
        val raw = ceil(position - up / spacingPx).toInt().coerceIn(0, count - 1)
        val r = raw % stride
        return if (r == 0) raw else raw + (stride - r)
    }

    /** Última marca a la vista, para un dial que llega [down] px por debajo de la muesca. */
    fun last(position: Float, down: Float, spacingPx: Float): Int {
        if (count <= 0 || spacingPx <= 0f) return -1
        return floor(position + down / spacingPx).toInt().coerceIn(0, count - 1)
    }

    /**
     * El tramo del arco que se pinta (px respecto a la muesca, en [out]: arriba
     * —negativo— y abajo): de la primera marca a la última con [padPx] de
     * margen, sin pasar de [up] ni de [down]. Con pocas marcas, un arco corto.
     */
    fun span(position: Float, spacingPx: Float, padPx: Float, up: Float, down: Float, out: FloatArray) {
        if (count <= 0) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        out[0] = ((0 - position) * spacingPx - padPx).coerceIn(-up.coerceAtLeast(0f), 0f)
        out[1] = ((count - 1 - position) * spacingPx + padPx).coerceIn(0f, down.coerceAtLeast(0f))
    }

    companion object {
        /** Distancias (dp) y cada cuántas se pinta, por tamaño de la lista. */
        const val SPACING_SHORT = 16f
        const val SPACING_MEDIUM = 11f
        const val SPACING_LONG = 6f
        const val SPACING_HUGE = 4f

        /** Lo más juntas (dp) que llegan a verse dos marcas pintadas. */
        const val MIN_DRAWN_GAP = 11f

        /** Sin juegos no hay dial. */
        fun of(count: Int): DialScale? = when {
            count <= 0 -> null
            count <= 24 -> DialScale(count, SPACING_SHORT, 1, 0)
            count <= 120 -> DialScale(count, SPACING_MEDIUM, 1, 5)
            count <= 600 -> DialScale(count, SPACING_LONG, 2, 10)
            else -> DialScale(count, SPACING_HUGE, 3, 30)
        }

        /** Cuánto se ilumina una marca a [dy] de la muesca: 1 en ella, 0 a una marca de distancia. */
        fun lit(dy: Float, spacingPx: Float): Float = 1f - WheelTransform.smoothstep(0f, spacingPx.coerceAtLeast(1f) * 0.9f, abs(dy))

        /** Lo que se apagan las marcas y el arco hacia los extremos del tramo ([fraction] = |dy| / extremo). */
        fun endFade(fraction: Float): Float = 1f - WheelTransform.smoothstep(0.62f, 1f, fraction)
    }
}

/**
 * La entrada de Meridian, medida en milisegundos desde que arranca (un solo
 * reloj para todo, leído en las capas): el dial se enciende desde la muesca
 * hacia fuera, las filas entran en cascada desde la izquierda (solo las
 * [CASCADE_ROWS] primeras esperan su turno) y el arte se funde.
 */
object MeridianMotion {

    const val IGNITE_MS = 640f
    const val CASCADE_STEP_MS = 25f
    const val CASCADE_ROW_MS = 360f
    const val CASCADE_ROWS = 8
    const val HERO_FADE_MS = 420f

    /** Lo que dura la entrada entera. */
    val TOTAL_MS: Float get() = maxOf(IGNITE_MS, (CASCADE_ROWS - 1) * CASCADE_STEP_MS + CASCADE_ROW_MS, HERO_FADE_MS)

    /** Cuánto dial hay encendido (0…1), con la curva de salida rápida y llegada suave. */
    fun ignite(ms: Float): Float = easeOut(ms / IGNITE_MS)

    /** Progreso de la fila que entra en el puesto [order] (0 = la de arriba de la pantalla). */
    fun cascade(ms: Float, order: Int): Float {
        val k = order.coerceIn(0, CASCADE_ROWS - 1)
        return easeOut((ms - k * CASCADE_STEP_MS) / CASCADE_ROW_MS)
    }

    fun heroFade(ms: Float): Float = easeOut(ms / HERO_FADE_MS)

    private fun easeOut(t: Float): Float {
        val u = t.coerceIn(0f, 1f)
        return 1f - (1f - u) * (1f - u) * (1f - u)
    }
}

/**
 * El reparto de la pantalla en Meridian (dp): la rueda a la izquierda (~42 %
 * del ancho; 40 % en una ventana baja), el dial en su canto derecho y el arte
 * con el bloque del hero a la derecha. [railHeight] es el alto que le queda a
 * la rueda bajo su cabecera y [rowHeight] el alto de la tarjeta enfocada
 * (= cada hueco de la lista).
 */
data class MeridianGeometry(
    val railWidth: Float,
    val railHeight: Float,
    val rowHeight: Float,
    /** La línea de foco medida desde arriba de la rueda (sin el ajuste de las listas cortas). */
    val focusY: Float,
    val compact: Boolean = false,
) {
    val axisX: Float get() = railWidth
    val heroLeft: Float get() = railWidth + HERO_GAP

    /** Radio del círculo que siguen filas y dial. */
    val arcRadius: Float get() = WheelArc.radius(railHeight)

    /** Relleno de arriba y de abajo de la lista: la primera y la última fila pueden llegar a la línea. */
    val padTop: Float get() = (focusY - rowHeight / 2f).coerceAtLeast(0f)
    val padBottom: Float get() = (railHeight - focusY - rowHeight / 2f).coerceAtLeast(0f)

    /** Margen izquierdo de las tarjetas: la curva de las vecinas (±1) no las saca de la pantalla; las lejanas pueden asomar. */
    val inset: Float get() = RAIL_START + abs(WheelArc.offset(WheelTransform.center(1f) * rowHeight, arcRadius))

    companion object {
        const val RAIL_FRACTION = 0.42f
        const val RAIL_MIN = 300f
        const val RAIL_MAX = 600f
        const val COMPACT_RAIL_FRACTION = 0.40f
        const val COMPACT_RAIL_MIN = 280f
        const val COMPACT_RAIL_MAX = 520f
        const val RAIL_START = 16f

        /**
         * La rueda ya no lleva el nombre junto a cada icono (está en el hero): su
         * columna mide lo que el icono enfocado (con la curva de las vecinas) más
         * [TILE_TO_DIAL] hasta el dial, entre [SLIM_MIN] y el ancho de antes.
         */
        const val TILE_TO_DIAL = 44f
        const val SLIM_MIN = 200f

        /** Lo que se cuenta para la cabecera de la rueda al estimar su alto (dp): normal y en ventana baja. */
        const val HEADER_ESTIMATE = 60f
        const val HEADER_ESTIMATE_COMPACT = 50f

        /**
         * El ancho de la columna de la rueda (solo iconos) para una ventana de
         * [width] × [height] dp con tarjetas de proporción [aspect]. El icono
         * conserva el tamaño de siempre (el que daba la columna ancha).
         */
        fun slimRailWidth(width: Float, height: Float, aspect: Float = 1f, compact: Boolean = false): Float {
            val classic = railWidthFor(width, compact)
            val railH = (height - if (compact) HEADER_ESTIMATE_COMPACT else HEADER_ESTIMATE).coerceAtLeast(WheelTransform.TILE_MIN)
            val tile = WheelTransform.tileHeight(railH, width, aspect, classic)
            val inset = RAIL_START + abs(WheelArc.offset(WheelTransform.center(1f) * tile, WheelArc.radius(railH)))
            // Nunca más que la de siempre (que con una ventana sin medir aún vale 0).
            return (inset + tile * aspect + TILE_TO_DIAL).coerceAtLeast(SLIM_MIN).coerceAtMost(classic)
        }

        /**
         * Hueco entre el dial y el arranque del bloque del hero. Antes medía 96
         * para el contador del nodo; sin él solo separa el dial del bloque, y
         * esos dp son arte. No baja de 48 por dos cosas medidas: el velo (que
         * necesita [MeridianScrims.FADE_DP] para no hacer canto) ya está por
         * debajo del 0,25 al llegar aquí, así que no apaga el texto blanco del
         * hero en el tema claro; y lo que asoma el dock de la cabecera
         * ([RailHeaderLayout.OVERHANG]) le deja en un móvil al menos el sitio
         * que tenía con el orden al lado.
         */
        const val HERO_GAP = 48f

        /** Margen de abajo del bloque del hero, en fracción del alto de la pantalla. */
        const val HERO_BOTTOM = 0.08f
        const val HERO_BOTTOM_COMPACT = 0.06f

        /** El ancho de la rueda para una ventana de [width] dp: ~42 % (40 % baja), con topes, y nunca más de la mitad. */
        fun railWidthFor(width: Float, compact: Boolean = false): Float =
            if (compact) {
                (width * COMPACT_RAIL_FRACTION).coerceIn(COMPACT_RAIL_MIN, COMPACT_RAIL_MAX).coerceAtMost(width * 0.5f)
            } else {
                (width * RAIL_FRACTION).coerceIn(RAIL_MIN, RAIL_MAX).coerceAtMost(width * 0.5f)
            }

        /**
         * [aspect] es ancho/alto del arte de las tarjetas (1 iconos, 2/3 carátulas).
         * [railWidth] es el ancho de la columna (por omisión, el de siempre); el
         * icono se mide siempre con el de siempre.
         */
        fun compute(width: Float, railHeight: Float, aspect: Float = 1f, compact: Boolean = false, railWidth: Float = railWidthFor(width, compact)): MeridianGeometry {
            val h = railHeight.coerceAtLeast(WheelTransform.TILE_MIN)
            val rail = railWidth
            val tile = WheelTransform.tileHeight(h, width, aspect, railWidthFor(width, compact))
            val focus = (h * WheelTransform.FOCUS_FRACTION).coerceIn(tile / 2f, (h - tile / 2f).coerceAtLeast(tile / 2f))
            return MeridianGeometry(rail, h, tile, focus, compact)
        }
    }
}

/**
 * El velo neutro del tema (con "Color de fondo adaptable" apagado, o cuando
 * el color del arte no daría el contraste): tinta honda en oscuro, perla
 * cálida en claro, más fuerte en el canto izquierdo, que se mantiene hasta el
 * eje y se desvanece del todo [FADE_DP] más allá, justo pasada la columna de
 * la rueda. El adaptable usa el mismo perfil de opacidad como máscara (ver
 * [ArtWash]). El peor caso es arte negro o blanco puro (o muy saturado).
 *
 * Todo en dp (quien pinta en px convierte antes: ver la máscara de [MeridianFog]).
 */
object MeridianScrims {

    const val MIN_TEXT = 4.5
    const val MIN_UI = 3.0

    /**
     * Opacidad del velo en el canto izquierdo y en el eje. Iguales: detrás
     * de las tarjetas (opacas) no hace falta más velo que bajo el texto, y
     * así el arte asoma entre las filas en vez de quedar lavado.
     */
    const val EDGE_ALPHA = 0.86f
    const val AXIS_ALPHA = 0.86f

    /**
     * Lo que tarda el velo en apagarse pasado el eje (dp). Antes era el 16 %
     * del ancho (hasta ~200 dp en una tableta), en parte para que el contador
     * del nodo tuviera fondo; sin contador basta con una banda fija junto a la
     * rueda y el resto es arte. Es lo más corto sin canto: con la caída de
     * [FALLOFF], por debajo de ~80 dp el velo baja más de 0,02 por dp y se ve
     * el borde.
     */
    const val FADE_DP = 88f

    /**
     * La caída más allá del eje: `(1 − smoothstep)^FALLOFF`. Con más de 1 el
     * velo es denso junto a la rueda y se aclara pronto hacia el arte (a mitad
     * del tramo queda en un tercio, no en la mitad), sin canto en el eje.
     */
    const val FALLOFF = 1.7f

    /** Paradas del degradado (≥ 6 para que no se vean bandas). */
    const val STOPS = 12

    /** Viñeta de arriba y de abajo: alto (fracción) y opacidad. */
    const val VIGNETTE_HEIGHT = 0.16f
    const val VIGNETTE_ALPHA = 0.22f

    /** El velo suave bajo el texto de la fila enfocada (en su centro; se desvanece hacia los bordes). */
    const val PLATE_ALPHA_DARK = 0.5f
    const val PLATE_ALPHA_LIGHT = 0.55f

    /** Perla cálida del tema claro (nunca gris). */
    const val PEARL = 0xFFF8F1E6.toInt()

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    fun tint(dark: Boolean): Int = if (dark) BrandTokens.DARK.paper else PEARL

    fun plate(dark: Boolean): Int = if (dark) BrandTokens.DARK.paper else WHITE

    fun plateAlpha(dark: Boolean): Float = if (dark) PLATE_ALPHA_DARK else PLATE_ALPHA_LIGHT

    /** Donde el velo ya es transparente (dp), en una ventana de [width]. */
    fun clearAt(axisX: Float, width: Float): Float = (axisX + FADE_DP).coerceAtMost(width)

    /** Opacidad del velo a [x] (mismas unidades que [axisX] y [width]). */
    fun alphaAt(x: Float, axisX: Float, width: Float): Float {
        if (axisX <= 0f) return 0f
        if (x <= axisX) {
            val t = WheelTransform.smoothstep(0f, axisX, x)
            return EDGE_ALPHA + (AXIS_ALPHA - EDGE_ALPHA) * t
        }
        val end = clearAt(axisX, width)
        val rest = 1f - WheelTransform.smoothstep(axisX, end, x)
        return AXIS_ALPHA * rest.toDouble().pow(FALLOFF.toDouble()).toFloat()
    }

    /**
     * El canto del cristal curvo del tema claro (dp) a la altura [y]: en la
     * línea de foco ([focusY]) pasa [WheelGlass.PAST_AXIS_DP] del eje, y se
     * corre a la izquierda lo mismo que las filas al seguir el círculo de la
     * rueda ([radius]); nunca más allá de [WheelGlass.MIN_EDGE_DP]. En la
     * banda de arriba ([band]) no se recoge más de lo que pide el dock.
     */
    fun glassEdgeAt(y: Float, axisX: Float, focusY: Float, radius: Float, band: MeridianFog.Band = MeridianFog.Band.NONE): Float =
        (axisX + WheelGlass.PAST_AXIS_DP - MeridianFog.shift(y, focusY, radius, band)).coerceAtLeast(WheelGlass.MIN_EDGE_DP)

    /** Lo más ancho que llega el cristal (en la línea de foco, o hasta [reachX] si la banda de arriba pasa de ahí), en dp. */
    fun glassRight(axisX: Float, width: Float, reachX: Float = 0f): Float =
        maxOf(axisX + WheelGlass.PAST_AXIS_DP, reachX + WheelGlass.RIM_DP).coerceAtMost(width)

    /** Las paradas del degradado horizontal: (posición 0…1 del ancho, opacidad). */
    fun stops(axisX: Float, width: Float): List<Pair<Float, Float>> {
        val end = clearAt(axisX, width)
        val xs = ArrayList<Float>()
        for (i in 0..STOPS / 2) xs += axisX * i / (STOPS / 2)
        for (i in 1..STOPS / 2) xs += axisX + (end - axisX) * i / (STOPS / 2)
        return xs.map { (it / width).coerceIn(0f, 1f) to alphaAt(it, axisX, width) }
    }

    /** Lo que se ve detrás del texto de la rueda a [x] sobre un arte de color [art], con el velo de color [tint] cubriendo [coverage]. */
    fun background(art: Int, x: Float, axisX: Float, width: Float, dark: Boolean, tint: Int = tint(dark), coverage: Float = 1f): Int =
        ColorMath.over(ColorMath.withAlpha(tint, alphaAt(x, axisX, width) * coverage), ColorMath.opaque(art))


    /** Lo mismo con el velo de la fila enfocada encima, en su centro. */
    fun plated(background: Int, dark: Boolean, tint: Int = plate(dark)): Int =
        ColorMath.over(ColorMath.withAlpha(tint, plateAlpha(dark)), background)

    /** La tinta de texto del tema. */
    fun ink(dark: Boolean): Int = (if (dark) BrandTokens.DARK else BrandTokens.LIGHT).ink

    /** Contraste del texto del tema (o de [ink]), con su opacidad [textAlpha], sobre [background]. */
    fun textContrast(background: Int, dark: Boolean, textAlpha: Float = 1f, ink: Int = ink(dark)): Double =
        ColorMath.contrast(ColorMath.over(ColorMath.withAlpha(ink, textAlpha), background), background)

    /** Lo menos que oscurece la tinta negra bajo el titular, las fichas y los botones (texto blanco). */
    fun titleAlpha(): Float {
        var a = 0f
        while (a < 1f && ColorMath.contrast(WHITE, ColorMath.over(ColorMath.withAlpha(BLACK, a), WHITE)) < MIN_TEXT) a += 0.01f
        return (a + 0.04f).coerceAtMost(1f)
    }
}

/**
 * La niebla de la rueda (la máscara del velo de [MeridianScrims]) curvada
 * por el mismo círculo que siguen las filas y el dial ([WheelArc]): a la
 * altura de cada fila, el perfil horizontal se corre a la izquierda lo mismo
 * que la fila. Así el texto de cada fila tiene detrás exactamente el velo que
 * tendría en la línea de foco (el contraste probado para la línea de foco vale
 * para todas), y arriba y abajo, lejos del centro de la lista, el arte queda
 * más limpio: la niebla dibuja la media luna de la rueda en vez de una banda recta.
 *
 * Todo en dp (el velo se apaga en [MeridianScrims.FADE_DP], que son dp): [focusY]
 * es la línea de foco y [radius] el radio del círculo (≤ 0 = sin curva, el
 * perfil recto de antes).
 */
object MeridianFog {

    /** Lado de cada muestra de la máscara (dp): se amplía con filtrado; el degradado es suave y el grano tapa las bandas. */
    const val CELL_DP = 6f

    /** Lo que se corre el perfil a la altura [y]: lo mismo que la fila que pasa por ahí (≥ 0). */
    fun shift(y: Float, focusY: Float, radius: Float): Float = -WheelArc.offset(y - focusY, radius)

    /**
     * La banda de arriba (la cabecera de la rueda, hasta [bottom]) en la que
     * la niebla no se recoge más de [maxShift]: ahí el canto llega al menos
     * hasta donde acaba el dock más su aire. Hace falta cuando el dock, ni en
     * su ancho mínimo, cabe bajo la curva (la curva se recoge mucho lejos de
     * la línea de foco): mejor ensanchar la niebla arriba que dejarlo medio
     * sobre el arte. Por debajo de [bottom] vuelve a la curva en [BAND_BLEND]
     * dp, sin escalón. [maxShift] puede ser negativo (el cristal claro pasa de
     * su canto de la línea de foco).
     */
    data class Band(val bottom: Float, val maxShift: Float) {
        companion object {
            val NONE = Band(0f, Float.POSITIVE_INFINITY)
        }
    }

    /** Lo que tarda la banda en volver a la curva por debajo de la cabecera (dp). */
    const val BAND_BLEND = 32f

    /** [shift] con la banda de arriba ([band]): nunca más de su tope dentro de ella, y de vuelta a la curva justo debajo. */
    fun shift(y: Float, focusY: Float, radius: Float, band: Band): Float {
        val s = shift(y, focusY, radius)
        if (s <= band.maxShift) return s
        return band.maxShift + (s - band.maxShift) * WheelTransform.smoothstep(band.bottom, band.bottom + BAND_BLEND, y)
    }

    fun alphaAt(x: Float, y: Float, axisX: Float, width: Float, focusY: Float, radius: Float, band: Band = Band.NONE): Float =
        MeridianScrims.alphaAt(x + shift(y, focusY, radius, band), axisX, width)

    /**
     * Hasta dónde se ve la niebla, por fuera del eje, en la línea de foco
     * (dp): donde el velo ya se ha quedado en la mitad ([VISIBLE]) de lo que
     * tiene en el eje. No es [MeridianScrims.FADE_DP] (ahí ya es transparente):
     * con la caída de [MeridianScrims.FALLOFF] el velo queda en un cuarto a
     * mitad de la banda y, a la vista, lo que hay más allá ya es arte. Medir
     * contra los 88 dp decía "cabe" a un dock que se veía medio sobre el arte.
     */
    const val VISIBLE = 0.5f

    val WASH_REACH: Float by lazy {
        // Inversa de la caída: (1 − smoothstep(t))^FALLOFF = VISIBLE.
        val target = 1f - VISIBLE.toDouble().pow(1.0 / MeridianScrims.FALLOFF).toFloat()
        var lo = 0f
        var hi = 1f
        repeat(40) {
            val mid = (lo + hi) / 2f
            if (WheelTransform.smoothstep(0f, 1f, mid) < target) lo = mid else hi = mid
        }
        (lo + hi) / 2f * MeridianScrims.FADE_DP
    }

    /** Lo que llega la niebla por fuera del eje en la línea de foco: el canto del cristal claro ([glass]) o [WASH_REACH]. */
    fun reach(glass: Boolean): Float = if (glass) WheelGlass.PAST_AXIS_DP else WASH_REACH

    /**
     * El canto que se ve de la niebla a la altura [y] (dp): el del cristal
     * claro ([glass], ver [MeridianScrims.glassEdgeAt]) o el del velo oscuro,
     * los dos curvados por el círculo de la rueda y con la banda de arriba.
     * Lo que va sobre la niebla (el dock de la cabecera, el bocadillo de
     * Masha) se queda a este lado, con su aire.
     */
    fun visibleEdge(y: Float, axisX: Float, focusY: Float, radius: Float, glass: Boolean, band: Band = Band.NONE): Float =
        if (glass) MeridianScrims.glassEdgeAt(y, axisX, focusY, radius, band) else axisX + WASH_REACH - shift(y, focusY, radius, band)

    /**
     * La banda de arriba hasta [bottom] para que el canto llegue a [reachX]
     * (dp; ≤ 0 = sin banda), con el velo o el cristal ([glass]) de eje [axisX].
     */
    fun band(bottom: Float, reachX: Float, axisX: Float, glass: Boolean): Band =
        if (reachX <= 0f) Band.NONE else Band(bottom, axisX + reach(glass) - reachX)

    /**
     * La máscara en una rejilla de [cols]×[rows] muestras que cubre una capa
     * de [layerWidth]×[layerHeight] (cada muestra en el centro de su celda),
     * como ARGB negro con la opacidad del velo. [width] es el ancho de la
     * ventana y [axisX] el eje; todo en dp, como las alturas. [band], la
     * banda de arriba (ver [Band]).
     */
    fun mask(cols: Int, rows: Int, layerWidth: Float, layerHeight: Float, axisX: Float, width: Float, focusY: Float, radius: Float, band: Band = Band.NONE): IntArray {
        val out = IntArray(cols * rows)
        if (cols <= 0 || rows <= 0) return out
        val sx = layerWidth / cols
        val sy = layerHeight / rows
        for (j in 0 until rows) {
            val shift = shift((j + 0.5f) * sy, focusY, radius, band)
            for (i in 0 until cols) {
                val a = MeridianScrims.alphaAt((i + 0.5f) * sx + shift, axisX, width)
                out[j * cols + i] = ColorMath.withAlpha(0xFF000000.toInt(), a)
            }
        }
        return out
    }

    /** Muestras para [length] (px) con celdas de [cellPx]: como poco 2. */
    fun samples(length: Float, cellPx: Float): Int = ceil(length / cellPx.coerceAtLeast(1f)).toInt().coerceAtLeast(2)
}

/**
 * El cristal oscuro de las píldoras y cápsulas de Meridian (volver, "Abrir",
 * emulador, hora y batería, buscar, Ajustes): con este suelo de
 * tinta el texto blanco llega al 4,5:1 y los iconos al 3:1 sobre cualquier
 * fondo, también sobre arte blanco o un velo pastel.
 */
object MeridianGlass {

    const val FLOOR = 0.66f

    /** El color de la píldora (tinta [shade] con opacidad [alpha]) sobre [background]. */
    fun over(background: Int, alpha: Float = FLOOR, shade: Int = BrandTokens.SHADE): Int =
        ColorMath.over(ColorMath.withAlpha(shade, alpha), ColorMath.opaque(background))
}

/** El arte del hero, más ancho que la ventana y anclado a la izquierda: su centro (el motivo) cae en la parte despejada de la derecha. */
object MeridianArtFrame {

    /** Lo que se ensancha el arte: su centro pasa del 50 % al ~67 % del ancho. */
    const val FOCAL_SHIFT = 0.35f

    fun width(windowWidth: Float): Float = windowWidth * (1f + FOCAL_SHIFT)

    /** Dónde cae el centro del arte, en fracción del ancho de la ventana. */
    fun subjectAt(): Float = (1f + FOCAL_SHIFT) / 2f
}

/**
 * La caja del logo en el hero (todo en las mismas unidades): ancho como mucho
 * min(60 % de la zona del hero —de la rueda al canto derecho—, 640 dp, lo
 * que deja el bloque), alto entre el 14 % y el 26 % de la ventana (11–20 %
 * en una ventana baja). Se encaja sin deformar; si la imagen es pequeña se
 * amplía como mucho [maxUpscale] veces sus píxeles.
 */
object LogoFit {

    const val WIDTH_FRACTION = 0.60f
    const val MAX_WIDTH_DP = 640f
    const val MAX_HEIGHT = 0.26f
    const val MIN_HEIGHT = 0.14f
    const val MAX_HEIGHT_COMPACT = 0.20f
    const val MIN_HEIGHT_COMPACT = 0.11f

    /** Lo que se amplía siempre un logo pequeño, y el tope cuando la pantalla es densa. */
    const val MAX_UPSCALE = 2f
    const val MAX_UPSCALE_DENSE = 2.5f

    /** Más allá de esto, cada píxel del logo ocuparía más de este tamaño (dp) y se vería borroso. */
    const val MAX_DP_PER_PIXEL = 1.25f

    data class Box(val maxWidth: Float, val maxHeight: Float, val minHeight: Float, val maxUpscale: Float = MAX_UPSCALE)

    /**
     * La caja para una zona del hero de [heroWidth] (de la rueda al canto), un
     * bloque de [contentWidth] y una ventana de [screenHeight]; [dp] = px por
     * dp (1 si todo va en dp) y [density] la densidad real (para el tope de ampliación).
     */
    fun box(heroWidth: Float, contentWidth: Float, screenHeight: Float, compact: Boolean = false, dp: Float = 1f, density: Float = dp): Box = Box(
        maxWidth = minOf(heroWidth * WIDTH_FRACTION, MAX_WIDTH_DP * dp, contentWidth),
        maxHeight = screenHeight * if (compact) MAX_HEIGHT_COMPACT else MAX_HEIGHT,
        minHeight = screenHeight * if (compact) MIN_HEIGHT_COMPACT else MIN_HEIGHT,
        maxUpscale = maxUpscale(density),
    )

    /** Doble siempre; hasta 2,5 veces solo si así cada píxel de la imagen no pasa de 1,25 dp (pantallas densas). */
    fun maxUpscale(density: Float): Float = (density * MAX_DP_PER_PIXEL).coerceIn(MAX_UPSCALE, MAX_UPSCALE_DENSE)

    /** El tamaño en pantalla de un logo de [width]×[height] px de imagen dentro de [box]. */
    fun fit(box: Box, width: Float, height: Float): Pair<Float, Float> {
        if (width <= 0f || height <= 0f) return 0f to 0f
        var s = minOf(box.maxWidth / width, box.maxHeight / height, box.maxUpscale)
        if (height * s < box.minHeight) {
            s = maxOf(s, minOf(box.minHeight / height, box.maxWidth / width, box.maxUpscale))
        }
        return width * s to height * s
    }
}

/**
 * Dónde va el bocadillo de Masha sin tapar nada, y nunca encima de quien
 * habla: su propio botón cuenta siempre como obstáculo, igual que lo que se
 * le pase (el dock, el grupo de arriba a la derecha, el bloque del hero).
 *
 * En Meridian, Masha es la primera pieza del grupo de arriba a la derecha y
 * el bocadillo va justo debajo del grupo, sobre el arte ([underGroup]): ahí
 * hay ancho para la línea entera en una fila. Antes iba en la cabecera de la
 * rueda, a la derecha del emblema o, si no cabía, en la columna, partido en
 * tres líneas y empujando la rueda hacia abajo.
 *
 * En el Clásico, debajo del botón, alineado a su izquierda y bajando por
 * debajo de lo que pise —el dock— ([below]). Sin cola: el bocadillo se lee
 * de quién es por estar pegado al botón (y por su filo de acento).
 */
object BubbleSlot {

    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        fun overlaps(o: Area): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
        fun at(x: Float, y: Float, w: Float, h: Float) = Area(x, y, x + w, y + h)
    }

    /**
     * Dónde queda el bocadillo: arriba a la izquierda y su ancho. Sin [clear]
     * no hay sitio que no tape algo (letra muy grande con un hero muy alto):
     * mejor no enseñarlo que tapar los botones del hero.
     */
    data class Slot(val x: Float, val y: Float, val width: Float, val clear: Boolean = true)

    /**
     * Debajo del grupo de arriba a la derecha ([group]), en el que va el botón
     * de Masha ([anchor]), para un bocadillo de ancho natural [natural] (la
     * línea en una fila) y alto [heightFor] según el ancho.
     *
     * Pegado al canto derecho del grupo si es más ancho que lo que va de
     * Masha a ese canto; si es más estrecho, alineado a la izquierda de Masha:
     * así siempre queda debajo de ella. No pasa de [maxWidth] ni sale por la
     * izquierda de [bounds] (lo que queda del arte, sin la columna de la
     * rueda), con [margin] de aire: si la línea no cabe, se estrecha y el
     * texto se parte (en dos líneas, lo normal). Si pisa algún obstáculo por
     * la izquierda (el dock que asoma), se estrecha hasta dejarlo libre; si
     * aun así pisa algo (el bloque del hero, que no se puede esquivar a lo
     * ancho), [Slot.clear] es false. Nunca baja de [minWidth].
     */
    fun underGroup(
        anchor: Area,
        group: Area,
        natural: Float,
        maxWidth: Float,
        heightFor: (Float) -> Float,
        bounds: Area,
        obstacles: List<Area>,
        gap: Float,
        margin: Float,
        minWidth: Float = 0f,
    ): Slot {
        val right = minOf(group.right, bounds.right - margin)
        var left = bounds.left + margin
        val y = maxOf(group.bottom, anchor.bottom) + gap
        repeat(obstacles.size + 1) {
            val w = minOf(natural, maxWidth, right - left).coerceAtLeast(minWidth)
            val x = minOf(right - w, anchor.left).coerceAtLeast(left)
            val box = Area(x, y, x + w, y + heightFor(w))
            val hits = (obstacles + anchor).filter { it.overlaps(box) }
            if (hits.isEmpty()) return Slot(x, y, w)
            // Lo que asoma por la izquierda (el dock) se deja a un lado; lo que lo cruza entero, no.
            val side = hits.filter { it.right < right - minWidth }.maxOfOrNull { it.right } ?: return Slot(x, y, w, clear = false)
            if (side + gap <= left) return Slot(x, y, w, clear = false)
            left = side + gap
        }
        val w = minOf(natural, maxWidth, right - left).coerceAtLeast(minWidth)
        return Slot(minOf(right - w, anchor.left).coerceAtLeast(left), y, w, clear = false)
    }

    /**
     * Debajo de [anchor], alineado a su izquierda, dentro de [bounds] a lo
     * ancho y por debajo de lo que pise de [obstacles]. Nunca sube por encima
     * del botón (antes, al recolocarlo dentro de [bounds], podía quedar encima).
     */
    fun below(anchor: Area, w: Float, h: Float, bounds: Area, obstacles: List<Area>, gap: Float, margin: Float): Pair<Float, Float> {
        val x = anchor.left.coerceIn(bounds.left + margin, (bounds.right - w - margin).coerceAtLeast(bounds.left + margin))
        var y = maxOf(anchor.bottom + gap, bounds.top + margin)
        repeat(obstacles.size) {
            val box = Area(x, y, x + w, y + h)
            val hit = obstacles.filter { it.overlaps(box) }.maxOfOrNull { it.bottom } ?: return x to y
            y = hit + gap
        }
        return x to y
    }

    /**
     * El sitio del bocadillo en el Clásico: debajo del botón ([below]) con su
     * ancho natural [natural], hasta [maxWidth]. [heightFor] da su alto para
     * un ancho (el texto se parte en líneas).
     */
    fun choose(
        anchor: Area,
        natural: Float,
        maxWidth: Float,
        heightFor: (Float) -> Float,
        bounds: Area,
        obstacles: List<Area>,
        gap: Float,
        margin: Float,
    ): Slot {
        val w = minOf(natural, maxWidth)
        val (x, y) = below(anchor, w, heightFor(w), bounds, obstacles, gap, margin)
        return Slot(x, y, w)
    }

    /** Ancho máximo del bocadillo: el de siempre, sin salirse de la columna de la rueda. */
    fun maxWidth(columnWidth: Float, margin: Float, preferred: Float): Float = min(preferred, (columnWidth - margin * 2f).coerceAtLeast(0f))
}

/** Una ruta larga en una línea: el principio y el final, con "…" en medio. */
object MiddleEllipsis {
    fun shorten(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        if (maxChars <= 1) return "…"
        val keep = maxChars - 1
        val head = (keep + 1) / 2
        val tail = keep - head
        return text.take(head) + "…" + text.takeLast(tail)
    }
}

/** Los glifos de botón del mando (A, X, Y, B) solo se ven con un mando conectado; aparecen y se van con un fundido. */
object PadGlyphs {
    fun visible(gamepadPresent: Boolean): Boolean = gamepadPresent

    /** Progreso (0…1) hacia el que se anima el hueco del glifo. */
    fun target(gamepadPresent: Boolean): Float = if (visible(gamepadPresent)) 1f else 0f
}

/**
 * La fila de arriba de Meridian (dp): la cabecera de la rueda (el dock) a la
 * izquierda y el grupo de la derecha (Masha, hora y batería, "Abrir", orden,
 * buscar, Ajustes). Las dos cuelgan del mismo tope y miden lo mismo de alto
 * ([TopBarLayout.BAR_HEIGHT] = [DotTabsLayout.HEIGHT]), así comparten la
 * línea central. Antes la cabecera empezaba 2 dp más arriba que la barra en
 * una ventana baja y el dock, debajo de Masha, no se alineaba con nada.
 */
object MeridianTopRow {
    const val TOP = 8f
    const val TOP_COMPACT = 6f

    /** Debajo de la cabecera, antes de la rueda. */
    const val BOTTOM = 6f
    const val BOTTOM_COMPACT = 2f

    /** Margen derecho del grupo de la derecha (el mismo que el bloque del hero). */
    const val END = 22f

    fun top(compact: Boolean): Float = if (compact) TOP_COMPACT else TOP

    fun bottom(compact: Boolean): Float = if (compact) BOTTOM_COMPACT else BOTTOM

    /** La línea central de la fila, desde arriba de la ventana. */
    fun centerY(compact: Boolean): Float = top(compact) + TopBarLayout.BAR_HEIGHT / 2f

    /**
     * Lo más ancho que puede medir el grupo de la derecha en una ventana de
     * [width] con la columna de [railWidth]: hasta donde puede llegar el dock
     * de la cabecera (que asoma sobre el hueco del hero,
     * [RailHeaderLayout.OVERHANG]) más el aire de la barra. Antes el tope
     * dejaba solo 2 dp entre los dos en el peor caso. Nunca menos que lo que
     * la barra no compacta (Masha, orden, buscar, Ajustes): eso no se corta.
     */
    fun barMaxWidth(width: Float, railWidth: Float): Float {
        val dockEnd = RailHeaderLayout.START + RailHeaderLayout.dockMaxWidth(railWidth)
        val fixed = TopBarLayout.fixedWidth(TopBarLayout.searchWidth(open = false, landscape = true), masha = true, sort = true)
        return maxOf(width - END - dockEnd - TopBarLayout.GAP, fixed)
    }
}

/**
 * La cabecera de la rueda (dp): solo el dock de secciones, en una fila con el
 * grupo de la derecha ([MeridianTopRow]). Masha ya no va aquí: es la primera
 * pieza de la barra de la derecha. Encima del dock, solo, se veía apretado y
 * sin alinear con nada, y su fila le quitaba a la rueda ~48 dp de alto.
 *
 * En un móvil apaisado la columna es estrecha ([MeridianGeometry.SLIM_MIN]):
 * ahí el dock no cabe entero (con cuatro secciones pide ~255 dp y la columna
 * da 162). Por eso puede asomar sobre el hueco entre el dial y el bloque del
 * hero ([OVERHANG]), que a esa altura está vacío, sin pasar del canto que se
 * ve de la niebla a la altura de la cabecera, que es curvo (ver [plan]).
 */
object RailHeaderLayout {
    const val START = 22f
    const val END = 16f

    /** Lo que puede pasar el dock del canto de la columna: el hueco del hero menos un margen hasta su bloque. */
    const val OVERHANG = MeridianGeometry.HERO_GAP - MeridianGeometry.RAIL_START

    /** Lo más ancho que puede medir el dock en una columna de [railWidth]. */
    fun dockMaxWidth(railWidth: Float): Float = (railWidth - START - END + OVERHANG).coerceAtLeast(0f)

    /** Borde izquierdo del dock: pegado al canto derecho de la columna si cabe; si no, desde [START] hacia el hueco. */
    fun dockX(railWidth: Float, dockWidth: Float): Float = maxOf(START, railWidth - END - dockWidth)

    /** Aire entre el final del dock y el canto que se ve de la niebla ([MeridianFog.visibleEdge]). */
    const val FOG_MARGIN = 16f

    /**
     * Lo que el dock puede pasar del canto curvo (y la niebla ensancharse en
     * la banda de arriba para seguirlo) para que sus rótulos se lean enteros:
     * con tres secciones en un móvil faltaban 2–7 dp y "Consolas" se cortaba.
     * Más allá, el rótulo se recorta: la niebla no se hincha sobre el arte.
     */
    const val LABEL_STRETCH = 12f

    /**
     * La cabecera colocada (dp, desde arriba de la fila sin su relleno): dónde
     * y cuánto mide el dock y hasta dónde tiene que llegar la niebla en la
     * banda de arriba ([reach], el final del dock más [FOG_MARGIN]; 0 = sin
     * curva medida).
     */
    data class Plan(val dockX: Float, val dockTop: Float, val dockWidth: Float, val height: Float, val reach: Float)

    /**
     * Cómo va el dock de [dockHeight] cuyas píldoras naturales son [pills] en
     * una columna de [railWidth]. El alto de la cabecera es el del dock: no
     * depende de la curva, que a su vez depende de ese alto.
     *
     * El ancho sale de la curva: el canto de la niebla ([edge], a una altura
     * de la fila) se recoge hacia la izquierda lejos de la línea de foco, así
     * que a la altura de la cabecera queda bastante más a la izquierda que en
     * el foco (y ahora, con la cabecera más arriba, todavía más). El dock no
     * pasa del canto a la altura de su borde de arriba ni del de abajo (el
     * más justo), menos [FOG_MARGIN]; y como mide lo mismo con cualquier
     * sección ([DotTabsLayout.uniformWidth]), cambiar de sección no lo saca
     * de ahí. Si ahí los rótulos no caben enteros, el dock crece hasta
     * [LABEL_STRETCH] más (lo justo para que quepan, nunca más allá de la
     * columna) y [Plan.reach] pide a la niebla que llegue hasta él; si ni en
     * su mínimo cabe, se queda en el mínimo, también con la niebla hasta él.
     */
    fun plan(railWidth: Float, dockHeight: Float, pills: FloatArray, edge: ((Float) -> Float)?): Plan {
        val top = 0f
        val column = START + dockMaxWidth(railWidth)
        val curve = edge?.let { minOf(it(top), it(top + dockHeight)) - FOG_MARGIN } ?: Float.POSITIVE_INFINITY
        val limit = minOf(column, curve)
        // Un poco más allá de la curva si así los rótulos se leen enteros (la niebla lo sigue).
        val stretch = minOf(column, curve + LABEL_STRETCH) - START
        val room = maxOf(limit - START, minOf(DotTabsLayout.fullWidth(pills), stretch), DotTabsLayout.minWidth(pills.size))
        val width = DotTabsLayout.uniformWidth(pills, room)
        // Pegado al canto de la columna si cabe; si la curva no llega, pegado a la curva; si ni así, desde [START].
        val right = minOf(railWidth - END, maxOf(limit, START + width))
        val x = maxOf(START, right - width)
        return Plan(x, top, width, dockHeight, reach = if (edge == null) 0f else x + width + FOG_MARGIN)
    }
}
