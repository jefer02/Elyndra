package com.elyndra.launcher.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.elyndra.launcher.R
import com.elyndra.launcher.data.Accent
import com.elyndra.launcher.data.ACCENTS
import com.elyndra.launcher.data.TINTS
import com.elyndra.launcher.data.Tint

/* ─────────────────────────────────────────────────────────────
   Tipografía — Poppins, los mismos seis pesos que carga el diseño
   (300…800). Los .ttf vienen de Google Fonts (licencia OFL, ver
   POPPINS-OFL.txt en la raíz) y viven en `res/font`.
   ───────────────────────────────────────────────────────────── */

val Poppins = FontFamily(
    Font(R.font.poppins_light, FontWeight.Light),
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_semibold, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
)

/** Familia activa, provista por [ElyndraTheme]. */
val LocalPoppins = staticCompositionLocalOf<FontFamily> { Poppins }

/* ─────────────────────────────────────────────────────────────
   Estado visual global — el equivalente a las variables CSS
   --a1/--a2/--gb/--gbd que el diseño cuelga del contenedor.
   ───────────────────────────────────────────────────────────── */

@Immutable
data class ElyndraSkin(
    val accent: Accent,
    val tint: Tint,
    /** Radio de desenfoque del cristal, 0…40. */
    val blur: Int,
    /** Opacidad del cristal, 5…90 (%). */
    val alphaPct: Int,
    /** Intensidad del velo del hero, 20…85 (%). */
    val scrimPct: Int,
) {
    val a1: Color get() = accent.a
    val a2: Color get() = accent.b
    val alpha: Float get() = alphaPct / 100f
    val scrim: Float get() = scrimPct / 100f
}

val DefaultSkin = ElyndraSkin(
    accent = ACCENTS[0],
    tint = TINTS[0],
    blur = 16,
    alphaPct = 55,
    scrimPct = 62,
)

val LocalSkin = staticCompositionLocalOf { DefaultSkin }

/** Ancho de ventana: el diseño llama `L` (landscape) al layout de 892×412. */
val LocalLandscape = staticCompositionLocalOf { false }

@Composable
fun ElyndraTheme(skin: ElyndraSkin, landscape: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalLandscape provides landscape,
        LocalPoppins provides Poppins,
        content = content,
    )
}
