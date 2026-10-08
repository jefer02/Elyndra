package com.elyndra.launcher.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.GameMeta
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.metadata.DescriptionView
import com.elyndra.launcher.metadata.PackState
import com.elyndra.launcher.metadata.RaAchievement
import com.elyndra.launcher.metadata.RetroAchievementsClient
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.AchievementsState
import com.elyndra.launcher.ui.DescriptionsController
import com.elyndra.launcher.ui.DetailsInfo
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.PadScrollBinding
import com.elyndra.launcher.ui.SheetAction
import com.elyndra.launcher.ui.SheetHero
import com.elyndra.launcher.ui.SheetIcon
import com.elyndra.launcher.ui.UiText
import com.elyndra.launcher.ui.rememberDescription
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.ArtVariant
import com.elyndra.launcher.ui.components.BackdropScrim
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.FADE_INTO_GLASS
import com.elyndra.launcher.ui.components.GAP
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.LogoImage
import com.elyndra.launcher.ui.components.MashaMemoryBlock
import com.elyndra.launcher.ui.components.OverlayPanelShell
import com.elyndra.launcher.ui.components.OverlaySectionLabel
import com.elyndra.launcher.ui.components.PAD
import com.elyndra.launcher.ui.components.PANEL_MARGIN
import com.elyndra.launcher.ui.components.PadFocusGroup
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.DetailsLayout
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.components.SheetGlyph
import com.elyndra.launcher.ui.theme.pressFeedback
import com.elyndra.launcher.ui.theme.shapeClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import com.elyndra.launcher.ui.components.SECTION_GAP
import com.elyndra.launcher.ui.components.ScrollHints
import com.elyndra.launcher.ui.components.ScrollViewport
import com.elyndra.launcher.ui.components.TILE_RADIUS
import com.elyndra.launcher.ui.components.padInitialFocus
import com.elyndra.launcher.ui.components.padScrollFallback
import com.elyndra.launcher.ui.components.panelTone
import com.elyndra.launcher.ui.components.readingStop
import com.elyndra.launcher.ui.components.rememberArtAccent
import com.elyndra.launcher.ui.components.OverlayHost
import com.elyndra.launcher.ui.components.Backdrop
import com.elyndra.launcher.ui.components.staggered
import com.elyndra.launcher.ui.components.tileFill
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.LocalLandscape
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import java.text.DateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/* ─────────────────────────────────────────────────────────────
   La ficha de un juego, con el lenguaje del menú de acciones.

     · El mismo fondo apartado (OverlayBackdrop): desenfoque y velo a
       sangre, y el panel que sale de la card con muelle (o en su sitio).
     · Cabecera: el fondo del juego a sangre que se funde con el cristal,
       con su logo (o icono y título), el tipo y el tiempo jugado. Sin
       arte, el arte de reserva del juego.
     · El color del juego (ArtPalette) en el botón principal, el aro del
       foco y los rótulos de sección.
     · Bajo la cabecera, una fila compacta con actualizar metadatos y
       editar nombre (para jugar están la card y A en el carrusel).
     · En ventana ancha, dos columnas equilibradas por lo que ocupa cada
       bloque (DetailsLayout): ninguna se queda vacía.
     · "Masha recuerda", la sinopsis y la información (géneros en
       etiquetas, datos en rejilla, fuentes en insignias, paquete en
       pequeño). Lo vacío no se enseña.
     · Pie con las pistas del mando y "Cerrar".

   Con mando la ficha se recorre con el foco de Compose: botones y bloques
   de lectura en orden; lo que no cabe se desplaza con la cruceta (ver
   PadFocus). B cierra.
   ───────────────────────────────────────────────────────────── */

/**
 * Monta la ficha mientras se abre, está abierta o se está cerrando: al
 * cerrar, `detailsKey` pasa a null enseguida y el panel tiene que poder
 * salir con su animación.
 */
@Composable
fun DetailsHost(vm: ElyndraViewModel) {
    OverlayHost(vm.detailsKey?.let { DetailsTarget(it, vm.detailsOrigin) }, "details") { target, isOpen, open ->
        DetailsSheet(vm, target.key, target.origin, isOpen, open)
    }
}

private data class DetailsTarget(val key: String, val origin: Rect?)

@Composable
private fun DetailsSheet(vm: ElyndraViewModel, key: String, origin: Rect?, isOpen: Boolean, open: State<Float>) {
    val rom = vm.library.roms.firstOrNull { it.key == key }
    val app = vm.library.apps.firstOrNull { it.key == key }
    if (rom == null && app == null) {
        LaunchedEffect(key) { vm.closeDetails() }
        return
    }
    val meta = rom?.meta ?: app!!.meta
    val hero = remember(key, meta, rom?.stats, app?.stats) { vm.detailsHero(key) }
    val accent = rememberArtAccent(hero)
    val tone = panelTone(accent)

    Box(Modifier.fillMaxSize()) {
        // El mismo velo que el menú: a sangre, y tocar fuera cierra.
        BackdropScrim(open, vm::closeDetails, open = isOpen, z = Backdrop.Z_DETAILS)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                .padding(PANEL_MARGIN),
            contentAlignment = Alignment.Center,
        ) {
            // En horizontal y con sitio, dos columnas; si no, una.
            val wide = LocalLandscape.current && maxWidth >= WIDE_MIN
            OverlayPanelShell(origin, open, accent, tone, if (wide) DETAILS_WIDE_MAX else DETAILS_MAX, maxHeight) {
                // Mientras sale ya no es la capa del mando: el foco vuelve a la pantalla.
                PadFocusGroup(modal = true, padFocus = isOpen) {
                    DetailsContent(vm, key, hero, accent, tone, open, wide)
                }
            }
        }
    }
}

@Composable
private fun DetailsContent(
    vm: ElyndraViewModel,
    key: String,
    hero: SheetHero?,
    accent: Color,
    tone: Color,
    open: State<Float>,
    wide: Boolean,
) {
    val rom = vm.library.roms.firstOrNull { it.key == key }
    val app = vm.library.apps.firstOrNull { it.key == key }
    if (rom == null && app == null) return
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    val meta = rom?.meta ?: app!!.meta
    val stats = rom?.stats ?: app!!.stats
    val title = rom?.displayTitle ?: app!!.displayTitle
    val fallback = hero?.fallback ?: rom?.let { vm.romFallback(it) } ?: ArtFallback(app!!.key, app.displayTitle, app.meta.icon, app.packageName)
    val system = rom?.let { Systems.byId(it.systemId) }
    val folder = rom?.let { r -> vm.library.folders.firstOrNull { it.id == r.folderId } }
    val emulator = rom?.let { it.emulatorId ?: folder?.emulatorId }?.let { vm.emulatorName(it) }
    val lang = vm.settings.lang
    val locale = remember(lang) { Locale.forLanguageTag(lang) }
    val dateFormat = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    val description = rememberDescription(vm, key, meta)
    val info = remember(meta, stats, rom, app, locale) {
        DetailsInfo.of(
            meta = meta,
            stats = stats,
            file = rom?.relPath,
            fileSize = rom?.size?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
            packageName = app?.packageName,
            formatDate = { dateFormat.format(Date(it)) },
            formatDay = { dateFormat.format(Date.from(it.atStartOfDay(ZoneId.systemDefault()).toInstant())) },
        )
    }

    val scroll = rememberScrollState()
    // LB/RB pasan página (ver InputController.details).
    PadScrollBinding(vm, scroll)
    val viewport = remember { ScrollViewport() }
    val stopShape = RoundedCornerShape(TILE_RADIUS)
    val stop: @Composable (Modifier) -> Modifier = { it.readingStop(scroll, viewport, stopShape, accent) }

    val subtitle = buildList {
        add(system?.name ?: stringResource(R.string.chip_android_app))
        add(if (stats.minutes > 0) stringResource(R.string.played_time, fmtMinutes(stats.minutes)) else stringResource(R.string.never_played))
    }

    Column {
        Box(Modifier.weight(1f, fill = false)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(viewport.modifier)
                    .padScrollFallback(scroll)
                    .verticalScroll(scroll),
            ) {
                DetailsHeader(
                    title = title,
                    subtitle = subtitle.joinToString(" · "),
                    emulatorLine = emulator?.let { stringResource(R.string.details_emulator_line, it) },
                    hero = hero,
                    fallback = fallback,
                    isApp = app != null,
                    cover = meta.cover,
                    height = if (wide) HEADER_WIDE else HEADER_TALL,
                    tone = tone,
                    open = open,
                    reduced = reduced,
                    modifier = Modifier.staggered(open, 0, reduced),
                )

                val actions: @Composable () -> Unit = {
                    Column(Modifier.staggered(open, 1, reduced)) {
                        val needsName = vm.needsName(title)
                        val refresh = remember { SheetAction(UiText.res(R.string.refresh_metadata), icon = SheetIcon.Refresh) {} }
                        val identify = remember(needsName) {
                            SheetAction(
                                UiText.res(R.string.identify_action),
                                // Sin nombre que sirva (o identificado mal): que se vea aquí.
                                detail = if (needsName) UiText.res(R.string.needs_name_badge) else null,
                                icon = SheetIcon.Search,
                            ) {}
                        }
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                            // Con mando, el foco entra por aquí.
                            CompactAction(refresh, accent, Modifier.weight(1f).fillMaxHeight().padInitialFocus()) {
                                vm.refreshMetadata(listOf(key))
                            }
                            CompactAction(identify, accent, Modifier.weight(1f).fillMaxHeight()) {
                                vm.identify.open(key)
                            }
                        }
                    }
                }
                val memory: @Composable () -> Unit = {
                    // Lo que Masha recuerda de este juego: la última sesión, el emulador, cómo se identificó.
                    MashaMemoryBlock(vm, key, meta, modifier = stop(Modifier.staggered(open, 2, reduced)), accent = accent)
                }
                val about: @Composable () -> Unit = {
                    // La sinopsis en el idioma de la app; si solo la hay en otro, con su
                    // etiqueta y "Traducir" (ver DescriptionPick). Sin sinopsis, nada:
                    // ni hueco ni texto de relleno.
                    description?.let { d ->
                        Column(Modifier.staggered(open, 3, reduced)) {
                            Column(stop(Modifier.fillMaxWidth()).padding(horizontal = 4.dp, vertical = 2.dp)) {
                                OverlaySectionLabel(stringResource(R.string.details_about), accent)
                                ElyText(d.text, size = 11f, color = P.ink, lineHeightRatio = 1.6f, modifier = Modifier.padding(horizontal = 4.dp))
                            }
                            DescriptionLanguageRow(vm, key, meta, d)
                        }
                    }
                }
                val facts: @Composable () -> Unit = {
                    Column(Modifier.staggered(open, 4, reduced)) {
                        // Sin datos de ninguna fuente, decirlo una vez y en pequeño.
                        if (!meta.matched && meta.description == null) {
                            ElyText(
                                stringResource(R.string.details_no_metadata),
                                size = 10f,
                                color = P.ink2,
                                lineHeightRatio = 1.5f,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                            if (!info.isEmpty) Spacer(Modifier.height(SECTION_GAP))
                        }
                        if (!info.isEmpty) InfoBlock(info, accent, stop(Modifier.fillMaxWidth()))
                    }
                }
                val achievements: @Composable () -> Unit = {
                    meta.ra?.let { AchievementsBlock(vm, key, it, accent, stop) }
                }

                val noMetadataNote = !meta.matched && meta.description == null
                val loadedAchievements = (vm.achievements as? AchievementsState.Loaded)?.progress?.achievements?.size ?: 0
                val (left, right) = remember(description?.text, info, noMetadataNote, meta.ra != null, loadedAchievements) {
                    DetailsLayout.columns(
                        listOf(
                            DetailsLayout.Block.About to (description?.let { DetailsLayout.aboutWeight(it.text.length) } ?: 0f),
                            DetailsLayout.Block.Memory to DetailsLayout.MEMORY_WEIGHT,
                            DetailsLayout.Block.Facts to DetailsLayout.factsWeight(info, noMetadataNote),
                            DetailsLayout.Block.Achievements to (if (meta.ra != null) DetailsLayout.achievementsWeight(loadedAchievements) else 0f),
                        ),
                    )
                }
                val block: @Composable (DetailsLayout.Block) -> Unit = {
                    when (it) {
                        DetailsLayout.Block.About -> about()
                        DetailsLayout.Block.Memory -> memory()
                        DetailsLayout.Block.Facts -> facts()
                        DetailsLayout.Block.Achievements -> achievements()
                    }
                }

                val body = Modifier.fillMaxWidth().padding(start = PAD, end = PAD, bottom = PAD)
                Column(body, verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
                    actions()
                    if (wide && right.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(PAD)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) { left.forEach { block(it) } }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) { right.forEach { block(it) } }
                        }
                    } else {
                        memory()
                        about()
                        facts()
                        achievements()
                    }
                }
            }
            ScrollHints(scroll, tone, accent)
        }
        // Pie: las pistas del mando y "Cerrar", siempre a la vista.
        Row(
            Modifier.fillMaxWidth().padding(start = PAD, end = 10.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                PadHints(hints = DETAILS_HINTS, visible = vm.input.gamepadPresent)
            }
            GhostButton(stringResource(R.string.close), vm::closeDetails)
        }
    }
}

private val DETAILS_HINTS = listOf(PadHint("A", R.string.hint_select), PadHint("B", R.string.close))

/**
 * Una acción secundaria de la ficha en una pieza baja: el glifo a la
 * izquierda y el rótulo (dos líneas como mucho) con su aviso debajo.
 */
@Composable
private fun CompactAction(action: SheetAction, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(TILE_RADIUS)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressFeedback(interaction)
            .clip(shape)
            .background(tileFill())
            .border(1.dp, P.hairline.copy(alpha = 0.8f), shape)
            .shapeClickable(shape, interactionSource = interaction, color = accent, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        action.icon?.let { SheetGlyph(it, accent, size = 18.dp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ElyText(
                action.label.resolve(),
                size = 10.5f,
                weight = FontWeight.SemiBold,
                color = P.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeightRatio = 1.25f,
            )
            action.detail?.let {
                ElyText(it.resolve(), size = 8.5f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * La cabecera: el fondo del juego a sangre por arriba (se acerca un poco al
 * abrir), un velo que deja leer lo de encima y, al final, el fundido al color
 * del cristal. Encima, el icono (o la carátula), el logo o el título y la
 * línea de tipo y tiempo jugado. Sin arte, el arte de reserva del juego.
 */
@Composable
private fun DetailsHeader(
    title: String,
    subtitle: String,
    emulatorLine: String?,
    hero: SheetHero?,
    fallback: ArtFallback,
    isApp: Boolean,
    cover: String?,
    height: Dp,
    tone: Color,
    open: State<Float>,
    reduced: Boolean,
    modifier: Modifier = Modifier,
) {
    val textShadow = Shadow(P.shade.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 6f)
    Box(modifier.fillMaxWidth().height(height)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (!reduced) {
                        val z = 1.12f - 0.12f * open.value.coerceIn(0f, 1f)
                        scaleX = z
                        scaleY = z
                    }
                },
        ) {
            ArtImage(hero?.backgroundPath ?: hero?.coverPath, fallback, Modifier.fillMaxSize(), variant = ArtVariant.Banner, alignment = Alignment.TopCenter)
        }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // Velo de abajo arriba: el texto blanco se lee sobre cualquier arte.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.35f to P.shade.copy(alpha = 0.16f),
                            1f to P.shade.copy(alpha = 0.72f),
                        ),
                    )
                    // Fundido al cristal: la cabecera no acaba en un corte.
                    val fade = FADE_INTO_GLASS.toPx()
                    drawRect(
                        Brush.verticalGradient(listOf(Color.Transparent, tone), startY = size.height - fade, endY = size.height),
                        topLeft = Offset(0f, size.height - fade),
                        size = Size(size.width, fade),
                    )
                },
        )
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = FADE_INTO_GLASS + 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // Un juego Android se representa con su icono; una ROM, con su carátula.
            if (isApp) {
                val iconShape = RoundedCornerShape(14.dp)
                GameIcon(
                    hero?.iconPath,
                    hero?.packageName,
                    Modifier
                        .size(56.dp)
                        .shadow(8.dp, iconShape, clip = false, ambientColor = P.shade, spotColor = P.shade)
                        .clip(iconShape),
                    ContentScale.Crop,
                )
            } else {
                val coverShape = RoundedCornerShape(10.dp)
                ArtImage(
                    cover,
                    fallback,
                    Modifier
                        .size(54.dp, 72.dp)
                        .shadow(8.dp, coverShape, clip = false, ambientColor = P.shade, spotColor = P.shade)
                        .clip(coverShape),
                    showTitle = false,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                val logo = hero?.logoPath
                if (logo != null) {
                    LogoImage(logo, Modifier.fillMaxWidth(0.78f).height(52.dp), alignment = Alignment.BottomStart)
                } else {
                    ElyText(
                        title,
                        size = 18f,
                        weight = FontWeight.ExtraBold,
                        color = Color.White,
                        letterSpacing = tracking(-0.01f),
                        lineHeightRatio = 1.15f,
                        shadow = textShadow,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                ElyText(
                    subtitle,
                    size = 10f,
                    weight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f),
                    shadow = textShadow,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                emulatorLine?.let {
                    ElyText(
                        it,
                        size = 9.5f,
                        color = Color.White.copy(alpha = 0.85f),
                        shadow = textShadow,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * La información: géneros en etiquetas, datos en rejilla de dos columnas,
 * fuentes en insignias y el paquete en pequeño. Solo lo que hay (ver
 * [DetailsInfo]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoBlock(info: DetailsInfo, accent: Color, modifier: Modifier) {
    Column(modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        OverlaySectionLabel(stringResource(R.string.details_info), accent)
        if (info.genres.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                info.genres.forEach { GenreChip(it, accent) }
            }
            Spacer(Modifier.height(12.dp))
        }
        info.facts.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { fact ->
                    Column(Modifier.weight(1f)) {
                        ElyText(
                            stringResource(fact.label),
                            size = 8.5f,
                            weight = FontWeight.SemiBold,
                            color = P.ink2,
                            letterSpacing = tracking(0.1f),
                            uppercase = true,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        ElyText(fact.value, size = 11f, weight = FontWeight.Medium, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (info.sources.isNotEmpty()) {
            val names = info.sources.mapNotNull { id -> Service.entries.firstOrNull { it.id == id }?.let(::serviceName) }
            if (names.isNotEmpty()) {
                ElyText(
                    stringResource(R.string.details_sources),
                    size = 8.5f,
                    weight = FontWeight.SemiBold,
                    color = P.ink2,
                    letterSpacing = tracking(0.1f),
                    uppercase = true,
                )
                Spacer(Modifier.height(5.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    names.forEach { SourceBadge(it) }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        info.packageName?.let {
            ElyText(it, size = 9f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun GenreChip(text: String, accent: Color) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .background(accent.copy(alpha = if (P.isDark) 0.18f else 0.10f))
            .border(1.dp, accent.copy(alpha = 0.35f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        ElyText(text, size = 10f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 1)
    }
}

@Composable
private fun SourceBadge(text: String) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .clip(shape)
            .background(tileFill())
            .border(1.dp, P.hairline, shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        ElyText(text, size = 8.5f, weight = FontWeight.SemiBold, color = P.ink2, maxLines = 1, letterSpacing = tracking(0.04f))
    }
}

/** Logros de RetroAchievements: resumen, barra, y la lista (un bloque de lectura). */
@Composable
private fun AchievementsBlock(
    vm: ElyndraViewModel,
    key: String,
    ra: com.elyndra.launcher.data.RaInfo,
    accent: Color,
    stop: @Composable (Modifier) -> Modifier,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { OverlaySectionLabel(stringResource(R.string.achievements_title), accent) }
            GhostButton(stringResource(R.string.open_in_ra) + " ↗", { vm.openUrl(RetroAchievementsClient.gameUrl(ra.gameId)) })
        }
        val loaded = (vm.achievements as? AchievementsState.Loaded)?.progress
        val earned = loaded?.earned ?: ra.earned
        val total = loaded?.total ?: ra.achievements
        val points = loaded?.points ?: ra.points
        val earnedPoints = loaded?.earnedPoints ?: ra.earnedPoints
        Column(stop(Modifier.fillMaxWidth()).padding(horizontal = 4.dp, vertical = 4.dp)) {
            ElyText(stringResource(R.string.achievements_summary, earned, total, earnedPoints, points), size = 10.5f, color = P.ink)
            if (ra.matchedBy == "title") {
                ElyText(stringResource(R.string.matched_by_title), size = 9f, color = P.ink2)
            }
            AchievementProgressBar(if (total > 0) earned.toFloat() / total else 0f, accent)
            val state = vm.achievements
            if (state is AchievementsState.Loaded) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.progress.achievements.forEach { AchievementRow(it, accent) }
                }
            }
        }
        when (vm.achievements) {
            AchievementsState.Loading -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ArcSpinner(size = 16.dp)
                Spacer(Modifier.width(8.dp))
                ElyText(stringResource(R.string.achievements_loading), size = 10f, color = P.ink2)
            }
            AchievementsState.Failed -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ElyText(stringResource(R.string.achievements_failed), size = 10f, color = P.red, modifier = Modifier.weight(1f))
                GhostButton(stringResource(R.string.retry), { vm.loadAchievements(key) })
            }
            AchievementsState.Idle -> Box(Modifier.padding(top = 6.dp)) {
                GhostButton(stringResource(R.string.achievements_load), { vm.loadAchievements(key) })
            }
            is AchievementsState.Loaded -> Unit
        }
    }
}

@Composable
private fun AchievementProgressBar(fraction: Float, accent: Color) {
    Spacer(Modifier.height(6.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(P.ink.copy(alpha = 0.1f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
    }
}

@Composable
private fun AchievementRow(a: RaAchievement, accent: Color) {
    val unlocked = a.earned || a.earnedHardcore
    Row(Modifier.fillMaxWidth().alpha(if (unlocked) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = RetroAchievementsClient.badgeUrl(a.badge, locked = !unlocked),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(P.mediaBack),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ElyText(a.title, size = 11f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ElyText(a.description, size = 9.5f, color = P.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeightRatio = 1.35f)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(if (a.earnedHardcore) accent.copy(alpha = 0.16f) else Color.Transparent)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            ElyText("${a.points}", size = 11f, weight = FontWeight.Bold, color = if (a.earnedHardcore) accent else P.ink2)
        }
    }
}

/**
 * Debajo de una sinopsis que no está en el idioma de la app: su idioma, en
 * pequeño, y "Traducir" (que ofrece bajar el paquete si falta); o, ya
 * traducida, de qué idioma viene y "Ver original".
 */
@Composable
private fun DescriptionLanguageRow(vm: ElyndraViewModel, key: String, meta: GameMeta, d: DescriptionView) {
    val lang = vm.settings.lang
    val c = vm.descriptions
    val original = d.originalLang ?: return
    // En el idioma de la app (o sin idioma conocido): nada que etiquetar.
    if (!d.foreign && !d.translated) return
    Spacer(Modifier.height(6.dp))
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val name = DescriptionsController.languageName(original, lang)
        ElyText(
            if (d.translated) {
                stringResource(R.string.description_translated_from, DescriptionsController.languageNameInline(original, lang))
            } else {
                name
            },
            size = 9f,
            weight = FontWeight.SemiBold,
            color = P.ink2,
            letterSpacing = tracking(0.08f),
            uppercase = true,
        )
        when {
            c.busy == key -> ElyText(stringResource(R.string.description_translating), size = 9.5f, color = P.ink2)
            // El paquete se está bajando: al terminar, la traducción aparece sola.
            !d.translated && c.packStates[lang] != null && c.packStates[lang] != PackState.Error ->
                ElyText(stringResource(R.string.translate_state_downloading), size = 9.5f, color = P.ink2)
            d.translated -> GhostButton(stringResource(R.string.description_show_original), { c.showOriginal(key, true) })
            else -> GhostButton(stringResource(R.string.description_translate), { c.translate(key, meta) })
        }
    }
}

private val DETAILS_MAX = 560.dp
private val DETAILS_WIDE_MAX = 860.dp
private val WIDE_MIN = 640.dp
private val HEADER_TALL = 176.dp
private val HEADER_WIDE = 136.dp
