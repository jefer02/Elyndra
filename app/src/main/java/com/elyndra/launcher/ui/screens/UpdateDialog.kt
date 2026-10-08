package com.elyndra.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.UpdateController
import com.elyndra.launcher.ui.UpdateController.Dialog
import com.elyndra.launcher.ui.components.AccentButton
import com.elyndra.launcher.ui.components.ArcSpinner
import com.elyndra.launcher.ui.components.Backdrop
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GhostButton
import com.elyndra.launcher.ui.components.GlassCard
import com.elyndra.launcher.ui.components.LocalPadInput
import com.elyndra.launcher.ui.components.PadHint
import com.elyndra.launcher.ui.components.PadHints
import com.elyndra.launcher.ui.components.ScrimLayer
import com.elyndra.launcher.ui.components.consumeClicks
import com.elyndra.launcher.ui.components.overlayEmerge
import com.elyndra.launcher.ui.components.padFocus
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.MinTouch

/**
 * El diálogo de actualización (ver [UpdateController]): la versión nueva con
 * sus notas, la descarga con su barra, el permiso de instalar y los errores.
 *
 * Va en la capa de diálogos (mismo velo y entrada que [com.elyndra.launcher.ui.components.ElyDialogView]),
 * con botones de 48 dp y el principal señalado al abrirlo con mando.
 */
@Composable
fun UpdateDialogView(vm: ElyndraViewModel, d: Dialog, open: Boolean, progress: State<Float>) {
    val c = vm.updates
    val reduced = LocalReducedMotion.current
    ScrimLayer(onDismiss = c::dismiss, alignment = Alignment.Center, open = open, progress = progress, z = Backdrop.Z_DIALOG, light = true) {
        GlassCard(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .widthIn(max = 440.dp)
                .overlayEmerge(progress, reduced)
                .consumeClicks(),
            cornerRadius = 24.dp,
            frost = 0.82f,
            glowColor = if (d is Dialog.Failed) P.red else null,
            glow = if (d is Dialog.Failed) 1f else 0.35f,
            elevation = 24.dp,
            padding = PaddingValues(18.dp),
        ) {
            val version = d.release.version?.toString() ?: d.release.tag
            val title = when (d) {
                is Dialog.Prompt -> stringResource(R.string.update_available_title, version)
                is Dialog.Permission -> stringResource(R.string.update_permission_title)
                is Dialog.Downloading -> stringResource(R.string.update_downloading_title, version)
                is Dialog.Installing -> stringResource(R.string.update_installing_title)
                is Dialog.Failed -> stringResource(R.string.update_failed_title)
            }
            ElyText(title, size = 15.5f, weight = FontWeight.Bold, color = P.ink)
            Spacer(Modifier.height(8.dp))
            when (d) {
                is Dialog.Prompt -> PromptBody(c, d)
                is Dialog.Permission -> Message(stringResource(R.string.update_permission_msg))
                is Dialog.Downloading -> DownloadBody(c.progress)
                is Dialog.Installing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    ArcSpinner(size = 16.dp)
                    Spacer(Modifier.width(10.dp))
                    Message(stringResource(R.string.update_installing_msg))
                }
                is Dialog.Failed -> Message(stringResource(d.reason))
            }
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // En pantalla, el principal a la derecha; para el mando cuentan en el orden de la lista.
                val buttons = c.buttons(d)
                buttons.withIndex().reversed().forEach { (i, b) ->
                    Box(Modifier.padFocus(c.focus == i, radius = if (b.primary) 17.dp else 13.dp)) {
                        val label = stringResource(b.label)
                        if (b.primary) {
                            AccentButton(label, b.action, Modifier.heightIn(min = MinTouch), fontSize = 12f)
                        } else {
                            GhostButton(label, b.action, Modifier.heightIn(min = MinTouch))
                        }
                    }
                }
            }
            PadHints(
                hints = HINTS,
                visible = LocalPadInput.current?.gamepadPresent == true,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun PromptBody(c: UpdateController, d: Dialog.Prompt) {
    Message(stringResource(R.string.update_available_msg, c.installedVersion))
    Spacer(Modifier.height(10.dp))
    // Las notas, recortadas (ver UpdateLogic.trimNotes); si aun así no caben, se desplazan.
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(P.ink.copy(alpha = 0.05f))
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        ElyText(d.notes.ifBlank { stringResource(R.string.update_notes_empty) }, size = 11f, color = P.ink, lineHeightRatio = 1.5f)
    }
}

@Composable
private fun DownloadBody(fraction: Float) {
    val skin = LocalSkin.current
    val pct = (fraction * 100).toInt().coerceIn(0, 100)
    Message(stringResource(R.string.update_downloading_msg))
    Spacer(Modifier.height(12.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(P.ink.copy(alpha = 0.10f))
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.coerceIn(0f, 1f), 0f..1f) },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(skin.a1),
        )
    }
    Spacer(Modifier.height(6.dp))
    ElyText("$pct %", size = 10.5f, weight = FontWeight.Medium, color = P.ink2)
}

@Composable
private fun Message(text: String) {
    ElyText(text, size = 12f, color = P.ink2, lineHeightRatio = 1.5f)
}

private val HINTS = listOf(PadHint("A", R.string.hint_select), PadHint("B", R.string.cancel))
