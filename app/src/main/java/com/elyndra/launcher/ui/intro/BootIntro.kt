package com.elyndra.launcher.ui.intro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.withFrameNanos
import com.elyndra.launcher.R
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.IntroController
import com.elyndra.launcher.ui.masha.MashaQuality
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   Intro de arranque: el rótulo ELYNDRA que nace de una explosión
   de luz dorada (ver IntroTimeline para las fases).

   Un único lienzo y un único bucle de fotogramas. El reloj se lee
   solo en la fase de dibujo, así que la intro no recompone mientras
   dura y no roba tiempo a la biblioteca que se monta debajo. Lo caro
   (rótulo, desenfoques, subtítulo, sprites) se pinta una vez en otro
   hilo (IntroArt); cada fotograma solo copia mapas de bits y dibuja
   degradados ya creados, sin crear objetos.

   Oscuro: luz que suma (BlendMode.Plus) sobre ámbar ahumado.
   Claro: perla y oro champán con mezcla normal; Plus sobre un fondo
   claro no se vería.
   ───────────────────────────────────────────────────────────── */

private const val DUST_HIGH = 220
private const val DUST_LITE = 90
private const val EMBERS_HIGH = 36
private const val EMBERS_LITE = 16

@Composable
fun BootIntro(intro: IntroController, baseColor: Int) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val reduced = LocalReducedMotion.current
    val run = intro.run
    val palette = remember(run, baseColor) { IntroPalettes.derive(baseColor, P.isDark) }
    val dust = remember(run) {
        val lite = MashaQuality.detect(context) == MashaQuality.Lite
        when {
            reduced -> Dust(0, 0)
            lite -> Dust(DUST_LITE, EMBERS_LITE)
            else -> Dust(DUST_HIGH, EMBERS_HIGH)
        }
    }
    val subtitle = stringResource(R.string.intro_subtitle)

    var area by remember { mutableStateOf(IntSize.Zero) }
    val art by produceState<IntroArt?>(null, area, palette, subtitle) {
        if (area.width > 0 && area.height > 0) {
            value = withContext(Dispatchers.Default) {
                IntroArt.render(context, area.width, area.height, density, palette, subtitle)
            }
        }
    }
    val current = art
    DisposableEffect(current) { onDispose { current?.release() } }

    val frame = remember(run) {
        val elapsed = if (intro.startNanos == 0L) 0f else (System.nanoTime() - intro.startNanos) / 1_000_000f
        IntroTimeline.fill(elapsed, reduced, intro.skipAt, IntroFrame())
    }
    val clock = remember(run) { mutableFloatStateOf(0f) }
    val layerAlpha = remember(run) { mutableFloatStateOf(frame.alpha) }
    val onFrame: (Long) -> Boolean = remember(run) {
        { now ->
            if (intro.startNanos == 0L) intro.startNanos = now
            val t = (now - intro.startNanos) / 1_000_000f
            IntroTimeline.fill(t, reduced, intro.skipAt, frame)
            clock.floatValue = t
            layerAlpha.floatValue = frame.alpha
            frame.done
        }
    }
    LaunchedEffect(run) {
        while (!withFrameNanos(onFrame)) Unit
        intro.finish()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { area = it }
            .graphicsLayer { alpha = layerAlpha.floatValue }
            // Mientras se ve, nada la atraviesa; un toque la salta (pasados 0,8 s).
            .pointerInput(intro) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.changedToDown() }) intro.skip()
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .drawWithCache {
                val scene = Scene(size, density, palette, art)
                onDrawBehind { scene.draw(this, frame, clock.floatValue / 1000f, dust) }
            },
    )
}

/* ── polvo y ascuas: todo precalculado ─────────────────────────── */

private class Dust(val count: Int, val embers: Int) {
    /** Salida en el borde (unidades de media pantalla) y llegada alrededor del núcleo (unidades de lado corto). */
    val startX = FloatArray(count)
    val startY = FloatArray(count)
    val endX = FloatArray(count)
    val endY = FloatArray(count)
    val size = FloatArray(count)
    val phase = FloatArray(count)
    val speed = FloatArray(count)
    val delay = FloatArray(count)
    val curl = FloatArray(count)
    val kick = FloatArray(count)

    /** Ascuas: posición en el ancho del rótulo (-1…1), retardo, periodo (s) y tamaño. */
    val emberX = FloatArray(embers)
    val emberOffset = FloatArray(embers)
    val emberPeriod = FloatArray(embers)
    val emberSize = FloatArray(embers)

    init {
        val rnd = Random(0x454C59)
        for (i in 0 until count) {
            val a = rnd.nextFloat() * 2f * PI.toFloat()
            val far = 1.05f + rnd.nextFloat() * 0.35f
            startX[i] = cos(a) * far
            startY[i] = sin(a) * far
            // Nube elíptica, más ancha que alta, densa cerca del centro.
            val b = a + (rnd.nextFloat() - 0.5f) * 0.9f
            val r = 0.06f + rnd.nextFloat() * rnd.nextFloat() * 0.5f
            endX[i] = cos(b) * r * 1.5f
            endY[i] = sin(b) * r * 0.55f
            size[i] = 0.5f + rnd.nextFloat() * rnd.nextFloat() * 1.5f
            phase[i] = rnd.nextFloat() * 2f * PI.toFloat()
            speed[i] = 0.4f + rnd.nextFloat() * 1.2f
            delay[i] = rnd.nextFloat() * 0.35f
            curl[i] = (rnd.nextFloat() - 0.5f) * 0.5f
            kick[i] = 0.3f + rnd.nextFloat()
        }
        for (j in 0 until embers) {
            emberX[j] = rnd.nextFloat() * 2f - 1f
            emberOffset[j] = 1.6f + rnd.nextFloat() * 1.2f
            emberPeriod[j] = 0.8f + rnd.nextFloat() * 0.8f
            emberSize[j] = 0.6f + rnd.nextFloat() * 0.8f
        }
    }
}

/* ── escena: degradados y geometría creados una vez por tamaño ─── */

private class Scene(size: Size, private val dp: Float, private val p: IntroPalette, private val art: IntroArt?) {
    private val w = size.width
    private val h = size.height
    private val minDim = min(w, h)
    private val c = Offset(w / 2f, h * 0.47f)

    /** Luz: suma en oscuro; en claro, mezcla normal (Plus sobre perla no se ve). */
    private val light = if (p.dark) BlendMode.Plus else BlendMode.SrcOver

    private val bg = Color(p.background)
    private val fogRadius = max(w, h) * 0.65f
    private val fog = Brush.radialGradient(
        0f to Color(p.fog).copy(alpha = p.fogAlpha),
        1f to Color(p.fog).copy(alpha = 0f),
        center = c,
        radius = fogRadius,
    )
    private val wisp = Brush.radialGradient(
        0f to Color(p.fog).copy(alpha = p.fogAlpha * 0.55f),
        1f to Color(p.fog).copy(alpha = 0f),
        center = Offset.Zero,
        radius = fogRadius * 0.6f,
    )
    private val vignette = Brush.radialGradient(
        0f to Color.Transparent,
        0.55f to Color.Transparent,
        1f to Color(p.vignette).copy(alpha = p.vignetteAlpha),
        center = Offset(w / 2f, h / 2f),
        radius = hypot(w, h) * 0.6f,
    )

    private val coreRadius = minDim * 0.42f
    private val core = Brush.radialGradient(
        0f to Color(p.core).copy(alpha = if (p.dark) 1f else 0.95f),
        0.25f to Color(p.glow).copy(alpha = if (p.dark) 0.55f else 0.45f),
        1f to Color(p.glow).copy(alpha = 0f),
        center = c,
        radius = coreRadius,
    )
    private val bloomRadius = minDim * 0.7f
    private val bloom = Brush.radialGradient(
        0f to Color.White.copy(alpha = if (p.dark) 1f else 0.9f),
        0.15f to Color(p.glow).copy(alpha = if (p.dark) 0.7f else 0.5f),
        1f to Color(p.glow).copy(alpha = 0f),
        center = c,
        radius = bloomRadius,
    )
    private val streakRadius = minDim * 0.03f
    private val streak = Brush.radialGradient(
        0f to Color.White,
        0.3f to Color(p.streak).copy(alpha = 0.8f),
        1f to Color(p.streak).copy(alpha = 0f),
        center = c,
        radius = streakRadius,
    )
    private val frontRadius = minDim * 0.06f
    private val front = Brush.radialGradient(
        0f to Color(p.core),
        0.4f to Color(p.glow).copy(alpha = 0.6f),
        1f to Color(p.glow).copy(alpha = 0f),
        center = Offset.Zero,
        radius = frontRadius,
    )

    /* Rótulo: posición de las capas y del barrido. */
    private val wordLeft = art?.let { c.x - it.wordWidth / 2f } ?: 0f
    private val wordTop = art?.let { c.y - (it.inkTop + it.inkBottom) / 2f } ?: 0f
    private val wordRect = art?.let { Rect(wordLeft, wordTop, wordLeft + it.wordWidth, wordTop + it.wordHeight) } ?: Rect.Zero
    private val inkHalf = (art?.inkWidth ?: 0f) / 2f
    private val inkTop = wordTop + (art?.inkTop ?: 0f)
    private val inkBottom = wordTop + (art?.inkBottom ?: 0f)
    private val bandWidth = (art?.textSize ?: 0f) * 1.1f
    private val band = Brush.horizontalGradient(
        0f to Color.Transparent,
        0.5f to Color.White,
        1f to Color.Transparent,
        startX = -bandWidth / 2f,
        endX = bandWidth / 2f,
    )
    private val sweepPaint = Paint().apply { blendMode = light }
    private val subLeft = art?.let { c.x - it.subtitle.width / 2f } ?: 0f
    private val subTop = inkBottom + (art?.textSize ?: 0f) * 0.18f

    fun draw(s: DrawScope, f: IntroFrame, time: Float, dust: Dust) = with(s) {
        drawRect(bg)
        drawAtmosphere(f, time)
        if (f.core > 0f) {
            scale(0.6f + 0.4f * f.core, pivot = c) {
                drawCircle(core, coreRadius, c, alpha = f.core * f.fadeIn, blendMode = light)
            }
        }
        if (f.burst > 0f) {
            scale(0.5f + f.burst, pivot = c) {
                drawCircle(bloom, bloomRadius, c, alpha = f.burst, blendMode = light)
            }
        }
        if (f.streak > 0f) drawStreak(f)
        val ready = art ?: return@with
        if (f.gather > 0f) drawDust(f, time, dust, ready.particle)
        drawWord(f, ready)
        if (f.embers > 0f) drawEmbers(f, time, dust, ready.ember)
        if (f.subtitle > 0f) {
            translate(subLeft, subTop + (1f - f.subtitle) * 8f * dp) {
                drawImage(ready.subtitle, alpha = f.subtitle * f.fadeIn)
            }
        }
    }

    /** Niebla que deriva despacio y la viñeta. */
    private fun DrawScope.drawAtmosphere(f: IntroFrame, time: Float) {
        if (f.atmosphere <= 0f) return
        val a = f.atmosphere
        translate(sin(time * 0.35f) * w * 0.02f, cos(time * 0.27f) * h * 0.015f) {
            drawCircle(fog, fogRadius, c, alpha = a)
        }
        translate(c.x - w * 0.26f + sin(time * 0.31f) * w * 0.03f, c.y + h * 0.16f) {
            drawCircle(wisp, fogRadius * 0.6f, Offset.Zero, alpha = a)
        }
        translate(c.x + w * 0.28f + cos(time * 0.23f) * w * 0.03f, c.y - h * 0.13f) {
            drawCircle(wisp, fogRadius * 0.6f, Offset.Zero, alpha = a)
        }
        drawRect(vignette, alpha = a)
    }

    /** Raya anamórfica: el destello estirado en horizontal, ancha y suave más una línea fina. */
    private fun DrawScope.drawStreak(f: IntroFrame) {
        val reach = w * 0.5f * (0.15f + 0.85f * f.streakSpread)
        val sx = reach / streakRadius
        withTransform({ scale(sx, 1.4f, pivot = c) }) {
            drawCircle(streak, streakRadius, c, alpha = f.streak * 0.55f, blendMode = light)
        }
        withTransform({ scale(sx * 1.3f, 0.35f, pivot = c) }) {
            drawCircle(streak, streakRadius, c, alpha = f.streak, blendMode = light)
        }
    }

    private fun DrawScope.drawDust(f: IntroFrame, time: Float, d: Dust, sprite: ImageBitmap) {
        val half = sprite.width / 2f
        val spriteDp = sprite.width / dp
        val baseSize = (if (p.dark) 7f else 3.2f) / spriteDp
        val baseAlpha = if (p.dark) 0.9f else 0.55f
        val spin = time * 0.12f
        for (i in 0 until d.count) {
            val delay = d.delay[i]
            val g = ((f.gather - delay) / (1f - delay)).coerceIn(0f, 1f)
            if (g <= 0f) continue
            val e = g * g * (3f - 2f * g)
            // Llegada: la nube gira despacio alrededor del núcleo.
            val ang = spin * d.speed[i]
            val ca = cos(ang)
            val sa = sin(ang)
            val ex = (d.endX[i] * ca - d.endY[i] * sa * 2.7f) * minDim
            val ey = (d.endX[i] * sa / 2.7f + d.endY[i] * ca) * minDim
            val sx = d.startX[i] * w * 0.5f
            val sy = d.startY[i] * h * 0.5f
            var x = sx + (ex - sx) * e
            var y = sy + (ey - sy) * e
            // Curva de la trayectoria: perpendicular a la recta, máxima a medio camino.
            val bend = d.curl[i] * sin(PI.toFloat() * e)
            x += -(ey - sy) * bend
            y += (ex - sx) * bend
            val push = 1f + f.burst * d.kick[i] * 0.25f + f.disperse * f.disperse * (1.6f + d.kick[i])
            x *= push
            y = y * push - f.disperse * d.speed[i] * minDim * 0.12f
            val twinkle = 0.65f + 0.35f * sin(time * 3f * d.speed[i] + d.phase[i])
            val alpha = min(1f, g * 2.5f) * twinkle * (1f - f.disperse) * baseAlpha
            if (alpha <= 0.01f) continue
            val k = baseSize * d.size[i]
            withTransform({
                translate(c.x + x, c.y + y)
                scale(k, k, pivot = Offset.Zero)
            }) {
                drawImage(sprite, Offset(-half, -half), alpha = alpha, blendMode = light)
            }
        }
    }

    /**
     * El rótulo se abre desde el centro: se recorta a una franja que crece,
     * con un frente de luz en cada borde, y crece un poco al aparecer. Luego
     * el barrido: la máscara de brillo de las letras, recortada por una banda
     * inclinada que las cruza.
     */
    private fun DrawScope.drawWord(f: IntroFrame, art: IntroArt) {
        if (f.reveal <= 0f) return
        val open = art.wordWidth / 2f * f.reveal
        val a = min(1f, f.reveal * 4f) * f.fadeIn
        val k = 0.94f + 0.06f * f.reveal
        scale(k, pivot = c) {
            clipRect(c.x - open, wordRect.top, c.x + open, wordRect.bottom) {
                drawImage(art.depth, Offset(wordLeft, wordTop), alpha = a, blendMode = light)
                drawImage(art.body, Offset(wordLeft, wordTop), alpha = a)
            }
        }
        if (f.reveal < 1f) {
            val fa = (1f - f.reveal) * 0.9f
            val cy = (inkTop + inkBottom) / 2f
            val sy = (inkBottom - inkTop) / frontRadius * 0.9f
            for (side in -1..1 step 2) {
                withTransform({
                    translate(c.x + side * min(open, inkHalf + frontRadius), cy)
                    scale(0.5f, sy, pivot = Offset.Zero)
                }) {
                    drawCircle(front, frontRadius, Offset.Zero, alpha = fa, blendMode = light)
                }
            }
        }
        if (f.sweepAlpha > 0f) {
            sweepPaint.alpha = f.sweepAlpha * if (p.dark) 0.85f else 0.9f
            val canvas = drawContext.canvas
            canvas.saveLayer(wordRect, sweepPaint)
            drawImage(art.shine, Offset(wordLeft, wordTop))
            val x = c.x - inkHalf - bandWidth + f.sweep * (inkHalf * 2f + bandWidth * 2f)
            withTransform({
                translate(x, c.y)
                rotate(18f, pivot = Offset.Zero)
            }) {
                drawRect(band, Offset(-bandWidth / 2f, -wordRect.height), Size(bandWidth, wordRect.height * 2f), blendMode = BlendMode.DstIn)
            }
            canvas.restore()
        }
    }

    /** Ascuas que suben desde la parte ya descubierta de las letras. */
    private fun DrawScope.drawEmbers(f: IntroFrame, time: Float, d: Dust, sprite: ImageBitmap) {
        val half = sprite.width / 2f
        val baseSize = (if (p.dark) 6f else 3f) / (sprite.width / dp)
        val rise = (inkBottom - inkTop) * 1.8f
        val startY = inkTop + (inkBottom - inkTop) * 0.4f
        for (j in 0 until d.embers) {
            val ex = d.emberX[j]
            if (ex < -f.reveal || ex > f.reveal) continue
            val life = (time - d.emberOffset[j]) / d.emberPeriod[j]
            if (life < 0f) continue
            val u = life - floor(life)
            val alpha = sin(PI.toFloat() * u) * f.embers * (1f - f.disperse) * if (p.dark) 1f else 0.7f
            if (alpha <= 0.01f) continue
            val k = baseSize * d.emberSize[j] * (1f - 0.5f * u)
            withTransform({
                translate(c.x + ex * inkHalf + sin(u * 6f + j) * minDim * 0.012f, startY - u * rise)
                scale(k, k, pivot = Offset.Zero)
            }) {
                drawImage(sprite, Offset(-half, -half), alpha = alpha, blendMode = light)
            }
        }
    }
}
