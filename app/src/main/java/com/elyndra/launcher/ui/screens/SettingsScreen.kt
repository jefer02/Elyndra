package com.elyndra.launcher.ui.screens

import androidx.compose.runtime.LaunchedEffect
import com.elyndra.launcher.ui.components.padInitialFocus
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusGroup
import com.elyndra.launcher.core.device.StatusMode
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.TINTS
import com.elyndra.launcher.display.FrameRate
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.SettingsCategory
import com.elyndra.launcher.ui.SettingsNav
import com.elyndra.launcher.ui.SortMode
import com.elyndra.launcher.ui.components.AccentSlider
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.CssGrid
import com.elyndra.launcher.ui.components.CtaButton
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.GlyphBadge
import com.elyndra.launcher.ui.components.NavRow
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.Pill
import com.elyndra.launcher.ui.components.SectionHeader
import com.elyndra.launcher.ui.components.SegmentedControl
import com.elyndra.launcher.ui.components.SettingRow
import com.elyndra.launcher.ui.components.SettingsDivider
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.Swatch
import com.elyndra.launcher.ui.components.SwitchRow
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
import com.elyndra.launcher.ui.theme.cssLinearGradient
import com.elyndra.launcher.ui.theme.glass
import com.elyndra.launcher.ui.theme.motion
import com.elyndra.launcher.ui.theme.shapeClickable
import com.elyndra.launcher.ui.theme.staggerIn

/* ─────────────────────────────────────────────────────────────
   Ajustes, como el menú de sistema de una consola.

   Ventana ancha: un raíl con las ocho categorías a la izquierda y, a
   la derecha, un panel con la que está elegida. Ventana estrecha: la
   lista de categorías y, al tocar una, su página (atrás vuelve a la
   lista). Con mando, la cruceta recorre con el foco de Compose y
   LB/RB cambian de categoría (ver InputController.form).

   Las opciones son las de siempre, con sus mismas claves: lo que
   cambia es dónde vive cada una y que todas son filas planas del
   mismo patrón (rótulo y descripción a la izquierda, control a la
   derecha), separadas por filos.
   ───────────────────────────────────────────────────────────── */

@Composable
fun SettingsScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val s = vm.settings

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = SettingsNav.isWide(maxWidth.value)
        SideEffect { s.compact = !wide }
        AuroraBackdrop()

        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(start = m.pad, end = m.pad, top = m.pad),
        ) {
            SettingsHeader(vm, wide)
            Spacer(Modifier.height(Space.s))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (wide) WideSettings(vm) else CompactSettings(vm)
            }
            PadHints(
                hints = SETTINGS_HINTS,
                visible = vm.input.gamepadPresent,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(if (vm.input.gamepadPresent) 4.dp else Space.m))
        }
    }
}

private val SETTINGS_HINTS = listOf(
    PadHint("LB / RB", R.string.hint_category),
    PadHint("A", R.string.hint_select),
    PadHint("B", R.string.hint_back),
)

/** Volver y el título; en una página de ventana estrecha, el de la categoría. */
@Composable
private fun SettingsHeader(vm: ElyndraViewModel, wide: Boolean) {
    val s = vm.settings
    val backLabel = stringResource(R.string.hint_back)
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(
            onClick = { vm.back() },
            modifier = Modifier.semantics { contentDescription = backLabel },
            size = MinTouch,
            cornerRadius = 15.dp,
        ) { BackChevron() }
        Spacer(Modifier.width(12.dp))
        val inPage = !wide && s.detailOpen
        Column {
            if (inPage) {
                ElyText(
                    stringResource(R.string.settings_title),
                    size = TypeScale.Overline,
                    weight = FontWeight.SemiBold,
                    color = P.ink2,
                    letterSpacing = tracking(0.2f),
                    uppercase = true,
                )
            }
            ElyText(
                stringResource(if (inPage) s.category.title else R.string.settings_title),
                size = TypeScale.Headline,
                weight = FontWeight.SemiBold,
                color = P.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ── ventana ancha: raíl + panel ──────────────────────────────── */

@Composable
private fun WideSettings(vm: ElyndraViewModel) {
    val s = vm.settings
    val reduced = LocalReducedMotion.current
    Row(Modifier.fillMaxSize()) {
        SettingsRail(vm, Modifier.width(RAIL_WIDTH).fillMaxHeight())
        Spacer(Modifier.width(Space.m))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .consoleSurface(RoundedCornerShape(Radii.l)),
        ) {
            AnimatedContent(
                targetState = s.category,
                transitionSpec = { categoryTransition(reduced, forward = targetState.ordinal >= initialState.ordinal) },
                label = "settingsPane",
            ) { category ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .focusGroup()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, top = Space.m, bottom = Space.l),
                ) {
                    PaneHeader(category)
                    CategoryContent(vm, category, wide = true)
                }
            }
        }
    }
}

/**
 * El raíl: las categorías con su glifo. Un solo resalte se desliza hasta la
 * elegida; volver al raíl con la cruceta cae siempre en ella.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SettingsRail(vm: ElyndraViewModel, modifier: Modifier) {
    val s = vm.settings
    val skin = LocalSkin.current
    val categories = SettingsCategory.entries
    val requesters = remember { categories.map { FocusRequester() } }
    val position by animateFloatAsState(s.category.ordinal.toFloat(), motion(Springs.snappy()), label = "rail")
    // Con LB/RB el foco sigue a la categoría si estaba en el raíl.
    val railFocused = remember { BooleanArray(1) }
    LaunchedEffect(s.category) {
        if (railFocused[0]) runCatching { requesters[s.category.ordinal].requestFocus() }
    }
    Column(
        modifier
            // Al entrar en el raíl (y al abrir Ajustes con mando) el foco cae en la categoría elegida.
            .padInitialFocus()
            .onFocusChanged { railFocused[0] = it.hasFocus }
            .focusProperties { enter = { requesters[s.category.ordinal] } }
            .focusGroup()
            .verticalScroll(rememberScrollState())
            .drawBehind {
                val h = RAIL_ITEM_H.toPx()
                val y = position * (h + RAIL_GAP.toPx())
                drawRoundRect(
                    skin.a2.copy(alpha = if (P.isDark) 0.16f else 0.09f),
                    topLeft = Offset(0f, y),
                    size = Size(size.width, h),
                    cornerRadius = CornerRadius(Radii.s.toPx()),
                )
                val bar = Size(3.dp.toPx(), h * 0.46f)
                translate(0f, y + (h - bar.height) / 2f) {
                    drawRoundRect(accentGradient(skin, 180f, bar), size = bar, cornerRadius = CornerRadius(bar.width))
                }
            },
    ) {
        categories.forEachIndexed { i, category ->
            val selected = category == s.category
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(RAIL_ITEM_H)
                    .focusRequester(requesters[i])
                    .semantics { this.selected = selected }
                    .shapeClickable(RoundedCornerShape(Radii.s)) { s.openCategory(category) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphBadge(category.glyph, active = selected, size = 32.dp)
                Spacer(Modifier.width(10.dp))
                ElyText(
                    stringResource(category.title),
                    size = 12f,
                    weight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) P.ink else P.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeightRatio = 1.2f,
                )
            }
            if (i < categories.lastIndex) Spacer(Modifier.height(RAIL_GAP))
        }
    }
}

private val RAIL_WIDTH = 232.dp
private val RAIL_ITEM_H = 52.dp
private val RAIL_GAP = 4.dp

/** Cabecera del panel: el glifo, el nombre de la categoría y qué hay dentro. */
@Composable
private fun PaneHeader(category: SettingsCategory) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphBadge(category.glyph, active = true, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            ElyText(stringResource(category.title), size = 17f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(2.dp))
            ElyText(stringResource(category.description), size = TypeScale.Caption, color = P.ink2)
        }
    }
}

/* ── ventana estrecha: lista → página ─────────────────────────── */

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CompactSettings(vm: ElyndraViewModel) {
    val s = vm.settings
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = s.detailOpen,
        transitionSpec = { pageTransition(reduced, forward = targetState) },
        label = "settingsPage",
    ) { open ->
        Box(Modifier.fillMaxSize().consoleSurface(RoundedCornerShape(Radii.l))) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (open) Space.m else Space.s, vertical = Space.s),
            ) {
                if (open) {
                    // Con mando, la página empieza en su primer control (no en volver).
                    Column(Modifier.padInitialFocus().focusGroup()) {
                        CategoryContent(vm, s.category, wide = false)
                    }
                    Spacer(Modifier.height(Space.l))
                } else {
                    // Y la lista, en la categoría de la que se vuelve.
                    val rows = remember { SettingsCategory.entries.map { FocusRequester() } }
                    Column(
                        Modifier
                            .padInitialFocus()
                            .focusProperties { enter = { rows[s.category.ordinal] } }
                            .focusGroup(),
                    ) {
                        SettingsCategory.entries.forEachIndexed { i, category ->
                            NavRow(
                                title = stringResource(category.title),
                                description = stringResource(category.description),
                                glyph = category.glyph,
                                onClick = { s.openCategory(category) },
                                modifier = Modifier.staggerIn(i, key = "settingsList").focusRequester(rows[i]),
                            )
                            if (i < SettingsCategory.entries.lastIndex) SettingsDivider()
                        }
                    }
                }
            }
        }
    }
}

/** Cambio de categoría en el panel: fundido y un desliz vertical corto en el sentido del raíl. */
private fun AnimatedContentTransitionScope<*>.categoryTransition(reduced: Boolean, forward: Boolean): ContentTransform =
    if (reduced) {
        fadeIn(snap()) togetherWith fadeOut(snap())
    } else {
        (fadeIn(Springs.fade()) + slideInVertically(Springs.enter()) { h -> (if (forward) 1 else -1) * h / 24 })
            .togetherWith(fadeOut(Springs.exit()))
            .using(SizeTransform(clip = false) { _, _ -> snap() })
    }

/** Lista ↔ página: el eje compartido horizontal de las pantallas, en pequeño. */
private fun AnimatedContentTransitionScope<*>.pageTransition(reduced: Boolean, forward: Boolean): ContentTransform =
    if (reduced) {
        fadeIn(snap()) togetherWith fadeOut(snap())
    } else {
        (fadeIn(Springs.fade()) + slideInHorizontally(Springs.enter()) { w -> (if (forward) 1 else -1) * w / 10 })
            .togetherWith(fadeOut(Springs.exit()))
            .using(SizeTransform(clip = false) { _, _ -> snap() })
    }

/* ── el contenido de cada categoría ───────────────────────────── */

@Composable
private fun CategoryContent(vm: ElyndraViewModel, category: SettingsCategory, wide: Boolean) {
    when (category) {
        SettingsCategory.Display -> DisplayPage(vm)
        SettingsCategory.Sound -> SoundsSection(vm, wide)
        SettingsCategory.Music -> {
            MusicSection(vm)
            BackgroundSection(vm)
        }
        SettingsCategory.Appearance -> AppearancePage(vm)
        SettingsCategory.Library -> LibraryPage(vm)
        SettingsCategory.Metadata -> MetadataPage(vm, wide)
        SettingsCategory.Masha -> MashaColumn(vm)
        SettingsCategory.About -> AboutColumn(vm)
    }
}

/** Pantalla y tema: modo oscuro, intro, fotogramas, píldora de hora y batería e idioma. */
@Composable
private fun DisplayPage(vm: ElyndraViewModel) {
    val s = vm.settings
    val activity = LocalContext.current.findActivity()

    SectionLabel(stringResource(R.string.section_theme))
    SettingsGroup {
        SwitchRow(stringResource(R.string.dark_mode), stringResource(R.string.dark_mode_desc), s.darkMode, s::toggleDark)
    }

    IntroSection(vm)

    // Pantalla: 120 o 60 fps. En una pantalla de 60 Hz la opción de 120
    // sale apagada y se explica por qué.
    SectionLabel(stringResource(R.string.section_display))
    SettingsGroup {
        SettingRow(
            stringResource(R.string.frame_rate_title),
            description = stringResource(if (s.supportsHighRefresh) R.string.frame_rate_desc else R.string.frame_rate_unsupported),
        )
        SegmentedControl(
            options = listOf(stringResource(R.string.frame_rate_120), stringResource(R.string.frame_rate_60)),
            selected = if (s.frameRate == FrameRate.HIGH) 0 else 1,
            onSelect = { s.updateFrameRate(if (it == 0) FrameRate.HIGH else FrameRate.STANDARD) },
            isEnabled = { it != 0 || s.supportsHighRefresh },
        )
        // Masha va aparte: a 60 salvo que se pida alta fluidez (solo tiene sentido con la app a 120).
        if (s.frameRate == FrameRate.HIGH) {
            Spacer(Modifier.height(Space.s))
            SwitchRow(stringResource(R.string.masha_high_refresh), stringResource(R.string.masha_high_refresh_desc), s.mashaHighRefresh, s::toggleMashaHighRefresh)
        }
        Spacer(Modifier.height(Space.s))
        SettingsDivider()
        // Píldora de hora y batería (Biblioteca y Carpeta).
        SwitchRow(stringResource(R.string.status_title), stringResource(R.string.status_desc), s.statusVisible, s::toggleStatus)
        if (s.statusVisible) {
            SegmentedControl(
                options = StatusMode.entries.map { mode ->
                    stringResource(
                        when (mode) {
                            StatusMode.Both -> R.string.status_mode_both
                            StatusMode.Time -> R.string.status_mode_time
                            StatusMode.Battery -> R.string.status_mode_battery
                        },
                    )
                },
                selected = StatusMode.entries.indexOf(s.statusMode),
                onSelect = { s.updateStatusMode(StatusMode.entries[it]) },
            )
            Spacer(Modifier.height(Space.s))
        }
    }

    SectionLabel(stringResource(R.string.section_language))
    WrapRow(gap = Space.s) {
        AppLocale.SUPPORTED.forEach { (tag, name) ->
            Pill(name, s.lang == tag, {
                if (s.lang != tag) {
                    s.onLanguageChosen(tag)
                    activity?.let { AppLocale.set(it, tag) }
                }
            }, height = PILL_H)
        }
    }
}

/** Fondo de la interfaz: vídeo o imagen detrás de Elyndra (no del sistema). */
@Composable
private fun BackgroundSection(vm: ElyndraViewModel) {
    val s = vm.settings
    // SAF: se queda el permiso del archivo para que siga ahí tras reiniciar.
    // Admite vídeo e imagen; cuál es lo decide el propio controlador por el tipo.
    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        s.onBackgroundPicked(uri)
    }
    SectionLabel(stringResource(R.string.section_background))
    SettingsGroup {
        SwitchRow(stringResource(R.string.background_title), stringResource(R.string.background_desc), s.backgroundEnabled, s::toggleBackground)
        Spacer(Modifier.height(Space.s))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Miniatura de lo elegido. Coil saca el primer fotograma de un
            // vídeo igual que pinta una imagen, así que sirve para los dos.
            BackgroundThumb(s.backgroundUri)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                ElyText(
                    s.backgroundUri?.let { Uri.parse(it).lastPathSegment ?: it } ?: stringResource(R.string.background_none),
                    size = 10.5f,
                    weight = FontWeight.Medium,
                    color = if (s.backgroundUri == null) P.ink2 else P.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (s.backgroundUri != null) {
                    Spacer(Modifier.height(4.dp))
                    KindBadge(
                        stringResource(
                            if (s.backgroundIsVideo) R.string.background_kind_video else R.string.background_kind_image,
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // `onClick` va posicional: en GhostButton el último parámetro es el
            // modifier, así que una lambda al final no sería el clic.
            GhostButton(
                stringResource(if (s.backgroundUri == null) R.string.background_choose else R.string.background_change),
                { backgroundPicker.launch(arrayOf("video/*", "image/*")) },
            )
            if (s.backgroundUri != null) {
                Spacer(Modifier.width(Space.s))
                GhostButton(stringResource(R.string.remove), s::clearBackground)
            }
        }
        if (s.backgroundUri != null) {
            SliderRow(
                stringResource(R.string.background_opacity),
                "${s.backgroundOpacity} %",
                s.backgroundOpacity,
                10..100,
                s::updateBackgroundOpacity,
            )
        }
        Spacer(Modifier.height(Space.s))
    }
}

/**
 * Apariencia: estilo de la lista (Meridian o carrusel), paleta de firma,
 * acento, liquid glass y el marco de la selección (halo y partículas).
 */
@Composable
private fun AppearancePage(vm: ElyndraViewModel) {
    val skin = LocalSkin.current
    val s = vm.settings

    SectionLabel(stringResource(R.string.layout_style_title))
    LayoutStyleGroup(vm)

    // Acento, selección e intro de una vez; cada uno sigue aquí debajo por separado.
    SectionLabel(stringResource(R.string.signature_title))
    SignatureGroup(vm)

    SectionLabel(stringResource(R.string.section_accent))
    SettingsGroup {
        SwatchGrid(
            items = ACCENTS.map { accent ->
                {
                    Swatch(
                        brush = Brush.linearGradient(listOf(accent.a, accent.b)),
                        selected = s.accentId == accent.id,
                        onClick = { s.setAccent(accent.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        ElyText(stringResource(R.string.accent_label, stringResource(skin.accent.nameRes)), size = 10.5f, color = P.ink2)
    }

    SectionLabel(stringResource(R.string.section_glass))
    SettingsGroup {
        // Vista previa: una franja de color con una lámina de cristal encima.
        Box(
            Modifier
                .fillMaxWidth()
                .height(70.dp)
                .clip(RoundedCornerShape(16.dp))
                // `linear-gradient(120deg, a1, secundario 55%, fin del relleno)`
                .drawBehind {
                    drawRect(
                        cssLinearGradient(
                            120f,
                            listOf(skin.a1, skin.secondary, skin.fillEnd),
                            size,
                            stops = listOf(0f, 0.55f, 1f),
                        ),
                    )
                }
                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .glass(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ElyText(
                    stringResource(R.string.preview),
                    size = 11.5f,
                    weight = FontWeight.SemiBold,
                    color = P.ink,
                    letterSpacing = tracking(0.22f),
                    uppercase = true,
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        SwatchGrid(
            items = TINTS.map { tint ->
                {
                    Swatch(
                        brush = Brush.linearGradient(
                            listOf(tint.color.copy(alpha = 0.95f), tint.color.copy(alpha = 0.45f)),
                        ),
                        selected = s.tintId == tint.id,
                        onClick = { s.setTint(tint.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )

        SliderRow(stringResource(R.string.blur), "${s.blur} px", s.blur, 0..40, s::updateBlur, topPadding = 14.dp)
        SliderRow(stringResource(R.string.transparency), "${s.alphaPct} %", s.alphaPct, 5..90, s::setAlpha)
        SliderRow(stringResource(R.string.hero_intensity), "${s.scrimPct} %", s.scrimPct, 20..85, s::setScrim)
        Spacer(Modifier.height(Space.s))
    }

    // La card seleccionada: su halo, su polvo estelar y el color de los dos.
    SectionLabel(stringResource(R.string.section_selection))
    SelectionFxGroup(vm)
}

/** Biblioteca: orden del carrusel, reescanear, imágenes descargadas y la build de BannerHub. */
@Composable
private fun LibraryPage(vm: ElyndraViewModel) {
    val s = vm.settings
    val context = LocalContext.current

    SectionLabel(stringResource(R.string.sort_by))
    WrapRow(gap = Space.s) {
        SortMode.entries.forEach { mode ->
            Pill(stringResource(mode.label), s.sortMode == mode, { s.setSort(mode) }, height = PILL_H)
        }
    }

    SectionLabel(stringResource(R.string.section_library))
    SettingsGroup {
        SwitchRow(stringResource(R.string.tap_open_title), stringResource(R.string.tap_open_desc), s.tapOpensSelected, s::toggleTapOpensSelected)
        SettingsDivider()
        SettingRow(
            stringResource(R.string.rescan_all),
            description = pluralStringResource(R.plurals.folders_count, vm.library.folders.size, vm.library.folders.size),
        ) {
            if (s.rescanning) ArcSpinner(size = 18.dp) else GhostButton(stringResource(R.string.rescan), s::rescanAll)
        }
        SettingRow(
            stringResource(R.string.clear_images),
            description = if (s.mediaBytes >= 0) stringResource(R.string.images_size, Formatter.formatShortFileSize(context, s.mediaBytes)) else "…",
        ) {
            GhostButton(stringResource(R.string.remove), s::clearImages)
        }
        // Con qué build de BannerHub se lanzan los juegos de PC: hay forks
        // que se instalan bajo el paquete de otra app y puede haber varias.
        SettingRow(
            stringResource(R.string.bannerhub_package),
            description = s.bannerHubPackage
                ?: s.detectedBannerHubPackage()
                ?: stringResource(R.string.bannerhub_package_none),
        ) {
            GhostButton(stringResource(R.string.change), s::pickBannerHubPackage)
        }
    }
}

/** Metadatos: fuentes sin cuenta, servicios con cuenta, prioridad, traducción y descarga. */
@Composable
private fun MetadataPage(vm: ElyndraViewModel, wide: Boolean) {
    val s = vm.settings
    val skin = LocalSkin.current
    val context = LocalContext.current
    val p = vm.metaProgress
    var pendingForce by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        s.applyMetadata(pendingForce)
    }

    fun apply(force: Boolean) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingForce = force
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            s.applyMetadata(force)
        }
    }

    SectionLabel(stringResource(R.string.section_metadata_apis))
    KeylessPanel(vm)
    ApiPanels(vm)
    MetadataPriorityPanel(vm, wide)
    TranslationSection(vm)

    SettingsGroup {
        SwitchRow(stringResource(R.string.auto_meta_title), stringResource(R.string.auto_meta_desc), s.autoMeta, s::toggleAutoMeta)
    }

    if (p.running) {
        SettingsGroup {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElyText(stringResource(R.string.applying, p.done, p.total), size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, modifier = Modifier.weight(1f))
                GhostButton(stringResource(R.string.cancel), s::cancelMetadata)
            }
            Spacer(Modifier.height(9.dp))
            val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(P.ink.copy(alpha = 0.1f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .drawBehind { drawRect(accentGradient(skin, 90f, size)) },
                )
            }
            Spacer(Modifier.height(8.dp))
            ElyText(p.current, size = 9f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else if (p.finishedAt > 0) {
        SettingsGroup {
            ElyText(
                if (p.cancelled) stringResource(R.string.metadata_cancelled) else stringResource(R.string.applied_detail, p.matched, p.missed),
                size = 10.5f,
                color = P.ink2,
            )
            p.failures.forEach { (service, kind) ->
                Spacer(Modifier.height(4.dp))
                ElyText(
                    stringResource(R.string.service_failure_line, serviceName(service), s.failureText(kind).resolve()),
                    size = 10f,
                    color = P.red,
                    lineHeightRatio = 1.4f,
                )
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    CtaButton(
        label = when {
            p.running -> stringResource(R.string.applying, p.done, p.total)
            p.finishedAt > 0 && !p.cancelled -> pluralStringResource(R.plurals.applied, p.done, p.done)
            else -> stringResource(R.string.apply_all)
        },
        onClick = { apply(force = false) },
        enabled = !p.running && s.anyServiceConfigured,
    )
    if (!s.anyServiceConfigured) {
        Spacer(Modifier.height(8.dp))
        ElyText(stringResource(R.string.configure_a_service), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
    } else if (!p.running) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            GhostButton(stringResource(R.string.redo_all), { apply(force = true) })
        }
    }
}

/* ── Piezas ───────────────────────────────────────────────────── */

/** Alto de las píldoras de elección de Ajustes (idioma, orden): cerca de los 48 dp de zona táctil. */
private val PILL_H = 40.dp

@Composable
internal fun SectionLabel(text: String) {
    SectionHeader(text)
}

/** `grid-template-columns: repeat(5,1fr)` con 8dp de hueco. */
@Composable
private fun SwatchGrid(items: List<@Composable () -> Unit>) {
    CssGrid(columns = 5, horizontalGap = 8.dp, verticalGap = 8.dp, items = items)
}

/** Deslizador con su rótulo a la izquierda y el valor a la derecha. */
@Composable
internal fun SliderRow(
    label: String,
    value: String,
    current: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    topPadding: Dp = 10.dp,
) {
    Column(Modifier.fillMaxWidth().padding(top = topPadding)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ElyText(label, size = TypeScale.Body - 1f, weight = FontWeight.Medium, color = P.ink)
            Spacer(Modifier.weight(1f))
            ElyText(value, size = TypeScale.Body - 1f, weight = FontWeight.Medium, color = P.ink2)
        }
        Spacer(Modifier.height(7.dp))
        AccentSlider(current, range, onChange)
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/* ── Fondo de la interfaz: miniatura y etiqueta de tipo ────────── */

/**
 * Lo elegido como fondo, en pequeño. Coil saca el primer fotograma de un vídeo
 * con el mismo `model`, así que la misma miniatura vale para vídeo e imagen; si
 * no puede (códec raro, permiso perdido) queda el recuadro vacío, sin error.
 */
@Composable
private fun BackgroundThumb(uri: String?) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(width = 58.dp, height = 36.dp)
            .clip(shape)
            .background(P.chip)
            .border(1.dp, P.hairline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (uri == null) {
            ElyText("—", size = 12f, color = P.ink2)
        } else {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Etiqueta pasiva ("Vídeo" / "Imagen"). La píldora de verdad es un control. */
@Composable
private fun KindBadge(text: String) {
    val shape = RoundedCornerShape(7.dp)
    Box(Modifier.clip(shape).background(P.chip).padding(horizontal = 8.dp, vertical = 3.dp)) {
        ElyText(text, size = 8.5f, weight = FontWeight.SemiBold, color = P.ink2, letterSpacing = tracking(0.1f))
    }
}
