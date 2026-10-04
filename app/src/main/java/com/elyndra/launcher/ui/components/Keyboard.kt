package com.elyndra.launcher.ui.components

import android.view.InputDevice
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay

/* ─────────────────────────────────────────────────────────────
   Teclado en pantalla y campos de texto, en un solo sitio.

   La app va de borde a borde (MainActivity): Android ya no encoge la
   ventana al abrir el teclado (adjustResize no hace nada), solo avisa
   con el inset IME. Aquí se reparte lo que hace falta para que ningún
   campo quede debajo del teclado:

     · [imeSafePadding]: el hueco de una capa (diálogo, hoja) acaba
       encima del teclado; se anima con él.
     · [rememberPadField] + [padTextField]: lo que necesita cada campo.
       Se trae a la vista (con su cursor) al recibir el foco y cada vez
       que el teclado cambia de alto; con mando, el foco pasa por el
       campo sin abrir el teclado, A lo abre y la cruceta sale del campo.
     · [ImeBridge]: le dice al InputController si el teclado está a la
       vista y cómo cerrarlo, para que B lo cierre antes que la capa.
   ───────────────────────────────────────────────────────────── */

/** Padding de una capa modal: barras, muesca y, con el teclado abierto, el teclado. */
@Composable
fun Modifier.imeSafePadding(): Modifier =
    windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime))

/** ¿Está el teclado en pantalla? Se recompone al abrirse y al cerrarse, no en cada fotograma. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberImeVisible(): Boolean = WindowInsets.isImeVisible

/**
 * Estado de un campo de texto con mando.
 *
 * [armed]: el usuario pidió escribir (tocó el campo o pulsó A). Mientras el
 * mando esté en uso y no se haya pedido, el campo va en solo lectura: el
 * foco pasa por él sin que salte el teclado (el campo de Compose lo abre
 * en cuanto recibe el foco).
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
class PadField internal constructor() {
    var armed by mutableStateOf(false)
        internal set
    var focused by mutableStateOf(false)
        internal set
    internal val bring = BringIntoViewRequester()

    /** Pide escribir (A del mando o el atajo de un diálogo): deja de ir en solo lectura. */
    fun arm() {
        armed = true
    }

    /** El `readOnly` que se le pasa al `BasicTextField`. */
    fun readOnly(gamepad: Boolean): Boolean = gamepad && !armed
}

@Composable
fun rememberPadField(): PadField = remember { PadField() }

/**
 * Lo que necesita un campo de texto (va en el `modifier` del `BasicTextField`).
 *
 * - Al recibir el foco, y cada vez que el teclado se abre o cambia de alto,
 *   el campo se trae a la vista dentro de lo que se desplace.
 * - Con mando (teclas que vienen de una cruceta o un mando): la cruceta
 *   saca el foco del campo en vez de mover el cursor, y A abre el teclado.
 *   Un teclado físico sigue moviendo el cursor con sus flechas.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.padTextField(field: PadField): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val input = LocalPadInput.current
    val ime = rememberImeVisible()
    // Al abrirse el teclado la capa se encoge (imeSafePadding) mientras se
    // anima: se vuelve a traer el campo a la vista cuando ya ha acabado.
    LaunchedEffect(field.focused, ime) {
        if (!field.focused) return@LaunchedEffect
        runCatching { field.bring.bringIntoView() }
        if (ime) {
            delay(IME_SETTLE_MS)
            runCatching { field.bring.bringIntoView() }
        }
    }
    DisposableEffect(field) {
        onDispose { field.armed = false }
    }
    return this
        .bringIntoViewRequester(field.bring)
        .onFocusChanged {
            field.focused = it.isFocused
            // Con el dedo se escribe al tocar; con mando, al pulsar A.
            if (it.isFocused) {
                if (input?.active != true) field.armed = true
            } else {
                field.armed = false
            }
        }
        .onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || !fromPad(e.nativeKeyEvent)) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                Key.DirectionLeft -> focus.moveFocus(FocusDirection.Left)
                Key.DirectionRight -> focus.moveFocus(FocusDirection.Right)
                Key.DirectionCenter -> {
                    field.armed = true
                    keyboard?.show()
                    true
                }
                else -> false
            }
        }
}

/** La tecla viene de una cruceta o de un mando (no de un teclado físico). */
private fun fromPad(event: android.view.KeyEvent): Boolean =
    event.isFromSource(InputDevice.SOURCE_DPAD) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)

/**
 * Enlaza el teclado con el [com.elyndra.launcher.ui.InputController]: si está
 * a la vista y cómo cerrarlo. Va una vez, en la raíz de la app.
 */
@Composable
fun ImeBridge() {
    val input = LocalPadInput.current ?: return
    val keyboard = LocalSoftwareKeyboardController.current
    val visible = rememberImeVisible()
    LaunchedEffect(visible) { input.imeVisible = visible }
    DisposableEffect(input, keyboard) {
        input.hideKeyboard = { keyboard?.hide() }
        onDispose { input.hideKeyboard = null }
    }
}

/** Lo que tarda el teclado en asentarse (su animación de entrada). */
private const val IME_SETTLE_MS = 280L
