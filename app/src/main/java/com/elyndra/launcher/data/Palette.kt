package com.elyndra.launcher.data

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.elyndra.launcher.R

/* ─────────────────────────────────────────────────────────────
   Paleta del diseño (Elyndra.dc.html): marca, acentos, tintes del
   cristal y pares de degradado de las carátulas procedurales.

   Modo claro / oscuro
   ───────────────────
   `P` guarda el tema en un estado de Compose: cambiar [P.isDark]
   recompone toda la interfaz sin tocar ninguno de los ~145 sitios
   que ya leen `P.ink`, `P.paper`… Eso mantiene el estilo intacto,
   que es justo lo que se pide: solo cambian los valores.

   Ojo con los dos papeles de la "tinta":

     · [ink] / [ink2] son color de TEXTO — se invierten con el tema.
     · [shade] es tinta FÍSICA (sombras, velos, el fondo del hero y
       el cristal oscuro) — nunca se invierte: una sombra sigue
       siendo oscura en modo oscuro.

   Antes ambos usos compartían `ink`; separarlos evita que en modo
   oscuro las sombras y los velos se vuelvan neblina blanca.
   ───────────────────────────────────────────────────────────── */

/** Paleta base de marca (`P` en el diseño). */
object P {

    /** Tema activo. Lo fija SettingsController al arrancar y al conmutar. */
    var isDark by mutableStateOf(false)

    /* ── Marca: constantes, no dependen del tema ─────────────── */
    val light = Color(0xFFF59659)
    val strong = Color(0xFFEE7E28)
    val dark = Color(0xFFE26D19)
    val green = Color(0xFF9BD494)

    /** Tinta física: sombras, velos, fondo del hero y cristal oscuro. Siempre oscura. */
    val shade = Color(0xFF333333)

    /* ── Dependientes del tema ───────────────────────────────── */

    /** Fondo general de la app. */
    val paper: Color get() = if (isDark) Color(0xFF13161A) else Color(0xFFF6F8F9)

    /** Base opaca de diálogos y hojas (un punto por encima del fondo). */
    val surface: Color get() = if (isDark) Color(0xFF1D2227) else Color(0xFFF6F8F9)

    /** Texto principal. Casi blanco en oscuro: varios rótulos se pintan además con alpha. */
    val ink: Color get() = if (isDark) Color(0xFFF4F7F9) else Color(0xFF333333)

    /** Texto secundario. */
    val ink2: Color get() = if (isDark) Color(0xFFC2CBD2) else Color(0xFF555555)

    val red: Color get() = if (isDark) Color(0xFFFF8497) else Color(0xFFD33F5B)

    /**
     * Relleno de las piezas pequeñas (píldoras, botones fantasma, etiquetas).
     * En claro es un velo blanco; en oscuro tiene que ser un velo **claro sobre
     * oscuro**, o el texto [ink] —casi blanco— se pierde encima.
     */
    val chip: Color
        get() = if (isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.65f)

    /** Filo de las láminas de cristal y de las cards. */
    val hairline: Color
        get() = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.72f)
}

/** Color de acento: 10 opciones (las 3 de marca + 7 más). El nombre se traduce. */
data class Accent(val id: String, @StringRes val nameRes: Int, val a: Color, val b: Color)

val ACCENTS = listOf(
    Accent("mandarina", R.string.accent_mandarina, Color(0xFFF59659), Color(0xFFE26D19)),
    Accent("fuego", R.string.accent_fuego, Color(0xFFEE7E28), Color(0xFFB24A08)),
    Accent("menta", R.string.accent_menta, Color(0xFF9BD494), Color(0xFF3F9A62)),
    Accent("cobalto", R.string.accent_cobalto, Color(0xFF84B6F7), Color(0xFF2C63C8)),
    Accent("lila", R.string.accent_lila, Color(0xFFC2A6F2), Color(0xFF7343CE)),
    Accent("coral", R.string.accent_coral, Color(0xFFF79BA8), Color(0xFFD33F5B)),
    Accent("turquesa", R.string.accent_turquesa, Color(0xFF8CD9D3), Color(0xFF1E9A93)),
    Accent("oro", R.string.accent_oro, Color(0xFFF3CE7A), Color(0xFFC08A12)),
    Accent("chicle", R.string.accent_chicle, Color(0xFFF5A3D6), Color(0xFFC02E9B)),
    Accent("grafito", R.string.accent_grafito, Color(0xFF8C9196), Color(0xFF333333)),
)

/** Tintes del cristal: 8. */
data class Tint(val id: String, val color: Color)

val TINTS = listOf(
    Tint("papel", Color(0xFFFFFFFF)),
    Tint("arena", Color(0xFFF59659)),
    Tint("ámbar", Color(0xFFEE7E28)),
    Tint("cobre", Color(0xFFE26D19)),
    Tint("menta", Color(0xFF9BD494)),
    Tint("cielo", Color(0xFF84B6F7)),
    Tint("lila", Color(0xFFC2A6F2)),
    Tint("humo", Color(0xFF555555)),
)

/** Pares de degradado para carátulas. */
val PAIRS = listOf(
    Color(0xFFF59659) to Color(0xFFE26D19),
    Color(0xFFEE7E28) to Color(0xFF333333),
    Color(0xFF9BD494) to Color(0xFFEE7E28),
    Color(0xFF84B6F7) to Color(0xFF2C63C8),
    Color(0xFFC2A6F2) to Color(0xFF7343CE),
    Color(0xFFF79BA8) to Color(0xFFD33F5B),
    Color(0xFF8CD9D3) to Color(0xFF1E9A93),
    Color(0xFFF3CE7A) to Color(0xFFC08A12),
    Color(0xFF9BD494) to Color(0xFF333333),
    Color(0xFFF5A3D6) to Color(0xFFC02E9B),
    Color(0xFF555555) to Color(0xFF333333),
    Color(0xFFF59659) to Color(0xFF9BD494),
)

/** "742" → "12h 22m". */
fun fmtMinutes(m: Int): String = "${m / 60}h ${m % 60}m"

/** Índice de par de degradado derivado del nombre, igual que `art()` en el diseño. */
fun pairIndexFor(seed: String): Int =
    if (seed.isEmpty()) 0 else ((seed.length * 7 + seed[0].code) % PAIRS.size)
