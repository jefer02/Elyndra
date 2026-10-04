package com.elyndra.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.Emulators
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.library.InstalledApp
import com.elyndra.launcher.ui.AddTab
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.ScanState
import com.elyndra.launcher.ui.SystemGroups
import com.elyndra.launcher.ui.components.ActionBar
import com.elyndra.launcher.ui.components.AppIconImage
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ConsoleGlyph
import com.elyndra.launcher.ui.components.ConsoleGlyphIcon
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.Expandable
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassCard
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.GlassTabBar
import com.elyndra.launcher.ui.components.GlassTextField
import com.elyndra.launcher.ui.components.GlowCheck
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.padInitialFocus
import com.elyndra.launcher.ui.components.padPrimaryAction
import androidx.compose.foundation.focusGroup
import com.elyndra.launcher.ui.components.Pill
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.AuroraBackdrop
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.MinTouch
import com.elyndra.launcher.ui.theme.Radii
import com.elyndra.launcher.ui.theme.Space
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.TypeScale
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.consoleSurface
import com.elyndra.launcher.ui.theme.sheenProgress
import com.elyndra.launcher.ui.theme.staggerIn

/* ─────────────────────────────────────────────────────────────
   Añadir a la biblioteca.

   Dos pestañas (LB/RB con mando) y una barra de acción fija al pie,
   que dice qué hay elegido y, si el botón no se puede pulsar, por qué.

   · Juegos Android: buscador, "seleccionar todo" y la lista en dos
     columnas si hay ancho. Lo que ya está en la biblioteca se ve
     apagado y no se puede marcar.
   · Carpeta de ROMs: tres pasos en vertical —carpeta, sistema,
     emulador—. El que toca está abierto; los hechos se pliegan en
     una línea con su resumen y "Cambiar". Los sistemas van por
     fabricante y con buscador.
   ───────────────────────────────────────────────────────────── */

@Composable
fun AddScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val add = vm.add
    val reduced = LocalReducedMotion.current
    val backLabel = stringResource(R.string.hint_back)

    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop()

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(start = m.pad, end = m.pad, top = m.pad),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(
                        onClick = { vm.back() },
                        modifier = Modifier.semantics { contentDescription = backLabel },
                        size = MinTouch,
                        cornerRadius = 15.dp,
                    ) { BackChevron() }
                    Spacer(Modifier.width(12.dp))
                    ElyText(stringResource(R.string.add_title), size = TypeScale.Headline, weight = FontWeight.SemiBold, color = P.ink)
                }

                // Las dos pestañas viven dentro de una sola cápsula: lo que se
                // mueve es la pastilla de acento, no el fondo de cada una.
                GlassTabBar(
                    tabs = listOf(stringResource(R.string.tab_android), stringResource(R.string.tab_roms)),
                    selected = if (add.tab == AddTab.Android) 0 else 1,
                    onSelect = { add.updateTab(if (it == 0) AddTab.Android else AddTab.Roms) },
                    modifier = Modifier.padding(top = Space.m),
                )

                Box(Modifier.fillMaxWidth().weight(1f).padding(top = Space.s)) {
                    AnimatedContent(
                        targetState = add.tab,
                        transitionSpec = {
                            if (reduced) {
                                fadeIn(snap()) togetherWith fadeOut(snap())
                            } else {
                                val dir = if (targetState == AddTab.Roms) 1 else -1
                                (fadeIn(Springs.fade()) + slideInHorizontally(Springs.enter()) { w -> dir * w / 12 })
                                    .togetherWith(fadeOut(Springs.exit()))
                                    .using(SizeTransform(clip = false) { _, _ -> snap() })
                            }
                        },
                        label = "addTab",
                    ) { tab ->
                        when (tab) {
                            AddTab.Android -> AndroidTab(vm, wide)
                            AddTab.Roms -> RomsTab(vm)
                        }
                    }
                }

                // La barra de acción, fija: no se va con la lista. Con mando, Y salta a su botón.
                Box(Modifier.padding(top = Space.s).padPrimaryAction()) {
                    when (add.tab) {
                        AddTab.Android -> AndroidActionBar(vm)
                        AddTab.Roms -> RomsActionBar(vm)
                    }
                }
                PadHints(
                    hints = ADD_HINTS,
                    visible = vm.input.gamepadPresent,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Spacer(Modifier.height(if (vm.input.gamepadPresent) 4.dp else Space.m))
            }
        }
    }
}

private val ADD_HINTS = listOf(
    PadHint("LB / RB", R.string.hint_section),
    PadHint("A", R.string.hint_select),
    PadHint("Y", R.string.add_title),
    PadHint("B", R.string.hint_back),
)

/* ── Pestaña "Juegos Android" ─────────────────────────────────── */

@Composable
private fun AndroidTab(vm: ElyndraViewModel, wide: Boolean) {
    val add = vm.add
    val shown = add.shownApps()
    // Solo la primera vez que aparece cada app entra escalonada: volver a
    // subir por la lista no la repite.
    val seen = remember { HashSet<String>() }

    LazyVerticalGrid(
        columns = GridCells.Fixed(if (wide) 2 else 1),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        item(key = "toolbar", span = { GridItemSpan(maxLineSpan) }) { AppsToolbar(vm, wide) }
        if (!add.appsLoading && shown.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                ElyText(
                    stringResource(if (add.appQuery.isNotBlank()) R.string.add_no_app_match else R.string.no_games_detected),
                    modifier = Modifier.padding(vertical = Space.m),
                    size = 10.5f,
                    color = P.ink2,
                    lineHeightRatio = 1.5f,
                )
            }
        }
        itemsIndexed(shown, key = { _, app -> app.packageName }) { i, app ->
            DetectedRow(
                app = app,
                checked = add.picked.contains(app.packageName),
                inLibrary = add.isInLibrary(app.packageName),
                // Con mando, la lista empieza en la primera app (no en el buscador ni en volver).
                modifier = Modifier
                    .staggerIn(i, key = app.packageName, enabled = seen.add(app.packageName))
                    .then(if (i == 0) Modifier.padInitialFocus() else Modifier),
                onToggle = { add.togglePicked(app.packageName) },
            )
        }
    }
}

/** Cabecera de la lista: qué se ha detectado, el buscador y los atajos. */
@Composable
private fun AppsToolbar(vm: ElyndraViewModel, wide: Boolean) {
    val add = vm.add
    val search: @Composable (Modifier) -> Unit = { modifier ->
        GlassTextField(
            value = add.appQuery,
            onValueChange = add::updateAppQuery,
            modifier = modifier,
            placeholder = stringResource(R.string.add_search_apps),
            trailing = { ConsoleGlyphIcon(ConsoleGlyph.Search, P.ink2, size = 16.dp) },
        )
    }
    val selectAll: @Composable () -> Unit = {
        GhostButton(
            stringResource(if (add.allShownPicked()) R.string.add_deselect_all else R.string.add_select_all),
            add::toggleSelectAllShown,
        )
    }
    val showAll: @Composable () -> Unit = {
        GhostButton(
            stringResource(if (add.showAllApps) R.string.show_games_only else R.string.show_all_apps),
            add::toggleShowAll,
        )
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (add.appsLoading) {
                ArcSpinner(size = 18.dp)
                Spacer(Modifier.width(10.dp))
            }
            ElyText(stringResource(R.string.apps_title), size = TypeScale.Body, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.width(Space.s))
            ElyText(
                pluralStringResource(R.plurals.apps_summary, add.gameCount(), add.gameCount(), add.picked.size),
                modifier = Modifier.weight(1f),
                size = 9.5f,
                color = P.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Space.s))
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                search(Modifier.weight(1f))
                Spacer(Modifier.width(Space.s))
                selectAll()
                Spacer(Modifier.width(Space.s))
                showAll()
            }
        } else {
            search(Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.s))
            // En un móvil, si los dos atajos no caben (francés, alemán), saltan de línea.
            WrapRow(gap = Space.s) {
                selectAll()
                showAll()
            }
        }
    }
}

@Composable
private fun DetectedRow(app: InstalledApp, checked: Boolean, inLibrary: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    // Marcar una app la hace crecer un pelo con un muelle: el "sí" se siente
    // en el tamaño antes de leerse en la casilla.
    val lift by animateFloatAsState(
        targetValue = if (checked) 1.02f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "tileLift",
    )
    GlassCard(
        modifier = modifier
            // La escala y la opacidad se leen en fase de dibujo: marcar no remide la lista.
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
                // Lo que ya está en la biblioteca, apagado sin desaparecer.
                alpha = if (inLibrary) 0.55f else 1f
            },
        cornerRadius = ROW_RADIUS,
        // El halo solo se enciende en lo elegido: es el estado, no el adorno.
        glow = if (checked) 1f else 0f,
        frost = if (checked) 0.8f else 0.66f,
        padding = PaddingValues(ROW_PAD),
        // El clic va dentro de la lámina (después de su recorte): así el foco y
        // la pulsación tienen su misma forma y no dibujan una caja de otro radio.
        onClick = onToggle,
        enabled = !inLibrary,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // El icono es la propia pieza: lo llena entero y lleva el radio
            // concéntrico de la lámina (su radio menos el margen).
            AppIconImage(
                app.packageName,
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(ROW_RADIUS - ROW_PAD)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                ElyText(app.label, size = 12.5f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                ElyText(
                    if (inLibrary) stringResource(R.string.app_already_added) else app.packageName,
                    size = 8.5f,
                    color = P.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            GlowCheck(checked = checked || inLibrary)
        }
    }
}

private val ROW_RADIUS = 16.dp
private val ROW_PAD = 9.dp

/** Barra de la pestaña Android: cuántas hay marcadas y el botón de añadirlas. */
@Composable
private fun AndroidActionBar(vm: ElyndraViewModel) {
    val add = vm.add
    val n = add.picked.size
    ActionBar(
        status = pluralStringResource(R.plurals.add_selected_count, n, n),
        actionLabel = if (n == 0) stringResource(R.string.add_title) else pluralStringResource(R.plurals.add_n_games, n, n),
        onAction = add::addPicked,
        enabled = n > 0,
        reason = stringResource(R.string.add_pick_reason),
    )
}

/* ── Pestaña "Carpeta de ROMs" ────────────────────────────────── */

private enum class StepStatus { Done, Active, Pending }

@Composable
private fun RomsTab(vm: ElyndraViewModel) {
    val add = vm.add
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        add.onFolderPicked(uri)
    }
    val system = Systems.byId(add.systemId)
    val scan = add.scan
    // Paso hecho que se ha vuelto a abrir con "Cambiar"; se cierra solo al elegir.
    var editing by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(add.folder?.rootDocId) { if (editing == 1) editing = 0 }
    LaunchedEffect(add.systemId) { if (editing == 2) editing = 0 }
    val active = when {
        add.folder == null -> 1
        system == null -> 2
        else -> 3
    }
    fun status(step: Int) = when {
        step < active -> StepStatus.Done
        step == active -> StepStatus.Active
        else -> StepStatus.Pending
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = Space.s),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .consoleSurface(RoundedCornerShape(Radii.l))
                .padding(Space.m),
        ) {
            // Con mando, la pestaña empieza en el primer paso.
            Box(Modifier.padInitialFocus().focusGroup()) {
                Step(
                    label = stringResource(R.string.step_folder),
                    status = status(1),
                    expanded = active == 1 || editing == 1,
                    summary = add.folder?.displayPath,
                    onChange = { editing = 1 },
                ) { FolderStep(vm) { picker.launch(null) } }
            }
            Step(
                label = stringResource(R.string.step_system),
                status = status(2),
                expanded = active == 2 || editing == 2,
                summary = system?.name,
                onChange = { editing = 2 },
            ) { SystemStep(vm) }
            Step(
                label = stringResource(R.string.step_emulator),
                status = status(3),
                expanded = active == 3,
                summary = if (system == null) stringResource(R.string.choose_system_first) else null,
                onChange = null,
                last = true,
            ) { EmulatorStep(vm) }
        }
        if (scan !is ScanState.Idle) {
            Spacer(Modifier.height(Space.s))
            ScanPanel(vm)
        }
    }
}

/**
 * Un paso del asistente: su marca (hecho, en curso o pendiente) unida a la
 * del siguiente por una línea, el rótulo y, plegado, su resumen con
 * "Cambiar"; desplegado, su contenido.
 */
@Composable
private fun Step(
    label: String,
    status: StepStatus,
    expanded: Boolean,
    summary: String?,
    onChange: (() -> Unit)?,
    last: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val skin = LocalSkin.current
    val line = P.ink.copy(alpha = 0.12f)
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) {
                    val x = 14.dp.toPx()
                    drawLine(line, Offset(x, 32.dp.toPx()), Offset(x, size.height - 2.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
            },
    ) {
        Box(Modifier.width(28.dp).padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
            StepDot(status)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else Space.m)) {
            Row(Modifier.heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                ElyText(
                    label,
                    modifier = Modifier.weight(1f),
                    size = 9f,
                    weight = FontWeight.SemiBold,
                    color = if (status == StepStatus.Pending) P.ink2 else skin.a2,
                    letterSpacing = tracking(0.2f),
                    uppercase = true,
                )
                if (!expanded && status == StepStatus.Done && onChange != null) {
                    GhostButton(stringResource(R.string.change), onChange)
                }
            }
            if (!expanded && summary != null) {
                ElyText(
                    summary,
                    size = 11.5f,
                    weight = if (status == StepStatus.Done) FontWeight.Medium else FontWeight.Normal,
                    color = if (status == StepStatus.Done) P.ink else P.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Expandable(expanded) {
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

/** La marca del paso: hecho (relleno con su visto), en curso (aro y punto) o pendiente (aro tenue). */
@Composable
private fun StepDot(status: StepStatus) {
    val skin = LocalSkin.current
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .then(
                when (status) {
                    StepStatus.Done -> Modifier.drawBehind { drawRect(accentGradient(skin, 145f, size)) }
                    StepStatus.Active -> Modifier.border(2.dp, skin.a2, CircleShape)
                    StepStatus.Pending -> Modifier.border(1.5.dp, P.ink.copy(alpha = 0.2f), CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            StepStatus.Done -> ConsoleGlyphIcon(ConsoleGlyph.Check, Color.White, size = 14.dp)
            StepStatus.Active -> Box(Modifier.size(8.dp).clip(CircleShape).background(skin.a2))
            StepStatus.Pending -> Unit
        }
    }
}

/** Paso 1: la carpeta, y si dentro hay carpetas de varios sistemas, añadirlas todas de una vez. */
@Composable
private fun FolderStep(vm: ElyndraViewModel, onBrowse: () -> Unit) {
    val add = vm.add
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ElyText(
            add.folder?.displayPath ?: stringResource(R.string.no_folder),
            modifier = Modifier.weight(1f),
            size = 11f,
            color = if (add.folder != null) P.ink else P.ink2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        GhostButton(stringResource(R.string.browse), onBrowse)
    }
    val detected = add.folder?.detected.orEmpty()
    if (detected.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        ElyText(
            pluralStringResource(R.plurals.detected_systems, detected.size, detected.size),
            size = 10.5f,
            weight = FontWeight.SemiBold,
            color = P.ink,
        )
        Spacer(Modifier.height(3.dp))
        ElyText(
            detected.joinToString(" · ") { it.system.short },
            size = 9.5f,
            color = P.ink2,
            lineHeightRatio = 1.4f,
        )
        Spacer(Modifier.height(8.dp))
        val bulk = add.bulkProgress
        if (bulk != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArcSpinner(size = 16.dp)
                Spacer(Modifier.width(8.dp))
                ElyText(bulk.resolve(), size = 10f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            GhostButton(stringResource(R.string.add_all_detected), add::addAllDetected)
        }
    }
}

/** Paso 2: el sistema, con buscador y agrupado por fabricante. */
@Composable
private fun SystemStep(vm: ElyndraViewModel) {
    val add = vm.add
    val system = Systems.byId(add.systemId)
    GlassTextField(
        value = add.systemQuery,
        onValueChange = add::updateSystemQuery,
        placeholder = stringResource(R.string.add_search_systems),
        trailing = { ConsoleGlyphIcon(ConsoleGlyph.Search, P.ink2, size = 16.dp) },
    )
    val groups = SystemGroups.group(Systems.ALL, add.systemQuery)
    if (groups.isEmpty()) {
        Spacer(Modifier.height(10.dp))
        ElyText(stringResource(R.string.add_no_system_match), size = 10.5f, color = P.ink2)
    }
    groups.forEach { (maker, systems) ->
        ElyText(
            stringResource(maker.label),
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
            size = 8.5f,
            weight = FontWeight.SemiBold,
            color = P.ink2,
            letterSpacing = tracking(0.18f),
            uppercase = true,
        )
        WrapRow(gap = 7.dp) {
            systems.forEach { s ->
                Pill(s.short, add.systemId == s.id, { add.setSystem(s.id) }, fontSize = 10.5f, horizontalPadding = 11.dp, height = 36.dp)
            }
        }
    }
    // El PC se da de alta distinto y conviene decirlo aquí: lo que se elige no
    // es una carpeta de ROMs, es la raíz que tiene dentro una carpeta por juego.
    if (system?.folderGames == true) {
        Spacer(Modifier.height(10.dp))
        ElyText(stringResource(R.string.folder_games_hint), size = 10f, color = P.ink2, lineHeightRatio = 1.5f)
    }
}

/** Paso 3: con qué emulador se abre la carpeta, qué recibe y si falta instalarlo. */
@Composable
private fun EmulatorStep(vm: ElyndraViewModel) {
    val add = vm.add
    val system = Systems.byId(add.systemId) ?: return
    val emuId = add.emulatorId
    val emuName = emuId?.let { vm.emulatorName(it) }
    val emuInstalled = vm.isEmulatorInstalled(emuId)
    WrapRow(gap = 7.dp) {
        vm.emulatorOptions(system.id).forEach { opt ->
            Pill(
                opt.name,
                emuId == opt.id,
                { add.setEmulator(opt.id) },
                modifier = Modifier.alpha(if (opt.installed) 1f else 0.5f),
                fontSize = 10.5f,
                horizontalPadding = 11.dp,
                height = 36.dp,
            )
        }
        Pill(
            stringResource(R.string.other_app),
            emuId?.startsWith(Emulators.CUSTOM_PREFIX) == true,
            add::chooseOtherApp,
            fontSize = 10.5f,
            horizontalPadding = 11.dp,
            height = 36.dp,
        )
    }
    Spacer(Modifier.height(8.dp))
    ElyText(stringResource(R.string.emulator_legend), size = 9f, color = P.ink2.copy(alpha = 0.8f))
    if (emuName != null) {
        Spacer(Modifier.height(8.dp))
        // Un runtime de Windows no recibe el .exe: o se le da el acceso directo
        // que exporta, o ni eso y solo se abre. Mejor decirlo antes de añadir
        // la carpeta que dejar que parezca que Elyndra falla.
        val launchOnly = Emulators.byId(emuId)?.launchOnly == true
        ElyText(
            when {
                launchOnly -> stringResource(R.string.pc_runtime_explainer, emuName)
                system.folderGames -> stringResource(R.string.pc_shortcut_explainer, emuName)
                else -> stringResource(R.string.emulator_explainer, emuName)
            },
            size = 10.5f,
            color = P.ink2,
            lineHeightRatio = 1.5f,
        )
        if (!emuInstalled) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElyText(
                    stringResource(R.string.emulator_not_installed, emuName),
                    size = 10f,
                    weight = FontWeight.SemiBold,
                    color = P.red,
                    modifier = Modifier.weight(1f),
                )
                Emulators.byId(emuId)?.let { profile ->
                    Spacer(Modifier.width(8.dp))
                    GhostButton(stringResource(R.string.install), { vm.openExternal(vm.app.launcher.storeIntent(profile)) })
                }
            }
        }
    }
}

/** El análisis de la carpeta: progreso, resultado o error. */
@Composable
private fun ScanPanel(vm: ElyndraViewModel) {
    val skin = LocalSkin.current
    val add = vm.add
    val system = Systems.byId(add.systemId)
    Column(
        Modifier
            .fillMaxWidth()
            .consoleSurface(RoundedCornerShape(Radii.l))
            .padding(Space.m),
    ) {
        when (val scan = add.scan) {
            is ScanState.Scanning -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ElyText(stringResource(R.string.reading_folder), size = 11.5f, weight = FontWeight.SemiBold, color = P.ink)
                    Spacer(Modifier.weight(1f))
                    ElyText(
                        pluralStringResource(R.plurals.scan_progress, scan.found, scan.found, scan.scanned),
                        size = 10f,
                        weight = FontWeight.SemiBold,
                        color = skin.a2,
                    )
                }
                Spacer(Modifier.height(9.dp))
                IndeterminateBar()
                Spacer(Modifier.height(8.dp))
                ElyText(scan.current, size = 9f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            is ScanState.Done -> {
                ElyText(stringResource(R.string.analysis_done), size = 11.5f, weight = FontWeight.SemiBold, color = P.ink)
                Spacer(Modifier.height(6.dp))
                ElyText(
                    if (scan.found.isNotEmpty()) {
                        pluralStringResource(R.plurals.scan_found, scan.found.size, scan.found.size, system?.name ?: "")
                    } else if (system?.folderGames == true) {
                        stringResource(R.string.scan_none_folder_games)
                    } else {
                        stringResource(
                            R.string.scan_none,
                            system?.name ?: "",
                            system?.extensions?.sorted()?.joinToString(", ") { ".$it" } ?: "",
                        )
                    },
                    size = 10.5f,
                    color = P.ink2,
                    lineHeightRatio = 1.5f,
                )
            }
            is ScanState.Failed -> ElyText(scan.message.resolve(), size = 10.5f, color = P.red, lineHeightRatio = 1.5f)
            ScanState.Idle -> Unit
        }
    }
}

/** Barra de la pestaña ROMs: sistema y emulador elegidos, y analizar o añadir. */
@Composable
private fun RomsActionBar(vm: ElyndraViewModel) {
    val add = vm.add
    val system = Systems.byId(add.systemId)
    val scan = add.scan
    val emuName = add.emulatorId?.let { vm.emulatorName(it) }
    val label = when {
        scan is ScanState.Scanning -> stringResource(R.string.scanning_cta)
        scan is ScanState.Done && scan.found.isNotEmpty() -> stringResource(R.string.add_folder_to_library)
        scan is ScanState.Done -> stringResource(R.string.scan_again)
        else -> stringResource(R.string.analyze_folder)
    }
    val bulk = add.bulkProgress
    val reason = when {
        add.folder == null -> stringResource(R.string.no_folder_cta)
        system == null -> stringResource(R.string.choose_system_first)
        bulk != null -> bulk.resolve()
        scan is ScanState.Scanning -> stringResource(R.string.reading_folder)
        else -> null
    }
    ActionBar(
        status = listOfNotNull(system?.name, emuName).joinToString(" · "),
        actionLabel = label,
        onAction = add::primaryAction,
        enabled = add.folder != null && system != null && scan !is ScanState.Scanning && bulk == null,
        reason = reason,
        busy = scan is ScanState.Scanning || bulk != null,
    )
}

/** Barra de progreso sin total conocido: una franja de acento que va y viene. */
@Composable
private fun IndeterminateBar(height: Dp = 6.dp) {
    val skin = LocalSkin.current
    val p = sheenProgress()
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(3.dp))
            .background(P.ink.copy(alpha = 0.1f))
            .clipToBounds(),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.3f)
                .graphicsLayer { translationX = size.width / 0.3f * (p / 3.6f + 0.3f) }
                .clip(RoundedCornerShape(3.dp))
                .drawBehind { drawRect(accentGradient(skin, 90f, size)) },
        )
    }
}

/** `display:flex; flex-wrap:wrap; gap:N` — filas de píldoras que saltan de línea. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WrapRow(gap: Dp, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}
