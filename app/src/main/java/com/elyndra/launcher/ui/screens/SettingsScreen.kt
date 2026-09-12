package com.elyndra.launcher.ui.screens

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.elyndra.launcher.R
import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.TINTS
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.SortMode
import com.elyndra.launcher.ui.components.AccentSlider
import com.elyndra.launcher.ui.components.AccentSwitch
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.CssGrid
import com.elyndra.launcher.ui.components.CtaButton
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.GlassPanel
import com.elyndra.launcher.ui.components.Pill
import com.elyndra.launcher.ui.components.Swatch
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.AuroraBackdrop
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animRiseSheet
import com.elyndra.launcher.ui.theme.cssLinearGradient
import com.elyndra.launcher.ui.theme.glass

@Composable
fun SettingsScreen(vm: ElyndraViewModel) {
    val m = metrics()

    Box(Modifier.fillMaxSize().animRiseSheet(key = Screen.Settings)) {
        AuroraBackdrop()

        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = m.pad, end = m.pad, top = m.pad, bottom = 34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(onClick = { vm.go(Screen.Library) }) { BackChevron() }
                Spacer(Modifier.width(11.dp))
                ElyText(stringResource(R.string.settings_title), size = 19f, weight = FontWeight.SemiBold, color = P.ink)
            }

            CssGrid(
                columns = if (m.landscape) 2 else 1,
                horizontalGap = 16.dp,
                verticalGap = 0.dp,
                items = listOf(
                    { AppearanceColumn(vm) },
                    { MetadataColumn(vm) },
                ),
            )
        }
    }
}

/* ── Columna izquierda: acento, cristal, idioma y biblioteca ───── */

@Composable
private fun AppearanceColumn(vm: ElyndraViewModel) {
    val skin = LocalSkin.current
    val s = vm.settings
    val activity = LocalContext.current.findActivity()
    // SAF: se queda el permiso del vídeo para que siga ahí tras reiniciar.
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        s.onVideoPicked(uri)
    }

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.section_theme))
        GlassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ElyText(stringResource(R.string.dark_mode), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
                    Spacer(Modifier.height(4.dp))
                    ElyText(stringResource(R.string.dark_mode_desc), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
                }
                Spacer(Modifier.width(12.dp))
                AccentSwitch(s.darkMode, s::toggleDark)
            }
        }

        SectionLabel(stringResource(R.string.section_video_bg))
        GlassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ElyText(stringResource(R.string.video_bg_title), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
                    Spacer(Modifier.height(4.dp))
                    ElyText(stringResource(R.string.video_bg_desc), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
                }
                Spacer(Modifier.width(12.dp))
                AccentSwitch(s.videoBgEnabled, s::toggleVideoBg)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ElyText(
                    s.videoBgUri?.let { Uri.parse(it).lastPathSegment ?: it } ?: stringResource(R.string.video_bg_none),
                    size = 9.5f,
                    color = P.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // `onClick` va posicional: en GhostButton el último parámetro es el
                // modifier, así que una lambda al final no sería el clic.
                GhostButton(
                    stringResource(if (s.videoBgUri == null) R.string.video_bg_choose else R.string.video_bg_change),
                    { videoPicker.launch(arrayOf("video/*")) },
                )
                if (s.videoBgUri != null) {
                    Spacer(Modifier.width(6.dp))
                    GhostButton(stringResource(R.string.remove), s::clearVideoBg)
                }
            }
            if (s.videoBgUri != null) {
                SliderRow(
                    stringResource(R.string.video_bg_opacity),
                    "${s.videoBgOpacity} %",
                    s.videoBgOpacity,
                    10..100,
                    s::updateVideoBgOpacity,
                )
            }
        }

        SectionLabel(stringResource(R.string.section_accent))
        GlassPanel {
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
        GlassPanel {
            // Vista previa: una franja de color con una lámina de cristal encima.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(70.dp)
                    .clip(RoundedCornerShape(16.dp))
                    // `linear-gradient(120deg, a1, verde 55%, a2)`
                    .drawBehind {
                        drawRect(
                            cssLinearGradient(
                                120f,
                                listOf(skin.a1, P.green, skin.a2),
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
        }

        SectionLabel(stringResource(R.string.section_language))
        WrapRow(gap = 7.dp) {
            AppLocale.SUPPORTED.forEach { (tag, name) ->
                Pill(name, s.lang == tag, {
                    if (s.lang != tag) {
                        s.onLanguageChosen(tag)
                        activity?.let { AppLocale.set(it, tag) }
                    }
                })
            }
        }

        SectionLabel(stringResource(R.string.sort_by))
        WrapRow(gap = 7.dp) {
            SortMode.entries.forEach { mode ->
                Pill(stringResource(mode.label), s.sortMode == mode, { s.setSort(mode) })
            }
        }

        SectionLabel(stringResource(R.string.section_library))
        GlassPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ElyText(stringResource(R.string.rescan_all), size = 12f, weight = FontWeight.SemiBold, color = P.ink)
                    Spacer(Modifier.height(2.dp))
                    ElyText(
                        pluralStringResource(R.plurals.folders_count, vm.library.folders.size, vm.library.folders.size),
                        size = 9.5f,
                        color = P.ink2,
                    )
                }
                if (s.rescanning) ArcSpinner(size = 18.dp) else GhostButton(stringResource(R.string.rescan), s::rescanAll)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    ElyText(stringResource(R.string.clear_images), size = 12f, weight = FontWeight.SemiBold, color = P.ink)
                    Spacer(Modifier.height(2.dp))
                    val context = LocalContext.current
                    ElyText(
                        if (s.mediaBytes >= 0) stringResource(R.string.images_size, Formatter.formatShortFileSize(context, s.mediaBytes)) else "…",
                        size = 9.5f,
                        color = P.ink2,
                    )
                }
                GhostButton(stringResource(R.string.remove), s::clearImages)
            }
        }
    }
}

/* ── Columna derecha: APIs de metadatos ────────────────────────── */

@Composable
private fun MetadataColumn(vm: ElyndraViewModel) {
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

    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.section_metadata_apis))
        ApiPanels(vm)

        GlassPanel(Modifier.padding(top = 9.dp), padding = 0.dp, cornerRadius = 16.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ElyText(stringResource(R.string.auto_meta_title), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
                    Spacer(Modifier.height(4.dp))
                    ElyText(stringResource(R.string.auto_meta_desc), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
                }
                Spacer(Modifier.width(12.dp))
                AccentSwitch(s.autoMeta, s::toggleAutoMeta)
            }
        }

        if (p.running) {
            GlassPanel(Modifier.padding(top = 9.dp)) {
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
            GlassPanel(Modifier.padding(top = 9.dp)) {
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
}

/* ── Piezas ───────────────────────────────────────────────────── */

@Composable
internal fun SectionLabel(text: String) {
    ElyText(
        text,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp),
        size = 9.5f,
        weight = FontWeight.SemiBold,
        color = P.ink2,
        letterSpacing = tracking(0.26f),
        uppercase = true,
    )
}

/** `grid-template-columns: repeat(5,1fr)` con 8dp de hueco. */
@Composable
private fun SwatchGrid(items: List<@Composable () -> Unit>) {
    CssGrid(columns = 5, horizontalGap = 8.dp, verticalGap = 8.dp, items = items)
}

@Composable
private fun SliderRow(
    label: String,
    value: String,
    current: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    topPadding: Dp = 10.dp,
) {
    Column(Modifier.fillMaxWidth().padding(top = topPadding)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ElyText(label, size = 11.5f, weight = FontWeight.Medium, color = P.ink)
            Spacer(Modifier.weight(1f))
            ElyText(value, size = 11.5f, weight = FontWeight.Medium, color = P.ink2)
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
