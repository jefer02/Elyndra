package com.elyndra.launcher.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.Springs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.components.GameActionOverlayContainer
import com.elyndra.launcher.ui.components.ArtImage
import com.elyndra.launcher.ui.components.BootSplash
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
import com.elyndra.launcher.ui.screens.MashaScreen
import com.elyndra.launcher.ui.screens.SettingsScreen
import com.elyndra.launcher.ui.theme.ElyndraTheme
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
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

    // "Elegir de la galería": el selector solo se puede abrir desde un
    // composable, así que el ViewModel deja aquí la petición y este efecto la
    // lanza. Cancelar devuelve null y el ViewModel lo trata como "nada".
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::onMediaPicked)
    val request = vm.mediaRequest
    LaunchedEffect(request) {
        request?.let { mediaPicker.launch(it.mimeTypes.toTypedArray()) }
    }

    ElyndraTheme(skin = vm.settings.skin, landscape = landscape) {
        // Atrás cierra, por orden: diálogo, hoja, ficha, pantalla y buscador.
        // Atrás predictivo (Android 13+): mientras el gesto dura, la pantalla
        // que se abandona se encoge y se apaga siguiendo al dedo; si el gesto
        // se cancela, vuelve a su sitio con un muelle.
        val backProgress = remember { Animatable(0f) }
        val backScope = rememberCoroutineScope()
        val reduced = LocalReducedMotion.current
        PredictiveBackHandler(enabled = vm.canGoBack) { events ->
            try {
                events.collect { e -> if (!reduced) backProgress.snapTo(e.progress) }
                vm.back()
                backScope.launch { backProgress.snapTo(0f) }
            } catch (c: CancellationException) {
                backScope.launch { backProgress.animateTo(0f, Springs.snappy()) }
                throw c
            }
        }
        val screenBack = vm.screen != Screen.Library && vm.dialog == null && vm.detailsKey == null && vm.sheet == null

        Box(Modifier.fillMaxSize().background(P.paper)) {
            // Fondo de la app (solo de Elyndra, no del sistema), debajo de todo:
            // el vídeo en bucle o la imagen fija que el usuario haya elegido.
            val s = vm.settings
            if (s.backgroundEnabled) {
                s.backgroundUri?.let { uri ->
                    val opacity = s.backgroundOpacity / 100f
                    if (s.backgroundIsVideo) {
                        VideoBackdrop(uri, opacity, Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().alpha(opacity),
                        )
                    }
                }
            }

            // Las barras van ocultas (pantalla completa), así que sus insets son 0;
            // se mantiene el del recorte de pantalla para que en un móvil con muesca
            // el contenido no quede debajo.
            // La pantalla entera va dentro del contenedor del menú de
            // acciones: es él quien la oscurece y la desenfoca cuando el menú
            // está abierto, y quien monta el overlay por encima.
            GameActionOverlayContainer(
                isOverlayVisible = vm.sheet != null,
                onOverlayDismissed = vm::dismissSheet,
                onActionClicked = { action ->
                    vm.dismissSheet()
                    action.action()
                },
                spec = vm.sheet,
                origin = vm.sheetOrigin,
                focus = vm.input.sheetFocus,
                dimForOtherLayer = vm.dialog != null,
            ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                // Las métricas reparten este alto entre hero y cards (móvil y tableta).
                CompositionLocalProvider(LocalScreenSize provides DpSize(maxWidth, maxHeight)) {
                    // Cambiar de pantalla se ve: la que entra llega deslizando
                    // desde el lado al que se va, y la que sale se aparta por
                    // el contrario. Volver a la biblioteca invierte el sentido,
                    // así que el gesto de "entrar" y el de "volver" no se
                    // confunden.
                    AnimatedContent(
                        targetState = vm.screen,
                        modifier = Modifier.graphicsLayer {
                            val p = if (screenBack) backProgress.value else 0f
                            val k = 1f - 0.08f * p
                            scaleX = k
                            scaleY = k
                            alpha = 1f - 0.35f * p
                        },
                        transitionSpec = {
                            // Eje compartido con muelles: la que entra llega
                            // desde el lado al que se va con un leve zoom; volver
                            // invierte el sentido. Interrumpible a mitad.
                            val dir = if (targetState == Screen.Library) -1 else 1
                            if (reduced) {
                                fadeIn(snap()).togetherWith(fadeOut(snap()))
                            } else {
                                (
                                    slideInHorizontally(Springs.enter()) { w -> dir * w / 10 } +
                                        fadeIn(Springs.fade()) +
                                        scaleIn(Springs.enter(), initialScale = 0.96f)
                                    ).togetherWith(
                                    slideOutHorizontally(Springs.enter()) { w -> -dir * w / 10 } +
                                        fadeOut(Springs.fade()) +
                                        scaleOut(Springs.enter(), targetScale = 1.02f),
                                )
                            }
                        },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            Screen.Library -> LibraryScreen(vm)
                            Screen.Folder -> FolderScreen(vm)
                            Screen.Add -> AddScreen(vm)
                            Screen.Settings -> SettingsScreen(vm)
                            Screen.Masha -> MashaScreen(vm)
                        }
                    }
                }
            }
            }

            vm.detailsKey?.let { DetailsSheet(vm, it) }
            vm.artPicker?.let { ArtPickerSheet(vm, it) }
            vm.dialog?.let { ElyDialogView(it, onDismiss = vm::dismissDialog, focus = vm.input.dialogFocus) }
            vm.toast?.let {
                ToastView(
                    it,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(bottom = 76.dp),
                )
            }

            // Arranque de consola, encima de todo y solo una vez por arranque
            // (sobrevive a la recreación por cambio de idioma). La biblioteca
            // ya se compone debajo, así que al irse no hay espera.
            var booted by rememberSaveable { mutableStateOf(false) }
            if (!booted) BootSplash(onFinished = { booted = true })
        }
    }
}

