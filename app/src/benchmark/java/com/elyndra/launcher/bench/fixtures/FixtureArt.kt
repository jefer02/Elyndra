package com.elyndra.launcher.bench.fixtures

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import com.elyndra.launcher.bench.fixtures.FixtureLibrary.ArtSpec
import com.elyndra.launcher.metadata.ArtCandidate
import com.elyndra.launcher.metadata.ArtKind
import java.io.File

/**
 * El arte de la biblioteca de prueba, pintado en el propio móvil la primera
 * vez (WebP en filesDir/benchfixtures). Así no hay imágenes en el repositorio
 * ni en ningún APK, y siguen siendo archivos de verdad que Coil decodifica
 * como los de cualquier biblioteca: mismos tamaños, proporciones y
 * transparencias que el arte que llega de los servicios.
 */
object FixtureArt {

    private const val QUALITY = 80
    private const val MAX_CANDIDATES = 12

    /** Pinta lo que falte (las que ya están se dejan). */
    fun render(filesDir: File, specs: List<ArtSpec>) {
        for (spec in specs) {
            val out = File(filesDir, spec.path)
            if (out.isFile && out.length() > 0) continue
            out.parentFile?.mkdirs()
            val bitmap = Bitmap.createBitmap(spec.width, spec.height, Bitmap.Config.ARGB_8888)
            try {
                draw(Canvas(bitmap), spec)
                val tmp = File(out.parentFile, out.name + ".tmp")
                tmp.outputStream().use { bitmap.compress(webp(), QUALITY, it) }
                tmp.renameTo(out)
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * Para el selector de arte: imágenes de la misma clase de otros juegos de
     * prueba, como `file://` (Coil las lee del disco; nada va a la red).
     */
    fun candidates(filesDir: File, key: String, kind: ArtKind): List<ArtCandidate> {
        val wanted = when (kind) {
            ArtKind.Cover -> ArtSpec.Kind.Cover
            ArtKind.Background -> ArtSpec.Kind.Hero
            ArtKind.Logo -> ArtSpec.Kind.Logo
            ArtKind.Icon -> ArtSpec.Kind.Icon
        }
        // Siempre las mismas para el mismo juego: el orden depende solo de la clave.
        val all = FixtureLibrary.art.filter { it.kind == wanted }
        if (all.isEmpty()) return emptyList()
        val start = Math.floorMod(key.hashCode(), all.size)
        return (all.indices).asSequence()
            .map { all[(start + it) % all.size] }
            .map { it to File(filesDir, it.path) }
            .filter { (_, file) -> file.isFile }
            .take(MAX_CANDIDATES)
            .map { (spec, file) ->
                val uri = Uri.fromFile(file).toString()
                ArtCandidate(url = uri, thumb = uri, label = "${spec.label} · ${spec.width}×${spec.height}")
            }
            .toList()
    }

    @Suppress("DEPRECATION")
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    /** Degradado del tono de cada juego, unas formas y el nombre: se distinguen a simple vista. */
    private fun draw(canvas: Canvas, spec: ArtSpec) {
        val w = spec.width.toFloat()
        val h = spec.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val base = Color.HSVToColor(floatArrayOf(spec.hue, 0.62f, 0.78f))
        val deep = Color.HSVToColor(floatArrayOf((spec.hue + 40f) % 360f, 0.85f, 0.22f))
        if (spec.kind == ArtSpec.Kind.Logo) {
            // Un logo de verdad es texto sobre transparencia.
            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.setShadowLayer(h * 0.04f, 0f, h * 0.02f, deep)
            drawFitted(canvas, spec.label.uppercase(), w, h, paint, maxSize = h * 0.42f)
            return
        }
        paint.shader = LinearGradient(0f, 0f, w, h, base, deep, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        val unit = minOf(w, h)
        paint.color = Color.argb(70, 255, 255, 255)
        canvas.drawCircle(w * 0.78f, h * 0.28f, unit * 0.32f, paint)
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawOval(RectF(-w * 0.2f, h * 0.62f, w * 0.7f, h * 1.25f), paint)
        if (spec.kind == ArtSpec.Kind.Icon) {
            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
            drawFitted(canvas, initials(spec.label), w, h, paint, maxSize = h * 0.46f)
        } else {
            paint.color = Color.WHITE
            paint.typeface = Typeface.DEFAULT_BOLD
            drawFitted(canvas, spec.label, w, h * 0.42f, paint, maxSize = unit * 0.14f, top = h * 0.5f)
        }
    }

    private fun drawFitted(canvas: Canvas, text: String, w: Float, h: Float, paint: Paint, maxSize: Float, top: Float = 0f) {
        paint.textSize = maxSize
        val width = paint.measureText(text)
        if (width > w * 0.9f) paint.textSize = maxSize * (w * 0.9f / width)
        paint.textAlign = Paint.Align.CENTER
        val y = top + h / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, w / 2f, y, paint)
    }

    private fun initials(label: String): String =
        label.split(' ').filter { it.firstOrNull()?.isLetter() == true }.take(2).joinToString("") { it.first().uppercase() }
}
