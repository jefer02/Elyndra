package com.elyndra.launcher.ui.screens

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

/* ── Acerca de: versión (siete toques = desarrollador), licencias ── */

@Composable
internal fun AboutColumn(vm: ElyndraViewModel) {
    val s = vm.settings
    Column(Modifier.fillMaxWidth()) {
        SectionLabel(stringResource(R.string.section_about))
        SettingsGroup(padding = 0.dp) {
            Column(Modifier.padding(vertical = 4.dp)) {
                LinkRow(
                    title = stringResource(R.string.about_version),
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    onClick = s::tapVersion,
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

@Composable
private fun LinkRow(title: String, value: String, onClick: () -> Unit, desc: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
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
    ThirdParty("Poppins", "Indian Type Foundry", "SIL Open Font License 1.1"),
)

private const val APACHE = "Apache License 2.0"
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

    Box(Modifier.fillMaxSize()) {
        AuroraBackdrop()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = m.pad, end = m.pad, top = m.pad, bottom = 34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(onClick = { vm.go(Screen.Settings) }) { BackChevron() }
                Spacer(Modifier.width(11.dp))
                ElyText(stringResource(R.string.licenses_title), size = 19f, weight = FontWeight.SemiBold, color = P.ink)
            }
            Spacer(Modifier.height(10.dp))
            ElyText(stringResource(R.string.licenses_desc), size = 10.5f, color = P.ink2, lineHeightRatio = 1.45f)

            SectionLabel(stringResource(R.string.licenses_components))
            SettingsGroup {
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
            SettingsGroup {
                ElyText(stringResource(R.string.licenses_mpfb_note), size = 11.5f, weight = FontWeight.SemiBold, color = P.ink, lineHeightRatio = 1.35f)
                Spacer(Modifier.height(2.dp))
                ElyText("MPFB2 · MakeHuman system assets · CC0 1.0", size = 9.5f, color = P.ink2)
            }

            SectionLabel("CMUdict")
            SettingsGroup {
                ElyText(stringResource(R.string.licenses_cmudict_use), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
                Spacer(Modifier.height(10.dp))
                ElyText(cmudict ?: "…", size = 9.5f, color = P.ink, lineHeightRatio = 1.4f)
            }

            Spacer(Modifier.height(14.dp))
            GhostButton(stringResource(R.string.close), { vm.go(Screen.Settings) })
        }
    }
}
