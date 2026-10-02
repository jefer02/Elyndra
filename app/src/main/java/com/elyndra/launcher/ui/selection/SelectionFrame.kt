package com.elyndra.launcher.ui.selection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.components.FallbackArtCache
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import com.elyndra.launcher.ui.theme.Swift
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/* ─────────────────────────────────────────────────────────────
   El marco de la card seleccionada, el mismo para todas: iconos de
   juegos Android, carpetas de emulador, la card de "Añadir" y las
   carátulas de ROM (con arte o con el de reserva), en Biblioteca y
   en Carpeta. Sigue la forma exacta de la card que recibe.

   Capas, de atrás adelante:
     · Luz en el estante — una elipse suave bajo la card.
     · Halo — tres trazos por fuera del contorno, cada vez más anchos
       y tenues, que respiran (±10 %, ~3 s).
     · (la card)
     · Filo interior de contraste — oscuro en el tema oscuro, claro en
       el claro: el marco se lee sobre cualquier carátula.
     · Filo — 1,75 dp en degradado, del tono a uno más claro.
     · Reflejo — arriba a la izquierda, como en un cristal pulido.
     · Barrido — un arco de luz que da la vuelta al contorno, a la
       misma velocidad (dp/s) en cualquier card.
     · Polvo estelar — si está encendido.

   Al llegar la selección el filo se dibuja desde arriba en ~280 ms y
   el halo entra detrás; al irse, todo se apaga deprisa. Con "reducir
   movimiento", quieto: sin encendido, sin respiración, sin barrido y
   sin partículas.

   Rendimiento: un nodo de dibujo (no recompone nada), todo cacheado
   por tamaño y color, y solo la card seleccionada tiene reloj.
   ───────────────────────────────────────────────────────────── */

/** Cómo se ve la selección: el color elegido en Ajustes, qué capas van y el tema. */
@Immutable
data class SelectionLook(
    val color: Int,
    val glow: Boolean,
    val particles: Boolean,
    val dark: Boolean,
    val reduced: Boolean,
    val lite: Boolean,
)

@Composable
fun rememberSelectionLook(color: Int, glow: Boolean, particles: Boolean, dark: Boolean = P.isDark): SelectionLook {
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val lite = remember(context, preview) { !preview && FallbackArtCache.lite(context) }
    return SelectionLook(color, glow, particles, dark, reduced, lite)
}

/**
 * El marco de selección de una card de forma [shape]. Va **antes** del
 * recorte de la card (para que halo, luz y partículas asomen por fuera) y
 * después de su escala (para acompañarla). Para que el halo no quede bajo las
 * vecinas, la card seleccionada lleva `zIndex` en la raíz de su elemento.
 */
fun Modifier.selectionFrame(selected: Boolean, look: SelectionLook, shape: Shape): Modifier =
    this then SelectionFrameElement(selected, look, shape)

private data class SelectionFrameElement(
    val selected: Boolean,
    val look: SelectionLook,
    val shape: Shape,
) : ModifierNodeElement<SelectionFrameNode>() {
    override fun create() = SelectionFrameNode(selected, look, shape)
    override fun update(node: SelectionFrameNode) = node.update(selected, look, shape)
    override fun InspectorInfo.inspectableProperties() {
        name = "selectionFrame"
        properties["selected"] = selected
    }
}

private class SelectionFrameNode(
    private var selected: Boolean,
    private var look: SelectionLook,
    private var shape: Shape,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {

    private var reveal: Animatable<Float, AnimationVector1D>? = null
    private var fade: Animatable<Float, AnimationVector1D>? = null
    private var revealJob: Job? = null
    private var fadeJob: Job? = null
    private var loop: Job? = null
    private var seconds = 0f
    private var geometry: FrameGeometry? = null
    private var ink: SelectionInk? = null
    private var inkDensity = 0f
    private var dust: Stardust? = null
    private var dustDirty = true
    /** Vista previa de Android Studio: todo encendido desde el primer fotograma. */
    private var still = false

    override fun onAttach() {
        still = currentValueOf(LocalInspectionMode)
        if (selected) enter()
        // Desmontada a mitad del fundido de salida: vuelve apagada.
        else if ((fade?.value ?: 0f) > 0f) fade = Animatable(0f)
    }

    override fun onDetach() {
        revealJob = null
        fadeJob = null
        loop = null
    }

    fun update(selected: Boolean, look: SelectionLook, shape: Shape) {
        val was = this.selected
        val lookChanged = look != this.look
        if (shape != this.shape) {
            this.shape = shape
            geometry?.invalidate()
            dustDirty = true
        }
        if (lookChanged) {
            this.look = look
            ink = null
            dustDirty = true
        }
        this.selected = selected
        if (!isAttached) return
        when {
            selected && !was -> enter()
            !selected && was -> exit()
            selected && lookChanged -> {
                if (look.reduced) settle(1f)
                syncLoop()
            }
        }
        invalidateDraw()
    }

    private val animate get() = !look.reduced && !still

    private fun enter() {
        seconds = 0f
        dustDirty = true
        revealJob?.cancel()
        fadeJob?.cancel()
        if (!animate) {
            settle(1f)
        } else {
            val r = Animatable(0f).also { reveal = it }
            val f = fade ?: Animatable(0f).also { fade = it }
            revealJob = coroutineScope.launch {
                r.animateTo(1f, tween(SelectionFx.IGNITE_MS, easing = Swift)) { invalidateDraw() }
            }
            fadeJob = coroutineScope.launch {
                f.animateTo(1f, tween(SelectionFx.FADE_IN_MS, easing = Swift)) { invalidateDraw() }
            }
        }
        syncLoop()
    }

    private fun exit() {
        revealJob?.cancel()
        fadeJob?.cancel()
        val f = fade
        if (!animate || f == null) {
            settle(0f)
            stopLoop()
            return
        }
        // El reloj sigue mientras se apaga: las partículas se desvanecen moviéndose.
        fadeJob = coroutineScope.launch {
            f.animateTo(0f, tween(SelectionFx.FADE_OUT_MS, easing = LinearEasing)) { invalidateDraw() }
            stopLoop()
        }
    }

    private fun settle(value: Float) {
        reveal = Animatable(1f)
        fade = Animatable(value)
        invalidateDraw()
    }

    private fun syncLoop() {
        val wants = selected && animate && (look.glow || look.particles)
        if (!wants) {
            stopLoop()
            return
        }
        if (loop?.isActive == true) return
        loop = coroutineScope.launch {
            var last = withFrameNanos { it }
            while (isActive) {
                withFrameNanos { now ->
                    // Tope al paso: volver de segundo plano no da un salto.
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    last = now
                    seconds += dt
                    val geo = geometry
                    val d = dust
                    if (look.particles && geo != null && geo.ready && d != null && !dustDirty) d.step(dt, geo, geo.density, Random)
                }
                invalidateDraw()
            }
        }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    override fun ContentDrawScope.draw() {
        val f = fade?.value ?: 0f
        if (f <= 0f) {
            drawContent()
            return
        }
        val geo = (geometry ?: FrameGeometry().also { geometry = it })
        geo.update(size, layoutDirection, this, shape)
        val ink = ink?.takeIf { inkDensity == density } ?: SelectionInks.of(look.color, look.dark, density).also {
            ink = it
            inkDensity = density
        }
        geo.brushes(ink)

        val moving = !look.reduced
        if (look.particles && moving && dustDirty) {
            val d = dust ?: Stardust().also { dust = it }
            d.reset(SelectionFx.particleCount(geo.perimeterDp, look.lite), geo, geo.density, Random, warm = still)
            dustDirty = false
        }

        val p = reveal?.value ?: 1f
        val bloom = f * SelectionFx.igniteBloom(p) * (if (animate) SelectionFx.breath(seconds) else 1f)

        if (look.glow) {
            drawUnderglow(geo, ink, bloom)
            drawBloom(geo, ink, bloom)
        }
        drawContent()
        drawRim(geo, ink, SelectionFx.igniteRim(p), f)
        if (look.glow) {
            drawPath(geo.specular, geo.specularBrush, alpha = f * p, style = geo.specularStroke)
            if (moving) drawSheen(geo, ink, f * SelectionFx.igniteBloom(p))
        }
        val d = dust
        if (look.particles && moving && d != null) {
            for (i in 0 until d.count) {
                val a = d.alpha(i) * f
                if (a < 0.01f) continue
                drawStar(ink.atlas, d.variant(i), d.isGlint(i), d.x(i), d.y(i), d.sizeDp(i) * geo.density, a, ink.blend)
            }
        }
    }

    private fun DrawScope.drawUnderglow(geo: FrameGeometry, ink: SelectionInk, k: Float) {
        val c = geo.underCenter
        withTransform({ scale(1f, geo.underScaleY, c) }) {
            drawCircle(geo.underBrush, radius = geo.underRadius, center = c, alpha = ink.palette.underglowAlpha * k, blendMode = ink.blend)
        }
    }

    private fun DrawScope.drawBloom(geo: FrameGeometry, ink: SelectionInk, k: Float) {
        val alphas = ink.palette.bloomAlphas
        clipPath(geo.outer, ClipOp.Difference) {
            for (i in geo.bloomStrokes.indices) {
                drawPath(geo.outer, ink.bloom, alpha = (alphas[i] * k).coerceIn(0f, 1f), style = geo.bloomStrokes[i], blendMode = ink.blend)
            }
        }
    }

    private fun DrawScope.drawRim(geo: FrameGeometry, ink: SelectionInk, drawn: Float, f: Float) {
        drawPath(geo.keyline, ink.keyline, alpha = f * drawn, style = geo.keylineStroke)
        val brush: Brush = if (look.glow) geo.rimBrush else geo.flatRim
        if (drawn >= 1f) {
            drawPath(geo.rim, brush, alpha = f, style = geo.rimStroke)
        } else if (drawn > 0f) {
            // Se dibuja desde arriba hacia los dos lados y se cierra abajo.
            val half = drawn * geo.length / 2f
            geo.segment(geo.ignite, geo.anchor - half, half * 2f)
            drawPath(geo.ignite, brush, alpha = f, style = geo.rimStroke)
        }
    }

    private fun DrawScope.drawSheen(geo: FrameGeometry, ink: SelectionInk, k: Float) {
        if (k <= 0f) return
        val head = geo.anchor + geo.sign * SelectionFx.sheenFraction(seconds, geo.perimeterDp) * geo.length
        val len = SelectionFx.sheenLengthDp(geo.perimeterDp) * geo.density
        // Tres tramos que acaban en la cabeza: cola larga y tenue, cabeza corta y viva.
        for (i in 0 until 3) {
            val l = len * SHEEN_SPANS[i]
            geo.segment(geo.seg[i], if (geo.sign > 0f) head - l else head, l)
            drawPath(geo.seg[i], ink.sheen, alpha = SHEEN_ALPHAS[i] * k, style = geo.sheenStrokes[i], blendMode = ink.blend)
        }
        val at = geo.position(head)
        drawStar(ink.atlas, 0, false, at.x, at.y, 2.6f * geo.density, 0.9f * k, ink.blend)
    }

    private companion object {
        val SHEEN_SPANS = floatArrayOf(1f, 0.5f, 0.2f)
        val SHEEN_ALPHAS = floatArrayOf(0.22f, 0.45f, 0.9f)
    }
}

/**
 * Lo que depende del tamaño y la forma de la card: contornos (el exterior y
 * los interiores del filo), su medida, dónde está "arriba en el centro",
 * hacia dónde gira el trazado y los pinceles. Se rehace solo si cambia algo.
 */
private class FrameGeometry : PerimeterSampler {
    private var size = Size.Unspecified
    private var direction: LayoutDirection? = null
    private var shape: Shape? = null
    var density = 1f
        private set
    var ready = false
        private set

    val outer = Path()
    val rim = Path()
    val keyline = Path()
    val specular = Path()
    val ignite = Path()
    val seg = arrayOf(Path(), Path(), Path())
    private val measure = PathMeasure()
    var length = 0f
        private set
    var perimeterDp = 0f
        private set
    /** Distancia (sobre el filo) del punto más alto y centrado: ahí nace el trazo. */
    var anchor = 0f
        private set
    /** +1 si el trazado va en sentido horario en pantalla; -1 si no. */
    var sign = 1f
        private set

    var rimStroke = Stroke(1f)
        private set
    var keylineStroke = Stroke(1f)
        private set
    var specularStroke = Stroke(1f)
        private set
    var bloomStrokes = arrayOf(Stroke(1f), Stroke(1f), Stroke(1f))
        private set
    var sheenStrokes = arrayOf(Stroke(1f), Stroke(1f), Stroke(1f))
        private set

    var underCenter = Offset.Zero
        private set
    var underRadius = 1f
        private set
    var underScaleY = 1f
        private set

    private var brushInk: SelectionInk? = null
    lateinit var rimBrush: Brush
        private set
    lateinit var flatRim: Brush
        private set
    lateinit var specularBrush: Brush
        private set
    lateinit var underBrush: Brush
        private set

    fun invalidate() {
        size = Size.Unspecified
        ready = false
    }

    fun update(size: Size, direction: LayoutDirection, d: Density, shape: Shape) {
        if (ready && size == this.size && direction == this.direction && d.density == density && shape == this.shape) return
        this.size = size
        this.direction = direction
        this.shape = shape
        density = d.density
        brushInk = null

        val px = d.density
        val rimW = 1.75f * px
        outer.reset()
        outer.addOutline(shape.createOutline(size, direction, d))
        inset(rim, shape, size, direction, d, rimW / 2f)
        inset(keyline, shape, size, direction, d, rimW + 0.5f * px)
        inset(specular, shape, size, direction, d, rimW + 1.1f * px)

        measure.setPath(rim, false)
        length = measure.length
        perimeterDp = length / px
        findAnchor()

        rimStroke = Stroke(rimW)
        keylineStroke = Stroke(1f * px)
        specularStroke = Stroke(1.2f * px)
        // Trazos centrados en el contorno: la mitad de fuera es el halo.
        bloomStrokes = arrayOf(Stroke(2f * 3f * px), Stroke(2f * 6.5f * px), Stroke(2f * 11f * px))
        sheenStrokes = arrayOf(
            Stroke(3.5f * px, cap = StrokeCap.Round),
            Stroke(2.75f * px, cap = StrokeCap.Round),
            Stroke(2f * px, cap = StrokeCap.Round),
        )

        underRadius = size.width * 0.58f
        underCenter = Offset(size.width / 2f, size.height + 4f * px)
        underScaleY = (10f * px / underRadius).coerceAtMost(1f)
        ready = length > 0f
    }

    fun brushes(ink: SelectionInk) {
        if (brushInk === ink) return
        brushInk = ink
        val w = size.width
        val h = size.height
        rimBrush = Brush.linearGradient(
            0f to ink.rimLight,
            0.45f to ink.rim,
            1f to ink.rim,
            start = Offset.Zero,
            end = Offset(w, h),
        )
        flatRim = SolidColor(ink.rim)
        val m = minOf(w, h) * 0.55f
        specularBrush = Brush.linearGradient(
            listOf(ink.sheen.copy(alpha = if (ink.dark) 0.55f else 0.75f), Color.Transparent),
            start = Offset.Zero,
            end = Offset(m, m),
        )
        underBrush = Brush.radialGradient(
            0f to ink.underglow,
            0.45f to ink.underglow.copy(alpha = 0.35f),
            1f to Color.Transparent,
            center = underCenter,
            radius = underRadius,
        )
    }

    private fun inset(path: Path, shape: Shape, size: Size, direction: LayoutDirection, d: Density, by: Float) {
        path.reset()
        val inner = Size((size.width - 2f * by).coerceAtLeast(1f), (size.height - 2f * by).coerceAtLeast(1f))
        path.addOutline(shape.createOutline(inner, direction, d))
        path.translate(Offset(by, by))
    }

    private fun findAnchor() {
        if (length <= 0f) return
        val cx = size.width / 2f
        var top = Float.MAX_VALUE
        for (i in 0 until ANCHOR_SAMPLES) top = minOf(top, measure.getPosition(length * i / ANCHOR_SAMPLES).y)
        var best = 0f
        var bestDx = Float.MAX_VALUE
        for (i in 0 until ANCHOR_SAMPLES) {
            val dist = length * i / ANCHOR_SAMPLES
            val p = measure.getPosition(dist)
            if (p.y > top + density) continue
            val dx = abs(p.x - cx)
            if (dx < bestDx) {
                bestDx = dx
                best = dist
            }
        }
        anchor = best
        val ahead = measure.getPosition(wrap(best + 2f * density))
        sign = if (ahead.x >= measure.getPosition(best).x) 1f else -1f
    }

    private fun wrap(d: Float): Float = ((d % length) + length) % length

    fun position(distance: Float): Offset = measure.getPosition(wrap(distance))

    /** El tramo del filo de [from] a [from] + [len] (en el orden del trazado), dando la vuelta si hace falta. */
    fun segment(dst: Path, from: Float, len: Float) {
        dst.reset()
        if (length <= 0f || len <= 0f) return
        val start = wrap(from)
        val end = start + len.coerceAtMost(length)
        if (end <= length) {
            measure.getSegment(start, end, dst, true)
        } else {
            measure.getSegment(start, length, dst, true)
            measure.getSegment(0f, end - length, dst, true)
        }
    }

    override fun sample(fraction: Float, out: FloatArray) {
        val dist = wrap(fraction * length)
        val p = measure.getPosition(dist)
        val t = measure.getTangent(dist)
        out[0] = p.x
        out[1] = p.y
        out[2] = sign * t.y
        out[3] = -sign * t.x
    }

    private companion object {
        const val ANCHOR_SAMPLES = 128
    }
}
