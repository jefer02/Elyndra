package com.elyndra.launcher.input

import android.view.KeyEvent

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

    /** Start: el menú de la app. */
    Menu,

    /** Select: el buscador. */
    Search,

    /** L1 / R1: saltar de grupo (filtro, página del carrusel). */
    PagePrev,
    PageNext,
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

        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> Pad.PagePrev
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> Pad.PageNext

        else -> null
    }

    /**
     * La tecla del sistema equivalente a un botón de mando.
     *
     * Las pantallas de formulario (Ajustes, Añadir, Lucy) se mueven con el
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
        source and SOURCE_GAMEPAD == SOURCE_GAMEPAD ||
            source and SOURCE_JOYSTICK == SOURCE_JOYSTICK ||
            source and SOURCE_DPAD == SOURCE_DPAD

    private const val SOURCE_GAMEPAD = 0x00000401
    private const val SOURCE_JOYSTICK = 0x01000010
    private const val SOURCE_DPAD = 0x00000201

    /**
     * Una sola dirección a partir de todos los ejes del mando.
     *
     * Se miran los tres a la vez porque cada mando manda la cruceta por donde
     * quiere: los de Xbox y muchos clónicos por el "hat", los de PlayStation
     * por el stick derecho en Z/RZ, y el stick izquierdo siempre en X/Y. Gana
     * el eje que más lejos esté del centro, así que mover un stick no se pisa
     * con el reposo de otro.
     */
    fun axisDirection(
        hatX: Float,
        hatY: Float,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float,
    ): Pair<Float, Float> {
        val x = listOf(hatX, leftX, rightX).maxBy { kotlin.math.abs(it) }
        val y = listOf(hatY, leftY, rightY).maxBy { kotlin.math.abs(it) }
        return x to y
    }
}

/**
 * Convierte la posición de un stick en pulsaciones.
 *
 * Un stick no se suelta: mientras está inclinado el mando manda su posición
 * sesenta veces por segundo. Sin esto, empujar a la derecha recorrería la
 * biblioteca entera en un suspiro. Funciona como la repetición de una tecla:
 * dispara al inclinar, espera [firstDelayMs] y a partir de ahí repite cada
 * [repeatMs] mientras siga inclinado.
 *
 * [RELEASE] es más bajo que [THRESHOLD] a propósito (histéresis): un stick
 * gastado tiembla alrededor de su umbral, y sin esa holgura cada temblor
 * contaría como una pulsación nueva.
 */
class StickRepeater(
    private val firstDelayMs: Long = 380,
    private val repeatMs: Long = 110,
) {
    private var direction: Pad? = null
    private var nextFire = 0L

    /** La dirección que toca disparar ahora, o null si no toca ninguna. */
    fun update(x: Float, y: Float, now: Long): Pad? {
        val pad = directionOf(x, y)
        if (pad == null) {
            direction = null
            return null
        }
        if (pad != direction) {
            direction = pad
            nextFire = now + firstDelayMs
            return pad
        }
        if (now < nextFire) return null
        nextFire = now + repeatMs
        return pad
    }

    /** Se olvida de la inclinación en curso (al cambiar de pantalla o de capa). */
    fun reset() {
        direction = null
    }

    private fun directionOf(x: Float, y: Float): Pad? {
        val limit = if (direction == null) THRESHOLD else RELEASE
        val ax = kotlin.math.abs(x)
        val ay = kotlin.math.abs(y)
        if (ax < limit && ay < limit) return null
        // El eje dominante manda: en diagonal no se disparan dos direcciones.
        return if (ax >= ay) {
            if (x > 0) Pad.Right else Pad.Left
        } else {
            if (y > 0) Pad.Down else Pad.Up
        }
    }

    companion object {
        /** Desde dónde cuenta como inclinado (la zona muerta típica es 0.25). */
        const val THRESHOLD = 0.55f

        /** Hasta dónde hay que volver para darlo por soltado. */
        const val RELEASE = 0.35f
    }
}
