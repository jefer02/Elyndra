package com.elyndra.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.AppIconImage
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.CssGrid
import com.elyndra.launcher.ui.components.CtaButton
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.GlassPanel
import com.elyndra.launcher.ui.components.Pill
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.AuroraBackdrop
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animFadeUp
import com.elyndra.launcher.ui.theme.animRiseSheet
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.sheenProgress

@Composable
fun AddScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val add = vm.add

    Box(Modifier.fillMaxSize().animRiseSheet(key = Screen.Add)) {
        AuroraBackdrop()

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = m.pad, end = m.pad, top = m.pad, bottom = 34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(onClick = { vm.go(Screen.Library) }) { BackChevron() }
                Spacer(Modifier.width(11.dp))
                ElyText(stringResource(R.string.add_title), size = 19f, weight = FontWeight.SemiBold, color = P.ink)
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Pill(
                    stringResource(R.string.tab_android),
                    add.tab == AddTab.Android,
                    { add.updateTab(AddTab.Android) },
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    fontSize = 12f,
                    cornerRadius = 14.dp,
                )
                Pill(
                    stringResource(R.string.tab_roms),
                    add.tab == AddTab.Roms,
                    { add.updateTab(AddTab.Roms) },
                    modifier = Modifier.weight(1f),
                    height = 40.dp,
                    fontSize = 12f,
                    cornerRadius = 14.dp,
                )
            }

            when (add.tab) {
                AddTab.Android -> AndroidTab(vm, m.landscape)
                AddTab.Roms -> RomsTab(vm, m.landscape)
            }
        }
    }
}

/* ── Pestaña "Juegos Android" ─────────────────────────────────── */

@Composable
private fun AndroidTab(vm: ElyndraViewModel, landscape: Boolean) {
    val add = vm.add
    val visible = add.visibleApps()

    Column(Modifier.fillMaxWidth().animFadeUp(key = AddTab.Android)) {
        GlassPanel(Modifier.padding(top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (add.appsLoading) {
                    ArcSpinner()
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    ElyText(stringResource(R.string.apps_title), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
                    Spacer(Modifier.height(2.dp))
                    ElyText(
                        pluralStringResource(R.plurals.apps_summary, add.gameCount(), add.gameCount(), add.picked.size),
                        size = 9.5f,
                        color = P.ink2,
                    )
                }
                Spacer(Modifier.width(8.dp))
                GhostButton(
                    stringResource(if (add.showAllApps) R.string.show_games_only else R.string.show_all_apps),
                    add::toggleShowAll,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        if (!add.appsLoading && visible.isEmpty()) {
            ElyText(stringResource(R.string.no_games_detected), size = 10.5f, color = P.ink2, lineHeightRatio = 1.5f)
        }
        CssGrid(
            columns = if (landscape) 2 else 1,
            horizontalGap = 8.dp,
            verticalGap = 8.dp,
            items = visible.mapIndexed { i, app ->
                {
                    DetectedRow(
                        app = app,
                        checked = add.picked.contains(app.packageName),
                        inLibrary = add.isInLibrary(app.packageName),
                        index = i,
                        onToggle = { add.togglePicked(app.packageName) },
                    )
                }
            },
        )

        Spacer(Modifier.height(14.dp))
        CtaButton(
            label = if (add.picked.isEmpty()) {
                stringResource(R.string.select_games)
            } else {
                pluralStringResource(R.plurals.add_n_games, add.picked.size, add.picked.size)
            },
            onClick = add::addPicked,
            enabled = add.picked.isNotEmpty(),
        )
    }
}

@Composable
private fun DetectedRow(app: InstalledApp, checked: Boolean, inLibrary: Boolean, index: Int, onToggle: () -> Unit) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .animFadeUp(delayMs = minOf(index, 14) * 40, key = app.packageName)
            .alpha(if (inLibrary) 0.6f else 1f)
            .glass(RoundedCornerShape(15.dp), borderColor = if (checked) skin.a1 else Color.White.copy(alpha = 0.72f))
            .clickable(enabled = !inLibrary, onClick = onToggle)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.7f))
                .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            AppIconImage(app.packageName, Modifier.size(32.dp))
        }
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
        Box(
            Modifier
                .size(23.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (checked || inLibrary) Modifier.drawBehind { drawRect(accentGradient(skin, 145f, size)) }
                    else Modifier.background(P.ink.copy(alpha = 0.07f)),
                )
                .border(1.dp, P.ink.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked || inLibrary) ElyText("✓", size = 12f, color = Color.White)
        }
    }
}

/* ── Pestaña "Carpeta de ROMs" ────────────────────────────────── */

@Composable
private fun RomsTab(vm: ElyndraViewModel, landscape: Boolean) {
    val skin = LocalSkin.current
    val add = vm.add
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        add.onFolderPicked(uri)
    }
    val system = Systems.byId(add.systemId)
    val emuId = add.emulatorId
    val emuName = emuId?.let { vm.emulatorName(it) }
    val emuInstalled = vm.isEmulatorInstalled(emuId)
    val scan = add.scan

    val panels = buildList<@Composable () -> Unit> {
        add {
            GlassPanel(Modifier.padding(top = 10.dp)) {
                StepLabel(stringResource(R.string.step_folder))
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ElyText(
                        add.folder?.displayPath ?: stringResource(R.string.no_folder),
                        modifier = Modifier.weight(1f),
                        size = 11f,
                        color = if (add.folder != null) P.ink else P.ink2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(10.dp))
                    GhostButton(stringResource(R.string.browse), { picker.launch(null) })
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
        }
        add {
            GlassPanel(Modifier.padding(top = 10.dp)) {
                StepLabel(stringResource(R.string.step_system))
                Spacer(Modifier.height(9.dp))
                WrapRow(gap = 7.dp) {
                    Systems.ALL.forEach { s ->
                        Pill(s.short, add.systemId == s.id, { add.setSystem(s.id) }, fontSize = 10.5f, horizontalPadding = 11.dp)
                    }
                }
            }
        }
        add {
            GlassPanel(Modifier.padding(top = 10.dp)) {
                StepLabel(stringResource(R.string.step_emulator))
                Spacer(Modifier.height(9.dp))
                if (system == null) {
                    ElyText(stringResource(R.string.choose_system_first), size = 10.5f, color = P.ink2)
                } else {
                    WrapRow(gap = 7.dp) {
                        vm.emulatorOptions(system.id).forEach { opt ->
                            Pill(
                                opt.name,
                                emuId == opt.id,
                                { add.setEmulator(opt.id) },
                                modifier = Modifier.alpha(if (opt.installed) 1f else 0.5f),
                                fontSize = 10.5f,
                                horizontalPadding = 11.dp,
                            )
                        }
                        Pill(
                            stringResource(R.string.other_app),
                            emuId?.startsWith(Emulators.CUSTOM_PREFIX) == true,
                            add::chooseOtherApp,
                            fontSize = 10.5f,
                            horizontalPadding = 11.dp,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ElyText(stringResource(R.string.emulator_legend), size = 9f, color = P.ink2.copy(alpha = 0.8f))
                    Spacer(Modifier.height(8.dp))
                    if (emuName != null) {
                        ElyText(
                            stringResource(R.string.emulator_explainer, emuName),
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
            }
        }
        if (scan !is ScanState.Idle) {
            add {
                GlassPanel(Modifier.padding(top = 10.dp)) {
                    when (scan) {
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
        }
        add {
            Box(Modifier.padding(top = 14.dp)) {
                val label = when {
                    add.folder == null -> stringResource(R.string.no_folder_cta)
                    system == null -> stringResource(R.string.choose_system_first)
                    scan is ScanState.Scanning -> stringResource(R.string.scanning_cta)
                    scan is ScanState.Done && scan.found.isNotEmpty() -> stringResource(R.string.add_folder_to_library)
                    scan is ScanState.Done -> stringResource(R.string.scan_again)
                    else -> stringResource(R.string.analyze_folder)
                }
                CtaButton(
                    label = label,
                    onClick = add::primaryAction,
                    enabled = add.folder != null && system != null && scan !is ScanState.Scanning && add.bulkProgress == null,
                )
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp).animFadeUp(key = AddTab.Roms)) {
        CssGrid(
            columns = if (landscape) 2 else 1,
            horizontalGap = 14.dp,
            verticalGap = 0.dp,
            items = panels,
        )
    }
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

@Composable
private fun StepLabel(text: String) {
    val skin = LocalSkin.current
    ElyText(text, size = 8.5f, weight = FontWeight.SemiBold, color = skin.a2, letterSpacing = tracking(0.2f), uppercase = true)
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
