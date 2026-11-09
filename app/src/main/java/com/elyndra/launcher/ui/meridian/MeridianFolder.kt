package com.elyndra.launcher.ui.meridian

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.elyndra.launcher.ui.theme.LocalPoppins
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.RomEntry
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.library.NameCheck
import com.elyndra.launcher.metadata.ArtKind
import com.elyndra.launcher.ui.BarItem
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.TapAction
import com.elyndra.launcher.ui.TapGate
import com.elyndra.launcher.ui.rememberTapSlop
import android.os.SystemClock
import com.elyndra.launcher.ui.LibraryItem
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.COVER_RATIO
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.ConsoleIconButton
import com.elyndra.launcher.ui.components.DisintegratingContainer
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.EmptyState
import com.elyndra.launcher.ui.components.MaterializingContainer
import com.elyndra.launcher.ui.components.NeedsNameBadge
import com.elyndra.launcher.ui.components.OpenButton
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.StatusStrip
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.heroDescription
import com.elyndra.launcher.ui.rememberDescription
import com.elyndra.launcher.ui.screens.EmulatorSelector
import com.elyndra.launcher.ui.screens.EMULATOR_LABEL_SP
import com.elyndra.launcher.ui.FolderBarLayout
import androidx.compose.ui.graphics.Shadow
import com.elyndra.launcher.ui.screens.FolderChips
import com.elyndra.launcher.ui.screens.playedLabel
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.theme.LocalReducedMotion

/**
 * Una carpeta de ROMs en Meridian: la misma rueda con las carátulas (2:3,
 * sin recortar), el arte de la ROM enfocada a pantalla completa y su bloque.
 * Arriba de la rueda, volver, "Abrir" y el emulador, y las fichas de la
 * carpeta alineadas con el eje; a la derecha, el sistema y la hora.
 */
@Composable
internal fun MeridianFolder(vm: ElyndraViewModel, item: LibraryItem.Folder) {
    val reduced = LocalReducedMotion.current
    val ink = rememberMeridianInk()
    val roms = vm.folderRoms(item.folder.id)
    val rom = vm.selectedRom()
    val look = rememberSelectionLook(vm.settings.selectionParticleColor, vm.settings.selectionGlow, vm.settings.selectionParticles)
    val focused = roms.indexOfFirst { it.key == rom?.key }.coerceAtLeast(0)
    val enter = rememberMeridianEnter(vm.intro.visible && !vm.intro.fading)
    val wheel = remember { mutableStateOf<WheelState?>(null) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val entries = remember(roms, configuration) { roms.map { romEntry(context.resources, it, item) } }
    val keyIndex = remember(roms) { roms.withIndex().associate { (i, it) -> it.key to i } }
    val emulatorLabel = item.emulatorName ?: stringResource(R.string.choose_emulator)
    // Tocar: seleccionar o abrir (ver TapGate); nada se abre dos veces seguidas (vm.tryOpen).
    val gate = remember { TapGate() }
    val slop = rememberTapSlop()
    val openKey: (String?) -> Unit = { key ->
        roms.firstOrNull { it.key == key }?.let {
            if (vm.tryOpen()) {
                vm.selectRom(it.key)
                vm.openRom(it)
            }
        }
    }

    MeridianScene(
        enterMs = { enter.value },
        art = MeridianArt(
            key = rom?.key ?: item.key,
            imagePath = rom?.let { it.meta.hero ?: it.meta.screenshot },
            fallback = rom?.let { vm.romFallback(it) } ?: vm.fallbackOf(item),
            vanishing = rom?.let { vm.isVanishingArt(it.key, ArtKind.Background) } == true,
            onVanished = { vm.finishVanish() },
        ),
        wheel = { wheel.value },
        artIndex = { key -> keyIndex[key] },
        railHeader = {
            FolderRailHeader(
                compact = LocalMeridianFrame.current.compact,
                bar = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConsoleIconButton(onClick = { vm.go(Screen.Library) }, focused = vm.input.isBarFocused(BarItem.Back)) { glyph ->
                            Box(glyph) { BackChevron(color = Color.White) }
                        }
                        PadGlyphBadge("B", vm.input.gamepadPresent)
                        Spacer(Modifier.width(8.dp))
                        OpenButton(enabled = rom != null, focused = vm.input.isBarFocused(BarItem.Open)) { openKey(rom?.key) }
                    }
                },
            )
        },
        topEnd = {
            // El emulador va con el sistema, a la derecha: en la cabecera de la rueda
            // obligaba al cristal a abrirse arriba y perdía su curva.
            FolderHeader(
                title = item.system.name,
                path = item.folder.displayPath,
                emulatorLabel = emulatorLabel,
                // Una carpeta virtual (ports) no tiene emulador.
                emulator = if (item.folder.isVirtual) null else ({ iconOnly -> EmulatorSelector(vm, item, emulatorLabel, iconOnly = iconOnly) }),
                // La ficha de la carpeta, con el sistema (antes bajo volver y "Abrir", quitándole alto a la rueda).
                pill = { short -> FolderChips(vm, item, roms.size, short = short) },
            ) {
                if (vm.settings.statusVisible) StatusStrip(vm.settings.statusMode)
            }
        },
        tileAspect = COVER_RATIO,
        folderHeader = true,
        hints = {
            PadHints(hints = FOLDER_WHEEL_HINTS, visible = vm.input.gamepadPresent, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
        },
        hero = { FolderHero(vm, item, rom, ink, openKey) },
        adaptive = vm.settings.meridianAdaptiveColor,
        wheelGlass = vm.settings.wheelGlassFor(P.isDark),
    ) { geo, wash ->
        MeridianWheelArea(
            geo = geo,
            section = item.folder.id,
            forward = true,
            entries = entries,
            add = null,
            focused = focused,
            selectedKey = rom?.key,
            addFocused = false,
            tileAspect = COVER_RATIO,
            look = look,
            ink = ink,
            wash = wash,
            enterMs = { enter.value },
            reduced = reduced,
            lite = LocalMeridianFrame.current.lite,
            current = wheel,
            onSettle = { row -> roms.getOrNull(row)?.let { vm.selectRom(it.key) } },
            tile = { e, _, _ -> (e.payload as? RomEntry)?.let { RomWheelTile(vm, it) } },
            wrap = { e, content ->
                DisintegratingContainer(isDisintegrating = vm.vanishing == e.key, onAnimationEnd = { vm.finishVanish() }) { content() }
            },
            onTap = { row, at, moving ->
                val key = roms.getOrNull(row)?.key
                if (key != null) {
                    val d = gate.tap(key, rom?.key, moving, vm.settings.tapOpensSelected, SystemClock.uptimeMillis(), at.x, at.y, slop)
                    when (d.action) {
                        TapAction.Select -> vm.selectRom(key)
                        TapAction.Open -> openKey(d.key)
                        TapAction.Ignore -> Unit
                    }
                }
            },
            onOpen = { row -> openKey(roms.getOrNull(row)?.key) },
            onLongPress = { e, bounds ->
                vm.selectRom(e.key)
                vm.markSheetOrigin(bounds)
                (e.payload as? RomEntry)?.let(vm::romOptions)
            },
            onBounds = vm::noteSelectedCard,
            empty = { focusY ->
                val top = with(LocalDensity.current) { (focusY.toDp() - 60.dp).coerceAtLeast(0.dp) }
                EmptyState(
                    title = null,
                    message = stringResource(R.string.folder_empty),
                    actionLabel = stringResource(R.string.rescan_folder),
                    onAction = { vm.rescanFolder(item.folder) },
                    modifier = Modifier.fillMaxWidth().padding(top = top, end = 24.dp),
                    glyph = ConsoleGlyph.Folder,
                )
            },
        )
    }
}

/**
 * La cabecera de la rueda de la carpeta: solo volver y "Abrir", en una fila,
 * alineados a la izquierda ([RailHeaderLayout.START]) y con el mismo tope que
 * la barra de la derecha. La ficha de la carpeta ("4 ROMs · 0h 16m") iba
 * debajo y le quitaba a la rueda ~28 dp de alto; ahora va arriba a la derecha,
 * con el emulador y el sistema ([FolderHeader]). Le dice a la niebla (o al
 * cristal) hasta dónde llegar en su banda ([FogArc.reach]).
 */
@Composable
internal fun FolderRailHeader(compact: Boolean, bar: @Composable () -> Unit) {
    val fog = LocalRailFog.current
    Layout(
        content = { Box { bar() } },
        modifier = Modifier.fillMaxWidth().padding(top = MeridianTopRow.top(compact).dp, bottom = MeridianTopRow.bottom(compact).dp),
    ) { measurables, constraints ->
        val rail = constraints.maxWidth.toDp().value
        val loose = Constraints(maxWidth = RailHeaderLayout.dockMaxWidth(rail).dp.roundToPx())
        val barP = measurables[0].measure(loose)
        val start = RailHeaderLayout.START.dp.roundToPx()
        val reach = RailHeaderLayout.START + barP.width.toDp().value + RailHeaderLayout.FOG_MARGIN
        layout(constraints.maxWidth, barP.height) {
            barP.place(start, 0)
            fog?.arc?.let { state -> if (state.value.reach != reach) state.value = state.value.copy(reach = reach) }
        }
    }
}

/** La fila de una ROM: su título, "2h 10m jugado · ISO" y, enfocada, sus fichas. */
private fun romEntry(res: Resources, rom: RomEntry, folder: LibraryItem.Folder): WheelEntry {
    val played = if (rom.stats.minutes > 0) res.getString(R.string.played_time, fmtMinutes(rom.stats.minutes)) else res.getString(R.string.never_played)
    val ext = rom.extension.ifEmpty { "DIR" }.uppercase()
    return WheelEntry(
        key = rom.key,
        title = rom.displayTitle,
        subline = "$played · $ext",
        chips = listOf(folder.system.short, played),
        payload = rom,
        art = rom.meta.cover,
    )
}

/** La carátula de una fila (entera, sin recortar), con la extensión y, si hace falta, la marca de "sin nombre". */
@Composable
private fun RomWheelTile(vm: ElyndraViewModel, rom: RomEntry) {
    Box(Modifier.fillMaxSize()) {
        MaterializingContainer(
            isMaterializing = vm.isMaterializingArt(rom.key, ArtKind.Cover),
            onAnimationEnd = { vm.finishMaterializeArt() },
            modifier = Modifier.fillMaxSize(),
        ) {
            DisintegratingContainer(
                isDisintegrating = vm.isVanishingArt(rom.key, ArtKind.Cover),
                onAnimationEnd = { vm.finishVanish() },
                modifier = Modifier.fillMaxSize(),
            ) {
                ArtImage(rom.meta.cover, vm.romFallback(rom), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        val unnamed = remember(rom.displayTitle) { !NameCheck.isNameUsable(rom.displayTitle) }
        if (unnamed) NeedsNameBadge(Modifier.align(Alignment.TopStart).padding(3.dp), onDark = true)
        // Sin carátula, el título de la reserva va abajo a la izquierda: la extensión sube para no taparlo.
        Box(
            Modifier
                .align(if (rom.meta.cover == null) Alignment.TopEnd else Alignment.BottomEnd)
                .padding(3.dp)
                .clip(RoundedCornerShape(4.dp))
                // Va encima de la carátula, no de una superficie del tema: clara con tinta oscura en los dos modos.
                .background(Color.White.copy(alpha = 0.82f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            ElyText(rom.extension.ifEmpty { "DIR" }, size = 6.5f, weight = FontWeight.SemiBold, color = P.shade, letterSpacing = tracking(0.1f))
        }
    }
}

/** El bloque del hero de la carpeta: la ROM enfocada o, sin ROMs, el sistema. */
@Composable
private fun FolderHero(vm: ElyndraViewModel, item: LibraryItem.Folder, rom: RomEntry?, ink: MeridianInk, openKey: (String?) -> Unit) {
    val description = rememberDescription(vm, rom?.key, rom?.meta)
    val content = if (rom == null) {
        MeridianHeroContent(item.key, listOf(stringResource(R.string.rom_chip, item.system.short)), item.system.name, null)
    } else {
        MeridianHeroContent(
            key = rom.key,
            chips = listOfNotNull(
                stringResource(R.string.rom_chip, item.system.short),
                rom.meta.ra?.takeIf { it.achievements > 0 }?.let { "🏆 ${it.earned}/${it.achievements}" },
                playedLabel(rom),
                rom.subtitle.takeIf { it.isNotEmpty() },
            ),
            title = rom.displayTitle,
            description = description?.let { heroDescription(it, vm.settings.lang) },
            logo = rom.meta.logo,
        )
    }
    val actions = rom?.let {
        MeridianActions(
            primary = stringResource(R.string.sheet_play),
            onPrimary = { openKey(it.key) },
            details = stringResource(R.string.details),
            onDetails = { vm.showDetails(it.key) },
            options = stringResource(R.string.hint_options),
            onOptions = { vm.romOptions(it) },
        )
    }
    MeridianHeroPanel(content, actions, ink, vm.input.gamepadPresent) { shown, reveal, box ->
        val key = shown.key as? String ?: return@MeridianHeroPanel
        val logo = shown.logo ?: return@MeridianHeroPanel
        MaterializingContainer(isMaterializing = vm.isMaterializingArt(key, ArtKind.Logo), onAnimationEnd = { vm.finishMaterializeArt() }) {
            DisintegratingContainer(isDisintegrating = vm.isVanishingArt(key, ArtKind.Logo), onAnimationEnd = { vm.finishVanish() }) {
                MeridianLogo(logo, box, reveal)
            }
        }
    }
}

/** Pistas del mando bajo la rueda de la carpeta (A, X e Y ya están en el hero). */
private val FOLDER_WHEEL_HINTS = listOf(
    PadHint("↑↓", R.string.hint_move),
    PadHint("B", R.string.hint_back),
)

/**
 * La cabecera de la carpeta, arriba a la derecha: el emulador de la carpeta
 * ([emulator], con su rótulo [emulatorLabel]), su nombre en una línea y,
 * debajo, la ruta en otra línea pequeña y tenue, recortada por el centro
 * ("…") para que se vean el principio y la carpeta final. La píldora de hora
 * y batería ([status]) va siempre entera, a 12 dp como poco: el texto cede el
 * sitio, ella no. Si no cabe todo, el emulador se recorta y, en lo más
 * justo, se queda en su icono ([FolderBarLayout]); [emulator] recibe si va
 * solo con el icono. Va sobre el velo de arriba del arte (blanco ≥ 4,5:1).
 *
 * Con [spread] (el Clásico, tras volver y "Abrir") el emulador va al
 * principio del hueco y el nombre con la píldora al final; si no, todo junto
 * a la derecha. [shadow], la sombra del texto sobre el hero del Clásico.
 */
@Composable
internal fun FolderHeader(
    title: String,
    path: String,
    modifier: Modifier = Modifier,
    emulatorLabel: String = "",
    emulator: (@Composable (iconOnly: Boolean) -> Unit)? = null,
    spread: Boolean = false,
    shadow: Shadow? = null,
    /** La ficha de la carpeta, a la izquierda del emulador: entera o, si no cabe, sin el tiempo ([FolderBarLayout.Pill]). */
    pill: (@Composable (short: Boolean) -> Unit)? = null,
    status: @Composable () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val family = LocalPoppins.current
    val density = LocalDensity.current
    // El rótulo del emulador medido de verdad (fuente, idioma y escala de letra).
    val labelWidth = remember(emulatorLabel, family, density) {
        val style = TextStyle(fontFamily = family, fontSize = EMULATOR_LABEL_SP.sp, fontWeight = FontWeight.SemiBold)
        measurer.measure(emulatorLabel, style, maxLines = 1, density = density).size.width / density.density
    }
    Layout(
        content = {
            Box { emulator?.invoke(false) }
            Box { emulator?.invoke(true) }
            Column(horizontalAlignment = Alignment.End) {
                ElyText(title, size = 13f, weight = FontWeight.SemiBold, color = Color.White, shadow = shadow, maxLines = 1, overflow = TextOverflow.Ellipsis, align = TextAlign.End)
                Spacer(Modifier.height(2.dp))
                MiddleEllipsisText(path, size = 9f, color = Color.White.copy(alpha = ArtWash.TOP_TEXT_ALPHA), shadow = shadow)
            }
            Box { status() }
            Box { pill?.invoke(false) }
            Box { pill?.invoke(true) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE / 2
        val statusP = measurables[3].measure(Constraints(maxWidth = maxW))
        // Las dos fichas de la carpeta medidas de verdad (idioma y escala de letra): el plan elige una.
        val pillFull = measurables[4].measure(Constraints(maxWidth = maxW))
        val pillShort = measurables[5].measure(Constraints(maxWidth = maxW))
        val plan = if (emulator == null && pill == null) {
            null
        } else {
            FolderBarLayout.plan(
                maxW.toDp().value,
                if (emulator == null) 0f else labelWidth,
                statusP.width.toDp().value,
                pillWidth = pillFull.width.toDp().value,
                pillShortWidth = pillShort.width.toDp().value,
            )
        }
        val chipP = if (emulator == null) null else plan?.let {
            if (it.fit.emulatorLabel) measurables[0].measure(Constraints(maxWidth = it.chipWidth.dp.roundToPx())) else measurables[1].measure(Constraints())
        }
        val pillP = when (plan?.pill) {
            FolderBarLayout.Pill.Full -> pillFull.takeIf { it.width > 0 }
            FolderBarLayout.Pill.Short -> pillShort.takeIf { it.width > 0 }
            else -> null
        }
        val gap = FolderBarLayout.GAP.dp.roundToPx()
        val statusGap = if (statusP.width > 0) gap else 0
        val chipSpace = chipP?.let { it.width + gap } ?: 0
        val pillSpace = pillP?.let { it.width + gap } ?: 0
        val titleP = measurables[2].measure(Constraints(maxWidth = (maxW - chipSpace - pillSpace - statusGap - statusP.width).coerceAtLeast(0)))
        val content = pillSpace + chipSpace + titleP.width + statusGap + statusP.width
        val w = maxOf(content, constraints.minWidth).coerceAtMost(maxW)
        val h = maxOf(chipP?.height ?: 0, pillP?.height ?: 0, titleP.height, statusP.height, constraints.minHeight)
        layout(w, h) {
            val statusX = w - statusP.width
            statusP.place(statusX, (h - statusP.height) / 2)
            val titleX = statusX - statusGap - titleP.width
            titleP.place(titleX, (h - titleP.height) / 2)
            val chipX = if (spread) pillSpace else titleX - chipSpace
            chipP?.place(chipX, (h - chipP.height) / 2)
            pillP?.place(if (spread) 0 else chipX - pillSpace, (h - pillP.height) / 2)
        }
    }
}

/** Una línea recortada por el centro a lo que quepa (lo mide de verdad, en cualquier idioma y tamaño de letra). */
@Composable
private fun MiddleEllipsisText(text: String, size: Float, color: Color, shadow: Shadow? = null) {
    val measurer = rememberTextMeasurer()
    val family = LocalPoppins.current
    val density = LocalDensity.current
    BoxWithConstraints {
        val maxPx = constraints.maxWidth
        val shown = remember(text, maxPx, family, density) {
            val style = TextStyle(fontFamily = family, fontSize = size.sp, letterSpacing = 0.06.em)
            fun fits(t: String) = maxPx == Constraints.Infinity || measurer.measure(t, style, maxLines = 1, density = density).size.width <= maxPx
            if (fits(text)) return@remember text
            var lo = 1
            var hi = text.length
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (fits(MiddleEllipsis.shorten(text, mid))) lo = mid else hi = mid - 1
            }
            MiddleEllipsis.shorten(text, lo)
        }
        ElyText(shown, size = size, color = color, letterSpacing = tracking(0.06f), shadow = shadow, maxLines = 1, align = TextAlign.End)
    }
}
