package com.elyndra.launcher.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ─────────────────────────────────────────────────────────────
   Los @keyframes del diseño, uno a uno.

   Las entradas (fadeUp, popIn, heroIn…) se reproducen una sola vez
   al montarse; los bucles (sheen, ring, bob…) viven en una
   InfiniteTransition. La curva es siempre la misma que en el CSS.
   ───────────────────────────────────────────────────────────── */

/** `cubic-bezier(.2,.8,.2,1)` — la curva de toda la interfaz. */
val Swift: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

/** `ease-in-out` de CSS. */
val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Progreso 0→1 que se reproduce una vez al entrar en composición. */
@Composable
fun playOnce(durationMs: Int, delayMs: Int = 0, easing: Easing = Swift, key: Any? = Unit): State<Float> {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        p.snapTo(0f)
        p.animateTo(1f, tween(durationMs, delayMs, easing))
    }
    return p.asState()
}

/* ── Entradas ───────────────────────────────────────────────── */

/** `@keyframes fadeIn` */
@Composable
fun Modifier.animFadeIn(durationMs: Int = 300, key: Any? = Unit): Modifier {
    val p by playOnce(durationMs, easing = LinearEasing, key = key)
    return this.alpha(p)
}

/** `@keyframes fadeUp` — opacidad + 12px de subida. */
@Composable
fun Modifier.animFadeUp(durationMs: Int = 400, delayMs: Int = 0, key: Any? = Unit): Modifier {
    val p by playOnce(durationMs, delayMs, key = key)
    val dy = with(LocalDensity.current) { (12.dp * (1f - p)).toPx() }
    return this.graphicsLayer { alpha = p; translationY = dy }
}

/** `@keyframes popIn` — escala .92→1 y 8px de subida. */
@Composable
fun Modifier.animPopIn(durationMs: Int = 450, delayMs: Int = 0, key: Any? = Unit): Modifier {
    val p by playOnce(durationMs, delayMs, key = key)
    val dy = with(LocalDensity.current) { (8.dp * (1f - p)).toPx() }
    return this.graphicsLayer {
        alpha = p
        scaleX = 0.92f + 0.08f * p
        scaleY = 0.92f + 0.08f * p
        translationY = dy
    }
}

/** `@keyframes riseSheet` — la hoja que sube 34px. */
@Composable
fun Modifier.animRiseSheet(durationMs: Int = 380, key: Any? = Unit): Modifier {
    val p by playOnce(durationMs, key = key)
    val dy = with(LocalDensity.current) { (34.dp * (1f - p)).toPx() }
    return this.graphicsLayer { alpha = p; translationY = dy }
}

/** `@keyframes heroIn` — el arte del hero entra desde scale(1.06). */
@Composable
fun Modifier.animHeroIn(durationMs: Int = 600, key: Any? = Unit): Modifier {
    val p by playOnce(durationMs, key = key)
    return this.graphicsLayer {
        alpha = p
        scaleX = 1.06f - 0.06f * p
        scaleY = 1.06f - 0.06f * p
    }
}

/** `@keyframes titleIn` — el titular entra desde 16px abajo y scale(.97). */
@Composable
fun Modifier.animTitleIn(durationMs: Int = 450, key: Any? = Unit): Modifier {
    val p by playOnce(durationMs, key = key)
    val dy = with(LocalDensity.current) { (16.dp * (1f - p)).toPx() }
    return this.graphicsLayer {
        alpha = p
        translationY = dy
        scaleX = 0.97f + 0.03f * p
        scaleY = 0.97f + 0.03f * p
    }
}

/** `@keyframes msgIn` — burbujas del chat. */
@Composable
fun Modifier.animMsgIn(durationMs: Int = 500, delayMs: Int = 0, key: Any? = Unit): Modifier {
    val p by playOnce(durationMs, delayMs, key = key)
    val dy = with(LocalDensity.current) { (16.dp * (1f - p)).toPx() }
    return this.graphicsLayer {
        alpha = p
        translationY = dy
        scaleX = 0.96f + 0.04f * p
        scaleY = 0.96f + 0.04f * p
    }
}

/** `@keyframes curtain` — el telón color papel que descubre cada card. */
@Composable
fun curtainAlpha(delayMs: Int, key: Any? = Unit): Float {
    val p by playOnce(500, delayMs, key = key)
    return 1f - p
}

/* ── Bucles ─────────────────────────────────────────────────── */

/** `@keyframes bob` — el punto del wordmark, ±3px. */
@Composable
fun bobOffset(): Float {
    val t = rememberInfiniteTransition(label = "bob")
    val v by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(3400, easing = EaseInOut), RepeatMode.Reverse),
        label = "bob",
    )
    return -3f * v
}

/** `@keyframes sheen` — banda de brillo, de -120% a 240% del ancho. */
@Composable
fun sheenProgress(): Float {
    val t = rememberInfiniteTransition(label = "sheen")
    val v by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2600, easing = EaseInOut), RepeatMode.Restart),
        label = "sheen",
    )
    // El CSS termina el recorrido al 60% del ciclo y descansa el resto.
    val eased = (v / 0.6f).coerceAtMost(1f)
    return -1.2f + eased * 3.6f
}

/** `@keyframes spin` — spinners. */
@Composable
fun spinAngle(periodMs: Int = 1100): Float {
    val t = rememberInfiniteTransition(label = "spin")
    val v by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    return v
}

/** `@keyframes ring` — el aro que se expande y se desvanece. */
@Composable
fun ringProgress(periodMs: Int = 2600): Float {
    val t = rememberInfiniteTransition(label = "ring")
    val v by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = "ring",
    )
    return v
}

/** `@keyframes pulseHint` — la pista del hero respirando entre .45 y .95. */
@Composable
fun pulseHintAlpha(): Float {
    val t = rememberInfiniteTransition(label = "hint")
    val v by t.animateFloat(
        0.45f, 0.95f,
        infiniteRepeatable(tween(1300, easing = EaseInOut), RepeatMode.Reverse),
        label = "hint",
    )
    return v
}

/** `@keyframes livePulse` — el punto de estado de Lucy. */
@Composable
fun livePulseScale(): Float {
    val t = rememberInfiniteTransition(label = "live")
    val v by t.animateFloat(
        1f, 1.5f,
        infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
        label = "live",
    )
    return v
}

/** `@keyframes barPlay` — cada barra del ecualizador, escala .35→1. */
@Composable
fun barPlayScale(index: Int): Float {
    val t = rememberInfiniteTransition(label = "bar$index")
    val period = (800 + index * 140) / 2
    val v by t.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(period, delayMillis = index * 90, easing = EaseInOut), RepeatMode.Reverse),
        label = "bar$index",
    )
    return v
}

/** `@keyframes dots` — los tres puntos de "Lucy está escribiendo". */
@Composable
fun typingDotAlpha(index: Int): Pair<Float, Float> {
    val t = rememberInfiniteTransition(label = "dot$index")
    val v by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1100, delayMillis = index * 180, easing = LinearEasing), RepeatMode.Restart),
        label = "dot$index",
    )
    // 0-30%: sube a opacidad 1 y -3px; 30-60%: vuelve; 60-100%: quieto.
    val alpha: Float
    val dy: Float
    when {
        v < 0.30f -> { val k = v / 0.30f; alpha = 0.25f + 0.75f * k; dy = -3f * k }
        v < 0.60f -> { val k = (v - 0.30f) / 0.30f; alpha = 1f - 0.75f * k; dy = -3f * (1f - k) }
        else -> { alpha = 0.25f; dy = 0f }
    }
    return alpha to dy
}

/** `@keyframes auraSpin` — el halo cónico del avatar de Lucy. */
@Composable
fun auraAngle(): Float = spinAngle(6000)

/** `@keyframes aurora` — deriva y respiración de los blobs de fondo. */
@Composable
fun auroraOffset(periodMs: Int, reverse: Boolean): Triple<Float, Float, Float> {
    val t = rememberInfiniteTransition(label = "aurora$periodMs")
    val v by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(periodMs / 2, easing = EaseInOut), RepeatMode.Reverse),
        label = "aurora$periodMs",
    )
    val k = if (reverse) 1f - v else v
    return Triple(0.06f * k, -0.04f * k, 1f + 0.14f * k)
}

/** Elevación en dp de la card seleccionada (`transform: translateY(-8px)`). */
@Composable
fun selectionLift(selected: Boolean): Dp {
    val t = androidx.compose.animation.core.animateDpAsState(
        targetValue = if (selected) (-8).dp else 0.dp,
        animationSpec = tween(300, easing = Swift),
        label = "lift",
    )
    return t.value
}

/** Ampliación de la card seleccionada (`transform: scale(1.08)`). */
@Composable
fun selectionScale(selected: Boolean): Float {
    val t = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = tween(300, easing = Swift),
        label = "scale",
    )
    return t.value
}
