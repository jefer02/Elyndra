package com.elyndra.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ActionSheetSpec
import com.elyndra.launcher.ui.DialogInput
import com.elyndra.launcher.ui.DialogSpec
import com.elyndra.launcher.ui.SheetAction
import com.elyndra.launcher.ui.UiText
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animFadeIn
import com.elyndra.launcher.ui.theme.animFadeUp
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.animRiseSheet
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.liquidGlass

/** Clic sin ondulación, para que las capas no dejen pasar toques al fondo. */
@Composable
fun Modifier.consumeClicks(onClick: () -> Unit = {}): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Velo oscuro a pantalla completa; tocar fuera cierra. */
@Composable
fun ScrimLayer(
    onDismiss: (() -> Unit)?,
    alignment: Alignment,
    key: Any,
    alpha: Float = 0.45f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .animFadeIn(200, key = key)
            .background(P.shade.copy(alpha = alpha))
            .consumeClicks { onDismiss?.invoke() }
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = alignment,
        content = content,
    )
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
fun ElyDialogView(spec: DialogSpec, onDismiss: () -> Unit, focus: Int = -1) {
    ScrimLayer(onDismiss = onDismiss, alignment = Alignment.Center, key = spec) {
        // Lámina de cristal: en un borrado, el filo y el halo van en rojo, así
        // que el aviso se reconoce por el color antes de leer una palabra.
        GlassCard(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .widthIn(max = 400.dp)
                .animPopIn(320, key = spec)
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
            // Lo escrito vive aquí, no en el spec: el diálogo se repinta con
            // cada tecla y el botón de aceptar lee este mismo estado al pulsar.
            val typed = remember(spec) { mutableStateOf(spec.input?.initial.orEmpty()) }
            spec.input?.let { input ->
                Spacer(Modifier.height(14.dp))
                DialogField(input, typed.value) { typed.value = it }
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
                    val written = typed.value
                    onDismiss()
                    if (input != null) input.onConfirm(written) else spec.confirm.action()
                }
                Box(Modifier.padFocus(focus == 0)) {
                    if (spec.destructive) {
                        DangerButton(spec.confirm.label.resolve(), confirm, fontSize = 12f)
                    } else {
                        AccentButton(spec.confirm.label.resolve(), confirm, fontSize = 12f)
                    }
                }
            }
        }
    }
}

/**
 * Aro de acento alrededor de lo que señala el mando.
 *
 * Con el dedo se ve lo que se toca; con mando, no: sin una marca no hay forma
 * de saber sobre qué va a caer el botón A. Va como un aro por fuera —no tiñe
 * ni mueve nada— para que el mismo menú sirva para las dos formas de manejarlo.
 */
@Composable
fun Modifier.padFocus(focused: Boolean, radius: Dp = 13.dp): Modifier {
    if (!focused) return this
    val skin = LocalSkin.current
    return this
        .border(2.dp, skin.a2, RoundedCornerShape(radius))
        .padding(2.dp)
}

/**
 * Campo de un diálogo que pide un dato suelto (ver [DialogInput]).
 *
 * Pide el foco y abre el teclado al aparecer: el diálogo sale sobre un velo a
 * pantalla completa y, sin eso, se puede teclear creyendo que se está
 * escribiendo en el campo cuando no lo tiene nadie.
 */
@Composable
private fun DialogField(input: DialogInput, value: String, onChange: (String) -> Unit) {
    val skin = LocalSkin.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(input) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.7f))
            .border(1.dp, P.ink.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            input.placeholder?.let { ElyText(it.resolve(), size = 12f, color = P.ink2.copy(alpha = 0.6f)) }
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = inputStyle(12f),
            cursorBrush = SolidColor(skin.a2),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (input.numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
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
