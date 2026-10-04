package com.elyndra.launcher.ui

import com.elyndra.launcher.ui.components.rememberBackdropStack
import com.elyndra.launcher.ui.components.LocalBackdropStack
import com.elyndra.launcher.ui.components.OverlayHost
import com.elyndra.launcher.ui.screens.IdentifySheet
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.components.GameActionOverlayContainer
import com.elyndra.launcher.ui.components.ElyDialogView
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.components.LocalPadHints
import com.elyndra.launcher.ui.components.LocalPadInput
import com.elyndra.launcher.ui.components.ImeBridge
import com.elyndra.launcher.ui.components.PadFocusGroup
import com.elyndra.launcher.ui.components.ToastView
import com.elyndra.launcher.ui.components.VideoBackdrop
import com.elyndra.launcher.ui.screens.AddScreen
import com.elyndra.launcher.ui.screens.ArtPickerSheet
import com.elyndra.launcher.ui.screens.DetailsHost
import com.elyndra.launcher.ui.screens.FolderScreen
import com.elyndra.launcher.ui.screens.LibraryScreen
import com.elyndra.launcher.ui.screens.LicensesScreen
import com.elyndra.launcher.ui.screens.MashaScreen
import com.elyndra.launcher.ui.screens.SettingsScreen
import com.elyndra.launcher.ui.screens.VoiceSyncScreen
import com.elyndra.launcher.ui.theme.ElyndraTheme
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.masha.Holo
import com.elyndra.launcher.ui.intro.BootIntro
import androidx.compose.runtime.key
import com.elyndra.launcher.data.argb

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

    // Menús, diálogos y pantallas que cambian suenan desde aquí (ver UiSoundEffects).
    UiSoundEffects(vm)

    ElyndraTheme(skin = vm.settings.skin, landscape = landscape) {
    // Las capas modales abiertas: un solo desenfoque y velos que se apilan (ver BackdropStack).
    val backdrop = rememberBackdropStack()
    CompositionLocalProvider(LocalUiSounds provides vm.sound, LocalPadInput provides vm.input, LocalBackdropStack provides backdrop) {
        // Atrás cierra, por orden: diálogo, hoja, ficha, pantalla y buscador.
        // Atrás predictivo (Android 13+): mientras el gesto dura, la pantalla
        // que se abandona se encoge y se apaga siguiendo al dedo; si el gesto
        // se cancela, vuelve a su sitio con un muelle.
        // El teclado en pantalla, para el mando: B lo cierra antes que la capa.
        ImeBridge()
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
        // Capas con foco propio encima de la pantalla.
        val overlayOpen = vm.dialog != null || vm.sheet != null || vm.detailsKey != null || vm.artPicker != null || vm.identify.state != null

        // Lanzar un juego saca la pantalla igual que abrir una carpeta: se
        // desliza una décima del ancho, se funde y crece hasta 1.02.
        val launchSlide = remember { Animatable(0f) }
        val launchFade = remember { Animatable(0f) }
        val launching = vm.opening != null
        LaunchedEffect(launching) {
            val target = if (launching) 1f else 0f
            if (reduced) {
                launchSlide.snapTo(target)
                launchFade.snapTo(target)
            } else {
                launch { launchSlide.animateTo(target, Springs.enter()) }
                launchFade.animateTo(target, Springs.fade())
            }
        }

        // Masha y la calibración de voz son oscuras de borde a borde: el fondo de
        // la raíz también, o la franja de la muesca (fuera del padding de
        // insets) quedaría clara junto a ellas.
        val holoScreen = vm.screen == Screen.Masha || vm.screen == Screen.VoiceSync
        Box(Modifier.fillMaxSize().background(if (holoScreen) Holo.bg else P.paper)) {
            // Las barras van ocultas (pantalla completa), así que sus insets son 0;
            // se mantiene el del recorte de pantalla para que en un móvil con muesca
            // el contenido no quede debajo.
            // La pantalla entera va dentro del contenedor del menú de
            // acciones: es él quien la desenfoca y la oscurece cuando el menú
            // está abierto, y quien monta el overlay por encima.
            GameActionOverlayContainer(
                isOverlayVisible = vm.sheet != null,
                onOverlayDismissed = vm::dismissSheet,
                spec = vm.sheet,
                input = vm.input,
                origin = vm.sheetOrigin,
            ) {
            // Fondo de la app (solo de Elyndra, no del sistema), debajo de todo:
            // el vídeo en bucle o la imagen fija que el usuario haya elegido. Va
            // dentro del contenedor para desenfocarse con el resto al abrir el menú.
            val s = vm.settings
            if (s.backgroundEnabled && !holoScreen) {
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
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
            ) {
                // Las métricas reparten este alto entre hero y cards (móvil y tableta).
                CompositionLocalProvider(
                    LocalScreenSize provides DpSize(maxWidth, maxHeight),
                    LocalPadHints provides vm.input.gamepadPresent,
                ) {
                    // Cambiar de pantalla se ve: la que entra llega deslizando
                    // desde el lado al que se va, y la que sale se aparta por
                    // el contrario. Volver a la biblioteca invierte el sentido,
                    // así que el gesto de "entrar" y el de "volver" no se
                    // confunden.
                    AnimatedContent(
                        targetState = vm.screen,
                        modifier = Modifier.graphicsLayer {
                            val p = if (screenBack) backProgress.value else 0f
                            val k = (1f - 0.08f * p) * (1f + 0.02f * launchSlide.value)
                            scaleX = k
                            scaleY = k
                            translationX = -size.width / 10f * launchSlide.value
                            alpha = (1f - 0.35f * p) * (1f - launchFade.value)
                        },
                        transitionSpec = {
                            // Eje compartido con muelles: la que entra llega
                            // desde el lado al que se va con un leve zoom; volver
                            // invierte el sentido. Interrumpible a mitad.
                            val dir = if (targetState == Screen.Library || (targetState == Screen.Settings && initialState.isSettingsPage)) -1 else 1
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
                        // Cada pantalla es un grupo de foco (ver PadFocusGroup): con mando
                        // nace con algo señalado, recupera lo que tenía al cerrarse una
                        // capa y, mientras hay una capa encima, el foco no se cuela aquí.
                        PadFocusGroup(
                            Modifier.fillMaxSize(),
                            blocked = overlayOpen,
                            // Biblioteca y Carpeta van con la selección del InputController.
                            padFocus = screen != Screen.Library && screen != Screen.Folder,
                        ) {
                            when (screen) {
                                Screen.Library -> LibraryScreen(vm)
                                Screen.Folder -> FolderScreen(vm)
                                Screen.Add -> AddScreen(vm)
                                Screen.Settings -> SettingsScreen(vm)
                                Screen.Masha -> MashaScreen(vm)
                                Screen.Licenses -> LicensesScreen(vm)
                                Screen.VoiceSync -> VoiceSyncScreen(vm)
                            }
                        }
                    }
                }
            }
            }

            DetailsHost(vm)
            // Cada capa se monta con su host: entra y sale con la animación del menú.
            OverlayHost(vm.artPicker, "artPicker") { state, open, progress -> ArtPickerSheet(vm, state, open, progress) }
            OverlayHost(vm.identify.state, "identify") { state, open, progress -> IdentifySheet(vm, vm.identify, state, open, progress) }
            OverlayHost(vm.dialog, "dialog") { spec, open, progress ->
                ElyDialogView(spec, open, progress, onDismiss = vm::dismissDialog, focus = vm.input.dialogFocus, text = vm.dialogText, onText = vm::updateDialogText)
            }
            // Un paquete de idioma bajándose: arriba, pequeño, sin tapar nada.
            TranslationPackBanner(
                vm,
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(top = 8.dp),
            )
            vm.toast?.let {
                ToastView(
                    it,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(bottom = 76.dp),
                )
            }

            // Intro de arranque, encima de todo: una vez por proceso (la decide
            // MainActivity) o al pedir la vista previa en Ajustes. La biblioteca
            // ya se compone debajo, así que al fundirse no hay espera.
            if (vm.intro.visible) {
                key(vm.intro.run) { BootIntro(vm.intro, vm.settings.introColor.base(LocalSkin.current.a1.argb())) }
            }
        }
    }
    }
}

