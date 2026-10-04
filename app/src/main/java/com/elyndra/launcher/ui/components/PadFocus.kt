package com.elyndra.launcher.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import com.elyndra.launcher.ui.InputController
import com.elyndra.launcher.ui.theme.focusRing
import kotlinx.coroutines.launch

/* ─────────────────────────────────────────────────────────────
   Foco del mando, en un solo sitio.

   Las pantallas de formulario (Ajustes, Añadir, Masha) y las capas que
   no maneja el InputController (ficha, selector de arte, editar nombre)
   se recorren con el foco de Compose: la cruceta y el stick llegan como
   teclas de cruceta (ver MainActivity) y A como DPAD_CENTER.

   Lo que aquí se reparte:
     · [PadFocusGroup]: el grupo de foco de una pantalla o capa. Coloca el
       foco inicial con el mando, lo devuelve a lo que tenía al volver de
       una capa, no deja que se escape de una capa modal ni que entre en
       la pantalla de detrás mientras hay una capa encima.
     · [padScrollFallback]: si la cruceta no encuentra dónde ir, desplaza.
     · [readingStop]: un bloque de lectura (sinopsis, datos) en el que el
       foco se para y que, si es más alto que el hueco, se recorre a pasos.
   ───────────────────────────────────────────────────────────── */

/** El mando, para las piezas que no reciben el ViewModel (campos de texto, grupos de foco). */
val LocalPadInput = staticCompositionLocalOf<InputController?> { null }

/**
 * Un grupo de foco registrado en el [InputController]. [entry] es el propio
 * grupo: pedirle el foco lo devuelve al último elemento que lo tuvo (o al
 * inicial). [hasFocus] lo lleva el grupo; no es estado de Compose.
 */
class PadFocusScope internal constructor(val entry: FocusRequester) {
    var hasFocus: Boolean = false
        internal set

    /** Lo que recibe el foco la primera vez (ver [padInitialFocus]). */
    internal var initial: FocusRequester? = null
}

private val LocalPadScope = staticCompositionLocalOf<PadFocusScope?> { null }

/**
 * Grupo de foco de una pantalla o capa. Todas las pantallas lo llevan de
 * serie (ElyndraApp); las capas que se manejan con el foco de Compose (ficha,
 * selector de arte, editar nombre) también.
 *
 * - [modal]: capa encima de otra: el foco no sale de ella.
 * - [blocked]: hay una capa encima: el foco no entra aquí desde ella.
 * - [padFocus]: false en las pantallas que maneja el propio InputController
 *   (Biblioteca y Carpeta, con su selección): ni se registra ni coloca foco,
 *   así no aparece un aro suelto sobre un botón del hero.
 *
 * Dentro, el foco vuelve al último elemento que lo tuvo al regresar de una
 * capa (el que la abrió); la primera vez, al marcado con [padInitialFocus]
 * o, si no hay, al primero que encuentre la cruceta. Mientras esté compuesto
 * es la capa de arriba para el mando: la primera pulsación sin nada señalado
 * coloca el foco aquí ([InputController.wakeFocus]), y con el mando en uso
 * nace ya con el foco puesto.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PadFocusGroup(
    modifier: Modifier = Modifier,
    modal: Boolean = false,
    blocked: Boolean = false,
    padFocus: Boolean = true,
    content: @Composable () -> Unit,
) {
    val input = LocalPadInput.current
    val scope = remember { PadFocusScope(FocusRequester()) }
    val blockedNow = rememberUpdatedState(blocked)
    DisposableEffect(scope, input, padFocus) {
        if (padFocus) input?.pushFocusScope(scope)
        onDispose { input?.popFocusScope(scope) }
    }
    // Quien navega con la cruceta espera encontrar algo señalado al abrir.
    val active = input?.active == true
    LaunchedEffect(scope, active, padFocus) {
        if (!padFocus || !active || scope.hasFocus) return@LaunchedEffect
        withFrameNanos { }
        if (input?.isTopFocusScope(scope) == true) runCatching { scope.entry.requestFocus() }
    }
    Box(
        modifier
            .onFocusChanged { scope.hasFocus = it.hasFocus }
            // Grupo de fuera: la frontera de la capa.
            .focusProperties {
                enter = { if (blockedNow.value) FocusRequester.Cancel else FocusRequester.Default }
                if (modal) exit = { FocusRequester.Cancel }
            }
            .focusGroup()
            // Grupo de dentro: recuerda lo último señalado.
            .focusRequester(scope.entry)
            .focusRestorer { scope.initial ?: FocusRequester.Default }
            .focusGroup(),
    ) {
        CompositionLocalProvider(LocalPadScope provides scope) { content() }
    }
}

/** Lo que recibe el foco cuando se llega a su grupo ([PadFocusGroup]) por primera vez. */
@Composable
fun Modifier.padInitialFocus(): Modifier {
    val scope = LocalPadScope.current ?: return this
    val requester = remember { FocusRequester() }
    DisposableEffect(scope, requester) {
        scope.initial = requester
        onDispose { if (scope.initial === requester) scope.initial = null }
    }
    return focusRequester(requester)
}

/**
 * El botón principal de la pantalla (Añadir → "Añadir N juegos"): Y del mando
 * lleva el foco a él sin recorrer toda la lista. Va en el botón o en lo que
 * lo contiene.
 */
@Composable
fun Modifier.padPrimaryAction(): Modifier {
    val input = LocalPadInput.current ?: return this
    val requester = remember { FocusRequester() }
    DisposableEffect(input, requester) {
        input.primaryAction = requester
        onDispose { if (input.primaryAction === requester) input.primaryAction = null }
    }
    return focusRequester(requester)
}

/**
 * La cruceta que no lleva el foco a ningún sitio desplaza [scroll]: al
 * principio de la ficha, arriba deja ver la cabecera; al final, abajo
 * enseña lo que queda. Va en el contenedor que desplaza (antes de
 * `verticalScroll`), así ve las teclas que no consumió nadie de dentro.
 */
@Composable
fun Modifier.padScrollFallback(scroll: ScrollState, step: Float = PAD_SCROLL_STEP): Modifier {
    val focus = LocalFocusManager.current
    val coroutines = rememberCoroutineScope()
    return onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
        val (direction, delta) = when (e.key) {
            Key.DirectionUp -> FocusDirection.Up to -step
            Key.DirectionDown -> FocusDirection.Down to step
            else -> return@onKeyEvent false
        }
        if (!focus.moveFocus(direction)) {
            coroutines.launch { scroll.animateScrollBy(delta) }
        }
        true
    }
}

/** Dónde está el hueco que se ve de un panel desplazable (coordenadas de ventana; no es estado). */
class ScrollViewport {
    internal var top = 0f
    internal var bottom = 0f

    /** Se pone en el contenedor que desplaza, antes de `verticalScroll`. */
    val modifier: Modifier = Modifier.onGloballyPositioned {
        top = it.positionInWindow().y
        bottom = top + it.size.height
    }
}

/**
 * Un bloque de lectura en el que el foco se para (con su aro, sin pulsarse).
 * Si es más alto que lo que se ve, arriba y abajo lo recorren a pasos antes
 * de saltar al siguiente: así una sinopsis larga se lee entera con la cruceta.
 */
@Composable
fun Modifier.readingStop(
    scroll: ScrollState,
    viewport: ScrollViewport,
    shape: Shape,
    accent: Color,
    step: Float = PAD_SCROLL_STEP,
): Modifier {
    val coroutines = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val bounds = remember { FloatArray(2) }
    return this
        .onGloballyPositioned {
            val y = it.positionInWindow().y
            bounds[0] = y
            bounds[1] = y + it.size.height
        }
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val delta = when (e.key) {
                Key.DirectionDown -> (bounds[1] - viewport.bottom).takeIf { it > 1f }?.coerceAtMost(step)
                Key.DirectionUp -> (viewport.top - bounds[0]).takeIf { it > 1f }?.coerceAtMost(step)?.unaryMinus()
                else -> null
            } ?: return@onPreviewKeyEvent false
            coroutines.launch { scroll.animateScrollBy(delta) }
            true
        }
        .indication(interaction, focusRing(shape, accent))
        .focusable(interactionSource = interaction)
}

/** Lo que baja un panel por pulsación cuando no hay a dónde llevar el foco (px). */
const val PAD_SCROLL_STEP = 420f
