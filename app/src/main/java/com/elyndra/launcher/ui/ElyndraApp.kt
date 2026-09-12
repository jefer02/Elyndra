package com.elyndra.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.components.ActionSheetView
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.ElyDialogView
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.GameIcon
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.components.ToastView
import com.elyndra.launcher.ui.components.VideoBackdrop
import com.elyndra.launcher.ui.components.tracking
import com.elyndra.launcher.ui.screens.AddScreen
import com.elyndra.launcher.ui.screens.ArtPickerSheet
import com.elyndra.launcher.ui.screens.DetailsSheet
import com.elyndra.launcher.ui.screens.FolderScreen
import com.elyndra.launcher.ui.screens.LibraryScreen
import com.elyndra.launcher.ui.screens.LucyScreen
import com.elyndra.launcher.ui.screens.SettingsScreen
import com.elyndra.launcher.ui.theme.ElyndraTheme
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.animFadeIn
import com.elyndra.launcher.ui.theme.animPopIn
import com.elyndra.launcher.ui.theme.drawArcSpinner
import com.elyndra.launcher.ui.theme.sheenBrush
import com.elyndra.launcher.ui.theme.sheenProgress
import com.elyndra.launcher.ui.theme.spinAngle

/**
 * Raíz de la app.
 *
 * En el diseño, la orientación se elegía con dos botones porque todo vivía
 * dentro de un marco de móvil dibujado en una página. Aquí el marco es el
 * dispositivo de verdad, así que la bandera `L` (el layout ancho de 892×412)
 * la decide la orientación real de la pantalla.
 */
@Composable
fun ElyndraApp(vm: ElyndraViewModel) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp

    ElyndraTheme(skin = vm.settings.skin, landscape = landscape) {
        // Atrás cierra, por orden: diálogo, hoja, ficha, pantalla y buscador.
        BackHandler(enabled = vm.canGoBack) { vm.back() }

        Box(Modifier.fillMaxSize().background(P.paper)) {
            // Fondo animado de la app (solo de Elyndra, no del sistema), debajo de todo.
            val s = vm.settings
            if (s.videoBgEnabled) {
                s.videoBgUri?.let { uri ->
                    VideoBackdrop(uri, s.videoBgOpacity / 100f, Modifier.fillMaxSize())
                }
            }

            // Las barras van ocultas (pantalla completa), así que sus insets son 0;
            // se mantiene el del recorte de pantalla para que en un móvil con muesca
            // el contenido no quede debajo.
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                // Las métricas reparten este alto entre hero y cards (móvil y tableta).
                CompositionLocalProvider(LocalScreenSize provides DpSize(maxWidth, maxHeight)) {
                    when (vm.screen) {
                        Screen.Library -> LibraryScreen(vm)
                        Screen.Folder -> FolderScreen(vm)
                        Screen.Add -> AddScreen(vm)
                        Screen.Settings -> SettingsScreen(vm)
                        Screen.Lucy -> LucyScreen(vm)
                    }
                }
            }

            vm.detailsKey?.let { DetailsSheet(vm, it) }
            vm.artPicker?.let { ArtPickerSheet(vm, it) }
            vm.sheet?.let { ActionSheetView(it, onDismiss = vm::dismissSheet) }
            vm.dialog?.let { ElyDialogView(it, onDismiss = vm::dismissDialog) }
            vm.launching?.let { LaunchOverlay(it, landscape) }
            vm.toast?.let {
                ToastView(
                    it,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(bottom = 76.dp),
                )
            }
        }
    }
}

/**
 * Velo de lanzamiento: carátula grande con destello, título, vía y spinner.
 * Elyndra no ejecuta nada — solo enseña a dónde va el título antes de ceder.
 */
@Composable
private fun LaunchOverlay(launch: Launch, landscape: Boolean) {
    val skin = LocalSkin.current
    val sheen = sheenProgress()
    val angle = spinAngle(1000)

    Column(
        Modifier
            .fillMaxSize()
            .animFadeIn(280, key = launch.title)
            .background(P.shade.copy(alpha = 0.72f))
            .windowInsetsPadding(WindowInsets.systemBars),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(
                    width = if (landscape) 92.dp else 124.dp,
                    height = if (landscape) 122.dp else 164.dp,
                )
                .animPopIn(500, key = launch.title)
                .shadow(24.dp, RoundedCornerShape(16.dp), clip = false, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
        ) {
            ArtImage(launch.coverPath, launch.pairIndex, Modifier.fillMaxSize())
            if (launch.coverPath == null && (launch.packageName != null || launch.iconPath != null)) {
                GameIcon(launch.iconPath, launch.packageName, Modifier.fillMaxSize(), ContentScale.Crop)
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.44f)
                    .graphicsLayer { translationX = size.width / 0.44f * sheen }
                    .drawBehind { drawRect(sheenBrush(size)) },
            )
        }

        Spacer(Modifier.height(16.dp))
        ElyText(
            launch.title,
            size = 17f,
            weight = FontWeight.SemiBold,
            color = Color.White,
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(7.dp))
        ElyText(
            launch.via.resolve(),
            size = 9.5f,
            weight = FontWeight.SemiBold,
            color = skin.a1,
            letterSpacing = tracking(0.18f),
            align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .size(26.dp)
                .rotate(angle)
                .drawBehind {
                    drawArcSpinner(skin.a1, 2.dp, 45f)
                },
        )
    }
}
