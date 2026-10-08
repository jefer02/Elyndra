package com.elyndra.launcher.ui.meridian

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * La rueda: su lista, la fila a la que va ([target], la seleccionada) y el
 * muelle que la lleva. Todas las filas miden [rowPx], así que la posición en
 * filas sale del desplazamiento lógico de la lista sin medir nada.
 *
 * [position] lee el estado de la lista: solo se lee al dibujar (capas de las
 * filas, paralaje) y en efectos, nunca al componer.
 */
@Stable
internal class WheelState(val list: LazyListState, initialTarget: Int) {

    /** Alto de fila en px; lo pone la rueda al medirse. */
    var rowPx by mutableFloatStateOf(0f)

    /** Alto de la rueda y su línea de foco (px), y cuántas filas tiene: de ahí sale el centrado de las listas cortas. */
    var railPx by mutableFloatStateOf(0f)
    var focusPx by mutableFloatStateOf(0f)
    var count by mutableIntStateOf(0)

    /** Lo que ocupa arriba el bocadillo de Masha mientras se ve (px desde el canto de la rueda); se lee al dibujar. */
    var reserve: () -> Float = { 0f }

    /** La fila que tiene que quedar en la línea de foco. */
    var target by mutableIntStateOf(initialTarget)

    /** La mueve el muelle (no el dedo): ni selecciona al parar ni da toques. */
    var programmatic = false
        private set

    /** El dedo la ha movido desde la última vez que se paró. */
    internal var touched = false

    /** Velocidad del muelle (filas/s), que sobrevive a un cambio de destino a mitad de camino. */
    private var velocity = 0f
    private val step = FloatArray(2)

    val position: Float get() = WheelMath.position(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, rowPx)

    fun distance(index: Int): Float = WheelMath.distance(index, position)

    /**
     * Lo que se mueve la línea de foco (px, + = abajo): el centrado de las
     * listas cortas ([ShortList]) y media reserva del bocadillo de Masha. Lo
     * leen al dibujar las filas, el nodo, el dial y el contador: van juntos.
     */
    fun lift(): Float {
        val row = rowPx
        if (row <= 0f) return 0f
        return ShortList.lift(position, count, railPx / row, focusPx / row) * row + reserve() * BubbleSlot.SHIFT
    }

    /**
     * Lleva la rueda hasta [target] con el muelle y no vuelve hasta que llega.
     * Si el destino cambia mientras tanto (el mando pide otra fila), sigue hacia
     * el nuevo sin frenar. Un salto largo se coloca cerca y recorre solo el
     * final. Con "reducir movimiento", salta. El dedo manda: si arrastra la
     * lista, el muelle se retira.
     */
    suspend fun follow(reduced: Boolean) {
        val px = rowPx
        if (px <= 0f || WheelMath.settled(position, velocity, target.toFloat())) return
        programmatic = true
        try {
            if (reduced) {
                velocity = 0f
                if (abs(target - position) > 0.001f) list.scrollToItem(target)
                return
            }
            val gap = target - position
            if (abs(gap) > WheelMath.JUMP) {
                velocity = 0f
                list.scrollToItem((target - sign(gap).toInt() * WheelMath.JUMP_TAIL).coerceAtLeast(0))
            }
            list.scroll { glide(px) }
            // Si la lista cambió a mitad (orden, búsqueda), se queda clavada en su fila.
            if (abs(target - position) > 0.01f) list.scrollToItem(target)
        } catch (e: CancellationException) {
            // El dedo le ha quitado la lista al muelle: se retira sin más.
            velocity = 0f
            if (!currentCoroutineContext().isActive) throw e
        } finally {
            programmatic = false
        }
    }

    private suspend fun ScrollScope.glide(px: Float) {
        var x = position
        var v = velocity
        var last = withFrameNanos { it }
        while (true) {
            val t = target.toFloat()
            if (WheelMath.settled(x, v, t)) {
                scrollBy((t - x) * px)
                break
            }
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            WheelMath.springStep(x, v, t, dt, step)
            val wanted = (step[0] - x) * px
            val used = scrollBy(wanted)
            x += used / px
            v = if (abs(used - wanted) > 0.5f) 0f else step[1]
            velocity = v
            // Contra un extremo de la lista no hay a dónde seguir.
            if (v == 0f && abs(used) < 0.5f && abs(wanted) > 0.5f) break
        }
        velocity = 0f
    }
}

@Composable
internal fun rememberWheelState(list: LazyListState, initialTarget: Int): WheelState =
    remember(list) { WheelState(list, initialTarget) }

/**
 * Al soltar la rueda con el dedo, se para con la fila más cercana en la línea
 * de foco (o la siguiente en el sentido del gesto si va rápida). Todas las
 * filas miden lo mismo, así que no hace falta mirar qué hay en pantalla.
 */
internal class WheelSnap(private val wheel: WheelState, private val count: () -> Int, private val minVelocity: Float) : SnapLayoutInfoProvider {

    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float =
        WheelMath.approachOffset(decayOffset, wheel.rowPx)

    override fun calculateSnapOffset(velocity: Float): Float {
        val position = wheel.position
        return (WheelMath.snapTarget(position, velocity, minVelocity, count()) - position) * wheel.rowPx
    }
}

/**
 * Lo que la rueda hace sola: ir con el muelle a la fila seleccionada, avisar
 * de la fila en la que se para tras moverla con el dedo ([onSettle]) y un
 * toque háptico por fila que pasa bajo el dedo (como mucho uno cada
 * [HAPTIC_GAP_MS]; los toques respetan el ajuste del sistema).
 */
@Composable
internal fun WheelEffects(wheel: WheelState, reduced: Boolean, count: Int, active: Boolean, onSettle: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val settle by rememberUpdatedState(onSettle)
    val total by rememberUpdatedState(count)
    LaunchedEffect(wheel, reduced) {
        snapshotFlow { wheel.target to wheel.rowPx }.collect { wheel.follow(reduced) }
    }
    LaunchedEffect(wheel, active) {
        if (!active) return@LaunchedEffect
        launch {
            wheel.list.interactionSource.interactions.collect { if (it is DragInteraction.Start) wheel.touched = true }
        }
        launch {
            var lastTick = 0L
            snapshotFlow { if (wheel.rowPx > 0f) wheel.position.roundToInt() else -1 }.collect { row ->
                if (row < 0 || !wheel.touched || wheel.programmatic) return@collect
                val now = System.nanoTime()
                if (now - lastTick >= HAPTIC_GAP_MS * 1_000_000L) {
                    lastTick = now
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
        snapshotFlow { wheel.list.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && wheel.touched) {
                wheel.touched = false
                if (total > 0) settle(wheel.position.roundToInt().coerceIn(0, total - 1))
            }
        }
    }
}

/** Entre dos toques hápticos de la rueda: a toda velocidad no se convierten en un zumbido. */
private const val HAPTIC_GAP_MS = 45L
