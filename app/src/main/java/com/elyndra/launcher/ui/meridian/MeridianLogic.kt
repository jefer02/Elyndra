package com.elyndra.launcher.ui.meridian

import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.data.ColorMath
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/* ─────────────────────────────────────────────────────────────
   Meridian, sin Compose: qué modo toca, cómo se deforma la rueda
   según la distancia a la línea de foco, el círculo que siguen las
   filas y el dial, dónde se para al soltarla, el muelle que la lleva,
   el dial y su contador, la tira A–Z y el reparto de la pantalla.
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
 * arriba (el bocadillo de Masha mientras se ve, o la cabecera) se apaga antes
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
 * El contador del dial: la posición grande y el total apagado, con las mismas
 * cifras siempre (de 1 a 4, como poco 2), para que la cápsula no cambie de
 * ancho al moverse. Cada cifra rueda en vertical como un cuentakilómetros.
 */
object DialCounter {

    const val MAX_DIGITS = 4

    /** Cifras del contador para [total] juegos: al menos 2 y como mucho [MAX_DIGITS] (más allá, el número entero). */
    fun digits(total: Int): Int = max(2, total.coerceAtLeast(0).toString().length)

    /** El número de la fila [index] (desde 0) entre [total], con ceros delante hasta [digits] cifras. */
    fun current(index: Int, total: Int): String {
        if (total <= 0) return "0".repeat(digits(total))
        return (index + 1).coerceIn(1, total).toString().padStart(digits(total), '0')
    }

    fun total(total: Int): String = total.coerceAtLeast(0).toString().padStart(digits(total), '0')

    /** La cifra de la columna [column] (0 = la de la izquierda) de [value] escrito con [digits] cifras. */
    fun digitAt(value: Int, digits: Int, column: Int): Int {
        var v = value.coerceAtLeast(0)
        repeat(digits - 1 - column) { v /= 10 }
        return v % 10
    }

    /** Hacia dónde rueda una cifra: +1 (sube, el número crece), −1 (baja) o 0 (no cambia esa cifra). */
    fun direction(from: Int, to: Int, digits: Int, column: Int): Int {
        if (digitAt(from, digits, column) == digitAt(to, digits, column)) return 0
        return if (to > from) 1 else -1
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

/** Una letra de la tira A–Z y el primer juego que empieza por ella. */
data class IndexEntry(val label: String, val firstIndex: Int)

/**
 * La tira A–Z para saltar: solo con el orden por nombre y más de
 * [MIN_ITEMS] juegos. Las letras salen de la propia lista, en su orden, así
 * que cualquier idioma funciona: lo que no empieza por una letra latina
 * (números, símbolos, otros alfabetos) va a "#".
 */
object IndexStrip {

    const val MIN_ITEMS = 12

    fun visible(sortedByName: Boolean, count: Int): Boolean = sortedByName && count > MIN_ITEMS

    /** La letra de un nombre: sin tildes ni diéresis ("Ödland" → "O"); "#" si no es A–Z. */
    fun letterOf(name: String): String {
        val first = name.trimStart().firstOrNull() ?: return "#"
        val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return "#"
        return if (base in 'A'..'Z') base.toString() else "#"
    }

    fun entries(names: List<String>): List<IndexEntry> {
        val seen = HashSet<String>()
        val out = ArrayList<IndexEntry>()
        names.forEachIndexed { i, name ->
            val letter = letterOf(name)
            if (seen.add(letter)) out += IndexEntry(letter, i)
        }
        return out
    }

    /** La entrada bajo el dedo, a la fracción [fraction] (0 arriba, 1 abajo) del alto de la tira. */
    fun entryAt(fraction: Float, size: Int): Int {
        if (size <= 0) return -1
        return (fraction * size).toInt().coerceIn(0, size - 1)
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

        /** Hueco entre el dial y el arranque del bloque del hero: ahí va el contador del nodo (hasta 4 cifras), que no lo pisa nunca. */
        const val HERO_GAP = 96f

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

        /** [aspect] es ancho/alto del arte de las tarjetas (1 iconos, 2/3 carátulas). */
        fun compute(width: Float, railHeight: Float, aspect: Float = 1f, compact: Boolean = false): MeridianGeometry {
            val h = railHeight.coerceAtLeast(WheelTransform.TILE_MIN)
            val rail = railWidthFor(width, compact)
            val tile = WheelTransform.tileHeight(h, width, aspect, rail)
            val focus = (h * WheelTransform.FOCUS_FRACTION).coerceIn(tile / 2f, (h - tile / 2f).coerceAtLeast(tile / 2f))
            return MeridianGeometry(rail, h, tile, focus, compact)
        }
    }
}

/**
 * El velo neutro del tema (con "Color de fondo adaptable" apagado, o cuando
 * el color del arte no daría el contraste): tinta honda en oscuro, perla
 * cálida en claro, más fuerte en el canto izquierdo, que se mantiene hasta el
 * eje y se desvanece del todo hacia el 62 % del ancho. El adaptable usa el
 * mismo perfil de opacidad como máscara (ver [ArtWash]). El peor caso es arte
 * negro o blanco puro (o muy saturado).
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

    /** Donde el velo ya es transparente, en fracción del ancho (y como poco esto más allá del eje). */
    const val CLEAR_AT = 0.58f
    const val MIN_FADE = 0.14f

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

    fun clearAt(axisX: Float, width: Float): Float = maxOf(width * CLEAR_AT, axisX + width * MIN_FADE).coerceAtMost(width)

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

    /** Contraste del texto del tema (con su opacidad [textAlpha]) sobre [background]. */
    fun textContrast(background: Int, dark: Boolean, textAlpha: Float = 1f): Double {
        val ink = (if (dark) BrandTokens.DARK else BrandTokens.LIGHT).ink
        return ColorMath.contrast(ColorMath.over(ColorMath.withAlpha(ink, textAlpha), background), background)
    }

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
 * Todo en las mismas unidades (dp o px): [focusY] es la línea de foco y
 * [radius] el radio del círculo (≤ 0 = sin curva, el perfil recto de antes).
 */
object MeridianFog {

    /** Lado de cada muestra de la máscara (dp): se amplía con filtrado; el degradado es suave y el grano tapa las bandas. */
    const val CELL_DP = 6f

    /** Lo que se corre el perfil a la altura [y]: lo mismo que la fila que pasa por ahí (≥ 0). */
    fun shift(y: Float, focusY: Float, radius: Float): Float = -WheelArc.offset(y - focusY, radius)

    fun alphaAt(x: Float, y: Float, axisX: Float, width: Float, focusY: Float, radius: Float): Float =
        MeridianScrims.alphaAt(x + shift(y, focusY, radius), axisX, width)

    /**
     * La máscara en una rejilla de [cols]×[rows] muestras que cubre una capa
     * de [layerWidth]×[layerHeight] (cada muestra en el centro de su celda),
     * como ARGB negro con la opacidad del velo. [width] es el ancho de la
     * ventana y [axisX] el eje; mismas unidades que las alturas.
     */
    fun mask(cols: Int, rows: Int, layerWidth: Float, layerHeight: Float, axisX: Float, width: Float, focusY: Float, radius: Float): IntArray {
        val out = IntArray(cols * rows)
        if (cols <= 0 || rows <= 0) return out
        val sx = layerWidth / cols
        val sy = layerHeight / rows
        for (j in 0 until rows) {
            val shift = shift((j + 0.5f) * sy, focusY, radius)
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
 * emulador, hora y batería, buscar, Ajustes, contador): con este suelo de
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
 * Dónde va el bocadillo de Masha sin tapar nada: debajo de su botón,
 * alineado a su izquierda; si ahí pisa algo (el dock que baja a una segunda
 * línea, el orden…), a su derecha; si tampoco, por debajo de lo que pisa.
 * Siempre dentro de [bounds] (en Meridian, la columna de la rueda: nunca
 * llega al bloque del hero ni a las píldoras de arriba a la derecha).
 */
object BubbleSlot {

    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        fun overlaps(o: Area): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
        fun at(x: Float, y: Float, w: Float, h: Float) = Area(x, y, x + w, y + h)
    }

    /** Arriba a la izquierda del bocadillo de [w]×[h] junto a [anchor], dentro de [bounds] y fuera de [obstacles]. */
    fun place(anchor: Area, w: Float, h: Float, bounds: Area, obstacles: List<Area>, gap: Float, margin: Float): Pair<Float, Float> {
        fun clampX(x: Float) = x.coerceIn(bounds.left + margin, (bounds.right - w - margin).coerceAtLeast(bounds.left + margin))
        fun clampY(y: Float) = y.coerceIn(bounds.top + margin, (bounds.bottom - h - margin).coerceAtLeast(bounds.top + margin))
        val candidates = listOf(
            clampX(anchor.left) to clampY(anchor.bottom + gap),
            clampX(anchor.right + gap) to clampY(anchor.top + (anchor.bottom - anchor.top) / 2f - h / 2f),
        )
        for ((x, y) in candidates) {
            val box = Area(x, y, x + w, y + h)
            if (obstacles.none { it.overlaps(box) }) return x to y
        }
        var (x, y) = candidates[0]
        repeat(obstacles.size) {
            val box = Area(x, y, x + w, y + h)
            val hit = obstacles.filter { it.overlaps(box) }.maxOfOrNull { it.bottom } ?: return x to y
            y = clampY(hit + gap)
        }
        return x to y
    }

    /** Lo que la rueda deja libre arriba (px desde su canto) mientras el bocadillo baja hasta [bubbleBottom]. */
    fun reserve(bubbleBottom: Float, railTop: Float, gap: Float): Float = (bubbleBottom - railTop + gap).coerceAtLeast(0f)

    /** La rueda baja la mitad de lo que ocupa el bocadillo (lo demás lo apaga el canto de arriba). */
    const val SHIFT = 0.5f

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
