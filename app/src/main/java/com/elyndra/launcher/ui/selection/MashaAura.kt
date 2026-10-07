package com.elyndra.launcher.ui.selection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.components.FallbackArtCache
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Partículas alrededor del emblema; la mitad en calidad ligera. */
private const val AURA_COUNT = 14
private const val AURA_COUNT_LITE = 8

/**
 * Las partículas de Masha alrededor de su emblema: el mismo polvo estelar de
 * la selección (atlas de sprites pintado una vez, sin desenfoque), del color
 * elegido en Ajustes → Masha. Nacen en el canto del orbe y suben despacio
 * con un rizo; por detrás del emblema, así que solo asoman por fuera.
 *
 * Solo esta pieza tiene reloj y lo que se repinta es ella. Con "reducir
 * movimiento" quedan quietas, ya repartidas alrededor.
 */
@Composable
fun Modifier.mashaAura(color: Int): Modifier {
    val reduced = LocalReducedMotion.current
    val preview = LocalInspectionMode.current
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val dark = P.isDark
    val ink = remember(color, dark, density) { SelectionInks.of(color, dark, density) }
    val count = remember(context, preview) { if (!preview && FallbackArtCache.lite(context)) AURA_COUNT_LITE else AURA_COUNT }
    val dust = remember { Stardust(AURA_COUNT) }
    val clock = remember { mutableFloatStateOf(0f) }
    // Estado de la simulación que no recompone: tiempo ya simulado, tamaño y si hay que sembrar.
    val sim = remember { FloatArray(3) }
    val still = reduced || preview

    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                // Tope al paso: volver de segundo plano no da un salto.
                clock.floatValue += ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
            }
        }
    }

    return drawBehind {
        val r = size.minDimension / 2f
        if (r <= 0f) return@drawBehind
        val cx = size.width / 2f
        val cy = size.height / 2f
        val sampler = PerimeterSampler { f, out ->
            val a = f * 2f * PI.toFloat()
            val nx = cos(a)
            val ny = sin(a)
            out[0] = cx + nx * r
            out[1] = cy + ny * r
            out[2] = nx
            out[3] = ny
        }
        if (sim[2] != size.width) {
            sim[2] = size.width
            sim[0] = clock.floatValue
            dust.reset(count, sampler, density, Random, warm = still)
        }
        val now = clock.floatValue
        val dt = now - sim[0]
        if (dt > 0f) {
            dust.step(dt, sampler, density, Random)
            sim[0] = now
        }
        for (i in 0 until dust.count) {
            val a = dust.alpha(i)
            if (a <= 0f) continue
            drawStar(ink.atlas, dust.variant(i), dust.isGlint(i), dust.x(i), dust.y(i), dust.sizeDp(i) * density, a, ink.blend)
        }
    }
}
