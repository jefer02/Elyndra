package com.elyndra.launcher.ui.screens

import com.elyndra.launcher.ui.components.DynamicBackdrop
import com.elyndra.launcher.ui.theme.rememberPress
import com.elyndra.launcher.ui.theme.pressScale
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.LibraryFilter
import com.elyndra.launcher.ui.LibraryItem
import com.elyndra.launcher.ui.BarItem
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.DisintegratingContainer
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.MaterializingContainer
import com.elyndra.launcher.ui.components.Hero
import com.elyndra.launcher.ui.components.rememberGameDescription
import com.elyndra.launcher.ui.components.ConsoleIconButton
import com.elyndra.launcher.ui.components.HeroBarHeight
import com.elyndra.launcher.ui.components.ParticleLayer
import com.elyndra.launcher.ui.components.rememberParticleField
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.components.MashaInsightBubble
import com.elyndra.launcher.ui.components.LogoImage
import com.elyndra.launcher.ui.components.OpenButton
import com.elyndra.launcher.ui.components.PortalExpandContainer
import com.elyndra.launcher.ui.components.PortalSpec
import com.elyndra.launcher.ui.components.Metrics
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.components.MAGNETIC_PULL_MS
import com.elyndra.launcher.ui.components.rememberMagneticPress
import com.elyndra.launcher.ui.components.SearchGlyph
import com.elyndra.launcher.ui.components.SettingsGlyph
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.HeroTitleShadow
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animAppEntrance
import com.elyndra.launcher.ui.theme.animFadeUp
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.animTitleIn
import com.elyndra.launcher.ui.theme.consoleFaceBrush
import com.elyndra.launcher.ui.theme.curtainAlpha
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.liquidGlass
import com.elyndra.launcher.ui.theme.pulseHintAlpha
import com.elyndra.launcher.ui.theme.ringProgress
import com.elyndra.launcher.ui.theme.selectionLift
import com.elyndra.launcher.ui.theme.selectionScale
import com.elyndra.launcher.ui.theme.sheenBrush
import com.elyndra.launcher.ui.theme.sheenProgress
import com.elyndra.launcher.ui.theme.FloatClock
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.consoleFocus
import com.elyndra.launcher.ui.theme.floatPhaseOf
import com.elyndra.launcher.ui.theme.floating
import com.elyndra.launcher.ui.theme.rememberFloatClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * El portal de lanzamiento de un juego Android: la card crece y se funde con
 * su velo de carga. Las carpetas de emulador ya no lo usan —se abren al
 * instante, sin expansión (ver `ElyndraViewModel.requestOpen`)—.
 */
private val GamePortal = PortalSpec()

@Composable
fun LibraryScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val skin = LocalSkin.current
    val items = vm.items()
    val sel = vm.selected()
    // Con mando la selección se mueve sin tocar el carrusel, así que el
    // carrusel va detrás de ella: si no, se estaría eligiendo a ciegas.
    val carousel = rememberLazyListState()
    LaunchedEffect(sel?.key, items.size) {
        val index = items.indexOfFirst { it.key == sel?.key }
        if (index >= 0) runCatching { carousel.animateScrollToItem(index) }
    }
    // Dónde se tocó la última card que se abrió: es el origen del destello.
    // Solo se abre una a la vez, así que con un par de valores basta; abrir
    // con el mando o con "Abrir" no pasa punto y el destello sale del centro.
    var portalKey by remember { mutableStateOf<String?>(null) }
    var portalTap by remember { mutableStateOf(Offset.Zero) }
    // Un solo reloj para la flotación de todos los logos de juego de la pantalla.
    val floatClock = rememberFloatClock()
    Box(Modifier.fillMaxSize()) {
        // Fondo vivo: el arte de la selección, desenfocado y teñido con su color.
        DynamicBackdrop(
            when (sel) {
                is LibraryItem.App -> sel.app.meta.hero ?: sel.app.meta.screenshot ?: sel.app.meta.icon
                is LibraryItem.Folder -> sel.heroPath ?: sel.iconPath
                null -> null
            },
        )
        Column(Modifier.fillMaxSize().animAppEntrance(key = Screen.Library)) {

            Hero(
                pairIndex = sel?.let { vm.pairIndexOf(it) } ?: 0,
                heroKey = sel?.key ?: "none",
                // Quitar el fondo lo deshace en polvo antes de borrarlo.
                backgroundVanishing = sel?.let { vm.isVanishingArt(it.key, ArtKind.Background) } == true,
                onBackgroundVanished = { vm.finishVanish() },
                height = m.iconHeroH,
                imagePath = when (sel) {
                    is LibraryItem.App -> sel.app.meta.hero ?: sel.app.meta.screenshot
                    is LibraryItem.Folder -> sel.heroPath
                    null -> null
                },
                topBar = {
                    Row(
                        Modifier
                            .align(Alignment.TopStart)
                            .fillMaxWidth()
                            .padding(start = m.pad, end = m.pad, top = if (m.landscape) 8.dp else 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Spacer(Modifier.weight(1f))
                        // "Abrir" vive aquí, sobre el fondo del juego. Mientras
                        // se busca desaparece: el campo abierto necesita ese
                        // ancho y ahí nadie está lanzando nada.
                        if (!vm.searchOpen) {
                            OpenButton(enabled = sel != null, focused = vm.input.isBarFocused(BarItem.Open)) {
                                sel?.let {
                                    portalKey = null
                                    vm.requestOpen(it)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                        SearchField(vm, m)
                        Spacer(Modifier.width(8.dp))
                        ConsoleIconButton(
                            onClick = { vm.go(Screen.Settings) },
                            focused = vm.input.isBarFocused(BarItem.Settings),
                        ) { glyph -> SettingsGlyph(glyph) }
                    }
                },
                info = {
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = m.pad, end = m.pad, bottom = if (m.landscape) 8.dp else 18.dp),
                    ) {
                        if (sel != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = if (m.landscape) 3.dp else 6.dp),
                            ) {
                                HeroChip(
                                    stringResource(
                                        if (sel is LibraryItem.Folder) R.string.chip_emulator_folder else R.string.chip_android_app,
                                    ),
                                )
                                Spacer(Modifier.width(8.dp))
                                ElyText(
                                    heroSubline(sel),
                                    size = 9.5f,
                                    weight = FontWeight.Medium,
                                    color = Color.White.copy(alpha = 0.85f),
                                    letterSpacing = tracking(0.1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        val logo = when (sel) {
                            is LibraryItem.App -> sel.app.meta.logo
                            is LibraryItem.Folder -> sel.logoPath
                            null -> null
                        }
                        // El `sel != null` es para el compilador: el `when` de arriba no le
                        // basta para deducir que si hay logo entonces hay elemento.
                        if (sel != null && logo != null) {
                            // Al quitar el logo se deshace en el sitio donde
                            // se está viendo, en vez de desaparecer de golpe.
                            MaterializingContainer(
                                isMaterializing = vm.isMaterializingArt(sel.key, ArtKind.Logo),
                                onAnimationEnd = { vm.finishMaterializeArt() },
                            ) {
                                DisintegratingContainer(
                                    isDisintegrating = vm.isVanishingArt(sel.key, ArtKind.Logo),
                                    onAnimationEnd = { vm.finishVanish() },
                                ) {
                                    LogoImage(
                                        logo,
                                        Modifier
                                            .animTitleIn(key = sel.key)
                                            .floating(floatClock, floatPhaseOf(sel.key), amplitude = 3.dp, periodSeconds = 4.2f)
                                            .fillMaxWidth(0.72f)
                                            .height(m.logoH),
                                    )
                                }
                            }
                        } else {
                            ElyText(
                                when {
                                    sel != null -> sel.name
                                    vm.loaded -> stringResource(R.string.empty_library_title)
                                    else -> ""
                                },
                                modifier = Modifier.animTitleIn(key = sel?.key ?: "none"),
                                size = m.titleSize,
                                weight = FontWeight.ExtraBold,
                                color = Color.White,
                                letterSpacing = tracking(-0.03f),
                                lineHeightRatio = 0.92f,
                                shadow = HeroTitleShadow,
                                uppercase = true,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val description = rememberGameDescription(
                            title = (sel as? LibraryItem.App)?.name,
                            stored = (sel as? LibraryItem.App)?.app?.meta?.description,
                            lang = vm.settings.lang,
                            short = true,
                        )
                        if (description != null) {
                            ElyText(
                                description,
                                modifier = Modifier
                                    .padding(top = if (m.landscape) 4.dp else 6.dp)
                                    .fillMaxWidth(0.86f)
                                    .animFadeUp(key = sel?.key ?: "none"),
                                size = 10f,
                                weight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.72f),
                                letterSpacing = tracking(0.02f),
                                lineHeightRatio = 1.28f,
                                maxLines = if (m.landscape) 2 else 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            ElyText(
                                stringResource(if (sel == null) R.string.empty_library_hint else R.string.hint_gestures),
                                modifier = Modifier
                                    .padding(top = if (m.landscape) 4.dp else 7.dp)
                                    .alpha(pulseHintAlpha()),
                                size = 9f,
                                weight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.8f),
                                letterSpacing = tracking(0.14f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                uppercase = true,
                            )
                        }
                    }
                },
            )

            // ── CARRUSEL ──
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = m.pad, end = m.pad, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LibraryFilter.entries.forEach { f ->
                        FilterTab(stringResource(f.label), vm.filter == f) { vm.updateFilter(f) }
                    }
                    Spacer(Modifier.weight(1f))
                    ElyText(
                        pluralStringResource(R.plurals.items_count, items.size, items.size),
                        size = 9f,
                        weight = FontWeight.Medium,
                        color = P.ink2.copy(alpha = 0.8f),
                        letterSpacing = tracking(0.18f),
                        uppercase = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    // "Ordenar por": el criterio en curso hace de etiqueta del botón.
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .clickable { vm.sortOptions() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        ElyText(
                            stringResource(vm.settings.sortMode.label),
                            size = 9f,
                            weight = FontWeight.SemiBold,
                            color = skin.a2,
                            letterSpacing = tracking(0.1f),
                            maxLines = 1,
                            uppercase = true,
                        )
                    }
                }

                val searchEmpty = vm.query.isNotBlank() && items.isEmpty() && vm.loaded
                if (searchEmpty) {
                    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = m.pad), contentAlignment = Alignment.Center) {
                        ElyText(stringResource(R.string.no_results), size = 11f, color = P.ink2, align = TextAlign.Center)
                    }
                } else {
                    // Sin resultados por el filtro (no por la búsqueda): la
                    // sección de Consolas puede estar vacía sin que la
                    // biblioteca lo esté. La aviso encima, pero la card de
                    // "Añadir" sigue viviendo en el propio carrusel —a la
                    // izquierda cuando no hay nada más, empujada al final en
                    // cuanto entra la primera consola— igual que en Android.
                    if (items.isEmpty() && vm.loaded && vm.filter != LibraryFilter.All) {
                        ElyText(
                            stringResource(emptyFilterHint(vm.filter)),
                            modifier = Modifier.padding(start = m.pad, end = m.pad, bottom = 4.dp),
                            size = 11f,
                            color = P.ink2,
                        )
                    }
                    LazyRow(
                        Modifier.fillMaxWidth().weight(1f),
                        state = carousel,
                        contentPadding = PaddingValues(
                            start = m.pad,
                            end = m.pad,
                            // Hueco para la card seleccionada, que sube y se amplía: sin él
                            // se metía sobre la fila de filtros.
                            top = m.carouselTop,
                            bottom = m.carouselBottom,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                            val materializing = item.key in vm.materializing
                            // Las dos caras de lo mismo: un juego recién
                            // añadido se monta desde el polvo al entrar, y al
                            // quitarlo se deshace en polvo antes de salir de
                            // la lista (`finishVanish` es quien borra).
                            PortalExpandContainer(
                                // Solo un juego Android llega a "abrirse" con
                                // portal; una carpeta nunca activa esto.
                                isOpening = vm.opening?.key == item.key,
                                spec = GamePortal,
                                tapOffset = if (portalKey == item.key) portalTap else null,
                                // Un juego Android arranca al empezar a crecer:
                                // el lanzamiento y la animación corren a la vez.
                                onExpandStart = { if (item is LibraryItem.App) vm.open(item) },
                                onLaunchComplete = { vm.openingFinished() },
                            ) {
                                MaterializingContainer(
                                    isMaterializing = materializing,
                                    onAnimationEnd = { vm.finishMaterialize(item.key) },
                                ) {
                                    DisintegratingContainer(
                                        isDisintegrating = vm.vanishing == item.key,
                                        onAnimationEnd = { vm.finishVanish() },
                                    ) {
                                        LibraryTile(
                                            item = item,
                                            index = i,
                                            floatClock = floatClock,
                                            selected = item.key == sel?.key,
                                            metrics = m,
                                            pairIndex = vm.pairIndexOf(item),
                                            entrance = !materializing,
                                            onTap = { vm.select(item.key) },
                                            onOpen = { offset ->
                                                portalKey = item.key
                                                portalTap = offset
                                                vm.requestOpen(item)
                                            },
                                            onLongPress = { center ->
                                                vm.select(item.key)
                                                // De aquí sale el overlay.
                                                vm.markSheetOrigin(center)
                                                vm.itemOptions(item)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        // "Añadir" es una card más y va al final de la fila: con la
                        // biblioteca (o el filtro) vacíos es la única que hay, y en
                        // cuanto entra un juego se corre detrás de todos sin dejar
                        // de estar a mano. El `loaded` evita que la card asome
                        // mientras se lee la biblioteca del disco, con el carrusel
                        // todavía vacío.
                        if (vm.loaded) {
                            item(key = "add") { AddTile(m, items.size) { vm.go(Screen.Add) } }
                        }
                    }
                }
            }

            // Sin dock: "Abrir" está arriba, sobre el fondo del juego, y
            // "Añadir" es la última card del carrusel. El alto que ocupaba se
            // lo reparten hero y cards (ver `libFree` en Metrics).
        }

        MashaFab(vm, m)
    }
}

@Composable
private fun heroSubline(sel: LibraryItem): String = when (sel) {
    is LibraryItem.Folder -> pluralStringResource(
        R.plurals.roms_with_emulator,
        sel.romCount,
        sel.romCount,
        sel.emulatorName ?: "—",
    )
    is LibraryItem.App -> when {
        !sel.installed -> stringResource(R.string.app_not_installed_badge)
        sel.app.stats.minutes > 0 -> stringResource(R.string.played_time, fmtMinutes(sel.app.stats.minutes))
        else -> stringResource(R.string.never_played)
    }
}

/* ─────────────────────────────────────────────────────────────
   Piezas de la biblioteca
   ───────────────────────────────────────────────────────────── */

@Composable
internal fun HeroChip(label: String) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .shadow(6.dp, RoundedCornerShape(8.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(RoundedCornerShape(8.dp))
            .drawBehind { drawRect(accentGradient(skin, 120f, size)) }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        ElyText(
            label,
            size = 8.5f,
            weight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = tracking(0.16f),
            maxLines = 1,
            uppercase = true,
        )
    }
}

/** Pestaña de filtro: texto + subrayado de acento de 2dp. */
@Composable
private fun FilterTab(label: String, active: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    // El ancho lo fija el propio rótulo (IntrinsicSize.Max): sin esto la
    // primera pestaña se llevaría todo el ancho de la fila.
    Column(
        Modifier
            .width(IntrinsicSize.Max)
            .clickable(onClick = onClick)
            .padding(start = 9.dp, end = 9.dp, top = 4.dp, bottom = 1.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ElyText(
            label,
            size = 11f,
            weight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) P.ink else P.ink2.copy(alpha = 0.6f),
            maxLines = 1,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(2.dp))
                .then(
                    if (active) Modifier.drawBehind { drawRect(accentGradient(skin, 90f, size)) }
                    else Modifier,
                ),
        )
    }
}

/** Buscador plegable de la barra superior. */
@Composable
private fun SearchField(vm: ElyndraViewModel, m: Metrics) {
    val open = vm.searchOpen
    // Al cerrarse, el campo suelta el foco para que el teclado no siga escribiendo en un buscador invisible.
    // Al abrirse lo pide, con su teclado: el buscador también se abre con el
    // mando (Select), y ahí no hay dedo que vaya a tocar el campo después.
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(open) {
        if (open) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        } else {
            focusManager.clearFocus()
        }
    }
    val fieldWidth by animateDpAsState(
        targetValue = if (open) (if (m.landscape) 190.dp else 96.dp) else 0.dp,
        animationSpec = motion(Springs.enter()),
        label = "searchW",
    )
    val fieldAlpha by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = motion(Springs.fade()),
        label = "searchA",
    )

    Row(
        Modifier
            .height(34.dp)
            .then(if (open) Modifier.darkGlass(RoundedCornerShape(12.dp)) else Modifier)
            .padding(start = if (open) 11.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(fieldWidth).alpha(fieldAlpha).clipToBounds()) {
            BasicTextField(
                value = vm.query,
                onValueChange = vm::updateQuery,
                enabled = open,
                singleLine = true,
                textStyle = inputStyle(12f, Color.White),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    if (vm.query.isEmpty()) {
                        ElyText(stringResource(R.string.search_hint), size = 12f, color = Color.White.copy(alpha = 0.55f), maxLines = 1)
                    }
                    inner()
                },
            )
        }
        ConsoleIconButton(
            onClick = { vm.toggleSearch() },
            focused = vm.input.isBarFocused(BarItem.Search),
            glass = !open,
        ) { glyph -> SearchGlyph(glyph) }
    }
}

/** Lado del botón de Masha: flota sobre el hero, así que no se ata a nada. */
private val MASHA_FAB = 58.dp

/**
 * Botón de Masha, con el aro que late.
 *
 * Nace **arriba a la izquierda**, a la altura de la barra del hero, y flota
 * con un vaivén vertical muy suave. Se arrastra directamente con el dedo a
 * cualquier punto de la pantalla —soltando partículas fosforescentes del
 * color elegido en Ajustes— y ahí se queda, también al volver a abrir la app
 * ([com.elyndra.launcher.ui.SettingsController.moveMasha]).
 *
 * **El fallo del arrastre "pesado" que se quedaba quieto y luego saltaba**
 * venía de cómo se guardaba la posición: el estado local se creaba con
 * `remember(vm.settings.mashaX, …)`, así que al soltar la primera vez (y
 * guardar la posición) se creaba un estado *nuevo*, mientras que el detector
 * de gestos —`pointerInput(maxX, maxY)`, con llaves que no cambiaban— seguía
 * vivo con la lambda del primer arrastre y escribía en el estado *viejo*. En
 * el segundo arrastre el dedo movía un estado que ya no se pintaba (el botón
 * no se movía) y al soltar se guardaba ese valor viejo (el salto). Además el
 * arrastre pedía mantener pulsado y cada píxel recomponía la pantalla.
 *
 * Ahora: un único estado que nunca se recrea ([dragDp], solo durante el
 * gesto), un detector con llave fija que lee siempre los límites actuales
 * (`rememberUpdatedState`), arrastre directo sin espera, y posición, escala
 * y flotación leídas en las fases de layout/capa: arrastrar no recompone.
 *
 * La posición se guarda en dp desde la esquina superior izquierda y se recorta
 * al pintar, no al guardar: así girar el móvil lo devuelve a la pantalla sin
 * perder el sitio que tenía en la otra orientación.
 */
@Composable
private fun MashaFab(vm: ElyndraViewModel, metrics: Metrics) {
    val skin = LocalSkin.current
    val screen = LocalScreenSize.current
    val haptics = LocalHapticFeedback.current
    val ring = ringProgress()
    val shape = CircleShape

    val maxX = (screen.width - MASHA_FAB).value.coerceAtLeast(0f)
    val maxY = (screen.height - MASHA_FAB).value.coerceAtLeast(0f)
    // Arriba a la izquierda, centrado en la altura de la barra del hero.
    val barTop = if (metrics.landscape) 8f else 16f
    val homeX = metrics.pad.value.coerceAtMost(maxX)
    val homeY = (barTop + HeroBarHeight.value / 2f - MASHA_FAB.value / 2f).coerceIn(4f, maxY.coerceAtLeast(4f))

    // Límites y posición de reposo, siempre al día para el detector de gestos
    // (que no se reinicia al cambiar: ver el comentario de arriba).
    val bounds by rememberUpdatedState(Offset(maxX, maxY))
    val rest by rememberUpdatedState(Offset(vm.settings.mashaX ?: homeX, vm.settings.mashaY ?: homeY))
    // Posición mientras se arrastra (dp); null = en reposo, manda lo guardado.
    val dragDp = remember { mutableStateOf<Offset?>(null) }
    val dragging = dragDp.value != null

    val particles = rememberParticleField()
    val particleColor = Color(vm.settings.mashaParticleColor)

    // Mientras se arrastra crece un poco y levanta más sombra: es lo que
    // distingue "lo llevo en el dedo" de "lo he pulsado". La flotación se
    // apaga en el dedo y vuelve al soltar.
    val lift = animateFloatAsState(if (dragging) 1.12f else 1f, motion(Springs.snappy()), label = "mashaLift")
    val bob = animateFloatAsState(if (dragging) 0f else 1f, motion(Springs.fade()), label = "mashaBob")
    val reduced = LocalReducedMotion.current
    val bobClock = rememberFloatClock()

    fun current(): Offset {
        val p = dragDp.value ?: rest
        return Offset(p.x.coerceIn(0f, bounds.x), p.y.coerceIn(0f, bounds.y))
    }

    // La estela, debajo del botón.
    ParticleLayer(particles, particleColor, Modifier.fillMaxSize())

    Box(
        Modifier
            // Posición leída en la fase de layout: moverlo no recompone.
            .offset {
                val p = current()
                IntOffset(p.x.dp.roundToPx(), p.y.dp.roundToPx())
            }
            .size(MASHA_FAB)
            .graphicsLayer {
                scaleX = lift.value
                scaleY = lift.value
                // Vaivén vertical muy sutil y continuo (±3 dp, ~4 s).
                if (!reduced) {
                    translationY = kotlin.math.sin(bobClock.seconds / 4f * 2f * Math.PI.toFloat()) * 3.dp.toPx() * bob.value
                }
            }
            .shadow(
                if (dragging) 22.dp else 14.dp,
                shape,
                clip = false,
                ambientColor = if (dragging) particleColor else P.shade.copy(alpha = 0.3f),
                spotColor = if (dragging) particleColor else P.shade.copy(alpha = 0.3f),
            )
            .glass(shape)
            .consoleFocus(vm.input.isBarFocused(BarItem.Masha), cornerRadius = MASHA_FAB / 2)
            .clickable { vm.go(Screen.Masha) }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        dragDp.value = current()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        val p = current()
                        vm.settings.moveMasha(p.x, p.y)
                        dragDp.value = null
                    },
                    onDragCancel = {
                        val p = current()
                        vm.settings.moveMasha(p.x, p.y)
                        dragDp.value = null
                    },
                    onDrag = { change, delta ->
                        change.consume()
                        val from = dragDp.value ?: current()
                        val to = Offset(
                            (from.x + delta.x.toDp().value).coerceIn(0f, bounds.x),
                            (from.y + delta.y.toDp().value).coerceIn(0f, bounds.y),
                        )
                        dragDp.value = to
                        // Chispas alrededor del centro, más cuanto más se mueve.
                        val half = MASHA_FAB.toPx() / 2f
                        particles.emit(
                            cx = to.x.dp.toPx() + half,
                            cy = to.y.dp.toPx() + half,
                            dx = delta.x,
                            dy = delta.y,
                            spread = half,
                            density = density,
                        )
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val k = 1f + 0.85f * ring
                    scaleX = k
                    scaleY = k
                    alpha = 0.5f * (1f - ring)
                }
                .border(1.5.dp, if (dragging) particleColor else skin.a1, shape),
        )
        // El logo, llenando el botón: es lo que tiene que verse.
        Image(
            painterResource(R.drawable.masha),
            contentDescription = stringResource(R.string.masha),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(MASHA_FAB * 0.86f).clip(shape),
        )
    }

    // La línea ambiental de Masha, junto a su botón. Se recoge sola al rato:
    // está para enterarse de un vistazo, no para quedarse tapando el carrusel.
    val insight = vm.masha.insight
    val revision = vm.masha.insightRevision
    var bubble by remember(insight?.id, revision) { mutableStateOf(insight != null) }
    LaunchedEffect(insight?.id, revision) {
        if (insight != null) {
            delay(MASHA_BUBBLE_MS)
            bubble = false
        }
    }
    if (insight != null && bubble && !dragging) {
        val anchor = current()
        MashaInsightBubble(
            text = vm.masha.insightText(insight).resolve(),
            anchorX = anchor.x.dp,
            anchorY = anchor.y.dp,
            anchorSize = MASHA_FAB,
            screen = screen,
            onTap = { vm.masha.actOnInsight(vm.settings.lang) },
            onDismiss = { vm.masha.dismissInsight() },
            key = insight.id,
        )
    }
}

/** Cuánto se queda a la vista la línea de Masha antes de recogerse. */
private const val MASHA_BUBBLE_MS = 14_000L

/** Aviso sobre el carrusel cuando el filtro elegido (Consolas, Android) no tiene nada todavía. */
private fun emptyFilterHint(filter: LibraryFilter): Int = when (filter) {
    LibraryFilter.Consoles -> R.string.empty_filter_consoles
    LibraryFilter.Android -> R.string.empty_filter_android
    LibraryFilter.All -> R.string.empty_library_hint
}

/**
 * La card de "Añadir": la única del carrusel cuando la biblioteca está vacía
 * y, en cuanto hay juegos, la última de la fila.
 */
@Composable
private fun AddTile(metrics: Metrics, index: Int, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .width(metrics.iconTile)
            .animPopIn(delayMs = minOf(index, 12) * 35, key = "add")
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(metrics.iconTile)
                .glass(RoundedCornerShape(16.dp), borderColor = skin.a1.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            ElyText("+", size = 28f, weight = FontWeight.SemiBold, color = skin.a2)
        }
        Spacer(Modifier.height(6.dp))
        ElyText(
            stringResource(R.string.add_long),
            size = 9.5f,
            weight = FontWeight.SemiBold,
            color = P.ink,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Una card del carrusel: carpeta de consola o app Android. */
@Composable
private fun LibraryTile(
    item: LibraryItem,
    index: Int,
    /** Reloj compartido de la flotación: solo flotan los iconos de juegos. */
    floatClock: FloatClock,
    selected: Boolean,
    metrics: Metrics,
    pairIndex: Int,
    /**
     * Entrada propia de la card (aparecer y descorrer el telón). Se apaga
     * cuando la card se está montando desde el polvo: ese montaje ya es su
     * entrada, y además lo que se captura para trocear tiene que ser la card
     * terminada, no a medio aparecer.
     */
    entrance: Boolean,
    onTap: () -> Unit,
    /** Recibe el punto tocado, en coordenadas de la card: el portal sale de ahí. */
    onOpen: (Offset) -> Unit,
    /** Recibe el centro de la card en la ventana: el menú de acciones sale de ahí. */
    onLongPress: (Offset) -> Unit,
) {
    val skin = LocalSkin.current
    // Juegos Android y carpetas de emulador se representan con icono, no con
    // carátula: su contenedor es cuadrado.
    val width: Dp = metrics.iconTile
    val lift = selectionLift(selected)
    val scale = selectionScale(selected)
    val press = rememberPress()
    val pressed = pressScale(press)
    val shape = RoundedCornerShape(16.dp)
    val curtain = if (entrance) curtainAlpha(index * 40, key = item.key) else 0f
    // Tirón magnético: al mantener pulsado, la card se hunde y rebota antes
    // de que salga el menú (ver `rememberMagneticPress`).
    val magnetic = rememberMagneticPress()
    var cardBounds by remember { mutableStateOf(Rect.Zero) }
    val scope = rememberCoroutineScope()
    val sheen = sheenProgress()
    val dimmed = item is LibraryItem.App && !item.installed

    Column(
        Modifier
            .width(width)
            .offset(y = lift)
            .then(if (entrance) Modifier.animPopIn(delayMs = index * 35, key = item.key) else Modifier)
            .onGloballyPositioned { cardBounds = it.boundsInWindow() }
            // La escala del tirón se lee en fase de dibujo: mover la card no
            // recompone ni la lista ni la pantalla.
            .graphicsLayer {
                scaleX = magnetic.value
                scaleY = magnetic.value
            }
            .alpha(if (dimmed) 0.5f else 1f)
            .pointerInput(item.key) {
                detectTapGestures(
                    onPress = { press.track(this) },
                    onTap = { onTap() },
                    onDoubleTap = { offset -> onOpen(offset) },
                    onLongPress = {
                        // Primero el tirón —la card se hunde y rebota— y solo
                        // cuando se ha sentido el agarre sale el menú.
                        scope.launch {
                            launch { magnetic.run() }
                            delay(MAGNETIC_PULL_MS.toLong())
                            onLongPress(cardBounds.center)
                        }
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(metrics.iconTile)
                .graphicsLayer {
                    scaleX = scale * pressed.value
                    scaleY = scale * pressed.value
                }
                .shadow(
                    if (press.pressed) 4.dp else if (selected) 16.dp else 8.dp,
                    shape,
                    clip = false,
                    ambientColor = P.shade.copy(alpha = if (selected) 0.32f else 0.2f),
                    spotColor = P.shade.copy(alpha = if (selected) 0.32f else 0.2f),
                )
                // Sin fondo de serie: cristal semitransparente, que deja ver la
                // aurora de la pantalla por detrás del icono.
                .liquidGlass(shape, if (selected) skin.a1 else P.hairline)
                // Marco más grueso: la selección tiene que leerse de lejos.
                .then(if (selected) Modifier.border(6.dp, skin.a1, shape) else Modifier),
        ) {
            // Ni un juego Android ni una carpeta de emulador usan carátula: se
            // representan con su icono — el elegido en "Personalizar icono" o, si
            // no hay ninguno, el de la app instalada / del emulador de la carpeta.
            // La carátula queda solo para las ROMs.
            val icon = when (item) {
                is LibraryItem.App -> item.app.meta.icon
                is LibraryItem.Folder -> item.iconPath
            }
            // Icono automático cuando no se ha elegido ninguno.
            val autoIconPackage = when (item) {
                is LibraryItem.App -> item.app.packageName
                is LibraryItem.Folder -> item.emulatorPackage
            }
            // Contenedor e icono son cuadrados: el icono lo llena entero,
            // centrado, sin dejar huecos y sin deformarse.
            if (icon != null || autoIconPackage != null) {
                // Un juego flota; una carpeta de emulador (icono de la app
                // del emulador, no de un juego) se queda quieta.
                GameIcon(
                    icon,
                    autoIconPackage,
                    Modifier
                        .fillMaxSize()
                        .then(if (item is LibraryItem.App) Modifier.floating(floatClock, floatPhaseOf(item.key)) else Modifier),
                    ContentScale.Fit,
                )
            } else if (item is LibraryItem.Folder) {
                // Carpeta sin icono y sin emulador instalado: rótulo de consola.
                Column(
                    Modifier
                        .fillMaxSize()
                        .drawBehind { drawRect(consoleFaceBrush(size)) }
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    ElyText(
                        item.system.abbr,
                        size = 9f,
                        weight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.75f),
                        letterSpacing = tracking(0.2f),
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    ElyText(
                        item.system.short,
                        // La card de carpeta ya no es apaisada, sino una carátula 2:3:
                        // el rótulo se mide contra su ancho, no contra su alto.
                        size = (metrics.iconTile.value * if (metrics.landscape) 0.2f else 0.18f).coerceAtMost(26f),
                        weight = FontWeight.Bold,
                        color = Color.White,
                        letterSpacing = tracking(-0.01f),
                        lineHeightRatio = 1f,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    ElyText(
                        pluralStringResource(R.plurals.roms_with_emulator, item.romCount, item.romCount, item.emulatorName ?: "—"),
                        size = 8f,
                        weight = FontWeight.Medium,
                        color = Color.White.copy(alpha = if (item.emulatorInstalled) 0.8f else 0.55f),
                        letterSpacing = tracking(0.08f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        uppercase = true,
                    )
                }
            }

            if (selected) {
                // `sheen`: banda del 36 % del ancho que cruza la card.
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.36f)
                        .graphicsLayer { translationX = size.width / 0.36f * sheen }
                        .drawBehind { drawRect(sheenBrush(size)) },
                )
            }

            if (curtain > 0f) {
                Box(Modifier.fillMaxSize().alpha(curtain).background(P.paper))
            }
        }

        Spacer(Modifier.height(6.dp))
        ElyText(
            item.name,
            size = 9.5f,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) P.ink else P.ink2,
            align = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
