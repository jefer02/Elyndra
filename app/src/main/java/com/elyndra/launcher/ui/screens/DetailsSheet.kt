package com.elyndra.launcher.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.Systems
import com.elyndra.launcher.data.fmtMinutes
import com.elyndra.launcher.data.pairIndexFor
import com.elyndra.launcher.metadata.RaAchievement
import com.elyndra.launcher.metadata.RetroAchievementsClient
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.AchievementsState
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.LibraryItem
import com.elyndra.launcher.ui.components.AccentButton
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.ScrimLayer
import com.elyndra.launcher.ui.components.consumeClicks
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animRiseSheet
import com.elyndra.launcher.ui.theme.glass
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Ficha de un juego: carátula, datos, sinopsis y logros de RetroAchievements. */
@Composable
fun DetailsSheet(vm: ElyndraViewModel, key: String) {
    val rom = vm.library.roms.firstOrNull { it.key == key }
    val app = vm.library.apps.firstOrNull { it.key == key }
    if (rom == null && app == null) {
        LaunchedEffect(key) { vm.closeDetails() }
        return
    }
    val context = LocalContext.current
    val meta = rom?.meta ?: app!!.meta
    val stats = rom?.stats ?: app!!.stats
    val title = rom?.displayTitle ?: app!!.displayTitle
    val pair = rom?.let { vm.romPairIndex(it) } ?: pairIndexFor(app!!.packageName)
    val system = rom?.let { Systems.byId(it.systemId) }
    val folder = rom?.let { r -> vm.library.folders.firstOrNull { it.id == r.folderId } }
    val emulator = rom?.let { it.emulatorId ?: folder?.emulatorId }?.let { vm.emulatorName(it) }
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.forLanguageTag(vm.settings.lang))
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.88f).dp

    ScrimLayer(onDismiss = vm::closeDetails, alignment = Alignment.BottomCenter, key = key) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(10.dp)
                .animRiseSheet(key = key)
                .glass(RoundedCornerShape(24.dp), solid = true)
                .consumeClicks(),
        ) {
            // ── Cabecera ──
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                Box(Modifier.size(78.dp, 104.dp).clip(RoundedCornerShape(12.dp))) {
                    ArtImage(meta.cover, pair, Modifier.fillMaxSize())
                    if (meta.cover == null && (app != null || meta.icon != null)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            GameIcon(meta.icon, app?.packageName, Modifier.size(44.dp))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    ElyText(title, size = 16f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    ElyText(system?.name ?: stringResource(R.string.chip_android_app), size = 10f, color = P.ink2, maxLines = 1)
                    emulator?.let {
                        ElyText(stringResource(R.string.details_emulator_line, it), size = 10f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    ElyText(
                        if (stats.minutes > 0) stringResource(R.string.played_time, fmtMinutes(stats.minutes)) else stringResource(R.string.never_played),
                        size = 10f,
                        color = P.ink2,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AccentButton(stringResource(R.string.open), {
                            vm.closeDetails()
                            if (rom != null) {
                                vm.openRom(rom)
                            } else {
                                vm.open(LibraryItem.App(app!!, installed = true))
                            }
                        }, fontSize = 11.5f)
                        GhostButton(stringResource(R.string.refresh_metadata), { vm.refreshMetadata(listOf(key)) })
                    }
                }
                Spacer(Modifier.width(8.dp))
                GlassIconButton(onClick = vm::closeDetails, size = 32.dp, cornerRadius = 11.dp) {
                    ElyText("✕", size = 12f, color = P.ink)
                }
            }

            // ── Cuerpo desplazable ──
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
            ) {
                if (!meta.matched && meta.description == null) {
                    ElyText(stringResource(R.string.details_no_metadata), size = 10.5f, color = P.ink2, lineHeightRatio = 1.5f)
                    Spacer(Modifier.height(10.dp))
                }

                val facts = buildList {
                    meta.releaseDate?.let { add(R.string.details_release to it) }
                    meta.developer?.let { add(R.string.details_developer to it) }
                    meta.publisher?.let { add(R.string.details_publisher to it) }
                    meta.genre?.let { add(R.string.details_genre to it) }
                    meta.players?.let { add(R.string.details_players to it) }
                    meta.rating?.let { add(R.string.details_rating to "${(it * 100).roundToInt()} / 100") }
                    if (meta.sources.isNotEmpty()) {
                        add(R.string.details_sources to meta.sources.mapNotNull { id -> Service.entries.firstOrNull { it.id == id }?.let(::serviceName) }.joinToString(" · "))
                    }
                    if (stats.lastPlayed > 0) add(R.string.details_last_played to dateFormat.format(Date(stats.lastPlayed)))
                    rom?.let {
                        add(R.string.details_file to it.relPath)
                        if (it.size > 0) add(R.string.details_size to Formatter.formatShortFileSize(context, it.size))
                    }
                    app?.let { add(R.string.details_package to it.packageName) }
                }
                facts.forEach { (label, value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        ElyText(
                            stringResource(label),
                            size = 8.5f,
                            weight = FontWeight.SemiBold,
                            color = P.ink2,
                            letterSpacing = tracking(0.1f),
                            uppercase = true,
                            modifier = Modifier.width(96.dp).padding(top = 1.dp),
                        )
                        ElyText(value, size = 10.5f, color = P.ink, modifier = Modifier.weight(1f))
                    }
                }

                meta.description?.let {
                    Spacer(Modifier.height(10.dp))
                    ElyText(it, size = 11f, color = P.ink, lineHeightRatio = 1.6f)
                }

                meta.ra?.let { ra ->
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ElyText(
                            stringResource(R.string.achievements_title),
                            size = 9.5f,
                            weight = FontWeight.SemiBold,
                            color = P.ink2,
                            letterSpacing = tracking(0.24f),
                            modifier = Modifier.weight(1f),
                            uppercase = true,
                        )
                        GhostButton(stringResource(R.string.open_in_ra) + " ↗", { vm.openUrl(RetroAchievementsClient.gameUrl(ra.gameId)) })
                    }
                    Spacer(Modifier.height(6.dp))
                    val loaded = (vm.achievements as? AchievementsState.Loaded)?.progress
                    val earned = loaded?.earned ?: ra.earned
                    val total = loaded?.total ?: ra.achievements
                    val points = loaded?.points ?: ra.points
                    val earnedPoints = loaded?.earnedPoints ?: ra.earnedPoints
                    ElyText(stringResource(R.string.achievements_summary, earned, total, earnedPoints, points), size = 10.5f, color = P.ink)
                    if (ra.matchedBy == "title") {
                        ElyText(stringResource(R.string.matched_by_title), size = 9f, color = P.ink2)
                    }
                    AchievementProgressBar(if (total > 0) earned.toFloat() / total else 0f)
                    Spacer(Modifier.height(8.dp))
                    when (val state = vm.achievements) {
                        AchievementsState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            ArcSpinner(size = 16.dp)
                            Spacer(Modifier.width(8.dp))
                            ElyText(stringResource(R.string.achievements_loading), size = 10f, color = P.ink2)
                        }
                        AchievementsState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                            ElyText(stringResource(R.string.achievements_failed), size = 10f, color = P.red, modifier = Modifier.weight(1f))
                            GhostButton(stringResource(R.string.retry), { vm.loadAchievements(key) })
                        }
                        AchievementsState.Idle -> GhostButton(stringResource(R.string.achievements_load), { vm.loadAchievements(key) })
                        is AchievementsState.Loaded -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.progress.achievements.forEach { AchievementRow(it) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementProgressBar(fraction: Float) {
    val skin = LocalSkin.current
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
                .drawBehind { drawRect(accentGradient(skin, 90f, size)) },
        )
    }
}

@Composable
private fun AchievementRow(a: RaAchievement) {
    val unlocked = a.earned || a.earnedHardcore
    Row(Modifier.fillMaxWidth().alpha(if (unlocked) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = RetroAchievementsClient.badgeUrl(a.badge, locked = !unlocked),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            ElyText(a.title, size = 11f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ElyText(a.description, size = 9.5f, color = P.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeightRatio = 1.35f)
        }
        Spacer(Modifier.width(8.dp))
        ElyText("${a.points}", size = 11f, weight = FontWeight.Bold, color = if (a.earnedHardcore) LocalSkin.current.a2 else P.ink2)
    }
}
