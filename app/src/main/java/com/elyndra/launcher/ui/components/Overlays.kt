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
        Column(
            Modifier
                .padding(horizontal = 26.dp)
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .animPopIn(320, key = spec)
                .glass(RoundedCornerShape(22.dp), solid = true)
                .consumeClicks()
                .padding(18.dp),
        ) {
            ElyText(spec.title.resolve(), size = 15f, weight = FontWeight.SemiBold, color = P.ink)
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
                Box(Modifier.padFocus(focus == 0)) {
                AccentButton(
                    spec.confirm.label.resolve(),
                    {
                        // El valor se lee al pulsar, no al componer: si no, se
                        // guardaría lo que hubiese antes de escribir.
                        val written = typed.value
                        onDismiss()
                        if (input != null) input.onConfirm(written) else spec.confirm.action()
                    },
                    fontSize = 12f,
                )
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

/**
 * Menú de pulsación larga.
 *
 * Antes era una lista plana: quince filas de texto seguidas donde "Abrir",
 * "Poner fondo" y "Quitar carpeta" pesaban lo mismo. Ahora la hoja tiene
 * jerarquía:
 *
 *   · Cabecera con la carátula de lo que se ha pulsado, su nombre y su ruta,
 *     para que se vea sobre qué se está actuando.
 *   · Las acciones van en bloques con rótulo (jugar, imágenes, gestionar) y
 *     cada bloque es una tarjeta propia con filas separadas por un filo.
 *   · Cada fila lleva su glifo en una pastilla teñida con el acento, y las
 *     destructivas van en rojo, en su propio bloque al final.
 *   · Asa arriba y "Cerrar" abajo: la hoja se lee y se cierra sin apuntar.
 */
@Composable
fun ActionSheetView(spec: ActionSheetSpec, onDismiss: () -> Unit, focus: Int = -1) {
    // A la lista se le da un alto máximo propio en vez de repartir el de la
    // hoja con `weight`: con `weight(fill = false)` la lista se medía más corta
    // de lo que luego pintaba y la última fila acababa por debajo de "Cerrar".
    // Midiendo así, la hoja es exactamente asa + cabecera + lista + botón, y
    // sigue encogiendo cuando el menú es corto.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val listMax = (screenHeight * 0.82f - SHEET_CHROME).coerceAtLeast(120.dp)

    val groups = spec.groups.filter { it.actions.isNotEmpty() }
    // Dónde empieza cada bloque: la primera fila que le toca (para saber cuál
    // está señalada) y su sitio en la lista (para poder llevarlo a la vista).
    val rowStarts = groups.runningFold(0) { acc, group -> acc + group.actions.size }
    val listIndex = groups.runningFold(0) { acc, group -> acc + if (group.header != null) 2 else 1 }
    val listState = rememberLazyListState()
    LaunchedEffect(focus) {
        if (focus < 0) return@LaunchedEffect
        val group = rowStarts.indexOfLast { it <= focus }.coerceIn(0, groups.lastIndex)
        runCatching { listState.animateScrollToItem(listIndex[group]) }
    }

    ScrimLayer(onDismiss = onDismiss, alignment = Alignment.BottomCenter, key = spec) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(10.dp)
                .animRiseSheet(key = spec)
                .glass(RoundedCornerShape(28.dp), solid = true)
                .consumeClicks()
                .padding(top = 10.dp, bottom = 12.dp),
        ) {
            GrabHandle()
            SheetHeader(spec)

            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = listMax),
                state = listState,
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 4.dp),
            ) {
                // La fila señalada por el mando se cuenta sobre el menú entero
                // y no sobre cada bloque: el mando no sabe de bloques, baja
                // fila a fila.
                groups.forEachIndexed { index, group ->
                    val start = rowStarts[index]
                    group.header?.let { header ->
                        item(key = "h$index") { GroupLabel(header) }
                    }
                    item(key = "g$index") {
                        GroupCard(group.actions, focus - start) { action ->
                            onDismiss()
                            action.action()
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }

            // GhostButton no centra su rótulo, así que se centra el botón entero.
            Box(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                GhostButton(stringResource(R.string.close), onDismiss)
            }
        }
    }
}

/** Lo que ocupa la hoja aparte de la lista: asa, cabecera, "Cerrar" y márgenes. */
private val SHEET_CHROME = 170.dp

/** El asa de una hoja: no arrastra, pero dice "esto se cierra hacia abajo". */
@Composable
private fun GrabHandle() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .padding(bottom = 10.dp)
                .size(width = 34.dp, height = 4.dp)
                .clip(CircleShape)
                .background(P.ink2.copy(alpha = 0.28f)),
        )
    }
}

/** Carátula (o icono), título y subtítulo de aquello sobre lo que actúa la hoja. */
@Composable
private fun SheetHeader(spec: ActionSheetSpec) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        spec.thumb?.let { thumb ->
            val shape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .size(width = 38.dp, height = 50.dp)
                    .clip(shape)
                    .border(1.dp, P.hairline, shape),
            ) {
                ArtImage(thumb.coverPath, thumb.pairIndex, Modifier.fillMaxSize())
                if (thumb.coverPath == null && (thumb.iconPath != null || thumb.packageName != null)) {
                    GameIcon(thumb.iconPath, thumb.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            ElyText(
                spec.title.resolve(),
                size = 15f,
                weight = FontWeight.SemiBold,
                color = P.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            spec.subtitle?.let {
                Spacer(Modifier.height(3.dp))
                ElyText(it.resolve(), size = 9.5f, color = P.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Rótulo de bloque: versalitas pequeñas y muy espaciadas, como el resto de Elyndra. */
@Composable
private fun GroupLabel(text: UiText) {
    ElyText(
        text.resolve().uppercase(),
        size = 8.5f,
        weight = FontWeight.SemiBold,
        color = P.ink2.copy(alpha = 0.85f),
        letterSpacing = tracking(0.22f),
        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
    )
}

/** Un bloque de filas como una sola tarjeta, con filo entre fila y fila. */
@Composable
private fun GroupCard(actions: List<SheetAction>, focus: Int, onClick: (SheetAction) -> Unit) {
    Column(Modifier.fillMaxWidth().liquidGlass(RoundedCornerShape(18.dp))) {
        actions.forEachIndexed { i, action ->
            if (i > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 54.dp)
                        .height(1.dp)
                        .background(P.hairline.copy(alpha = 0.55f)),
                )
            }
            SheetRow(action, focused = i == focus) { onClick(action) }
        }
    }
}

@Composable
private fun SheetRow(action: SheetAction, focused: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val accent = if (action.destructive) P.red else skin.a2

    Row(
        Modifier
            .fillMaxWidth()
            .padFocus(focused, radius = 12.dp)
            .clickable(onClick = onClick)
            .alpha(if (action.dimmed) 0.5f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pastilla del glifo. Se dibuja siempre, aunque la acción no traiga
        // icono: si no, las etiquetas de unas filas y otras no alinearían.
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = if (P.isDark) 0.22f else 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            action.icon?.let { SheetGlyph(it, accent, size = 15.dp) }
        }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            ElyText(
                action.label.resolve(),
                size = 12.5f,
                weight = if (action.selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (action.destructive) P.red else P.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            action.detail?.let {
                Spacer(Modifier.height(2.dp))
                ElyText(it.resolve(), size = 9.5f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        when {
            action.selected -> {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .drawBehind { drawRect(accentGradient(skin, 145f, size)) },
                )
            }
            action.opensSheet -> {
                Spacer(Modifier.width(8.dp))
                SheetChevron(P.ink2.copy(alpha = 0.6f))
            }
        }
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
