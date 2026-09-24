package com.elyndra.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.max

/* ─────────────────────────────────────────────────────────────
   Mandos.

   Elyndra es un lanzador: mucha gente lo usa en una tele o en una
   consola portátil Android, donde no hay dedo que dar a la pantalla.
   Aquí se traduce cualquier mando —Xbox, PlayStation, Switch Pro,
   los clónicos y los mandos integrados de las portátiles— a lo que
   el usuario quiere hacer, y a partir de ahí la app ya no sabe de
   qué mando venía.

   Android normaliza los botones de todos ellos: la cruceta llega
   como DPAD_*, el botón de abajo del rombo como BUTTON_A (la X de
   PlayStation, la A de Xbox) y el de la derecha como BUTTON_B (el
   círculo de PlayStation, la B de Xbox). Esa es la convención de
   Android TV —abajo acepta, derecha vuelve— y es la que se sigue.
   ───────────────────────────────────────────────────────────── */

/** Lo que ha pedido el usuario, ya sin saber con qué mando. */
enum class Pad {
    Up,
    Down,
    Left,
    Right,

    /** A / ✕: abrir lo que está señalado. */
    Confirm,

    /** B / ◯: volver. */
    Back,

    /** X / ▢: la ficha del juego. */
    Details,

    /** Y / △: el menú del juego (lo mismo que mantener pulsado). */
    Options,

    /** Start: Ajustes. */
    Menu,

    /** Select: el buscador. */
    Search,

    /** L3 / R3: el menú de la app (ordenar, añadir, Masha…). */
    AppMenu,

    /** L1 / R1: saltar de sección (filtro, página del carrusel). */
    PagePrev,
    PageNext,
    ;

    val isDirection: Boolean get() = this == Up || this == Down || this == Left || this == Right
}

object Gamepad {

    /**
     * Botón → acción.
     *
     * Están los códigos de mando y también los del teclado y el mando a
     * distancia de una tele: un lanzador se maneja igual con los tres, y
     * distinguirlos solo serviría para que uno de ellos no funcionase.
     */
    fun actionFor(keyCode: Int): Pad? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> Pad.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> Pad.Down
        KeyEvent.KEYCODE_DPAD_LEFT -> Pad.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> Pad.Right

        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        // Mandos genéricos sin perfil conocido: Android les numera los botones.
        KeyEvent.KEYCODE_BUTTON_1,
        -> Pad.Confirm

        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_2,
        -> Pad.Back

        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_3,
        KeyEvent.KEYCODE_INFO,
        -> Pad.Details

        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_4,
        -> Pad.Options

        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_MENU,
        -> Pad.Menu

        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_SEARCH,
        -> Pad.Search

        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        -> Pad.AppMenu

        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> Pad.PagePrev
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> Pad.PageNext

        else -> null
    }

    /**
     * La tecla del sistema equivalente a un botón de mando.
     *
     * Las pantallas de formulario (Ajustes, Añadir, Masha) se mueven con el
     * foco de Compose, que entiende de cruceta y de "aceptar", no de botones
     * de mando. Cuando Elyndra no consume la pulsación se reenvía traducida,
     * y así esas pantallas se manejan con el mando sin tener que reescribirlas.
     *
     * Null = la tecla ya es una de esas, o no tiene equivalente.
     */
    fun systemKey(keyCode: Int): Int? = actionFor(keyCode)?.let { systemKeyFor(it) }?.takeIf { it != keyCode }

    /** La misma equivalencia para lo que viene de un stick, que no trae tecla. */
    fun systemKeyFor(pad: Pad): Int? = when (pad) {
        Pad.Up -> KeyEvent.KEYCODE_DPAD_UP
        Pad.Down -> KeyEvent.KEYCODE_DPAD_DOWN
        Pad.Left -> KeyEvent.KEYCODE_DPAD_LEFT
        Pad.Right -> KeyEvent.KEYCODE_DPAD_RIGHT
        Pad.Confirm -> KeyEvent.KEYCODE_DPAD_CENTER
        else -> null
    }

    /** Lo que llega de un mando o un joystick, no del táctil. */
    fun isGamepadSource(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    /**
     * Un dispositivo que es un mando de verdad (Xbox, PlayStation, Switch,
     * clónicos por Bluetooth o USB), no el teclado virtual ni el táctil.
     */
    fun isGamepad(device: InputDevice?): Boolean {
        if (device == null || device.isVirtual) return false
        val sources = device.sources
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    /** Un eje, sin el temblor que el propio mando declara como "plano" (zona muerta de fábrica). */
    private fun axis(event: MotionEvent, axis: Int): Float {
        val value = event.getAxisValue(axis)
        val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
        return if (abs(value) <= flat) 0f else value
    }

    /**
     * Dirección de la cruceta analógica ("hat"), que muchos mandos Xbox y
     * clónicos mandan como eje en vez de como tecla. Siempre vale -1, 0 o 1.
     */
    fun hat(event: MotionEvent): Pair<Float, Float> =
        axis(event, MotionEvent.AXIS_HAT_X) to axis(event, MotionEvent.AXIS_HAT_Y)

    /**
     * El stick izquierdo. El derecho no navega a propósito: en bastantes mandos
     * genéricos los gatillos analógicos se publican por Z/RZ y reposan en -1,
     * con lo que "el stick derecho" empujaría hacia arriba para siempre.
     */
    fun leftStick(event: MotionEvent): Pair<Float, Float> =
        axis(event, MotionEvent.AXIS_X) to axis(event, MotionEvent.AXIS_Y)
}

/**
 * Convierte una posición analógica (stick o hat) en una dirección, con zona
 * muerta e histéresis.
 *
 * [PRESS] (~0.5) es cuánto hay que inclinar para que cuente; [RELEASE] es
 * más bajo a propósito: un stick gastado tiembla alrededor de su umbral, y sin
 * esa holgura cada temblor contaría como una pulsación nueva.
 */
class AxisGate {
    private var current: Pad? = null

    fun update(x: Float, y: Float): Pad? {
        val limit = if (current == null) PRESS else RELEASE
        val ax = abs(x)
        val ay = abs(y)
        if (max(ax, ay) < limit) {
            current = null
            return null
        }
        // El eje dominante manda: en diagonal no se disparan dos direcciones.
        // Un eje que ya estaba elegido conserva la preferencia hasta que el
        // otro le saque ventaja clara, para que una diagonal no alterne.
        val horizontal = when (current) {
            Pad.Left, Pad.Right -> ax >= ay * 0.8f
            Pad.Up, Pad.Down -> ax > ay * 1.25f
            else -> ax >= ay
        }
        current = if (horizontal) {
            if (x > 0) Pad.Right else Pad.Left
        } else {
            if (y > 0) Pad.Down else Pad.Up
        }
        return current
    }

    fun reset() {
        current = null
    }

    companion object {
        /** Desde dónde cuenta como inclinado: la mitad del recorrido. */
        const val PRESS = 0.5f

        /** Hasta dónde hay que volver para darlo por soltado. */
        const val RELEASE = 0.35f
    }
}

/**
 * La repetición de las direcciones, igual para el stick, el hat y la cruceta.
 *
 * No se usa la repetición del sistema (~20 pulsaciones/s): con ella, mantener
 * la cruceta recorría la lista demasiado deprisa. En su lugar:
 *
 *  - la primera pulsación dispara **un solo paso** al instante,
 *  - si se mantiene, espera [firstDelayMs] (~350 ms)
 *  - y a partir de ahí repite cada [repeatMs] (~150 ms),
 *
 * con un temporizador propio: un stick quieto a tope no manda eventos, así que
 * no se puede depender de ellos para repetir. Las repeticiones del sistema se
 * ignoran por completo.
 *
 * Cada fuente ([Channel]) sostiene su dirección por separado: hay mandos que
 * mandan la cruceta a la vez como tecla y como eje "hat", y así una misma
 * pulsación física no dispara dos veces.
 */
class DirectionalRepeater(
    private val fire: (Pad) -> Unit,
    private val firstDelayMs: Long = 350,
    private val repeatMs: Long = 150,
    private val handler: Ticker = MainThreadTicker,
) {
    enum class Channel { Keys, Hat, Stick }

    /** El temporizador de las repeticiones; en las pruebas se sustituye por uno de mentira. */
    interface Ticker {
        fun postDelayed(block: Runnable, delayMs: Long)
        fun removeCallbacks(block: Runnable)
    }

    /** El de verdad: el hilo principal, que es donde llegan los eventos de entrada. */
    object MainThreadTicker : Ticker {
        private val handler by lazy { Handler(Looper.getMainLooper()) }
        override fun postDelayed(block: Runnable, delayMs: Long) {
            handler.postDelayed(block, delayMs)
        }
        override fun removeCallbacks(block: Runnable) {
            handler.removeCallbacks(block)
        }
    }

    private val held = EnumMap<Channel, Pad>(Channel::class.java)
    private var active: Pad? = null

    private val tick = object : Runnable {
        override fun run() {
            val pad = active ?: return
            fire(pad)
            handler.postDelayed(this, repeatMs)
        }
    }

    /** [channel] empieza a sostener [pad] (o lo sigue haciendo: entonces no pasa nada). */
    fun press(channel: Channel, pad: Pad) {
        if (held[channel] == pad) return
        held[channel] = pad
        // Otra fuente ya sostiene esa misma dirección: es la misma pulsación física.
        if (active == pad) return
        handler.removeCallbacks(tick)
        active = pad
        fire(pad)
        handler.postDelayed(tick, firstDelayMs)
    }

    /** [channel] suelta [pad]; con null suelta lo que tuviera. */
    fun release(channel: Channel, pad: Pad? = null) {
        val old = held[channel] ?: return
        if (pad != null && pad != old) return
        held.remove(channel)
        if (old != active) return
        handler.removeCallbacks(tick)
        // Si otra fuente sigue empujando, toma el relevo sin disparar de nuevo.
        val next = held.values.lastOrNull()
        active = next
        if (next != null) handler.postDelayed(tick, firstDelayMs)
    }

    /** Suelta todo: al perder el foco la ventana o desconectarse un mando. */
    fun releaseAll() {
        held.clear()
        active = null
        handler.removeCallbacks(tick)
    }
}
