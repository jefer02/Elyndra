package com.elyndra.launcher.ui.components

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import com.elyndra.launcher.ui.BackdropStack
import com.elyndra.launcher.ui.BackdropLevels
import androidx.compose.ui.draw.drawBehind
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.Palettes
import com.elyndra.launcher.data.argb
import com.elyndra.launcher.ui.SheetHero
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion

/* ─────────────────────────────────────────────────────────────
   El fondo apartado de las capas modales: menú de acciones y ficha.

   Un solo sitio para afinar cómo se aparta lo de detrás:
     · Desenfoque (Android 12+) en fase de dibujo, a sangre, de borde a
       borde de la ventana (`TileMode.Clamp`: sin marco claro ni esquinas).
     · Velo oscuro a pantalla completa, también bajo barras y muesca.
       Sin RenderEffect (Android 11 o menos) no hay desenfoque, y el velo
       oscurece más para que el panel se separe igual del fondo.
     · Un progreso 0→1 gobierna velo y panel: muelle al abrir, salida más
       corta; con "reducir movimiento", un fundido.

   El desenfoque lo aplica [GameActionOverlayContainer] a la pantalla
   (es quien la envuelve); la capa de delante pone su velo con
   [BackdropScrim] y anima su panel con [emergeSurface] / [emergeContent].
   El desenfoque es un `renderEffect` de la capa de la pantalla: desplazar
   el panel de delante no lo vuelve a calcular.
   ───────────────────────────────────────────────────────────── */

object Backdrop {
    /** Radio del desenfoque del fondo, en dp (Android 12+). */
    const val BLUR_DP = 18f

    /** Con "reducir movimiento": un fundido corto y nada más. */
    const val REDUCED_FADE_MS = 160

    /** ¿Hay RenderEffect? */
    val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /** El orden de dibujo de las capas, de abajo arriba (ver [BackdropStack]). */
    const val Z_MENU = 0
    const val Z_DETAILS = 1
    const val Z_ART_PICKER = 2
    const val Z_IDENTIFY = 3
    const val Z_DIALOG = 4

    /** Escala de la que sale el panel cuando no viene de una card. */
    const val PANEL_MIN_SCALE = 0.9f

    /** El contenido crece desde un poco más pequeño y recorre parte del camino desde la card. */
    const val CONTENT_MIN_SCALE = 0.92f
    const val CONTENT_TRAVEL = 0.35f

    /** Escalonado de la entrada: cuándo empieza, cuánto se retrasa cada parte y cuánto dura. */
    const val STAGGER_START = 0.25f
    const val STAGGER_STEP = 0.07f
    const val STAGGER_SPAN = 0.45f

    /** Opacidad del cristal del panel: se transparenta un pelo del fondo desenfocado. */
    const val PANEL_ALPHA = 0.93f
}

/**
 * Progreso 0→1 de una capa modal: muelle al abrir, salida más corta (lo que
 * se va no se hace esperar) y fundido con "reducir movimiento". Se lee en
 * fase de dibujo: animar no recompone.
 */
@Composable
fun rememberOverlayProgress(visible: Boolean, label: String = "overlay"): State<Float> {
    val reduced = LocalReducedMotion.current
    return animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = when {
            reduced -> tween(Backdrop.REDUCED_FADE_MS)
            visible -> Springs.enter()
            else -> Springs.exit()
        },
        label = label,
    )
}

/** Progreso del desenfoque del fondo: entra como un fundido y sale con la capa. */
@Composable
fun rememberBackdropProgress(active: Boolean): State<Float> {
    val reduced = LocalReducedMotion.current
    return animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = when {
            reduced -> tween(Backdrop.REDUCED_FADE_MS)
            active -> Springs.fade()
            else -> Springs.exit()
        },
        label = "backdrop",
    )
}

/**
 * Desenfoque del fondo, en fase de dibujo. `TileMode.Clamp`: el borde del
 * desenfoque repite los píxeles del borde, así que llega entero hasta el
 * filo de la ventana, sin marco transparente ni esquinas redondeadas. Antes
 * de Android 12 no hace nada (el velo oscurece más, ver [BackdropLevels]).
 */
fun Modifier.backdropBlur(amount: State<Float>): Modifier =
    if (!Backdrop.blurSupported) this else graphicsLayer {
        val r = Backdrop.BLUR_DP.dp.toPx() * amount.value.coerceIn(0f, 1f)
        renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
    }

/** Las capas abiertas ahora mismo; la pone ElyndraApp para todo lo de dentro. */
val LocalBackdropStack = staticCompositionLocalOf<BackdropStack?> { null }

/** La pila de la app: una lista de estado, así velos y desenfoque se recomponen al cambiar. */
@Composable
fun rememberBackdropStack(): BackdropStack = remember { BackdropStack(mutableStateListOf()) }

/**
 * Velo a sangre de una capa modal: toda la ventana, también bajo las barras
 * y la muesca (no lleva padding de insets). Tocarlo cierra; el panel de
 * delante se come sus propios toques.
 *
 * Se apunta en la pila ([LocalBackdropStack]) con su orden de dibujo [z]:
 * la capa de abajo pone el velo de siempre y una capa encima de otra (editar
 * nombre sobre la ficha) solo lo oscurece un poco más, sin volver a
 * desenfocar; al cerrarse, su velo se funde con [progress] y el conjunto
 * vuelve al nivel de antes. [open] es false mientras la capa sale.
 * [light]: diálogo pequeño (ver [BackdropLevels.BLUR_LIGHT_LAYERS]).
 */
@Composable
fun BackdropScrim(
    progress: State<Float>,
    onDismiss: () -> Unit,
    open: Boolean,
    z: Int,
    modifier: Modifier = Modifier,
    light: Boolean = false,
) {
    val stack = LocalBackdropStack.current
    val id = remember { Any() }
    if (stack != null) {
        SideEffect { stack.put(id, z, open, light) }
        DisposableEffect(stack, id) { onDispose { stack.remove(id) } }
    }
    // Antes de apuntarse (primer fotograma) cuenta ya consigo misma.
    val blur = Backdrop.blurSupported &&
        (stack == null || stack.blurs() || (open && (BackdropLevels.BLUR_LIGHT_LAYERS || !light)))
    val depth = stack?.depthOrNext(id, z) ?: 0
    // Si cambia la profundidad (se cierra una capa de debajo), sin saltos.
    val alpha = animateFloatAsState(BackdropLevels.layerAlpha(depth, blur), motion(Springs.fade()), label = "scrimLevel")
    val shade = P.shade
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = progress.value.coerceIn(0f, 1f) }
            // Se lee al dibujar: cambiar de nivel no recompone nada.
            .drawBehind { drawRect(shade, alpha = alpha.value) }
            .consumeClicks { onDismiss() },
    )
}

/**
 * Monta una capa modal mientras se abre, está abierta y **mientras sale**: al
 * cerrarse, [value] pasa a null enseguida y la capa tiene que poder irse con
 * su animación. [content] recibe lo último que se enseñó, si sigue abierta y
 * el progreso 0→1 (muelle al entrar, salida más corta, fundido con "reducir
 * movimiento"). Mientras sale ya no responde a toques: un segundo "Aceptar"
 * no dispara dos veces.
 */
@Composable
fun <T : Any> OverlayHost(
    value: T?,
    label: String,
    content: @Composable (shown: T, open: Boolean, progress: State<Float>) -> Unit,
) {
    val held = remember { HeldValue<T>() }
    if (value != null) held.value = value
    val progress = rememberOverlayProgress(value != null, label)
    val visible by remember { derivedStateOf { progress.value > 0.001f } }
    val shown = held.value ?: return
    if (value == null && !visible) return
    Box(Modifier.fillMaxSize()) {
        content(shown, value != null, progress)
        if (value == null) Box(Modifier.fillMaxSize().consumeClicks())
    }
}

private class HeldValue<T : Any> {
    var value: T? = null
}

/**
 * La entrada y salida del panel de una capa que no sale de una card
 * (diálogos, selector de arte, editar nombre): la misma que el menú y la
 * ficha sin origen —crece desde 0,9 con un fundido— y, con "reducir
 * movimiento", solo el fundido. En fase de dibujo.
 */
fun Modifier.overlayEmerge(progress: State<Float>, reduced: Boolean): Modifier =
    graphicsLayer { emergeSurface(Rect.Zero, null, progress.value, reduced) }

/* ── el panel que sale ────────────────────────────────────────── */

internal fun lerpF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Rectángulo del panel en la ventana. No es estado: solo lo lee la transformación al dibujar. */
internal class PanelBounds {
    var rect: Rect = Rect.Zero
}

/** La superficie se transforma desde el rectángulo de la card ([origin]); sin card, crece desde 0,9. */
internal fun GraphicsLayerScope.emergeSurface(panel: Rect, origin: Rect?, p: Float, reduced: Boolean) {
    if (reduced) {
        alpha = p.coerceIn(0f, 1f)
        return
    }
    if (origin == null || panel.width <= 0f || panel.height <= 0f) {
        val s = lerpF(Backdrop.PANEL_MIN_SCALE, 1f, p)
        scaleX = s
        scaleY = s
        alpha = p.coerceIn(0f, 1f)
        return
    }
    // Del rectángulo de la card al del panel: la esquina de arriba a la
    // izquierda va de una a otra y el tamaño crece en cada eje por separado.
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = lerpF(origin.width / panel.width, 1f, p)
    scaleY = lerpF(origin.height / panel.height, 1f, p)
    translationX = lerpF(origin.left - panel.left, 0f, p)
    translationY = lerpF(origin.top - panel.top, 0f, p)
    alpha = (p * 2.5f).coerceIn(0f, 1f)
}

/** El contenido no se deforma: escala uniforme y un deslizamiento corto desde la card. */
internal fun GraphicsLayerScope.emergeContent(panel: Rect, origin: Rect?, p: Float, reduced: Boolean) {
    if (reduced) {
        alpha = p.coerceIn(0f, 1f)
        return
    }
    val s = lerpF(Backdrop.CONTENT_MIN_SCALE, 1f, p)
    scaleX = s
    scaleY = s
    if (origin != null && panel.width > 0f) {
        translationX = (origin.center.x - panel.center.x) * (1f - p) * Backdrop.CONTENT_TRAVEL
        translationY = (origin.center.y - panel.center.y) * (1f - p) * Backdrop.CONTENT_TRAVEL
    }
}

/**
 * Entrada escalonada de la parte [index] (0 = la de arriba), con el mismo
 * progreso que el panel: al cerrar, se van en orden inverso. En fase de dibujo.
 */
internal fun Modifier.staggered(open: State<Float>, index: Int, reduced: Boolean): Modifier =
    if (reduced) this else graphicsLayer {
        val t = ((open.value - Backdrop.STAGGER_START - index * Backdrop.STAGGER_STEP) / Backdrop.STAGGER_SPAN).coerceIn(0f, 1f)
        alpha = t
        translationY = (1f - t) * 14.dp.toPx()
    }

/* ── el color del juego ───────────────────────────────────────── */

/**
 * El acento de un juego: sale de su arte (fondo, carátula, icono, logo) o del
 * icono de la app, con la misma extracción que el menú de acciones. Mientras
 * se calcula, y si el arte es gris, el primario de la marca. Ya convertido a
 * color de contenido: se lee sobre el panel en los dos temas.
 */
@Composable
fun rememberArtAccent(hero: SheetHero?): Color {
    val skin = LocalSkin.current
    val context = LocalContext.current
    var artAccent by remember(hero) { mutableStateOf<Color?>(null) }
    LaunchedEffect(hero) {
        if (hero == null) return@LaunchedEffect
        val paths = listOfNotNull(hero.backgroundPath, hero.coverPath, hero.iconPath, hero.logoPath)
        val argb = runCatching { ArtPalette.load(context, paths) }.getOrNull()
            ?: hero.packageName?.let { runCatching { appIconAccent(context, it) }.getOrNull() }
        artAccent = argb?.let { Color(it) }
    }
    val base by animateColorAsState(artAccent ?: skin.a1, motion(Springs.fade()), label = "artAccent")
    return Color(Palettes.contentFor(base.argb(), P.isDark))
}

/** Color del cristal del panel: el papel con un toque del acento. Es también el de los fundidos. */
@Composable
@ReadOnlyComposable
fun panelTone(accent: Color): Color {
    val base = if (P.isDark) P.surface else P.paper
    return accent.copy(alpha = if (P.isDark) 0.10f else 0.07f).compositeOver(base).copy(alpha = Backdrop.PANEL_ALPHA)
}
