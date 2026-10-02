package com.elyndra.launcher.ui.intro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import com.elyndra.launcher.R
import com.elyndra.launcher.data.ColorMath
import kotlin.math.ceil
import kotlin.math.min

/**
 * Lo caro de la intro, pintado una sola vez fuera del hilo principal: el
 * rótulo (cuerpo, filo metálico y bisel), su resplandor o sombra con el
 * desenfoque ya hecho, la máscara del barrido de luz y los sprites de las
 * partículas. Cada fotograma solo copia mapas de bits.
 *
 * Todas las capas del rótulo comparten tamaño y origen, así que se dibujan
 * en la misma posición.
 */
class IntroArt private constructor(
    private val bitmaps: List<Bitmap>,
    val depth: ImageBitmap,
    val body: ImageBitmap,
    val shine: ImageBitmap,
    val particle: ImageBitmap,
    val ember: ImageBitmap,
    /** Ancho y alto de las capas del rótulo. */
    val wordWidth: Int,
    val wordHeight: Int,
    /** Ancho de tinta de las letras y su caja vertical, relativos a la capa. */
    val inkWidth: Float,
    val inkTop: Float,
    val inkBottom: Float,
    val textSize: Float,
) {
    fun release() = bitmaps.forEach { it.recycle() }

    companion object {

        fun render(context: Context, width: Int, height: Int, density: Float, palette: IntroPalette): IntroArt {
            val bitmaps = ArrayList<Bitmap>(6)
            fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { bitmaps += it }

            val display = ResourcesCompat.getFont(context, R.font.cinzel_bold) ?: Typeface.create(Typeface.SERIF, Typeface.BOLD)
            val word = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = display
                letterSpacing = 0.14f
                textAlign = Paint.Align.CENTER
                textSize = 100f
            }
            // Tamaño: el rótulo ocupa ~78 % del ancho en vertical, sin pasar de
            // un 16 % del alto (horizontal) ni de 120 dp de cuerpo (tabletas).
            val perUnit = word.measureText(WORD) / 100f
            val size = min(min(width * 0.78f / perUnit, height * 0.16f), 120f * density)
            word.textSize = size

            val ink = Rect().also { word.getTextBounds(WORD, 0, WORD.length, it) }
            val pad = size * 0.55f
            val w = ceil(ink.width() + pad * 2).toInt()
            val h = ceil(ink.height() + pad * 2).toInt()
            val cx = w / 2f
            val baseline = pad - ink.top
            val top = pad
            val bottom = pad + ink.height()

            val depth = bitmap(w, h)
            paintDepth(Canvas(depth), word, palette, cx, baseline, size)

            val body = bitmap(w, h)
            paintBody(body, word, palette, cx, baseline, top, bottom, size)

            val shine = bitmap(w, h)
            Canvas(shine).apply {
                val p = Paint(word).apply { color = palette.sheen }
                drawText(WORD, cx, baseline, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = size * 0.04f
                drawText(WORD, cx, baseline, p)
            }

            val particle = paintSprite(palette, (10f * density).toInt().coerceAtLeast(12), ::bitmap)
            val ember = paintSprite(palette, (8f * density).toInt().coerceAtLeast(10), ::bitmap)

            return IntroArt(
                bitmaps = bitmaps,
                depth = depth.asImageBitmap(),
                body = body.asImageBitmap(),
                shine = shine.asImageBitmap(),
                particle = particle.asImageBitmap(),
                ember = ember.asImageBitmap(),
                wordWidth = w,
                wordHeight = h,
                inkWidth = ink.width().toFloat(),
                inkTop = top,
                inkBottom = bottom,
                textSize = size,
            )
        }

        private const val WORD = "ELYNDRA"

        /** Oscuro: halo amplio más uno ceñido. Claro: sombra proyectada suave, sin halo. */
        private fun paintDepth(canvas: Canvas, word: Paint, palette: IntroPalette, cx: Float, baseline: Float, size: Float) {
            val p = Paint(word)
            if (palette.dark) {
                p.color = ColorMath.withAlpha(palette.depth, 0.5f)
                p.maskFilter = BlurMaskFilter(size * 0.3f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(WORD, cx, baseline, p)
                p.color = ColorMath.withAlpha(palette.depth, 0.85f)
                p.maskFilter = BlurMaskFilter(size * 0.07f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(WORD, cx, baseline, p)
            } else {
                p.color = ColorMath.withAlpha(palette.depth, 0.14f)
                p.maskFilter = BlurMaskFilter(size * 0.2f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(WORD, cx, baseline + size * 0.08f, p)
                p.color = ColorMath.withAlpha(palette.depth, 0.36f)
                p.maskFilter = BlurMaskFilter(size * 0.05f, BlurMaskFilter.Blur.NORMAL)
                canvas.drawText(WORD, cx, baseline + size * 0.04f, p)
            }
        }

        /**
         * Letras oscuras con bisel interior (luz arriba, sombra abajo) y un filo
         * de oro pulido: un degradado vertical con una banda oscura en medio,
         * como un metal que refleja un horizonte, y un brillo fino arriba.
         */
        private fun paintBody(target: Bitmap, word: Paint, palette: IntroPalette, cx: Float, baseline: Float, top: Float, bottom: Float, size: Float) {
            val canvas = Canvas(target)
            val fill = Paint(word).apply {
                shader = LinearGradient(0f, top, 0f, bottom, palette.bodyTop, palette.bodyBottom, Shader.TileMode.CLAMP)
            }
            canvas.drawText(WORD, cx, baseline, fill)

            val bevel = size * 0.035f
            val atop = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }
            for ((dy, color, alpha) in listOf(
                Triple(-bevel, palette.bevelLight, if (palette.dark) 110 else 150),
                Triple(bevel, palette.bevelDark, if (palette.dark) 150 else 110),
            )) {
                val band = bevelBand(target, word, cx, baseline, dy, color, size)
                atop.alpha = alpha
                canvas.drawBitmap(band, 0f, 0f, atop)
                band.recycle()
            }

            val rim = Paint(word).apply {
                style = Paint.Style.STROKE
                strokeJoin = Paint.Join.ROUND
                strokeWidth = size * if (palette.dark) 0.042f else 0.036f
                shader = LinearGradient(
                    0f, top, 0f, bottom,
                    intArrayOf(palette.rimLight, palette.rim, palette.rimDeep, palette.rim, palette.rimLight),
                    floatArrayOf(0f, 0.36f, 0.55f, 0.74f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawText(WORD, cx, baseline, rim)

            val spec = Paint(rim).apply {
                strokeWidth = size * 0.014f
                shader = LinearGradient(
                    0f, top, 0f, top + (bottom - top) * 0.45f,
                    ColorMath.mix(palette.rimLight, 0xFFFFFFFF.toInt(), 0.6f),
                    ColorMath.withAlpha(palette.rimLight, 0f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawText(WORD, cx, baseline, spec)
        }

        /**
         * La franja que queda de la letra al restarle ella misma desplazada
         * [dy]: con dy < 0 es el borde de arriba, con dy > 0 el de abajo.
         */
        private fun bevelBand(like: Bitmap, word: Paint, cx: Float, baseline: Float, dy: Float, color: Int, size: Float): Bitmap {
            val band = Bitmap.createBitmap(like.width, like.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(band)
            val p = Paint(word).apply {
                this.color = color
                maskFilter = BlurMaskFilter(size * 0.012f, BlurMaskFilter.Blur.NORMAL)
            }
            c.drawText(WORD, cx, baseline, p)
            p.maskFilter = null
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            c.drawText(WORD, cx, baseline - dy, p)
            return band
        }

        /**
         * Oscuro: una mota de luz (centro casi blanco, halo del color) que se
         * suma. Claro: una mota de oro pulido, opaca, con su brillo arriba a la
         * izquierda y el borde más oscuro, que se ve por mezcla normal.
         */
        private fun paintSprite(palette: IntroPalette, px: Int, bitmap: (Int, Int) -> Bitmap): Bitmap {
            val out = bitmap(px, px)
            val c = Canvas(out)
            val r = px / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (palette.dark) {
                p.shader = RadialGradient(
                    r, r, r,
                    intArrayOf(palette.particleLight, ColorMath.withAlpha(palette.particle, 0.55f), ColorMath.withAlpha(palette.particle, 0f)),
                    floatArrayOf(0f, 0.22f, 1f),
                    Shader.TileMode.CLAMP,
                )
                c.drawCircle(r, r, r, p)
            } else {
                p.shader = RadialGradient(
                    r * 0.72f, r * 0.68f, r * 0.9f,
                    intArrayOf(palette.particleLight, palette.particle, palette.rimDeep),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
                c.drawCircle(r, r, r * 0.62f, p)
            }
            return out
        }
    }
}
