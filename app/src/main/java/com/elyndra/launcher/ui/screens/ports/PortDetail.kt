package com.elyndra.launcher.ui.screens.ports

import com.elyndra.launcher.ui.components.edgeBleed
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.domain.ports.Installable
import com.elyndra.launcher.domain.ports.MatchStrength
import com.elyndra.launcher.domain.ports.PortAction
import com.elyndra.launcher.domain.ports.PortDisplay
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.RepoInfo
import com.elyndra.launcher.domain.ports.RomPortMatch
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.PortsController
import com.elyndra.launcher.ui.StoreLayout
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.padInitialFocus
import com.elyndra.launcher.ui.components.padScrollFallback
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.Store
import com.elyndra.launcher.update.UpdateLogic
import java.text.DateFormat
import java.util.Date

/** Lo que la ficha de un port necesita para pintarse, sin ViewModel (también para las vistas previas). */
class PortDetailState(
    val port: PortEntry,
    val action: PortAction,
    val progress: Float?,
    val installing: Boolean,
    val latest: Installable?,
    val releasesLoading: Boolean,
    val releasesFailed: Boolean,
    val rateLimited: Boolean,
    val repo: RepoInfo?,
    val owned: RomPortMatch?,
    /** El arte a sangre de la ficha: el del juego original o una captura (null: la imagen del port). */
    val hero: Any?,
    /** Capturas del port y del juego original, para comparar. */
    val portShots: List<Any> = emptyList(),
    val originalShots: List<Any> = emptyList(),
    /** Los ports del mismo juego, en el orden del selector (él incluido); con uno solo, su release. */
    val siblings: List<PortEntry> = listOf(port),
    /** Lo que diría el botón para cada port del selector. */
    val siblingAction: (PortEntry) -> PortAction = { action },
    val wishlisted: Boolean = false,
)

class PortDetailActions(
    val back: () -> Unit = {},
    val primary: () -> Unit = {},
    val cancel: () -> Unit = {},
    val openRepo: () -> Unit = {},
    /** El selector: otro port del mismo juego. */
    val select: (PortEntry) -> Unit = {},
    val toggleWishlist: () -> Unit = {},
)

@Composable
fun PortDetailScreen(vm: ElyndraViewModel, port: PortEntry) {
    val context = LocalContext.current
    val ports = vm.ports
    val releases = ports.releases[port.id]
    val state = PortDetailState(
        port = port,
        action = ports.action(port),
        progress = vm.portInstall.progress(port.id),
        installing = vm.portInstall.tasks[port.id] is com.elyndra.launcher.ui.PortInstallController.Task.Installing,
        latest = ports.latest(port),
        releasesLoading = releases == PortsController.Releases.Loading,
        releasesFailed = releases is PortsController.Releases.Failed,
        rateLimited = (releases as? PortsController.Releases.Failed)?.rateLimited == true,
        repo = ports.repoInfo[port.id],
        owned = ports.ownedRom(port),
        hero = ports.heroArt(port) ?: port.game.art.screenshots.firstOrNull() ?: port.screenshots.firstOrNull(),
        portShots = port.screenshots,
        originalShots = port.game.art.screenshots,
        siblings = ports.siblings(port),
        siblingAction = ports::action,
        wishlisted = port.id in ports.wishlist,
    )
    PortDetail(
        state,
        PortDetailActions(
            back = vm.ports::closeDetail,
            primary = { vm.portInstall.primary(port) },
            cancel = { vm.portInstall.cancel(port.id) },
            openRepo = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(port.releaseRepo.url + "/releases")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            select = { vm.ports.selectPort(it.id) },
            toggleWishlist = { vm.ports.toggleWishlist(port.id) },
        ),
        padHints = vm.input.gamepadPresent,
    )
}

/** ¿Solo se puede ver (sin APK o solo para gafas)? Entonces el botón lleva al proyecto en GitHub. */
private fun viewOnly(action: PortAction) = action == PortAction.NotOnAndroid || action == PortAction.VrOnly

/**
 * La ficha de un port, como la página de un juego en la tienda de una
 * consola: el arte a sangre, oscurecido a la izquierda, y encima un panel
 * flotante (≈ 40 % del ancho; todo el ancho en vertical) que entra desde la
 * izquierda con el nombre, el autor, las consolas, las variantes, el botón
 * grande y el de la lista de deseos. Debajo, los datos, las capturas (port
 * frente al original), la descripción y las novedades.
 */
@Composable
fun PortDetail(state: PortDetailState, actions: PortDetailActions, modifier: Modifier = Modifier, padHints: Boolean = false) {
    val scroll = rememberScrollState()
    val lang = LocalConfiguration.current.locales[0].language
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val wide = StoreLayout.detailWide(w, h)
        val margin = StoreLayout.margin(w, h).dp
        val heroH = StoreLayout.detailHeroHeight(w, h).dp
        Column(
            Modifier
                .fillMaxSize()
                .padScrollFallback(scroll)
                .verticalScroll(scroll)
                .padding(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                // El arte a sangre llega al filo (bajo el recorte de la cámara y arriba); el panel, no.
                Box(Modifier.fillMaxWidth().height(heroH).edgeBleed(bottom = false)) {
                    StoreImage(state.hero ?: state.port.image, "", Modifier.fillMaxSize())
                    // Oscuro a la izquierda (donde va el panel) y fundido abajo con el fondo.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.horizontalGradient(0f to Store.bg.copy(alpha = if (wide) 0.92f else 0.3f), 0.55f to Color.Transparent)),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Store.bg)))
                }
                DetailPanel(
                    state,
                    actions,
                    Modifier
                        .statusBarsPadding()
                        .padding(start = margin, end = if (wide) 0.dp else margin, top = StoreLayout.detailPanelTop(w, h).dp)
                        .width(StoreLayout.detailPanelWidth(w, h).dp),
                )
            }
            InfoRow(state, actions, Modifier.padding(horizontal = margin))
            RequirementNote(state, lang, Modifier.padding(horizontal = margin))
            Screenshots(state, w, h, margin)
            Description(state, lang, Modifier.padding(horizontal = margin))
            Changelog(state, Modifier.padding(horizontal = margin))
            ElyText(stringResource(R.string.ports_never_roms), Modifier.padding(horizontal = margin), size = 10f, color = Store.textFaint)
        }
        val primaryLabel = primaryLabelRes(state)
        PadHints(
            listOf(PadHint("A", primaryLabel), PadHint("B", R.string.hint_back)),
            padHints,
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            onDark = true,
        )
    }
}

private fun primaryLabelRes(state: PortDetailState): Int = when {
    viewOnly(state.action) -> R.string.ports_view_repo
    state.action == PortAction.Installed -> R.string.ports_action_open
    state.action == PortAction.Update -> R.string.ports_action_update
    else -> R.string.ports_action_install
}

/**
 * El panel flotante: entra desde la izquierda con un deslizamiento corto al
 * abrir la ficha (sin él con movimiento reducido).
 */
@Composable
private fun DetailPanel(state: PortDetailState, actions: PortDetailActions, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    val enter = remember(state.port.id) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(state.port.id) { if (!reduced) enter.animateTo(1f, tween(260)) }
    val port = state.port
    Column(
        modifier
            .graphicsLayer {
                translationX = (1f - enter.value) * -48.dp.toPx()
                alpha = enter.value
            }
            .storePanel(10.dp)
            .padding(horizontal = 22.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            ElyText(PortDisplay.title(port), size = 26f, weight = FontWeight.Light, color = Store.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            ElyText(
                (port.developer ?: port.releaseRepo.owner) + " · " + port.releaseRepo.slug,
                size = 10.5f,
                weight = FontWeight.Bold,
                color = Store.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StoreOutlinedChip(platformLabel(port.platform))
            StoreOutlinedChip(if (port.vrOnly) "VR" else "Android")
        }
        Variants(state, actions)
        val note = when {
            state.action == PortAction.NotOnAndroid -> stringResource(R.string.ports_pc_only_note)
            state.action == PortAction.VrOnly -> stringResource(R.string.ports_vr_only_note)
            state.action == PortAction.Unavailable -> stringResource(R.string.ports_unavailable_note)
            state.action == PortAction.SignatureChanged -> stringResource(R.string.ports_signature_mismatch)
            state.rateLimited -> stringResource(R.string.ports_rate_limited)
            state.releasesFailed -> stringResource(R.string.ports_release_failed)
            else -> null
        }
        note?.let { ElyText(it, size = 11f, color = Store.warn) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val enabled = viewOnly(state.action) ||
                (state.action != PortAction.Unavailable && state.action != PortAction.SignatureChanged && state.progress == null && !state.installing)
            StorePrimaryButton(
                label = stringResource(primaryLabelRes(state)),
                onClick = { if (viewOnly(state.action)) actions.openRepo() else actions.primary() },
                modifier = Modifier.weight(1f).padInitialFocus(),
                enabled = enabled,
                progress = state.progress,
                busy = state.installing || (state.releasesLoading && state.action == PortAction.Free),
            )
            Spacer(Modifier.width(10.dp))
            StoreIconButton(
                if (state.wishlisted) StoreIcon.HeartFilled else StoreIcon.Heart,
                stringResource(if (state.wishlisted) R.string.store_wishlist_remove else R.string.store_wishlist_add),
                actions.toggleWishlist,
                size = 46.dp,
                filled = true,
            )
        }
        if (state.progress != null || state.installing) {
            SmallPill(stringResource(R.string.ports_action_cancel), actions.cancel)
        }
        val badges = trustBadges(port)
        if (badges.isNotEmpty()) {
            ElyText(badges.map { stringResource(it) }.joinToString(" · "), size = 10f, color = Store.textDim, maxLines = 2)
        }
    }
}

/**
 * Las opciones, como las de compra de una tienda. Con varios ports del mismo
 * juego, "Elige un port": una fila con su botón de radio por port (nombre y
 * autor; el elegido, con fondo más claro). Con uno solo, "Release": la fila
 * de su archivo (y la etiqueta y el tamaño).
 */
@Composable
private fun Variants(state: PortDetailState, actions: PortDetailActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.siblings.size > 1) {
            VariantsLabel(stringResource(R.string.store_choose_port))
            state.siblings.forEach { p ->
                VariantRow(
                    selected = p.id == state.port.id,
                    title = p.name,
                    detail = p.developer ?: p.releaseRepo.owner,
                    trailing = null,
                    onClick = { actions.select(p) },
                )
            }
        } else {
            val latest = state.latest
            val tag = latest?.release?.tag ?: state.port.release.tag
            VariantsLabel(stringResource(R.string.store_release))
            VariantRow(
                selected = true,
                title = latest?.asset?.name ?: tag ?: stringResource(R.string.store_latest_release),
                detail = listOfNotNull(tag, latest?.asset?.size?.takeIf { it > 0 }?.let { formatSize(it) }).joinToString(" · ")
                    .ifEmpty { state.port.releaseRepo.slug },
                trailing = null,
                onClick = {},
            )
        }
    }
}

@Composable
private fun VariantsLabel(text: String) {
    ElyText(text, size = 10f, weight = FontWeight.Medium, color = Store.textDim, uppercase = true)
}

@Composable
private fun VariantRow(selected: Boolean, title: String, detail: String, trailing: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .storeFocusRing(focused, 8.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (selected) Store.panelRow else Color.Transparent)
            .storeClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // El botón de radio: un aro y, si es la elegida, el punto.
        Box(Modifier.size(18.dp).border(1.5.dp, Store.text, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(9.dp).background(Store.text, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ElyText(title, size = 14f, weight = FontWeight.Normal, color = Store.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ElyText(detail, size = 10f, color = Store.textDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            ElyText(it, size = 11f, color = Store.textDim, maxLines = 1)
        }
    }
}

/** La fila de datos con iconos: tamaño, versión, fecha, lo que pide, estrellas y las notas de la release en GitHub. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoRow(state: PortDetailState, actions: PortDetailActions, modifier: Modifier) {
    val port = state.port
    val latest = state.latest
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoItem(StoreIcon.Gamepad, stringResource(if (port.requires.userRom) R.string.store_needs_copy else R.string.store_no_files_needed))
        latest?.asset?.size?.takeIf { it > 0 }?.let { InfoItem(StoreIcon.Size, formatSize(it)) }
        (latest?.release?.tag ?: port.release.tag)?.let { InfoItem(StoreIcon.Version, it) }
        (latest?.release?.publishedAt?.takeIf { it > 0 } ?: port.release.publishedAtMillis.takeIf { it > 0 })?.let {
            InfoItem(StoreIcon.Calendar, stringResource(R.string.store_updated_on, formatDate(it)))
        }
        (state.repo?.stars ?: port.trust.stars)?.let { InfoItem(StoreIcon.Players, "★ $it") }
        InfoItem(StoreIcon.Link, stringResource(R.string.store_release_notes), onClick = actions.openRepo)
    }
}

@Composable
private fun InfoItem(icon: StoreIcon, text: String, onClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    val base = Modifier
        .storeFocusRing(focused, 6.dp)
        .onFocusChanged { focused = it.isFocused }
        .clip(RoundedCornerShape(6.dp))
    Row(
        (if (onClick != null) base.storeClickable(onClick = onClick) else base).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StoreIconGlyph(icon, Store.text, 18.dp)
        Spacer(Modifier.width(8.dp))
        ElyText(text, size = 12f, color = if (onClick != null) Store.link else Store.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Lo que pide el port: la copia propia del juego y si ya está en la biblioteca. */
@Composable
private fun RequirementNote(state: PortDetailState, lang: String, modifier: Modifier) {
    val req = state.port.requires
    if (!req.userRom) return
    Column(modifier.fillMaxWidth().storePanel().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ElyText(stringResource(R.string.ports_requirement_title), size = 13f, weight = FontWeight.Normal, color = Store.text)
        ElyText(stringResource(R.string.ports_needs_rom), size = 11.5f, color = Store.warn)
        state.port.requirementText(lang)?.let { ElyText(it, size = 11.5f, color = Store.textDim, lineHeightRatio = 1.4f) }
        if (req.roms.isNotEmpty()) {
            ElyText(stringResource(R.string.ports_accepted_dumps, req.roms.joinToString(" · ") { it.label }), size = 10f, color = Store.textFaint)
        }
        state.owned?.let { owned ->
            when (owned.strength) {
                MatchStrength.Hash -> ElyText(stringResource(R.string.ports_in_library_as, owned.rom.displayTitle), size = 11.5f, weight = FontWeight.SemiBold, color = Store.ok)
                MatchStrength.Title -> ElyText(stringResource(R.string.ports_probably_in_library, owned.rom.displayTitle), size = 11f, color = Store.ok.copy(alpha = 0.8f))
            }
        }
    }
}

/** Capturas: las del port y las del juego original, cada una con su rótulo, en un carrusel. */
@Composable
private fun Screenshots(state: PortDetailState, width: Float, height: Float, margin: Dp) {
    val shots = state.portShots.map { it to true } + state.originalShots.map { it to false }
    if (shots.isEmpty()) return
    val card = StoreLayout.cardWidth(width, height).dp * 1.3f
    Column {
        StoreSectionTitle(stringResource(R.string.store_screenshots), Modifier.padding(horizontal = margin).padding(bottom = 10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = margin), horizontalArrangement = Arrangement.spacedBy(StoreLayout.gap(width).dp)) {
            items(shots) { (shot, isPort) ->
                var focused by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(StoreLayout.TILE_RADIUS.dp)
                Box(
                    Modifier
                        .width(card)
                        .height(StoreLayout.cardHeight(card.value).dp)
                        .storeFocusRing(focused, StoreLayout.TILE_RADIUS.dp)
                        .onFocusChanged { focused = it.isFocused }
                        .clip(shape)
                        .storeClickable {},
                ) {
                    StoreImage(shot, "", Modifier.fillMaxSize())
                    StoreBadge(
                        stringResource(if (isPort) R.string.store_shot_port else R.string.store_shot_original),
                        Modifier.align(Alignment.BottomStart).padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Description(state: PortDetailState, lang: String, modifier: Modifier) {
    val text = state.port.longText(lang)
    if (text.isBlank()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StoreSectionTitle(listOfNotNull(stringResource(R.string.ports_about), PortDisplay.portName(state.port)).joinToString(" · "))
        ElyText(text, size = 12.5f, weight = FontWeight.Light, color = Store.text.copy(alpha = 0.88f), lineHeightRatio = 1.5f)
        ElyText(
            stringResource(kindLabel(state.port.kind)) + " · " + (state.repo?.license ?: state.port.license ?: stringResource(R.string.ports_license_unknown)),
            size = 10.5f,
            color = Store.textDim,
        )
    }
}

@Composable
private fun Changelog(state: PortDetailState, modifier: Modifier) {
    val release = state.latest?.release ?: return
    val notes = UpdateLogic.trimNotes(release.notes, maxChars = 1_200, maxLines = 24)
    if (notes.isBlank()) return
    Column(modifier.fillMaxWidth().storePanel().padding(16.dp)) {
        StoreSectionTitle(stringResource(R.string.ports_changelog) + " · " + release.tag)
        Spacer(Modifier.height(8.dp))
        ElyText(notes, size = 11.5f, weight = FontWeight.Light, color = Store.textDim, lineHeightRatio = 1.45f)
    }
}

private fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
