package com.elyndra.launcher.ui.screens.ports

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.elyndra.launcher.domain.ports.PortEntry
import com.elyndra.launcher.domain.ports.RepoInfo
import com.elyndra.launcher.domain.ports.RomPortMatch
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.PortsController
import com.elyndra.launcher.ui.PortsLayout
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.padInitialFocus
import com.elyndra.launcher.ui.components.padScrollFallback
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.MinTouch
import com.elyndra.launcher.ui.theme.Store
import com.elyndra.launcher.ui.theme.shapeClickable
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
    /** Lo que enseña la galería: capturas del catálogo, la imagen del port y el arte del original. */
    val media: List<Any>,
    val cover: Any?,
)

class PortDetailActions(
    val back: () -> Unit,
    val primary: () -> Unit,
    val cancel: () -> Unit,
    val openRepo: () -> Unit,
)

private val DETAIL_HINTS = listOf(
    PadHint("A", R.string.hint_select),
    PadHint("B", R.string.hint_back),
)

@Composable
fun PortDetailScreen(vm: ElyndraViewModel, port: PortEntry) {
    val context = LocalContext.current
    val ports = vm.ports
    val releases = ports.releases[port.id]
    val heroArt = ports.heroArt(port)
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
        media = (port.screenshots + port.image + listOfNotNull(heroArt)).distinct(),
        cover = ports.coverArt(port),
    )
    PortDetail(
        state,
        PortDetailActions(
            back = vm.ports::closeDetail,
            primary = { vm.portInstall.primary(port) },
            cancel = { vm.portInstall.cancel(port.id) },
            openRepo = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(port.releaseRepo.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        ),
        padHints = vm.input.gamepadPresent,
    )
}

/**
 * La ficha de un port, con el orden de la página de un juego en una tienda
 * de escritorio: migas, título, galería grande a la izquierda y, a la
 * derecha, la cápsula, la descripción corta y el bloque de datos; debajo,
 * las etiquetas, lo que pide (ROM propia / ✓ en tu biblioteca), el botón
 * grande y las novedades de la última release.
 */
@Composable
fun PortDetail(state: PortDetailState, actions: PortDetailActions, modifier: Modifier = Modifier, padHints: Boolean = false) {
    val scroll = rememberScrollState()
    val lang = LocalConfiguration.current.locales[0].language
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val pad = PortsLayout.pad(w).dp
        val two = PortsLayout.detailTwoColumns(w, h)
        Column(
            Modifier
                .fillMaxSize()
                .padScrollFallback(scroll)
                .verticalScroll(scroll)
                .statusBarsPadding()
                .padding(horizontal = pad)
                .padding(top = 10.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DetailHeader(state, actions)
            if (two) {
                val column = (w - 2 * pad.value) * PortsLayout.GALLERY_FRACTION
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Gallery(state, column.dp, Modifier.width(column.dp))
                    Summary(state, lang, Modifier.weight(1f))
                }
            } else {
                val column = w - 2 * pad.value
                Gallery(state, column.dp, Modifier.fillMaxWidth())
                Summary(state, lang, Modifier.fillMaxWidth())
            }
            InstallBox(state, actions)
            RequirementBox(state, lang)
            Column(Modifier.fillMaxWidth().storePanel().padding(14.dp)) {
                ElyText(stringResource(R.string.ports_about), size = 12f, weight = FontWeight.SemiBold, color = Store.text, uppercase = true)
                Spacer(Modifier.height(8.dp))
                ElyText(state.port.longText(lang), size = 12f, color = Store.textDim, lineHeightRatio = 1.45f)
            }
            Changelog(state)
            ElyText(stringResource(R.string.ports_never_roms), size = 10f, color = Store.textFaint)
        }
        PadHints(DETAIL_HINTS, padHints, Modifier.align(Alignment.BottomStart).padding(pad), onDark = true)
    }
}

@Composable
private fun DetailHeader(state: PortDetailState, actions: PortDetailActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlassIconButton(onClick = actions.back, size = MinTouch, cornerRadius = 15.dp, contentDescription = stringResource(R.string.hint_back)) {
            BackChevron(color = Store.text)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Migas: Ports › N64 › Ship of Harkinian.
            ElyText(
                stringResource(R.string.ports_title) + "  ›  " + platformLabel(state.port.original.platform) + "  ›  " + state.port.name,
                size = 10f,
                color = Store.textDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ElyText(state.port.name, size = 21f, weight = FontWeight.Bold, color = Store.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** La galería: la imagen grande y una tira de miniaturas que la cambian. */
@Composable
private fun Gallery(state: PortDetailState, width: Dp, modifier: Modifier) {
    var selected by remember(state.port.id) { mutableIntStateOf(0) }
    val media = state.media
    val skin = LocalSkin.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StoreImage(
            media.getOrNull(selected) ?: state.port.image,
            state.port.name,
            Modifier
                .fillMaxWidth()
                .height(PortsLayout.galleryHeight(width.value).dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, Store.line, RoundedCornerShape(4.dp)),
        )
        if (media.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(0.dp)) {
                itemsIndexed(media) { i, m ->
                    val shape = RoundedCornerShape(3.dp)
                    StoreImage(
                        m,
                        "",
                        Modifier
                            .width(PortsLayout.THUMB_WIDTH.dp)
                            .height((PortsLayout.THUMB_WIDTH / 1.78f).dp)
                            .clip(shape)
                            .border(if (i == selected) 2.dp else 1.dp, if (i == selected) skin.a1 else Store.line, shape)
                            .shapeClickable(shape, color = skin.a1) { selected = i },
                    )
                }
            }
        }
    }
}

/** La columna de la derecha: cápsula, descripción corta, datos y etiquetas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(state: PortDetailState, lang: String, modifier: Modifier) {
    val port = state.port
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StoreImage(
            port.image,
            port.name,
            Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .height(120.dp)
                .clip(RoundedCornerShape(4.dp)),
        )
        ElyText(port.shortText(lang), size = 11.5f, color = Store.text.copy(alpha = 0.9f), lineHeightRatio = 1.4f)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            InfoLine(stringResource(R.string.ports_info_original), platformLabel(port.original.platform) + " · " + port.original.title)
            InfoLine(stringResource(R.string.ports_info_developer), port.developer ?: port.releaseRepo.owner)
            InfoLine(stringResource(kindLabel(port.kind)), port.upstream?.slug ?: port.repo.slug)
            InfoLine(
                stringResource(R.string.ports_info_license),
                state.repo?.license ?: port.license ?: stringResource(R.string.ports_license_unknown),
            )
            state.latest?.let { l ->
                InfoLine(stringResource(R.string.ports_info_release), l.release.tag + if (l.release.publishedAt > 0) " · " + formatDate(l.release.publishedAt) else "")
                InfoLine(stringResource(R.string.ports_info_size), formatSize(l.asset.size))
            }
            state.repo?.let { InfoLine(stringResource(R.string.ports_info_stars), "★ " + it.stars) }
            InfoLine(stringResource(R.string.ports_info_repo), "github.com/" + port.releaseRepo.slug)
        }
        if (port.tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                port.tags.forEach { StoreChip(it) }
            }
        }
        if (!port.verified) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoreChip(stringResource(R.string.ports_unverified))
                Spacer(Modifier.width(6.dp))
                ElyText(stringResource(R.string.ports_unverified_note), Modifier.weight(1f), size = 10f, color = Store.warn)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        ElyText(label.uppercase(), Modifier.width(112.dp), size = 9.5f, weight = FontWeight.Medium, color = Store.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ElyText(value, Modifier.weight(1f), size = 10.5f, color = Store.link, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** El recuadro de compra de una tienda, aquí de instalar: título y el botón grande a la derecha. */
@Composable
private fun InstallBox(state: PortDetailState, actions: PortDetailActions) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .storePanel()
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Store.panelHigh.copy(alpha = 0.6f), Store.panel)))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val title = when (state.action) {
                PortAction.Update -> stringResource(R.string.ports_action_update)
                PortAction.Installed -> stringResource(R.string.ports_action_installed)
                else -> stringResource(R.string.ports_action_install)
            } + " " + state.port.name
            ElyText(title, size = 14f, weight = FontWeight.SemiBold, color = Store.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val note = when {
                state.action == PortAction.PcOnly -> stringResource(R.string.ports_pc_only_note)
                state.action == PortAction.Unavailable -> stringResource(R.string.ports_unavailable_note)
                state.rateLimited -> stringResource(R.string.ports_rate_limited)
                state.releasesFailed -> stringResource(R.string.ports_release_failed)
                else -> null
            }
            note?.let { ElyText(it, size = 10.5f, color = Store.warn) }
            if (state.progress != null || state.installing) {
                Spacer(Modifier.height(4.dp))
                StoreChip(stringResource(R.string.ports_action_cancel), onClick = actions.cancel)
            }
        }
        Spacer(Modifier.width(12.dp))
        val enabled = state.action != PortAction.PcOnly && state.action != PortAction.Unavailable && state.progress == null && !state.installing
        Box(
            Modifier
                .padInitialFocus()
                .clip(shape)
                .shapeClickable(shape, enabled = enabled || state.action == PortAction.PcOnly, color = skin.a1) {
                    if (state.action == PortAction.PcOnly) actions.openRepo() else actions.primary()
                },
        ) {
            when {
                state.installing || (state.releasesLoading && state.action == PortAction.Free) ->
                    Box(Modifier.width(120.dp).height(40.dp).background(Store.free), contentAlignment = Alignment.Center) { ArcSpinner(size = 18.dp, color = Store.text) }
                state.action == PortAction.Installed ->
                    // Instalado: el botón abre el port (solo si se pulsa; nunca se abre solo).
                    Box(Modifier.background(androidx.compose.ui.graphics.Brush.horizontalGradient(Store.install)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                        ElyText(stringResource(R.string.ports_action_open), size = 13.5f, weight = FontWeight.SemiBold, color = Store.text)
                    }
                state.action == PortAction.PcOnly ->
                    Box(Modifier.background(Store.chip).padding(horizontal = 14.dp, vertical = 10.dp)) {
                        ElyText(stringResource(R.string.ports_view_repo), size = 12f, weight = FontWeight.SemiBold, color = Store.chipText)
                    }
                state.action == PortAction.Free ->
                    PortActionPill(PortAction.Install, large = true)
                else -> PortActionPill(state.action, large = true, progress = state.progress)
            }
        }
    }
}

/** Lo que pide el port: la copia propia del juego (región/revisión) y si ya está en la biblioteca. */
@Composable
private fun RequirementBox(state: PortDetailState, lang: String) {
    val req = state.port.requires
    Column(
        Modifier
            .fillMaxWidth()
            .storePanel()
            .background(Store.requirement)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ElyText(stringResource(R.string.ports_requirement_title), size = 12f, weight = FontWeight.SemiBold, color = Store.text, uppercase = true)
        if (!req.userRom) {
            ElyText(stringResource(R.string.ports_no_rom_needed), size = 11.5f, color = Store.textDim)
            return@Column
        }
        ElyText(stringResource(R.string.ports_needs_rom), size = 11.5f, weight = FontWeight.Medium, color = Store.warn)
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

@Composable
private fun Changelog(state: PortDetailState) {
    val release = state.latest?.release ?: return
    val notes = UpdateLogic.trimNotes(release.notes, maxChars = 1_200, maxLines = 24)
    if (notes.isBlank()) return
    Column(Modifier.fillMaxWidth().storePanel().padding(14.dp)) {
        ElyText(stringResource(R.string.ports_changelog) + " · " + release.tag, size = 12f, weight = FontWeight.SemiBold, color = Store.text, uppercase = true)
        Spacer(Modifier.height(8.dp))
        ElyText(notes, size = 11f, color = Store.textDim, lineHeightRatio = 1.45f)
    }
}

/** La barra de descargas de abajo: una ficha por port bajándose o instalándose. */
@Composable
fun PortDownloadsBar(vm: ElyndraViewModel, modifier: Modifier = Modifier) {
    val tasks = vm.portInstall.tasks.values.toList()
    if (tasks.isEmpty()) return
    Row(
        modifier
            .padding(12.dp)
            .storePanel(8.dp)
            .background(Store.panelHigh)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ElyText(stringResource(R.string.ports_downloads), size = 10f, weight = FontWeight.SemiBold, color = Store.textDim, uppercase = true)
        tasks.forEach { t ->
            when (t) {
                is com.elyndra.launcher.ui.PortInstallController.Task.Downloading ->
                    ElyText(stringResource(R.string.ports_downloading, t.port.name) + " · ${(t.progress * 100).toInt()} %", size = 11f, color = Store.text, maxLines = 1)
                is com.elyndra.launcher.ui.PortInstallController.Task.Installing ->
                    ElyText(stringResource(R.string.ports_installing, t.port.name), size = 11f, color = Store.text, maxLines = 1)
            }
        }
    }
}

private fun formatDate(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1_048_576 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}
