package com.elyndra.launcher.ui.screens

import androidx.compose.runtime.remember
import com.elyndra.launcher.ui.theme.Radii
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.components.readingStop
import com.elyndra.launcher.ui.components.padScrollFallback
import com.elyndra.launcher.ui.components.ScrollViewport
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.PadHint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.BuildConfig
import android.icu.text.DateFormat
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import com.elyndra.launcher.ui.UpdateController
import com.elyndra.launcher.ui.components.AccentButton
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.SettingRow
import com.elyndra.launcher.ui.components.SettingsDivider
import com.elyndra.launcher.ui.components.SwitchRow
import com.elyndra.launcher.ui.theme.MinTouch
import com.elyndra.launcher.ui.theme.TypeScale
import com.elyndra.launcher.update.UpdateDebug
import com.elyndra.launcher.update.UpdateSource
import java.util.Date
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassIconButton
import com.elyndra.launcher.ui.components.SettingsGroup
import com.elyndra.launcher.ui.components.metrics
import com.elyndra.launcher.ui.theme.AuroraBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ── Acerca de: actualizaciones, autor, versión (siete toques = desarrollador), licencia ── */

@Composable
internal fun AboutColumn(vm: ElyndraViewModel) {
    val s = vm.settings
    Column(Modifier.fillMaxWidth()) {
        UpdatesSection(vm)

        SectionLabel(stringResource(R.string.section_about))
        SettingsGroup(padding = 0.dp) {
            Column(Modifier.padding(vertical = 4.dp)) {
                // Los enlaces salen al navegador del sistema.
                LinkRow(
                    title = stringResource(R.string.about_created_by, UpdateSource.AUTHOR_NAME),
                    desc = UpdateSource.AUTHOR_URL.removePrefix("https://"),
                    value = "↗",
                    onClick = { vm.openUrl(UpdateSource.AUTHOR_URL) },
                )
                LinkRow(
                    title = stringResource(R.string.about_source),
                    desc = UpdateSource.REPO_URL.removePrefix("https://"),
                    value = "↗",
                    onClick = { vm.openUrl(UpdateSource.REPO_URL) },
                )
                LinkRow(
                    title = stringResource(R.string.about_version),
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onClick = s::tapVersion,
                )
                LinkRow(
                    title = stringResource(R.string.about_license),
                    desc = stringResource(R.string.about_license_desc),
                    value = stringResource(R.string.about_license_value),
                    onClick = { vm.go(Screen.Licenses) },
                )
                LinkRow(
                    title = stringResource(R.string.licenses_title),
                    value = "›",
                    onClick = { vm.go(Screen.Licenses) },
                )
            }
        }

        if (s.developerOptions) {
            SectionLabel(stringResource(R.string.dev_section))
            SettingsGroup(padding = 0.dp) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    LinkRow(
                        title = stringResource(R.string.dev_voice_sync),
                        desc = stringResource(R.string.dev_voice_sync_desc),
                        value = "›",
                        onClick = { vm.go(Screen.VoiceSync) },
                    )
                    LinkRow(
                        title = stringResource(R.string.dev_hide),
                        value = "",
                        onClick = s::hideDeveloperOptions,
                    )
                }
            }
        }
    }
}

/**
 * Actualizaciones: versión instalada, buscar a mano (con su estado), cuándo
 * se miró por última vez y las dos opciones (automática y betas).
 */
@Composable
private fun UpdatesSection(vm: ElyndraViewModel) {
    val u = vm.updates
    val check = u.check
    val locale = LocalConfiguration.current.locales[0]
    val last = u.lastCheckAt.takeIf { it > 0 }?.let {
        stringResource(R.string.update_last_check, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it)))
    } ?: stringResource(R.string.update_never_checked)
    val status = when (check) {
        UpdateController.Check.Idle -> last
        UpdateController.Check.Checking -> stringResource(R.string.update_state_checking)
        UpdateController.Check.UpToDate -> stringResource(R.string.update_state_up_to_date) + " · " + last
        is UpdateController.Check.Available -> stringResource(R.string.update_state_available, check.release.version?.toString() ?: check.release.tag)
        is UpdateController.Check.Failed -> stringResource(check.reason)
    }

    SectionLabel(stringResource(R.string.section_updates))
    SettingsGroup {
        SettingRow(stringResource(R.string.update_current_version)) {
            ElyText("${u.installedVersion} (${u.installedCode})", size = TypeScale.Body, weight = FontWeight.Medium, color = P.ink2)
        }
        SettingsDivider()
        SettingRow(stringResource(R.string.update_check), description = status) {
            when (check) {
                UpdateController.Check.Checking -> ArcSpinner(size = 20.dp)
                is UpdateController.Check.Available ->
                    AccentButton(stringResource(R.string.update_view), u::showAvailable, Modifier.heightIn(min = MinTouch), fontSize = 12f)
                else -> AccentButton(stringResource(R.string.update_check_now), u::checkNow, Modifier.heightIn(min = MinTouch), fontSize = 12f)
            }
        }
        SettingsDivider()
        SwitchRow(stringResource(R.string.update_auto_title), stringResource(R.string.update_auto_desc), u.autoCheck, u::toggleAutoCheck)
        SwitchRow(stringResource(R.string.update_prerelease_title), stringResource(R.string.update_prerelease_desc), u.prereleases, u::togglePrereleases)
        // Solo existe en builds de depuración (src/debug): en release, label es null.
        UpdateDebug.label?.let { label ->
            Spacer(Modifier.height(6.dp))
            GhostButton(label, u::simulate, Modifier.heightIn(min = MinTouch))
        }
        Spacer(Modifier.height(8.dp))
        ElyText(stringResource(R.string.update_privacy), size = TypeScale.Caption, color = P.ink2, lineHeightRatio = 1.45f)
    }
}

@Composable
private fun LinkRow(title: String, value: String, onClick: () -> Unit, desc: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ElyText(title, size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
            if (desc != null) {
                Spacer(Modifier.height(4.dp))
                ElyText(desc, size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            }
        }
        Spacer(Modifier.width(12.dp))
        ElyText(value, size = 11.5f, weight = FontWeight.Medium, color = P.ink2)
    }
}

/* ── Licencias de código abierto ──────────────────────────────── */

/** Un componente de terceros incluido en la app y su licencia. */
private class ThirdParty(val name: String, val by: String, val license: String)

/**
 * Lo que de verdad va dentro del APK (ver app/build.gradle.kts y
 * gradle/libs.versions.toml, más los recursos y assets propios).
 */
private val COMPONENTS = listOf(
    ThirdParty("AndroidX (Core, Activity, Lifecycle, SplashScreen, Room, WorkManager, Glance, Hilt)", "The Android Open Source Project", APACHE),
    ThirdParty("Jetpack Compose (UI, Foundation, Material 3, Google Fonts)", "The Android Open Source Project", APACHE),
    ThirdParty("AndroidX Media3 (ExoPlayer)", "The Android Open Source Project", APACHE),
    ThirdParty("Kotlin, kotlinx.coroutines, kotlinx.serialization", "JetBrains s.r.o.", APACHE),
    ThirdParty("Dagger / Hilt", "Google LLC", APACHE),
    ThirdParty("OkHttp, Okio", "Square, Inc.", APACHE),
    ThirdParty("Coil", "Coil Contributors", APACHE),
    ThirdParty("SceneView", "SceneView contributors", APACHE),
    ThirdParty("Filament", "Google LLC", APACHE),
    ThirdParty("ONNX Runtime (onnxruntime-android)", "Microsoft Corporation", "MIT License"),
    ThirdParty("ML Kit Translation", "Google LLC", "ML Kit Terms of Service"),
    ThirdParty("sherpa-onnx (Supertonic inference code, ported)", "zengyw / Xiaomi Corporation", APACHE),
    ThirdParty("Supertonic helper.py (text processing, ported)", "Supertone Inc.", "MIT License"),
    ThirdParty("ES-DE (Android emulator configuration: es_find_rules / es_systems)", "Northwestern Software AB, Leon Styhre", "MIT License"),
    ThirdParty("Poppins", "Indian Type Foundry", "SIL Open Font License 1.1"),
    ThirdParty("Cinzel", "The Cinzel Project Authors", "SIL Open Font License 1.1"),
)

private const val APACHE = "Apache License 2.0"
private const val SUPERTONIC_LICENSE_URL = "https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE"
private const val CMUDICT_LICENSE = "lipsync/CMUDICT_LICENSE.txt"

@Composable
fun LicensesScreen(vm: ElyndraViewModel) {
    val m = metrics()
    val context = LocalContext.current
    // El aviso de CMUdict se lee tal cual del asset que va con el diccionario.
    val cmudict by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(CMUDICT_LICENSE).bufferedReader().use { it.readText() } }.getOrNull()
        }
    }

    // Es texto para leer: con mando, cada bloque es una parada y los largos se
    // recorren a pasos (ver readingStop); arriba y abajo desplazan si no hay más.
    val scroll = rememberScrollState()
    val viewport = remember { ScrollViewport() }
    val skin = LocalSkin.current
    val stop = RoundedCornerShape(Radii.m)
    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .then(viewport.modifier)
                .padScrollFallback(scroll)
                .verticalScroll(scroll)
                .padding(start = m.pad, end = m.pad, top = m.pad, bottom = 34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(onClick = { vm.go(Screen.Settings) }, size = com.elyndra.launcher.ui.theme.MinTouch, cornerRadius = 15.dp, contentDescription = stringResource(R.string.hint_back)) { BackChevron() }
                Spacer(Modifier.width(11.dp))
                ElyText(stringResource(R.string.licenses_title), size = 19f, weight = FontWeight.SemiBold, color = P.ink)
            }
            Spacer(Modifier.height(10.dp))
            ElyText(stringResource(R.string.licenses_desc), size = 10.5f, color = P.ink2, lineHeightRatio = 1.45f)

            // La licencia de la propia app (LICENSE en la raíz del repositorio).
            SectionLabel("Elyndra")
            SettingsGroup(Modifier.readingStop(scroll, viewport, stop, skin.a2)) {
                ElyText(stringResource(R.string.licenses_app_copyright, UpdateSource.AUTHOR_NAME), size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, lineHeightRatio = 1.35f)
                Spacer(Modifier.height(4.dp))
                ElyText(stringResource(R.string.licenses_app_terms), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            }

            SectionLabel(stringResource(R.string.licenses_components))
            SettingsGroup(Modifier.readingStop(scroll, viewport, stop, skin.a2)) {
                COMPONENTS.forEachIndexed { i, c ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    ElyText(c.name, size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, lineHeightRatio = 1.35f)
                    Spacer(Modifier.height(2.dp))
                    ElyText("${c.by} · ${c.license}", size = 9.5f, color = P.ink2)
                }
                Spacer(Modifier.height(12.dp))
                ElyText(stringResource(R.string.licenses_apache_notice), size = 9.5f, color = P.ink2, lineHeightRatio = 1.45f)
            }

            SectionLabel(stringResource(R.string.licenses_assets))
            SettingsGroup(Modifier.readingStop(scroll, viewport, stop, skin.a2)) {
                ElyText(stringResource(R.string.licenses_mpfb_note), size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, lineHeightRatio = 1.35f)
                Spacer(Modifier.height(2.dp))
                ElyText("MPFB2 · MakeHuman system assets · CC0 1.0", size = 9.5f, color = P.ink2)
            }

            // Pesos de Supertonic 3 (OpenRAIL-M): sus restricciones de uso pasan a quien usa la voz.
            SectionLabel(stringResource(R.string.licenses_voice_title))
            SettingsGroup(Modifier.readingStop(scroll, viewport, stop, skin.a2)) {
                ElyText("Supertonic 3 · Supertone Inc. · BigScience OpenRAIL-M", size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, lineHeightRatio = 1.35f)
                Spacer(Modifier.height(4.dp))
                ElyText(stringResource(R.string.licenses_voice_notice), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
                Spacer(Modifier.height(8.dp))
                GhostButton(stringResource(R.string.licenses_voice_open), { vm.openUrl(SUPERTONIC_LICENSE_URL) }, Modifier.heightIn(min = MinTouch))
            }

            SectionLabel("CMUdict")
            SettingsGroup(Modifier.readingStop(scroll, viewport, stop, skin.a2)) {
                ElyText(stringResource(R.string.licenses_cmudict_use), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
                Spacer(Modifier.height(10.dp))
                ElyText(cmudict ?: "…", size = 9.5f, color = P.ink, lineHeightRatio = 1.4f)
            }

            Spacer(Modifier.height(14.dp))
            GhostButton(stringResource(R.string.close), { vm.go(Screen.Settings) })
            PadHints(hints = LICENSES_HINTS, visible = vm.input.gamepadPresent, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

private val LICENSES_HINTS = listOf(PadHint("A", R.string.hint_select), PadHint("B", R.string.hint_back))
