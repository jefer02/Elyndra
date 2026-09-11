package com.elyndra.launcher.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.ServiceState
import com.elyndra.launcher.ui.SettingsController
import com.elyndra.launcher.ui.SettingsController.Field
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassPanel
import com.elyndra.launcher.ui.components.inputStyle
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.LocalSkin

/* ─────────────────────────────────────────────────────────────
   Paneles de credenciales de los cuatro servicios de metadatos.
   Cada uno: estado, qué aporta, campos, "Probar conexión", enlace
   a la página donde se consiguen las claves y una ayuda breve.
   ───────────────────────────────────────────────────────────── */

fun serviceName(s: Service): String = when (s) {
    Service.ScreenScraper -> "ScreenScraper"
    Service.Igdb -> "IGDB"
    Service.SteamGridDb -> "SteamGridDB"
    Service.RetroAchievements -> "RetroAchievements"
}

@Composable
fun ApiPanels(vm: ElyndraViewModel) {
    val s = vm.settings
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        ApiPanel(vm, Service.ScreenScraper, R.string.ss_desc, R.string.ss_help, R.string.test_connection) {
            CredentialField(stringResource(R.string.field_username), s.value(Field.SsUser), secret = false) { s.update(Field.SsUser, it) }
            Spacer(Modifier.height(8.dp))
            CredentialField(stringResource(R.string.field_password), s.value(Field.SsPassword), secret = true) { s.update(Field.SsPassword, it) }
            Spacer(Modifier.height(8.dp))
            val expanded = s.showSsDev || !s.builtInSsDev
            Row(
                Modifier.clickable { s.toggleSsDev() }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ElyText(
                    (if (expanded) "▾ " else "▸ ") + stringResource(R.string.ss_dev_toggle),
                    size = 10f,
                    weight = FontWeight.SemiBold,
                    color = LocalSkin.current.a2,
                )
            }
            if (expanded) {
                ElyText(
                    stringResource(if (s.builtInSsDev) R.string.ss_dev_builtin else R.string.ss_dev_missing),
                    size = 9.5f,
                    color = P.ink2,
                    lineHeightRatio = 1.45f,
                )
                Spacer(Modifier.height(8.dp))
                CredentialField(stringResource(R.string.field_dev_id), s.value(Field.SsDevId), secret = false) { s.update(Field.SsDevId, it) }
                Spacer(Modifier.height(8.dp))
                CredentialField(stringResource(R.string.field_dev_password), s.value(Field.SsDevPassword), secret = true) { s.update(Field.SsDevPassword, it) }
            }
        }
        ApiPanel(vm, Service.Igdb, R.string.igdb_desc, R.string.igdb_help, R.string.connect) {
            CredentialField(stringResource(R.string.field_client_id), s.value(Field.IgdbClientId), secret = false) { s.update(Field.IgdbClientId, it) }
            Spacer(Modifier.height(8.dp))
            CredentialField(stringResource(R.string.field_client_secret), s.value(Field.IgdbClientSecret), secret = true) { s.update(Field.IgdbClientSecret, it) }
        }
        ApiPanel(vm, Service.SteamGridDb, R.string.sgdb_desc, R.string.sgdb_help, R.string.test_connection) {
            CredentialField(stringResource(R.string.field_api_key), s.value(Field.SgdbKey), secret = true) { s.update(Field.SgdbKey, it) }
        }
        ApiPanel(vm, Service.RetroAchievements, R.string.ra_desc, R.string.ra_help, R.string.test_connection) {
            CredentialField(stringResource(R.string.field_username), s.value(Field.RaUser), secret = false) { s.update(Field.RaUser, it) }
            Spacer(Modifier.height(8.dp))
            CredentialField(stringResource(R.string.field_web_api_key), s.value(Field.RaKey), secret = true) { s.update(Field.RaKey, it) }
        }
    }
}

@Composable
private fun ApiPanel(
    vm: ElyndraViewModel,
    service: Service,
    @StringRes desc: Int,
    @StringRes help: Int,
    @StringRes testLabel: Int,
    fields: @Composable ColumnScope.() -> Unit,
) {
    val state = vm.settings.state(service)
    GlassPanel(padding = 0.dp, cornerRadius = 16.dp) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(state.status)
                Spacer(Modifier.width(8.dp))
                ElyText(serviceName(service), modifier = Modifier.weight(1f), size = 12.5f, weight = FontWeight.SemiBold, color = P.ink)
                StatusBadge(state.status)
            }
            Spacer(Modifier.height(5.dp))
            ElyText(stringResource(desc), size = 10f, color = P.ink2, lineHeightRatio = 1.45f)
            Spacer(Modifier.height(9.dp))
            fields()
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.status == ServiceState.Status.Checking) {
                    ArcSpinner(size = 18.dp)
                } else {
                    GhostButton(stringResource(testLabel), { vm.settings.test(service) })
                }
                Spacer(Modifier.width(8.dp))
                GhostButton(stringResource(R.string.get_credentials) + " ↗", { vm.openUrl(SettingsController.helpUrl(service)) })
            }
            state.detail?.let { detail ->
                Spacer(Modifier.height(8.dp))
                ElyText(
                    detail.resolve(),
                    size = 9.5f,
                    weight = FontWeight.Medium,
                    color = if (state.status == ServiceState.Status.Error) P.red else P.ink2,
                    lineHeightRatio = 1.4f,
                )
            }
            Spacer(Modifier.height(8.dp))
            ElyText(stringResource(help), size = 9f, color = P.ink2.copy(alpha = 0.85f), lineHeightRatio = 1.45f)
        }
    }
}

@Composable
private fun StatusDot(status: ServiceState.Status) {
    val color = when (status) {
        ServiceState.Status.Connected -> P.green
        ServiceState.Status.Error -> P.red
        ServiceState.Status.Checking, ServiceState.Status.Unverified -> LocalSkin.current.a1
        ServiceState.Status.Unconfigured -> P.ink.copy(alpha = 0.18f)
    }
    Box(
        Modifier
            .size(8.dp)
            .then(
                if (status == ServiceState.Status.Connected) {
                    Modifier.shadow(5.dp, CircleShape, clip = false, ambientColor = P.green, spotColor = P.green)
                } else Modifier,
            )
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun StatusBadge(status: ServiceState.Status) {
    val (label, bg) = when (status) {
        ServiceState.Status.Connected -> R.string.status_connected to P.green.copy(alpha = 0.45f)
        ServiceState.Status.Error -> R.string.status_error to P.red.copy(alpha = 0.18f)
        ServiceState.Status.Checking -> R.string.status_checking to P.ink.copy(alpha = 0.06f)
        ServiceState.Status.Unverified -> R.string.status_unverified to P.ink.copy(alpha = 0.06f)
        ServiceState.Status.Unconfigured -> R.string.status_not_configured to P.ink.copy(alpha = 0.06f)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        ElyText(
            stringResource(label),
            size = 7.5f,
            weight = FontWeight.SemiBold,
            color = if (status == ServiceState.Status.Connected) P.ink else P.ink2,
            letterSpacing = tracking(0.12f),
            uppercase = true,
        )
    }
}

/** Campo de credencial; los secretos van ocultos con opción de mostrarlos. */
@Composable
private fun CredentialField(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    val skin = LocalSkin.current
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ElyText(label, size = 8.5f, weight = FontWeight.SemiBold, color = P.ink2, letterSpacing = tracking(0.08f))
        Spacer(Modifier.height(3.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.7f))
                .border(1.dp, P.ink.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = inputStyle(11f),
                cursorBrush = SolidColor(skin.a2),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.weight(1f),
            )
            if (secret && value.isNotEmpty()) {
                ElyText(
                    stringResource(if (reveal) R.string.hide else R.string.show),
                    size = 8.5f,
                    weight = FontWeight.SemiBold,
                    color = skin.a2,
                    uppercase = true,
                    modifier = Modifier
                        .clickable { reveal = !reveal }
                        .padding(start = 8.dp),
                )
            }
        }
    }
}
