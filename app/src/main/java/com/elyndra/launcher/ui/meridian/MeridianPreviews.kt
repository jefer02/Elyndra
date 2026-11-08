package com.elyndra.launcher.ui.meridian

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import com.elyndra.launcher.ui.components.BackChevron
import com.elyndra.launcher.ui.components.CapsuleShape
import com.elyndra.launcher.ui.components.ConsoleIconButton
import com.elyndra.launcher.ui.components.OpenButton
import com.elyndra.launcher.ui.components.dockSurface
import com.elyndra.launcher.ui.screens.EmulatorChip
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.ColorMath
import com.elyndra.launcher.data.P
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.ui.components.COVER_RATIO
import com.elyndra.launcher.ui.components.DotTabs
import com.elyndra.launcher.ui.components.rememberDotTabPills
import com.elyndra.launcher.ui.screens.PreviewHeroBar
import com.elyndra.launcher.ui.DockPalettes
import com.elyndra.launcher.ui.components.ArtFallback
import com.elyndra.launcher.ui.components.ArtVariant
import com.elyndra.launcher.ui.components.ElyText
import com.elyndra.launcher.ui.components.FallbackArt
import com.elyndra.launcher.ui.components.LocalScreenSize
import com.elyndra.launcher.ui.selection.rememberSelectionLook
import com.elyndra.launcher.ui.theme.DefaultSkin
import com.elyndra.launcher.ui.theme.ElyndraTheme
import com.elyndra.launcher.ui.theme.darkGlass
import com.elyndra.launcher.ui.theme.liquidGlass

/*
 * Meridian en ventanas apaisadas: tableta (1280 × 800), móviles (915 × 412 y
 * 800 × 360), el borde del umbral (640 dp) y letra a 1,5×; con 0, 2, 3, 40 y
 * 500 elementos (iconos y carátulas); sobre arte muy claro, muy oscuro,
 * colorido y sin arte, en claro y en oscuro, con el fondo adaptable (y sin
 * él). Además, el bloque del hero con logos de varias formas y la cabecera de
 * una carpeta sobre arte muy claro. El arte, las carátulas y los logos son
 * dibujos: la vista previa no carga archivos; los colores del fondo salen de
 * [ArtWash.extract] sobre el mismo dibujo.
 */

@Preview(name = "Tableta · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 1280, heightDp = 800)
@Preview(name = "Tableta · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 1280, heightDp = 800)
annotation class MeridianPreviews

/**
 * Las tabletas y la tele donde el cristal se veía deformado: 2560 × 1600 y
 * 1280 × 800 (1280 × 800 dp), 2000 × 1200 (1000 × 600 dp) y una tele de
 * 1920 × 1080 (960 × 540 dp), en claro (el cristal Frost) y en oscuro.
 */
@Preview(name = "Tableta 1280 × 800 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 1280, heightDp = 800)
@Preview(name = "Tableta 1280 × 800 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 1280, heightDp = 800)
@Preview(name = "Tableta 1000 × 600 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 1000, heightDp = 600)
@Preview(name = "Tableta 1000 × 600 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 1000, heightDp = 600)
@Preview(name = "Tele 960 × 540 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 960, heightDp = 540)
@Preview(name = "Tele 960 × 540 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 960, heightDp = 540)
annotation class TabletSizesPreviews

@Preview(name = "915 × 412 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 915, heightDp = 412)
@Preview(name = "915 × 412 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 915, heightDp = 412)
@Preview(name = "800 × 360 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 800, heightDp = 360)
@Preview(name = "800 × 360 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 800, heightDp = 360)
annotation class PhonePreviews

/**
 * Los móviles apaisados de las carátulas pequeñas: 1600 × 720 px (a 1,75 y a 2),
 * 2340 × 1080 px a densidad 3 y un móvil pequeño de 1280 × 576 px.
 */
@Preview(name = "1600 × 720 · 914 × 411 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 914, heightDp = 411)
@Preview(name = "1600 × 720 · 914 × 411 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 914, heightDp = 411)
@Preview(name = "1600 × 720 · 800 × 360 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 800, heightDp = 360)
@Preview(name = "2340 × 1080 · 780 × 360 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 780, heightDp = 360)
@Preview(name = "2340 × 1080 · 851 × 393 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 851, heightDp = 393)
@Preview(name = "1280 × 576 · 640 × 288 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 640, heightDp = 288)
annotation class PhoneCoverPreviews

@Preview(name = "Umbral 640 dp · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 640, heightDp = 360)
@Preview(name = "Umbral 640 dp · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 640, heightDp = 360)
annotation class ThresholdPreviews

@Preview(name = "Letra 1,5 · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 915, heightDp = 412, fontScale = 1.5f)
@Preview(name = "Letra 1,5 · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 1280, heightDp = 800, fontScale = 1.5f)
annotation class FontScalePreviews

/** La pantalla principal en un móvil apaisado: en claro, sin velo y con cada pieza en su cristal claro; en oscuro, la de siempre. */
@Preview(name = "Principal · móvil horizontal · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 915, heightDp = 412)
@Preview(name = "Principal · móvil horizontal · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 915, heightDp = 412)
annotation class MainLandscapePreviews

@Preview(name = "Bloque · claro", uiMode = Configuration.UI_MODE_NIGHT_NO, widthDp = 620, heightDp = 420)
@Preview(name = "Bloque · oscuro", uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 620, heightDp = 420)
annotation class HeroBlockPreviews

/** Un arte de mentira: su dibujo y los colores que [ArtWash] saca de él (null = sin arte). */
private class SampleArt(val colors: List<Color>?) {
    val brush: Brush? = colors?.let { Brush.verticalGradient(it) }

    /** Lo que leería la app de una copia de 24 × 24 del mismo degradado. */
    val wash: ArtColors? = colors?.let { list ->
        val n = ArtWash.SAMPLE
        val px = IntArray(n * n) { i ->
            val t = (i / n) / (n - 1f) * (list.size - 1)
            val k = t.toInt().coerceAtMost(list.size - 2)
            val a = list[k]
            val b = list[k + 1]
            val f = t - k
            ColorMath.mix(argbOf(a), argbOf(b), f)
        }
        ArtWash.extract(px, n, n)
    }
}

private fun argbOf(c: Color): Int = ColorMath.argb((c.alpha * 255).toInt(), (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

/** Muy claro (blanco y amarillo), muy oscuro (azul marino, como el de Dante's Inferno), colorido (el rojo de Switch) y sin arte. */
private val Bright = SampleArt(listOf(Color(0xFFFFFFFF), Color(0xFFFFF1B8), Color(0xFFFFE600), Color(0xFFFFFFFF)))
private val Dark = SampleArt(listOf(Color(0xFF020204), Color(0xFF0B1A3A), Color(0xFF1C0F2C), Color(0xFF05070C)))
private val Colorful = SampleArt(listOf(Color(0xFFE60012), Color(0xFFFF4B5C), Color(0xFF2B6BFF), Color(0xFF101528)))
private val NoArt = SampleArt(null)

private val Names = listOf(
    "Alto's Odyssey", "Asphalt Legends", "Bloons TD 6", "Brawl Stars", "Call of Duty", "Celeste", "Dead Cells",
    "Genshin Impact", "Grand Mountain Adventure", "Hades", "Honkai Star Rail", "Into the Breach", "Kingdom Rush",
    "Limbo", "Minecraft", "Monument Valley", "Mortal Kombat", "Ori", "Pokémon Unite", "Red Dead Redemption 2",
    "Shadow Fight 4", "Slay the Spire", "Stardew Valley", "Super Smash Bros. Ultimate", "Super Mario Bros. Wonder",
    "The Room", "Vampire Survivors", "Zenless Zone Zero",
)

private val Tiles = listOf(
    listOf(Color(0xFF7C5CFF), Color(0xFF2BD9FF)),
    listOf(Color(0xFFFF8A1F), Color(0xFFFF3D5A)),
    listOf(Color(0xFFF7F7F7), Color(0xFFE9EDF5)),
    listOf(Color(0xFFFF4FA3), Color(0xFFB15CFF)),
    listOf(Color(0xFF2B3550), Color(0xFFE3A15C)),
)

/** El tema de la vista previa (claro u oscuro según el modo noche) a pantalla completa. */
@Composable
private fun MeridianTheme(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val night = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    remember(night) { P.isDark = night }
    ElyndraTheme(DefaultSkin, landscape = true) {
        CompositionLocalProvider(LocalScreenSize provides DpSize(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp)) {
            Box(Modifier.fillMaxSize().background(P.paper)) { content() }
        }
    }
}

/**
 * Un dibujo con formas (anillos y una rejilla de rayas) sobre el degradado de
 * [art]: si el arte dentro del cristal no casara con el de fuera (escala,
 * recorte o desplazamiento), los anillos y las rayas se partirían en el canto.
 */
@Composable
private fun PatternBackdrop(art: SampleArt) {
    val brush = art.brush
    Box(
        Modifier
            .fillMaxSize()
            .then(if (brush != null) Modifier.background(brush) else Modifier.liquidGlass(RectangleShape, Color.Transparent))
            .drawBehind {
                val step = size.height / 8f
                var x = 0f
                while (x < size.width) {
                    drawRect(Color.White.copy(alpha = 0.22f), topLeft = Offset(x, 0f), size = Size(step * 0.18f, size.height))
                    x += step
                }
                for ((cx, cy, r) in listOf(Triple(0.18f, 0.3f, 0.16f), Triple(0.33f, 0.72f, 0.22f), Triple(0.62f, 0.4f, 0.3f))) {
                    drawCircle(Color.White.copy(alpha = 0.7f), radius = size.height * r, center = Offset(size.width * cx, size.height * cy), style = Stroke(size.height * 0.02f))
                }
            },
    )
}

/**
 * Una biblioteca de mentira con [count] juegos (y la fila de "Añadir" si son
 * iconos), sobre [art]. Con [folder], la carpeta (volver, "Abrir" y su ficha
 * en la cabecera de la rueda; el emulador con el sistema, arriba a la
 * derecha); con [pattern], el arte lleva formas (ver [PatternBackdrop]).
 */
@Composable
private fun MeridianSample(
    count: Int,
    art: SampleArt,
    covers: Boolean = false,
    adaptive: Boolean = true,
    focusedAt: Int? = null,
    focusedTitle: String? = null,
    folder: Boolean = false,
    pattern: Boolean = false,
    mixed: Boolean = false,
) = MeridianTheme {
    val ink = rememberMeridianInk()
    val look = rememberSelectionLook(SettingsStore.DEFAULT_SELECTION_PARTICLE_COLOR, glow = true, particles = true)
    val focused = focusedAt ?: if (count == 0) 0 else (count / 2).coerceAtMost(count - 1)
    val entries = remember(count, focused, focusedTitle) {
        List(count) { i ->
            val name = if (i == focused && focusedTitle != null) focusedTitle else Names[i % Names.size] + if (i >= Names.size) " ${i / Names.size + 1}" else ""
            WheelEntry(
                "p:$i", name, "Android · ${i % 7}h ${i * 7 % 60}m", listOf(if (covers) "NSW" else "Android", if (i == focused) "Sin jugar" else "${i % 7}h ${i * 7 % 60}m"),
                payload = i,
                // Con [mixed], cajas de proporciones distintas y alguna sin carátula (NaN: la reserva, que llena el hueco).
                aspect = if (mixed) CoverAspects[i % CoverAspects.size] else null,
            )
        }
    }
    val wheel = remember { mutableStateOf<WheelState?>(null) }
    val title = entries.getOrNull(focused)?.title ?: "Biblioteca vacía"
    val aspect = if (covers) COVER_RATIO else 1f
    MeridianScene(
        enterMs = { MeridianMotion.TOTAL_MS },
        art = MeridianArt("preview", null, null, false) {},
        wheel = { wheel.value },
        artIndex = { null },
        tileAspect = aspect,
        folderHeader = folder,
        adaptive = adaptive,
        washPreset = art.wash,
        backdrop = {
            val brush = art.brush
            if (pattern) PatternBackdrop(art)
            else Box(Modifier.fillMaxSize().then(if (brush != null) Modifier.background(brush) else Modifier.liquidGlass(RectangleShape, Color.Transparent)))
        },
        // La cabecera de verdad (ver RailHeaderRow): solo el dock, en la fila de la barra, sin pasar
        // del canto curvo de la niebla; Masha y el orden van en la barra de la derecha.
        railHeader = if (folder) {
            {
                FolderRailHeader(
                    compact = LocalMeridianFrame.current.compact,
                    bar = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ConsoleIconButton(onClick = {}) { glyph -> Box(glyph) { BackChevron(color = Color.White) } }
                            Spacer(Modifier.width(8.dp))
                            OpenButton(enabled = true) {}
                        }
                    },
                )
            }
        } else {
            {
            val labels = listOf("Todo", "Android", "Consolas")
            RailHeaderRow(
                compact = LocalMeridianFrame.current.compact,
                pills = rememberDotTabPills(labels),
                dock = { DotTabs(labels = labels, selected = 2, onSelect = {}, glassMinAlpha = DockPalettes.SEAM_GLASS_MIN, light = !P.isDark) },
            )
            }
        },
        topEnd = {
            if (folder) {
                FolderHeader(
                    title = "Nintendo Switch",
                    path = "/storage/emulated/0/Android/media/Roms/Nintendo/Switch/Juegos completos",
                    emulatorLabel = "Eden Nightly",
                    emulator = { iconOnly -> EmulatorChip("Eden Nightly", installed = true, focused = false, iconOnly = iconOnly) {} },
                    // La ficha de la carpeta, con el sistema (como en la app).
                    pill = { short ->
                        Box(Modifier.height(24.dp).dockSurface(CapsuleShape, elevation = 6.dp).padding(horizontal = 11.dp), contentAlignment = Alignment.Center) {
                            ElyText(if (short) "$count ROMS" else "$count ROMS · 0H 16M", size = 8.5f, weight = FontWeight.SemiBold, color = Color(DockPalettes.ink2(P.isDark)), maxLines = 1)
                        }
                    },
                ) {
                    Box(Modifier.height(34.dp).darkGlass(RoundedCornerShape(12.dp), minAlpha = MeridianGlass.FLOOR).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        ElyText("21:47 · 84 %", size = 11f, weight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            } else if (!covers) {
                PreviewHeroBar(Modifier, landscape = true, masha = true, sort = true, grouped = true)
            } else {
                FolderHeader("Nintendo Switch", "/storage/emulated/0/Android/media/Roms/Nintendo/Switch/Juegos completos") {
                    Box(Modifier.height(34.dp).darkGlass(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        ElyText("21:47 · 84 %", size = 11f, weight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
        },
        hints = {},
        hero = {
            MeridianHeroPanel(
                content = MeridianHeroContent(
                    "preview:$focused",
                    if (count == 0) emptyList() else listOf("Carpeta de emulador", "8 ROMs · Eden", "37h 27m jugado"),
                    title,
                    if (count == 0) "Añade tus juegos Android o una carpeta de ROMs y aparecerán aquí." else "Una aventura de plataformas con un estilo precioso y una banda sonora que no se olvida.",
                ),
                actions = MeridianActions(if (count == 0) "Añadir juegos o ROMs" else "Jugar", {}, if (count == 0) null else "Ficha", {}, if (count == 0) null else "Opciones", {}),
                ink = ink,
                padGlyphs = false,
            )
        },
    ) { geo, wash ->
        MeridianWheelArea(
            geo = geo,
            section = "all",
            forward = true,
            entries = entries,
            add = if (covers) null else WheelAdd("Añadir", "Añadir juegos o ROMs") {},
            focused = focused,
            selectedKey = entries.getOrNull(focused)?.key,
            addFocused = false,
            tileAspect = aspect,
            look = look,
            ink = ink,
            wash = wash,
            enterMs = { MeridianMotion.TOTAL_MS },
            reduced = false,
            lite = false,
            current = wheel,
            onSettle = {},
            tile = { e, i, _ ->
                if (mixed) MixedCoverTile(e, i) else Box(Modifier.fillMaxSize().background(Brush.linearGradient(Tiles[i % Tiles.size])))
            },
            wrap = { _, content -> content() },
            onTap = { _, _, _ -> },
            onOpen = {},
            onLongPress = { _, _ -> },
            onBounds = {},
            empty = { y -> RailMessage("Nada coincide con la búsqueda", y) },
        )
    }
}

/**
 * Proporciones de cajas de verdad: PSP, PS2, Switch, SNES (PAL, apaisada),
 * sin carátula, un icono cuadrado de Android, GBA y otra sin carátula. Sin
 * carátula (NaN) la tarjeta es la reserva: llena el hueco 2:3 con su título.
 */
private val CoverAspects = listOf(0.58f, 0.70f, 0.62f, 1.40f, Float.NaN, 1f, 1.08f, Float.NaN)

/** Una carátula de mentira con su extensión, o la reserva con el título (la extensión arriba, sin taparlo). */
@Composable
private fun MixedCoverTile(e: WheelEntry, i: Int) {
    val placeholder = e.aspect?.isNaN() != false
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(Tiles[i % Tiles.size]))) {
        // La reserva de verdad: su título encoge a la caja (PlaceholderTitleLayout) antes de cortarse.
        if (placeholder) FallbackArt(ArtFallback(e.key, e.title, null), ArtVariant.Cover, Modifier.fillMaxSize())
        Box(
            Modifier
                .align(if (placeholder) Alignment.TopEnd else Alignment.BottomEnd)
                .padding(3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.82f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            ElyText(if (i % 3 == 0) "NSP" else if (i % 3 == 1) "ISO" else "ZIP", size = 6.5f, weight = FontWeight.SemiBold, color = P.shade)
        }
    }
}

/* ── Móviles apaisados: carátulas grandes, de proporciones distintas, con las vecinas fundidas en los cantos ── */

/** En medio: una carátula alta (PS2) entre una de PSP y una de Switch; las dos franjas, iguales. */
@PhoneCoverPreviews
@Composable
private fun PhoneMixedCoversFolderPreview() = MeridianSample(12, Colorful, covers = true, focusedAt = 1, folder = true, mixed = true)

/** Una carátula de reserva enfocada (título encogido a su caja, la extensión arriba) sobre arte muy claro. */
@PhoneCoverPreviews
@Composable
private fun PhoneMixedCoversPlaceholderPreview() = MeridianSample(12, Bright, covers = true, focusedAt = 4, folder = true, mixed = true, focusedTitle = "Shin Megami Tensei: Devil Summoner - Soul Hackers")

/** La primera: arriba no hay vecina, y la enfocada sigue en el centro. */
@PhoneCoverPreviews
@Composable
private fun PhoneMixedCoversFirstPreview() = MeridianSample(12, Dark, covers = true, focusedAt = 0, folder = true, mixed = true)

/** La última: abajo no hay vecina. */
@PhoneCoverPreviews
@Composable
private fun PhoneMixedCoversLastPreview() = MeridianSample(12, Colorful, covers = true, focusedAt = 11, folder = true, mixed = true)

/** Tres ROMs: en un móvil no se centra la pila (la enfocada no sube contra la cabecera). */
@PhoneCoverPreviews
@Composable
private fun PhoneThreeCoversPreview() = MeridianSample(3, Colorful, covers = true, focusedAt = 1, folder = true, mixed = true)

@TabletSizesPreviews
@Composable
private fun TabletMixedCoversFolderPreview() = MeridianSample(12, Colorful, covers = true, focusedAt = 3, folder = true, mixed = true)

@TabletSizesPreviews
@Composable
private fun TabletMixedCoversFirstPreview() = MeridianSample(12, Dark, covers = true, focusedAt = 0, folder = true, mixed = true)

@TabletSizesPreviews
@Composable
private fun TabletMixedCoversLastPreview() = MeridianSample(12, Bright, covers = true, focusedAt = 11, folder = true, mixed = true)

/* ── Tabletas y tele: el cristal sobre arte con formas (anillos y rayas sin cortes en el canto) ── */

@TabletSizesPreviews
@Composable
private fun TabletSizesLibraryPreview() = MeridianSample(40, Colorful, pattern = true)

@TabletSizesPreviews
@Composable
private fun TabletSizesFolderPreview() = MeridianSample(12, Colorful, covers = true, focusedAt = 3, folder = true, pattern = true)

/* ── Tableta ── */

@MeridianPreviews
@Composable
private fun TabletFortyIconsBrightPreview() = MeridianSample(40, Bright)

@MeridianPreviews
@Composable
private fun TabletFortyIconsDarkArtPreview() = MeridianSample(40, Dark)

@MeridianPreviews
@Composable
private fun TabletTwoCoversColorfulPreview() = MeridianSample(2, Colorful, covers = true, focusedAt = 0)

@MeridianPreviews
@Composable
private fun TabletThreeIconsNoArtPreview() = MeridianSample(3, NoArt)

@MeridianPreviews
@Composable
private fun TabletFiveHundredCoversDarkArtPreview() = MeridianSample(500, Dark, covers = true)

@MeridianPreviews
@Composable
private fun TabletEmptyPreview() = MeridianSample(0, Dark)

/** El ajuste apagado: el velo neutro del tema (compárese con TabletFortyIconsBrightPreview). */
@MeridianPreviews
@Composable
private fun TabletNeutralBrightPreview() = MeridianSample(40, Bright, adaptive = false)

/* ── Móviles apaisados ── */

@PhonePreviews
@Composable
private fun PhoneFortyIconsColorfulPreview() = MeridianSample(40, Colorful)

@PhonePreviews
@Composable
private fun PhoneTwoCoversBrightPreview() = MeridianSample(2, Bright, covers = true, focusedAt = 1)

@PhonePreviews
@Composable
private fun PhoneFiveHundredCoversDarkArtPreview() = MeridianSample(500, Dark, covers = true)

/** "03 / 13", con la tira A–Z y un juego sin logo de nombre largo en la fila enfocada y en el hero. */
@MainLandscapePreviews
@Composable
private fun MainLandscapeLongNameNoLogoPreview() = MeridianSample(13, Colorful, focusedAt = 2, focusedTitle = "Minecraft: Dream it, Build it!")

/** Sobre arte muy oscuro. */
@MainLandscapePreviews
@Composable
private fun MainLandscapeDarkArtPreview() = MeridianSample(13, Dark, focusedAt = 2)

/* ── Umbral y letra grande ── */

@ThresholdPreviews
@Composable
private fun ThresholdThreeIconsBrightPreview() = MeridianSample(3, Bright)

@FontScalePreviews
@Composable
private fun LargeFontFortyIconsColorfulPreview() = MeridianSample(40, Colorful)

@FontScalePreviews
@Composable
private fun LargeFontTwoCoversDarkArtPreview() = MeridianSample(2, Dark, covers = true, focusedAt = 0)

/* ── Bloque del hero ── */

/** Un logo de mentira de [w]×[h] px: una placa de color con su rótulo. */
private class FakeLogo(private val w: Float, private val h: Float, private val color: Color) : Painter() {
    override val intrinsicSize: Size get() = Size(w, h)
    override fun DrawScope.onDraw() {
        drawRoundRect(color, cornerRadius = CornerRadius(size.minDimension * 0.18f))
        drawRoundRect(Color.White.copy(alpha = 0.85f), topLeft = Offset(size.width * 0.12f, size.height * 0.38f), size = Size(size.width * 0.76f, size.height * 0.24f), cornerRadius = CornerRadius(size.height * 0.1f))
    }
}

/** El bloque del hero sobre arte claro con un logo de [w]×[h] px (null = sin logo, con el titular). */
@Composable
private fun HeroBlockSample(logo: Pair<Float, Float>?, title: String = "Kirby and the Forgotten Land", padGlyphs: Boolean = false) = MeridianTheme {
    val ink = rememberMeridianInk()
    val brush = Bright.brush ?: return@MeridianTheme
    CompositionLocalProvider(LocalMeridianFrame provides MeridianFrame(compact = false, heroWidth = 620f, lite = false)) {
        Box(Modifier.fillMaxSize().background(brush)) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 32.dp, end = 24.dp, bottom = 34.dp)) {
                MeridianHeroPanel(
                    content = MeridianHeroContent("block", listOf("ROM · NSW", "37h 27m jugado", "XCI"), title, "Una aventura en 3D por un mundo abandonado y misterioso.", logo = logo?.let { "logo" }),
                    actions = MeridianActions("Jugar", {}, "Ficha", {}, "Opciones", {}),
                    ink = ink,
                    padGlyphs = padGlyphs,
                ) { _, reveal, box ->
                    val (w, h) = logo ?: return@MeridianHeroPanel
                    MeridianLogoImage(remember(w, h) { FakeLogo(w, h, Color(0xFFE2457A)) }, box, reveal)
                }
            }
        }
    }
}

@HeroBlockPreviews
@Composable
private fun WideLogoPreview() = HeroBlockSample(1600f to 260f)

@HeroBlockPreviews
@Composable
private fun SquareLogoPreview() = HeroBlockSample(512f to 512f)

@HeroBlockPreviews
@Composable
private fun TallLogoPreview() = HeroBlockSample(300f to 820f)

@HeroBlockPreviews
@Composable
private fun SmallLogoPreview() = HeroBlockSample(96f to 40f, padGlyphs = true)

@HeroBlockPreviews
@Composable
private fun NoLogoLongTitlePreview() = HeroBlockSample(null, title = "The Legend of Heroes: Trails through Daybreak II — Deluxe Edition")

/** La cabecera de una carpeta sobre arte muy claro, con su velo de arriba: el nombre, la ruta recortada por el centro y la píldora entera. */
@HeroBlockPreviews
@Composable
private fun FolderHeaderBrightArtPreview() = MeridianTheme {
    val ink = rememberMeridianInk()
    val brush = Bright.brush ?: return@MeridianTheme
    val wash = rememberWash(null, P.isDark, ink.primary, enabled = true, preset = Bright.wash)
    Box(Modifier.fillMaxSize().background(brush).meridianScrims(heroLeft = 0f, scrim = 0.62f, wash = wash, barHeight = 60f)) {
        Column(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 22.dp).width(300.dp)) {
            FolderHeader("Nintendo Switch", "/storage/emulated/0/Android/media/Roms/Nintendo/Switch/Juegos completos/Favoritos") {
                Box(Modifier.height(34.dp).darkGlass(RoundedCornerShape(12.dp), minAlpha = MeridianGlass.FLOOR).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    ElyText("21:47 · 84 %", size = 11f, weight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}
