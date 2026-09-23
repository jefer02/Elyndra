package com.elyndra.launcher.ui.components

import com.elyndra.launcher.ui.theme.Springs
import com.elyndra.launcher.ui.theme.motion
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.data.P
import com.elyndra.launcher.ui.ActionSheetSpec
import com.elyndra.launcher.ui.GroupStyle
import com.elyndra.launcher.ui.SheetAction
import com.elyndra.launcher.ui.SheetThumb
import com.elyndra.launcher.ui.resolve
import com.elyndra.launcher.ui.theme.LocalSkin
import com.elyndra.launcher.ui.theme.Swift
import com.elyndra.launcher.ui.theme.accentGradient

/* ─────────────────────────────────────────────────────────────
   Menú de acciones: el overlay que sale al mantener pulsada una card.

   Tres ideas lo sostienen:

   1. SALE DE LA CARD. El panel no aparece centrado porque sí:
      crece desde el punto exacto donde está la card que se
      mantuvo pulsada. Con las coordenadas de la card y las del
      propio panel se calcula el `transformOrigin`, así que la
      escala arranca justo ahí (ver [emergeOrigin]).
   2. EL FONDO SE APARTA. Lo que queda detrás se oscurece y se
      desenfoca: la profundidad de campo es lo que deja claro
      que hay una sola cosa con la que se puede interactuar.
   3. CRISTAL, NO PANEL. El contenedor es translúcido, con un
      filo de luz y una sombra larga y suave. Sobre un fondo ya
      desenfocado, eso basta para que se lea como vidrio.

   Se cierra tocando fuera: no hay botón de cerrar. Con mando, B
   sigue cerrando (ver InputController).
   ───────────────────────────────────────────────────────────── */

/**
 * Envuelve la pantalla y monta encima el menú de acciones.
 *
 * [content] es la biblioteca: se queda detrás, oscurecida y desenfocada,
 * mientras el overlay está abierto. [origin] es el centro de la card que se
 * mantuvo pulsada, en coordenadas de ventana; de ahí sale el panel.
 *
 * El desenfoque necesita Android 12 (API 31): por debajo, `Modifier.blur` no
 * hace nada y la profundidad la dan el oscurecido y el retroceso de escala,
 * que sí funcionan en todas las versiones.
 */
@Composable
fun GameActionOverlayContainer(
    isOverlayVisible: Boolean,
    onOverlayDismissed: () -> Unit,
    onActionClicked: (SheetAction) -> Unit,
    spec: ActionSheetSpec?,
    modifier: Modifier = Modifier,
    origin: Offset? = null,
    focus: Int = -1,
    /**
     * Hay otra capa modal encima (un diálogo). El fondo se aparta igual
     * —desenfoque y retroceso— pero sin velo ni panel: de eso se encarga la
     * capa que esté delante, y dos velos encima del mismo fondo lo dejarían
     * negro.
     */
    dimForOtherLayer: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Un solo progreso 0→1 gobierna fondo y panel: así el desenfoque, el
    // oscurecido y la escala del panel entran y salen acompasados.
    val open by animateFloatAsState(
        targetValue = if (isOverlayVisible) 1f else 0f,
        animationSpec = motion(Springs.enter()),
        label = "overlay",
    )
    // El fondo se aparta si manda este overlay o si lo hace otra capa modal.
    val backdrop by animateFloatAsState(
        targetValue = if (isOverlayVisible || dimForOtherLayer) 1f else 0f,
        animationSpec = motion(Springs.fade()),
        label = "backdrop",
    )

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                // El fondo se va un poco hacia atrás además de desenfocarse:
                // es el gesto de "esto pasa a segundo plano", y es lo único
                // que se nota por debajo de Android 12.
                .graphicsLayer {
                    val back = 1f - BACKGROUND_PULL * backdrop
                    scaleX = back
                    scaleY = back
                }
                .blur(radius = (BACKGROUND_BLUR * backdrop).dp),
        ) {
            content()
        }

        if (open > 0.001f && spec != null) {
            ActionOverlayScrim(open, onOverlayDismissed) {
                ActionOverlayPanel(
                    spec = spec,
                    open = open,
                    origin = origin,
                    focus = focus,
                    onAction = onActionClicked,
                )
            }
        }
    }
}

/** El velo: oscurece el fondo y recoge el toque que cierra el menú. */
@Composable
private fun ActionOverlayScrim(
    open: Float,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = open }
            .background(P.shade.copy(alpha = SCRIM_ALPHA))
            // Tocar fuera cierra. El panel se come sus propios toques, así
            // que pulsar dentro no dispara esto.
            .consumeClicks { onDismiss() }
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** El panel de cristal, creciendo desde la card. */
@Composable
private fun ActionOverlayPanel(
    spec: ActionSheetSpec,
    open: Float,
    origin: Offset?,
    focus: Int,
    onAction: (SheetAction) -> Unit,
) {
    val skin = LocalSkin.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val groups = spec.groups.filter { it.actions.isNotEmpty() }

    // Índice de la primera fila de cada bloque: el mando baja fila a fila por
    // todo el menú y no sabe de bloques, así que hay que traducir.
    val rowStarts = groups.runningFold(0) { acc, group -> acc + group.actions.size }
    val listState = rememberLazyListState()
    LaunchedEffect(focus) {
        if (focus < 0) return@LaunchedEffect
        val group = rowStarts.indexOfLast { it <= focus }.coerceIn(0, groups.lastIndex)
        runCatching { listState.animateScrollToItem(group) }
    }

    // Sitio del panel en la ventana: hace falta para saber en qué punto *suyo*
    // cae la card de la que tiene que salir.
    var panelBounds by remember { mutableStateOf(Rect.Zero) }
    val shape = RoundedCornerShape(30.dp)

    Column(
        Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .onGloballyPositioned { panelBounds = it.boundsInWindow() }
            .graphicsLayer {
                // Crece desde la card: el origen de la escala es el punto del
                // panel que cae sobre ella (0,0 = su esquina superior
                // izquierda; 1,1 = la inferior derecha).
                transformOrigin = emergeOrigin(panelBounds, origin)
                val s = PANEL_MIN_SCALE + (1f - PANEL_MIN_SCALE) * open
                scaleX = s
                scaleY = s
                alpha = open
            }
            // Sombra larga y suave: es lo que despega el cristal del fondo.
            .shadow(34.dp, shape, clip = false, ambientColor = P.shade, spotColor = P.shade)
            .clip(shape)
            .glassPanel(skin.a1)
            .consumeClicks()
            .padding(vertical = 16.dp),
    ) {
        OverlayHeader(spec)

        Box {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = screenHeight * 0.52f),
            state = listState,
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            groups.forEachIndexed { index, group ->
                val start = rowStarts[index]
                item(key = "g$index") {
                    Column(Modifier.fillMaxWidth()) {
                        group.header?.let { header ->
                            ElyText(
                                header.resolve(),
                                modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
                                size = 9f,
                                weight = FontWeight.Bold,
                                color = P.ink2.copy(alpha = 0.75f),
                                letterSpacing = tracking(0.18f),
                                uppercase = true,
                            )
                        }
                        when (group.style) {
                            GroupStyle.Rows -> ActionList(group.actions, focus - start, onAction)
                            GroupStyle.Thumbnails -> ArtworkSection(group.actions, focus - start, onAction)
                        }
                    }
                }
            }
        }
            // Los dos filos se difuminan: con el menú largo, lo que entra y
            // sale de la vista se desvanece en el cristal en vez de quedar
            // cortado a cuchillo contra la cabecera o contra el borde.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(16.dp)
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(P.paper.copy(alpha = 0.92f), Color.Transparent),
                            ),
                        )
                    },
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(22.dp)
                    .drawBehind {
                        drawRect(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, P.paper.copy(alpha = 0.92f)),
                            ),
                        )
                    },
            )
        }
    }
}

/**
 * Cristal del panel: tinte translúcido, degradado de luz de arriba abajo y un
 * filo fino de acento.
 *
 * Compose no tiene `backdrop-filter`, así que el desenfoque de dentro es en
 * realidad el del fondo: lo que hay detrás ya está desenfocado por el
 * contenedor, y encima va este velo lechoso. El resultado se lee igual que un
 * cristal esmerilado y no cuesta ni una pasada de blur extra.
 */
private fun Modifier.glassPanel(accent: Color): Modifier = this
    .background(
        Brush.verticalGradient(
            listOf(
                P.paper.copy(alpha = if (P.isDark) 0.86f else 0.9f),
                P.paper.copy(alpha = if (P.isDark) 0.74f else 0.82f),
            ),
        ),
    )
    .drawBehind {
        // Halo: un realce muy tenue del acento pegado al borde superior.
        drawRect(
            Brush.verticalGradient(
                colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                endY = size.height * 0.35f,
            ),
        )
    }
    .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), accent.copy(alpha = 0.18f))), RoundedCornerShape(30.dp))

/** Cabecera: miniatura grande, título en negro fuerte y el paquete, discreto. */
@Composable
private fun OverlayHeader(spec: ActionSheetSpec) {
    val skin = LocalSkin.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        spec.thumb?.let { thumb ->
            OverlayThumb(thumb)
            Spacer(Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f)) {
            ElyText(
                spec.title.resolve(),
                size = 16f,
                weight = FontWeight.ExtraBold,
                color = P.ink,
                letterSpacing = tracking(-0.01f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            spec.subtitle?.let {
                Spacer(Modifier.height(3.dp))
                ElyText(
                    it.resolve(),
                    size = 9f,
                    weight = FontWeight.Medium,
                    // El paquete es dato de apoyo: se queda muy por detrás del título.
                    color = P.ink2.copy(alpha = 0.6f),
                    letterSpacing = tracking(0.04f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(2.dp))
        // Filo de acento a la derecha del bloque: firma de la marca.
        Box(
            Modifier
                .size(width = 3.dp, height = 30.dp)
                .clip(CircleShape)
                .drawBehind { drawRect(accentGradient(skin, 160f, size)) },
        )
    }
}

@Composable
private fun OverlayThumb(thumb: SheetThumb) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(52.dp)
            .shadow(10.dp, shape, clip = false, ambientColor = P.shade, spotColor = P.shade)
            .clip(shape)
            .background(P.hairline.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            thumb.coverPath != null -> ArtImage(thumb.coverPath, thumb.pairIndex, Modifier.fillMaxSize())
            thumb.iconPath != null || thumb.packageName != null ->
                GameIcon(thumb.iconPath, thumb.packageName, Modifier.fillMaxSize().padding(6.dp))
        }
    }
}

/** Bloque de filas normales. */
@Composable
private fun ActionList(actions: List<SheetAction>, focus: Int, onAction: (SheetAction) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEachIndexed { i, action ->
            ActionRow(action, focused = i == focus, onClick = { onAction(action) })
        }
    }
}

/**
 * Una acción.
 *
 * Cada fila es su propia tarjeta en vez de una línea dentro de una lista con
 * separadores: da sitio al glifo grande y hace que el pulsado se lea como un
 * objeto que se hunde, no como un resalte de fila.
 */
@Composable
private fun ActionRow(action: SheetAction, focused: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val accent = if (action.destructive) P.red else skin.a2
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Hundirse al pulsar, con muelle al soltar: el mismo gesto físico que la
    // card que abrió el menú.
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val shape = RoundedCornerShape(16.dp)

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(shape)
            .background(
                if (action.destructive) {
                    // "Quitar" no se camufla con el resto: fondo rojo muy
                    // tenue y filo propio, para que borrar nunca sea un
                    // descuido de un dedo rápido.
                    P.red.copy(alpha = if (P.isDark) 0.16f else 0.08f)
                } else {
                    P.paper.copy(alpha = if (P.isDark) 0.5f else 0.72f)
                },
            )
            .border(
                1.dp,
                if (action.destructive) P.red.copy(alpha = 0.35f) else P.hairline.copy(alpha = 0.7f),
                shape,
            )
            .padFocus(focused, radius = 16.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .alpha(if (action.dimmed) 0.5f else 1f)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pastilla del glifo: más grande y más viva que antes, con el acento
        // de verdad y no un gris.
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = if (pressed) 0.34f else if (P.isDark) 0.24f else 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            action.icon?.let { SheetGlyph(it, accent, size = 19.dp) }
        }
        Spacer(Modifier.size(13.dp))

        Column(Modifier.weight(1f)) {
            ElyText(
                action.label.resolve(),
                size = 13f,
                weight = if (action.selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (action.destructive) P.red else P.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            action.detail?.let {
                Spacer(Modifier.height(2.dp))
                ElyText(
                    it.resolve(),
                    size = 9.5f,
                    color = P.ink2.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (action.selected || action.opensSheet) {
            Spacer(Modifier.size(10.dp))
            if (action.selected) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(skin.a2))
            } else {
                SheetChevron(P.ink2.copy(alpha = 0.55f))
            }
        }
    }
}

/**
 * Bloque de imágenes: una tarjeta por clase (fondo, logo, icono…) con lo que
 * hay puesto ahora mismo, y debajo las de quitar.
 *
 * Enseñar la miniatura de verdad —y no un icono genérico— es lo que convierte
 * este bloque en algo que se mira antes de tocar: se ve de un vistazo a cuál
 * le falta imagen.
 */
@Composable
private fun ArtworkSection(actions: List<SheetAction>, focus: Int, onAction: (SheetAction) -> Unit) {
    val setters = actions.withIndex().filter { !it.value.destructive }
    val removers = actions.withIndex().filter { it.value.destructive }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (setters.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Todas las tarjetas miden lo mismo y se reparten el ancho.
                setters.forEach { (index, action) ->
                    ArtworkCard(
                        action = action,
                        focused = index == focus,
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(action) },
                    )
                }
            }
        }
        removers.forEach { (index, action) ->
            ActionRow(action, focused = index == focus, onClick = { onAction(action) })
        }
    }
}

/** Tarjeta de una clase de imagen, con su miniatura o su hueco. */
@Composable
private fun ArtworkCard(
    action: SheetAction,
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val skin = LocalSkin.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "artPress",
    )
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier
            .graphicsLayer {
                scaleX = press
                scaleY = press
            }
            .clip(shape)
            .background(P.paper.copy(alpha = if (P.isDark) 0.5f else 0.72f))
            .border(1.dp, P.hairline.copy(alpha = 0.7f), shape)
            .padFocus(focused, radius = 14.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (action.preview != null) P.hairline.copy(alpha = 0.35f) else skin.a2.copy(alpha = 0.14f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (action.preview != null) {
                LogoImage(action.preview, Modifier.fillMaxSize(), alignment = Alignment.Center)
            } else {
                action.icon?.let { SheetGlyph(it, skin.a2, size = 20.dp) }
            }
        }
        Spacer(Modifier.height(7.dp))
        ElyText(
            action.label.resolve(),
            size = 9.5f,
            weight = FontWeight.SemiBold,
            color = P.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * El punto del panel del que tiene que crecer, en fracción de su propio
 * tamaño.
 *
 * La card está en coordenadas de ventana y el panel también, así que basta
 * con restar su esquina y dividir por su tamaño. Se recorta a un margen algo
 * mayor que el panel: si la card queda muy lejos (abajo del todo, fuera de la
 * pantalla), un origen desbocado haría que el panel entrase de lado en vez de
 * crecer. Sin card conocida, crece desde su centro.
 */
private fun emergeOrigin(panel: Rect, origin: Offset?): TransformOrigin {
    if (origin == null || panel.width <= 0f || panel.height <= 0f) return TransformOrigin.Center
    val x = ((origin.x - panel.left) / panel.width).coerceIn(-ORIGIN_REACH, 1f + ORIGIN_REACH)
    val y = ((origin.y - panel.top) / panel.height).coerceIn(-ORIGIN_REACH, 1f + ORIGIN_REACH)
    return TransformOrigin(x, y)
}

/**
 * El tirón magnético de la pulsación larga.
 *
 * Devuelve la escala que hay que aplicarle a la card y la función que lo
 * dispara. El gesto son dos tiempos: primero se hunde deprisa —el dedo
 * "agarra" la card— y luego un muelle la suelta por encima de su tamaño antes
 * de asentarla. Ese rebote es lo que hace que el menú parezca salir *de* la
 * card y no delante de ella.
 */
@Composable
fun rememberMagneticPress(): MagneticPress {
    val scale = remember { Animatable(1f) }
    return remember {
        MagneticPress(
            scale = scale,
            pull = {
                scale.animateTo(PULL_SCALE, Springs.snappy())
                scale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow,
                    ),
                )
            },
        )
    }
}

/** La escala viva de la card y el gesto que la dispara (ver [rememberMagneticPress]). */
class MagneticPress(
    private val scale: Animatable<Float, *>,
    private val pull: suspend () -> Unit,
) {
    /** Se lee en fase de dibujo: mover la card no recompone nada. */
    val value: Float get() = scale.value

    suspend fun run() = pull()
}

/** Lo que tarda el tirón antes de soltar el muelle (lo espera quien abre el menú). */
const val MAGNETIC_PULL_MS = 120

private const val PULL_SCALE = 0.9f

/** Cuánto se va hacia atrás el fondo al abrirse el menú. */
private const val BACKGROUND_PULL = 0.04f

/** Radio del desenfoque del fondo, en dp (Android 12+). */
private const val BACKGROUND_BLUR = 18f

private const val SCRIM_ALPHA = 0.5f

/** Escala de la que sale el panel: no desde cero, que se lee como un parpadeo. */
private const val PANEL_MIN_SCALE = 0.86f

/** Hasta dónde puede caer el origen fuera del panel. */
private const val ORIGIN_REACH = 0.6f
