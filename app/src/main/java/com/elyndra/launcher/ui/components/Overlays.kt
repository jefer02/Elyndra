package com.elyndra.launcher.ui.components

import com.elyndra.launcher.ui.theme.LocalReducedMotion
import androidx.compose.runtime.State
import com.elyndra.launcher.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.DialogInput
import com.elyndra.launcher.ui.DialogSpec
import com.elyndra.launcher.ui.UiText
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.animFadeUp
import com.elyndra.launcher.ui.theme.darkGlass

/** Clic sin ondulación, para que las capas no dejen pasar toques al fondo. */
@Composable
fun Modifier.consumeClicks(onClick: () -> Unit = {}): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/**
 * El fondo de una capa modal (diálogo, selector de arte, editar nombre): el
 * mismo que el menú de acciones y la ficha ([BackdropScrim]): fondo
 * desenfocado una sola vez, velo a sangre que se apila con el de la capa de
 * debajo y se funde al salir. Tocar fuera cierra. Lo monta un [OverlayHost],
 * que da [open] y [progress]; el panel entra y sale con [overlayEmerge].
 *
 * El contenido acaba encima del teclado ([imeSafePadding]): un diálogo o una
 * hoja con un campo de texto no queda nunca debajo de él.
 */
@Composable
fun ScrimLayer(
    onDismiss: (() -> Unit)?,
    alignment: Alignment,
    open: Boolean,
    progress: State<Float>,
    z: Int,
    light: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        BackdropScrim(progress, { onDismiss?.invoke() }, open = open, z = z, light = light)
        Box(Modifier.fillMaxSize().imeSafePadding(), contentAlignment = alignment, content = content)
    }
}

/**
 * Diálogo.
 *
 * [focus] es el botón que señala el mando (-1 = ninguno, que es lo normal con
 * el dedo). Se pinta como un aro de acento alrededor del botón, en el mismo
 * orden en que los lee [com.elyndra.launcher.ui.InputController.dialogButtons]:
 * aceptar, descartar y el tercero.
 */
@Composable
fun ElyDialogView(
    spec: DialogSpec,
    open: Boolean,
    progress: State<Float>,
    onDismiss: () -> Unit,
    focus: Int = -1,
    /** Lo escrito en el campo (si lo hay): vive en el ViewModel para que A del mando lo confirme. */
    text: String = "",
    onText: (String) -> Unit = {},
) {
    val reduced = LocalReducedMotion.current
    // Un diálogo sin campo es una confirmación pequeña: capa ligera (ver BackdropLevels).
    ScrimLayer(onDismiss = onDismiss, alignment = Alignment.Center, open = open, progress = progress, z = Backdrop.Z_DIALOG, light = spec.input == null) {
        // Lámina de cristal: en un borrado, el filo y el halo van en rojo, así
        // que el aviso se reconoce por el color antes de leer una palabra.
        GlassCard(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .widthIn(max = 400.dp)
                .overlayEmerge(progress, reduced)
                .consumeClicks(),
            cornerRadius = 24.dp,
            frost = 0.82f,
            glowColor = if (spec.destructive) P.red else null,
            glow = if (spec.destructive) 1f else 0.35f,
            elevation = 24.dp,
            padding = PaddingValues(18.dp),
        ) {
            ElyText(spec.title.resolve(), size = 15.5f, weight = FontWeight.Bold, color = P.ink)
            Spacer(Modifier.height(8.dp))
            ElyText(spec.message.resolve(), size = 12f, color = P.ink2, lineHeightRatio = 1.5f)
            // Lo escrito vive en el ViewModel, no en el spec: el diálogo se
            // repinta con cada tecla y aceptar (con el dedo, con A o con Intro)
            // lee ese mismo estado al pulsar.
            val typed = rememberUpdatedState(text)
            val confirmTyped = {
                val input = spec.input
                if (input != null) {
                    val written = typed.value
                    onDismiss()
                    input.onConfirm(written)
                }
            }
            spec.input?.let { input ->
                Spacer(Modifier.height(14.dp))
                DialogField(input, text, onText, onDone = confirmTyped)
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                spec.extra?.let { b ->
                    Box(Modifier.padFocus(focus == 2)) { GhostButton(b.label.resolve(), { onDismiss(); b.action() }) }
                }
                Spacer(Modifier.weight(1f))
                spec.dismiss?.let { b ->
                    Box(Modifier.padFocus(focus == 1)) { GhostButton(b.label.resolve(), { onDismiss(); b.action() }) }
                }
                val input = spec.input
                val confirm = {
                    // El valor se lee al pulsar, no al componer: si no, se
                    // guardaría lo que hubiese antes de escribir.
                    if (input != null) {
                        confirmTyped()
                    } else {
                        onDismiss()
                        spec.confirm.action()
                    }
                }
                // El aro abraza el botón de 15 dp: su radio más el hueco de 2 dp.
                Box(Modifier.padFocus(focus == 0, radius = 17.dp)) {
                    if (spec.destructive) {
                        DangerButton(spec.confirm.label.resolve(), confirm, fontSize = 12f)
                    } else {
                        AccentButton(spec.confirm.label.resolve(), confirm, fontSize = 12f)
                    }
                }
            }
            // Las pistas del mando, como en el resto de capas (↑ abre el teclado del campo).
            PadHints(
                hints = if (spec.input != null) DIALOG_INPUT_HINTS else DIALOG_HINTS,
                visible = LocalPadInput.current?.gamepadPresent == true,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

private val DIALOG_HINTS = listOf(PadHint("A", R.string.hint_select), PadHint("B", R.string.close))
private val DIALOG_INPUT_HINTS = listOf(PadHint("↑", R.string.hint_keyboard)) + DIALOG_HINTS

/**
 * Aro de acento alrededor de lo que señala el mando.
 *
 * Con el dedo se ve lo que se toca; con mando, no: sin una marca no hay forma
 * de saber sobre qué va a caer el botón A. Va como un aro por fuera —no tiñe
 * ni mueve nada— para que el mismo menú sirva para las dos formas de manejarlo.
 */
@Composable
fun Modifier.padFocus(focused: Boolean, radius: Dp = 13.dp): Modifier {
    val skin = LocalSkin.current
    // El hueco del aro está siempre: si solo apareciera con el foco, los botones
    // saltarían 4 dp cada vez que el mando pasa por ellos.
    return this
        .border(2.dp, if (focused) skin.a2 else Color.Transparent, RoundedCornerShape(radius))
        .padding(2.dp)
}

/**
 * Campo de un diálogo que pide un dato suelto (ver [DialogInput]).
 *
 * Pide el foco al aparecer: el diálogo sale sobre un velo a pantalla completa
 * y, sin eso, se puede teclear creyendo que se está escribiendo en el campo
 * cuando no lo tiene nadie. Con el dedo abre el teclado; con mando, A lo abre
 * (ver [padTextField]). "Hecho" en el teclado acepta.
 */
@Composable
private fun DialogField(input: DialogInput, value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val skin = LocalSkin.current
    val focus = remember { FocusRequester() }
    val field = rememberPadField()
    val pad = LocalPadInput.current
    LaunchedEffect(input) {
        runCatching { focus.requestFocus() }
    }
    // Con mando, arriba abre el teclado aquí (el diálogo se come el resto de botones).
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(pad, field) {
        pad?.dialogKeyboard = {
            runCatching { focus.requestFocus() }
            field.arm()
            keyboard?.show()
        }
        onDispose { pad?.dialogKeyboard = null }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            // En oscuro, un blanco al 70 % dejaba el texto (casi blanco) ilegible.
            .background(if (P.isDark) P.chip else Color.White.copy(alpha = 0.7f))
            .border(1.dp, if (field.focused) skin.a2 else P.ink.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            input.placeholder?.let { ElyText(it.resolve(), size = 12f, color = P.ink2.copy(alpha = 0.6f)) }
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            readOnly = field.readOnly(pad?.active == true),
            textStyle = inputStyle(12f),
            cursorBrush = SolidColor(skin.a2),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (input.numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).padTextField(field),
        )
    }
}


@Composable
fun ToastView(text: UiText, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 24.dp)
            .animFadeUp(260, key = text)
            .darkGlass(RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ElyText(text.resolve(), size = 11.5f, weight = FontWeight.Medium, color = Color.White, maxLines = 3)
    }
}
