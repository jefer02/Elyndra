package com.elyndra.launcher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ActionSheetSpec
import com.elyndra.launcher.ui.DialogSpec
import com.elyndra.launcher.ui.SheetAction
import com.elyndra.launcher.ui.UiText
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.accentGradient
import com.elyndra.launcher.ui.theme.animFadeIn
import com.elyndra.launcher.ui.theme.animFadeUp
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.animRiseSheet
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.glass

/** Clic sin ondulación, para que las capas no dejen pasar toques al fondo. */
@Composable
fun Modifier.consumeClicks(onClick: () -> Unit = {}): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/** Velo oscuro a pantalla completa; tocar fuera cierra. */
@Composable
fun ScrimLayer(
    onDismiss: (() -> Unit)?,
    alignment: Alignment,
    key: Any,
    alpha: Float = 0.45f,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .animFadeIn(200, key = key)
            .background(P.shade.copy(alpha = alpha))
            .consumeClicks { onDismiss?.invoke() }
            .windowInsetsPadding(WindowInsets.systemBars),
        contentAlignment = alignment,
        content = content,
    )
}

@Composable
fun ElyDialogView(spec: DialogSpec, onDismiss: () -> Unit) {
    ScrimLayer(onDismiss = onDismiss, alignment = Alignment.Center, key = spec) {
        Column(
            Modifier
                .padding(horizontal = 26.dp)
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .animPopIn(320, key = spec)
                .glass(RoundedCornerShape(22.dp), solid = true)
                .consumeClicks()
                .padding(18.dp),
        ) {
            ElyText(spec.title.resolve(), size = 15f, weight = FontWeight.SemiBold, color = P.ink)
            Spacer(Modifier.height(8.dp))
            ElyText(spec.message.resolve(), size = 12f, color = P.ink2, lineHeightRatio = 1.5f)
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                spec.extra?.let { b -> GhostButton(b.label.resolve(), { onDismiss(); b.action() }) }
                Spacer(Modifier.weight(1f))
                spec.dismiss?.let { b -> GhostButton(b.label.resolve(), { onDismiss(); b.action() }) }
                AccentButton(spec.confirm.label.resolve(), { onDismiss(); spec.confirm.action() }, fontSize = 12f)
            }
        }
    }
}

@Composable
fun ActionSheetView(spec: ActionSheetSpec, onDismiss: () -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.78f).dp
    ScrimLayer(onDismiss = onDismiss, alignment = Alignment.BottomCenter, key = spec) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(10.dp)
                .animRiseSheet(key = spec)
                .glass(RoundedCornerShape(24.dp), solid = true)
                .consumeClicks()
                .padding(top = 16.dp, bottom = 8.dp),
        ) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                ElyText(spec.title.resolve(), size = 14.5f, weight = FontWeight.SemiBold, color = P.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                spec.subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    ElyText(it.resolve(), size = 9.5f, color = P.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                items(spec.actions) { action ->
                    SheetRow(action) {
                        onDismiss()
                        action.action()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(action: SheetAction, onClick: () -> Unit) {
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (action.dimmed) 0.55f else 1f)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ElyText(
                action.label.resolve(),
                size = 12.5f,
                weight = if (action.selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (action.destructive) P.red else P.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            action.detail?.let {
                Spacer(Modifier.height(1.dp))
                ElyText(it.resolve(), size = 9.5f, color = P.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action.selected) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .drawBehind { drawRect(accentGradient(skin, 145f, size)) },
            )
        }
    }
}

@Composable
fun ToastView(text: UiText, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = 24.dp)
            .animFadeUp(260, key = text)
            .darkGlass(RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        ElyText(text.resolve(), size = 11.5f, weight = FontWeight.Medium, color = Color.White, maxLines = 3)
    }
}
