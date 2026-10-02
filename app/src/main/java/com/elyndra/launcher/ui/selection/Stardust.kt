package com.elyndra.launcher.ui.selection

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.elyndra.launcher.data.ColorMath
import kotlin.math.ceil
import kotlin.math.roundToInt

/* ─────────────────────────────────────────────────────────────
   Polvo estelar: los sprites de las partículas, pintados una sola
   vez en un atlas pequeño (sin desenfoque por fotograma).

   Fila 0: punto — núcleo champán-blanco con un halo suave del color.
   Fila 1: destello de cuatro puntas, para alguna partícula rara.
   Una columna por variante de color de la paleta.

   El núcleo ocupa 1/[SPRITE_SCALE] del lado del sprite: para un
   núcleo de d px se dibuja el sprite a d·SPRITE_SCALE px.
   ───────────────────────────────────────────────────────────── */

/** Lado del sprite respecto al diámetro de su núcleo. */
internal const val SPRITE_SCALE = 5f

/** Lado de una celda del atlas, en dp: el sprite más grande sin estirarse. */
private const val CELL_DP = 16f

class StardustAtlas(val image: ImageBitmap, val cell: Int) {
    val cellSize = IntSize(cell, cell)

    fun offset(variant: Int, glint: Boolean): IntOffset = IntOffset(variant * cell, if (glint) cell else 0)

    companion object {
        fun build(palette: SelectionPalette, cell: Int): StardustAtlas {
            val n = palette.particles.size
            val bitmap = Bitmap.createBitmap(cell * n, cell * 2, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (v in 0 until n) {
                val color = palette.particles[v]
                val cx = v * cell + cell / 2f
                drawDot(canvas, paint, cx, cell / 2f, cell / 2f, color, palette.core, palette.dark)
                drawGlint(canvas, paint, cx, cell + cell / 2f, cell / 2f, color, palette.core, palette.dark)
            }
            return StardustAtlas(bitmap.asImageBitmap(), cell)
        }

        private fun withAlpha(c: Int, a: Float) = ColorMath.withAlpha(c, a)

        private fun drawDot(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, color: Int, core: Int, dark: Boolean) {
            val k = if (dark) 1.35f else 1.15f
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(withAlpha(color, 0.55f * k), withAlpha(color, 0.22f * k), withAlpha(color, 0.06f * k), withAlpha(color, 0f)),
                floatArrayOf(0f, 0.3f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r, paint)
            val coreR = r / SPRITE_SCALE
            paint.shader = RadialGradient(
                cx, cy, coreR * 1.45f,
                intArrayOf(core, core, withAlpha(color, 0.9f), withAlpha(color, 0f)),
                floatArrayOf(0f, 0.5f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, coreR * 1.45f, paint)
            paint.shader = null
        }

        /** Cuatro rayos finos que se afilan hacia fuera, sobre un halo más corto. */
        private fun drawGlint(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, color: Int, core: Int, dark: Boolean) {
            val k = if (dark) 1.35f else 1.15f
            paint.shader = RadialGradient(
                cx, cy, r * 0.55f,
                intArrayOf(withAlpha(color, 0.5f * k), withAlpha(color, 0.12f * k), withAlpha(color, 0f)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, r * 0.55f, paint)
            paint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(core, withAlpha(color, 0.75f), withAlpha(color, 0f)),
                floatArrayOf(0f, 0.32f, 1f),
                Shader.TileMode.CLAMP,
            )
            val w = r * 0.085f
            val ray = Path().apply {
                moveTo(cx - r, cy); lineTo(cx, cy - w); lineTo(cx + r, cy); lineTo(cx, cy + w); close()
                moveTo(cx, cy - r); lineTo(cx + w, cy); lineTo(cx, cy + r); lineTo(cx - w, cy); close()
            }
            canvas.drawPath(ray, paint)
            paint.shader = null
            paint.color = core
            canvas.drawCircle(cx, cy, r / SPRITE_SCALE, paint)
        }
    }
}

/**
 * Todo lo que el marco y el polvo necesitan de un color y un tema, ya como
 * colores de Compose, con su atlas. Se construye una vez por combinación.
 */
@Immutable
class SelectionInk(val palette: SelectionPalette, val atlas: StardustAtlas) {
    val dark = palette.dark
    /** Luz que suma en oscuro; en claro, mezcla normal (Plus sobre el perla se pierde). */
    val blend: BlendMode = if (dark) BlendMode.Plus else BlendMode.SrcOver
    val rim = Color(palette.rim)
    val rimLight = Color(palette.rimLight)
    val keyline = Color(palette.keyline).copy(alpha = palette.keylineAlpha)
    val bloom = Color(palette.bloom)
    val underglow = Color(palette.underglow)
    val sheen = Color(palette.sheen)
}

/** Caché pequeña (selección, estela de Masha y vistas previas): sin atlas repetidos. */
object SelectionInks {
    private data class Key(val color: Int, val dark: Boolean, val cell: Int)

    private val cache = object : LinkedHashMap<Key, SelectionInk>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, SelectionInk>?) = size > 6
    }

    fun of(color: Int, dark: Boolean, density: Float): SelectionInk {
        val key = Key(color, dark, ceil(CELL_DP * density).toInt().coerceAtLeast(8))
        return cache.getOrPut(key) {
            val palette = SelectionPalettes.derive(color, dark)
            SelectionInk(palette, StardustAtlas.build(palette, key.cell))
        }
    }
}

/**
 * Una partícula del atlas centrada en ([x], [y]) con un núcleo de
 * [coreDiameterPx]. La posición es subpíxel (traslación + filtrado bilineal):
 * a la velocidad del polvo, redondear a píxeles se vería a saltos.
 */
internal fun DrawScope.drawStar(
    atlas: StardustAtlas,
    variant: Int,
    glint: Boolean,
    x: Float,
    y: Float,
    coreDiameterPx: Float,
    alpha: Float,
    blend: BlendMode,
) {
    val side = (coreDiameterPx * SPRITE_SCALE).roundToInt().coerceAtLeast(2)
    val half = side / 2f
    translate(x - half, y - half) {
        drawImage(
            atlas.image,
            srcOffset = atlas.offset(variant, glint),
            srcSize = atlas.cellSize,
            dstSize = IntSize(side, side),
            alpha = alpha,
            blendMode = blend,
            filterQuality = FilterQuality.Low,
        )
    }
}
