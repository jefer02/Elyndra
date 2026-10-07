# Elyndra Console — lenguaje de interfaz

La intro de arranque (`ui/intro/`) fijó el tono: luz cálida que entra por
arriba, un brillo champán que recorre las piezas y curvas suaves sin rebote
exagerado. Este documento recoge cómo se traslada ese tono al resto de la app.
Todo vive en `ui/theme/Console.kt` (tokens, superficies, movimiento) y en
`ui/components/ConsoleKit.kt` (piezas reutilizables).

## 1. Auditoría (resumen)

| Pieza | Estado | Decisión |
|---|---|---|
| Barra superior del hero (hora y batería, Abrir, buscar, Ajustes) | Ya comparten `darkGlass`, 34 dp y radio 12 | Se queda. |
| Estante inferior de Biblioteca y Carpeta | Degradado plano sobre el papel: en claro se ve gris sucio | Superficie propia que nace del hero. |
| Pestañas de filtro y orden | Una fila entera bajo el hero (pestañas, "N elementos" y el orden en texto) | Dock de puntos en la barra del hero (o en la costura) y botón de orden con su menú; la fila se va y su alto es del carrusel. |
| Card seleccionada | Escala 1,14 y marco de 6 dp: tapa a las vecinas | Muelle a 1,05, marco fino y halo. |
| Biblioteca vacía | Titular gigante y una card "+" suelta | Estado vacío con icono, texto y botón principal. |
| Menú de pulsación larga | Rótulos a una línea (se cortan), imágenes sin estado y la zona de borrado mezclada con las imágenes | Rejilla de 2 líneas, imágenes con "Añadir/Cambiar" y borrado en un pie aparte. |
| Ajustes | Dos columnas largas y desequilibradas | Raíl de categorías + panel (ancho) o lista → página (estrecho). |
| Sonidos propios | 9 bloques de dos botones de texto | Sección plegable con filas compactas de iconos. |
| Prioridad de fuentes | 12 filas con flechas sueltas | Panel compacto plegable, con arrastre, estado y resumen. |
| APIs de metadatos | Todos los campos siempre abiertos | Acordeón con chip de estado. |
| Añadir → Carpeta de ROMs | Tres paneles en rejilla y una tarjeta vacía enorme | Pasos verticales con resumen, sistemas por fabricante y barra de acción fija. |
| Diálogos, ficha, selector de arte | Correctos | Solo accesibilidad y pistas de mando. |
| Ficha del juego | "Jugar" grande arriba y, en ancho, una columna llena y otra medio vacía | Sin "Jugar" (se juega desde la card): fila compacta de actualizar metadatos y editar nombre bajo la cabecera, y en ancho dos columnas equilibradas por lo que ocupa cada bloque (`DetailsLayout`). |
| Pantalla de Masha, calibración de voz | Estética de holograma propia | Fuera de alcance: se dejan igual. |

## 2. Tokens

**Espaciado (rejilla de 8 dp)** — `Space`: 4 (medio paso), 8, 16, 24, 32, 48.

**Radios** — `Radii`: 8 (chips), 12 (filas, botones), 16 (tarjetas), 20 (paneles),
28 (capas flotantes). Las piezas de dentro de un panel usan su radio menos el
margen (radios concéntricos).

**Tipografía (Poppins)** — `TypeScale` en sp:

| Rol | Tamaño | Peso |
|---|---|---|
| Display (titular del hero) | lo calcula `metrics()` | ExtraBold |
| Headline (título de pantalla) | 19 | SemiBold |
| Title (cabecera de panel) | 15 | SemiBold |
| Body (rótulo de fila) | 12,5 | SemiBold |
| Label (botones, valores) | 11 | Medium |
| Caption (descripciones) | 10 | Regular |
| Overline (cabeceras de sección) | 9,5 | SemiBold, mayúsculas, tracking 0,26 em |

**Color** — El acento se reserva para foco, selección y acción principal. El
champán de la intro (`P.champagne`) es un brillo de 1 dp en el canto superior
de las superficies; nunca rellena nada, no lleva texto y no entra en el foco
(un trazo dorado en el canto de un botón pequeño se leía como un fallo).

**Intro** — Sin color elegido en Ajustes, oro en tema oscuro e "Igual que el
acento" (el índigo de serie) en tema claro (`IntroColor.defaultFor`).

## 3. Superficies

- `consoleSurface()`: el cristal de siempre (`glass`, que respeta tinte,
  desenfoque y transparencia de Ajustes) más el brillo champán del canto. Para
  paneles, raíl de Ajustes, pasos de Añadir y barras de acción.
- Estante (`shelfSurface(extension)`): la mitad baja de Biblioteca y Carpeta.
  Perla en claro (papel con un velo del acento), tinta profunda en oscuro
  (`shelfColor()`). El arte del hero sigue por detrás de su parte de arriba
  (ver §9); desde ahí el estante es liso y se aclara apenas hacia el pie. Sin
  sombra ni filo en la costura: es la misma escena.
- Dock de secciones y botón de orden: el cristal oscuro de la barra del hero
  (`darkGlass`), en los dos temas, como la hora, "Abrir" y buscar; puntos
  claros y el acento en un tono aclarado para el cristal
  (`DockPalettes.glassAccent`). En la costura la tinta tiene un suelo más
  alto (0,6), porque media cápsula cae sobre el estante.
- Lámina clara del menú de orden y de las fichas de Carpeta (`dockSurface()`): Cristal del color
  del tema (perla cálida en claro, tinta honda en oscuro), casi opaco para leerse
  sobre cualquier arte, filo de luz arriba y el canto champán.
- Filas de Ajustes: planas, sin tarjeta, separadas por `SettingsDivider`.
- Fondo de las capas modales (`ui/components/OverlayBackdrop.kt`): menú de
  acciones, ficha, selector de arte, editar nombre y diálogos comparten el
  mismo. La pantalla se desenfoca **una sola vez** mientras haya una capa
  abierta; la capa de abajo pone el velo (0,5; 0,68 sin desenfoque) y cada
  capa de encima solo lo oscurece un escalón (+0,1, tope 0,82). Al cerrarse,
  su velo se funde y se vuelve al nivel anterior. Reglas puras en
  `ui/BackdropStack.kt` (`BackdropStackTest`); cada capa entra y sale con
  `OverlayHost` y `overlayEmerge` (fundido con "reducir movimiento").

## 4. Foco y mando

- Un solo realce (`FocusRing` / `consoleFocus`): velo y marco de acento, filo
  claro y una respiración lenta. Sin destellos de color: el foco es del acento.
- La card seleccionada del carrusel: muelle a 1,05 y el marco de selección
  (`ui/selection/SelectionFrame.kt`, `Modifier.selectionFrame`). Es uno solo
  para todas las cards —icono de juego Android, carpeta de emulador, "Añadir"
  y carátula de ROM con arte o de reserva— y sigue la forma de cada una:
  luz en el estante, halo de tres trazos que respira (±10 %, 3 s), filo de
  1,75 dp en degradado con un filo interior de contraste (se lee sobre
  cualquier carátula), reflejo arriba a la izquierda y un brillo que da la
  vuelta al contorno a la misma velocidad en dp/s (~4 s en una card típica).
  Al llegar la selección el filo se dibuja desde arriba (280 ms, `Swift`);
  al irse se apaga en 140 ms. La seleccionada va con `zIndex` 1.
- Tema: en oscuro la luz suma (`BlendMode.Plus`); en claro, mezcla normal con
  un tono más hondo y saturado, ≥ 3:1 frente al estante (`SelectionPalettes`,
  probado en `SelectionFxTest`).
- Polvo estelar (encendido de serie, se puede apagar): partículas de 1,1–3 dp que
  nacen en el contorno y suben despacio con un rizo, de un atlas de sprites
  pintado una vez (`Stardust.kt`); 22–30 en calidad alta y 11–15 en la ligera,
  con tope duro. La estela de Masha usa los mismos sprites.
- Con "reducir movimiento" el marco queda quieto: sin encendido, respiración,
  brillo ni partículas.
- Barra de pistas (`PadHints`): solo con un mando conectado
  (`InputController.gamepadPresent`). Biblioteca y Carpeta le reservan su alto
  en `metrics()`; los menús la llevan dentro del panel. En la biblioteca cambia
  con lo señalado: dock (A Seleccionar · LB/RB Sección · B Atrás), botón de
  orden y su menú (A Seleccionar · B Cerrar).
- Biblioteca (`LibraryFocus`, `LibraryFocusTest`): arriba desde el carrusel va
  al dock (al buscador si está abierto). Con el dock en la barra todo es una
  fila en el orden en que se ve (Masha, secciones, orden, Abrir, buscar,
  Ajustes); en la costura son dos (la barra y, debajo, secciones y orden).
  En el dock, izquierda/derecha recorren los puntos —el señalado enseña su
  rótulo un momento— y por los extremos se sale a la pieza de al lado; A elige.
  LB/RB cambian de sección desde cualquier sitio. El menú de orden se maneja
  con arriba/abajo, A aplica y B cierra (`BackPriority`: después de la ficha).
- Ajustes: la cruceta recorre con el foco de Compose; LB/RB cambian de
  categoría, salvo si hay una fuente "cogida" en la prioridad: entonces la mueven.

## 5. Movimiento

| Uso | Especificación |
|---|---|
| Cambio de pantalla | El eje compartido de siempre (desliza 1/10, escala 0,96 → 1, fundido) con `Springs.enter` (~300 ms) |
| Entrada de listas | `staggerIn`: fundido y 8 dp de subida, 25 ms entre elementos, máximo 8 |
| Indicadores (pestañas, segmentos, raíl) | Un solo objeto que se desliza con `Springs.snappy` |
| Interruptor | Pomo con muelle (ya lo tenía `GlowingSwitch`) |
| Pulsar un botón | Escala 0,97 con muelle (`pressFeedback`) |
| Acordeones | `expandVertically` + fundido con muelle; al cerrar, más rápido |
| Hero | Titular: fundido y 12 dp de subida; fondo: fundido y acercamiento lento 1,04 → 1 |
| Menú de acciones | Sale de la card con muelle y velo; la salida es más rápida (`Springs.exit`) |
| Dock de secciones | La píldora se estira hacia la nueva como una gota (canto delantero 900, trasero 320 de rigidez), la vieja se recoge en punto y el nuevo se abre, el rótulo entra recortado desde la izquierda con fundido (240 ms) y un brillo cruza la píldora (260 ms). Puntos a 0,9 al pulsar |
| Menú de orden | Sale del botón con `Springs.enter` (escala 0,9 → 1) y se va con `Springs.exit` |
| Fila del carrusel | Al aparecer o irse las pistas, su altura en pantalla se anima con `Springs.enter` |

Reglas: todo se anima en `graphicsLayer` o en dibujo (nada que remida listas);
nada de animaciones infinitas ni bucles por fotograma nuevos; nada de
desenfoque por fotograma. Con "reducir movimiento" (`LocalReducedMotion`)
todo pasa a fundido corto o salto.

## 6. Accesibilidad

- Botones de icono con `contentDescription` y 48 dp de zona táctil.
- Contraste: texto ≥ 4,5:1, piezas esenciales ≥ 3:1 en claro y en oscuro (los
  colores salen de `P` y de `Palettes.contentFor`, ya comprobados en
  `PaletteTest`).
- Textos de los seis idiomas sin cortes: los rótulos de acción admiten dos
  líneas y las filas crecen en vez de truncar. El dock mide sus rótulos con
  `TextMeasurer`; solo por encima de 152 dp de píldora se cortan.
- Dock: cada punto es una pestaña (`Role.Tab`, seleccionada o no, con su
  rótulo como descripción); zona táctil de 48 × 48 dp: cada pestaña cubre su
  hueco (44 dp) y la mitad de los de al lado, y sobresale de la cápsula por
  arriba y por abajo. El botón de orden y la ficha de PS4 de Carpeta hacen lo
  mismo (se ven de 34 y 24 dp; se tocan en 48). Puntos ≥ 3:1 y texto del menú ≥ 4,5:1 sobre la lámina puesta
  encima de negro y de blanco (`DockPaletteTest`).

## 7. Piezas (`ui/components/ConsoleKit.kt`)

| Pieza | Uso |
|---|---|
| `SectionHeader` | Cabecera de sección en versalitas (`SectionLabel` de Ajustes la usa) |
| `SettingRow`, `SwitchRow`, `NavRow` | El patrón de fila: rótulo y descripción a la izquierda, control a la derecha |
| `SegmentedControl` | Pocas opciones cortas (fotogramas, píldora de estado, paquete de sonidos, textos/imágenes) |
| `AccordionHeader` + `Expandable` | Sonidos propios, prioridad de fuentes, servicios con cuenta, credenciales de desarrollador |
| `IconAction` | Botón de icono con descripción y 48 dp de zona táctil |
| `SlidingTabs` | Pestañas con un solo indicador que se desliza |
| `DotTabs` | Dock de secciones: N opciones (iconos opcionales), puntos y la elegida en píldora. Geometría en `ui/DotTabsLayout.kt` |
| `SortButton` + `SortPopover` | Botón de orden y su menú anclado (`ui/components/SortControl.kt`) |
| `EmptyState` | Biblioteca, sección o carpeta vacías (en fila si el hueco es bajo) |
| `ActionBar` | Barra fija de Añadir: estado a la izquierda, botón a la derecha, el porqué si está apagado |
| `PadHints` | Pistas del mando, solo con un mando conectado |
| `ConsoleGlyph` | Glifos de categorías y acciones, familia de `SheetGlyph` |

Vistas previas en claro y oscuro: `ConsolePreviews.kt` (anotación `@ConsolePreviews`),
`DockPreviews.kt` (dock con 3 y 4 opciones, alemán y japonés, menú de orden) y
`ShelfPreviews.kt` (estante en ventana alta y baja con arte claro, oscuro y sin arte).

## 8. Ajustes: dónde vive cada opción

| Categoría | Contenido |
|---|---|
| Pantalla y tema | Modo oscuro, intro, fotogramas por segundo, alta fluidez de Masha, hora y batería, idioma |
| Sonido | Sonidos de la interfaz, volumen, sonido al navegar, paquete, sonidos propios (plegados) |
| Música y fondo | Música del menú, fondo de la interfaz |
| Apariencia | Color de acento, liquid glass, selección (halo y partículas, con su color) |
| Biblioteca | Ordenar por, reescanear, imágenes descargadas, build de BannerHub |
| Metadatos | Fuentes sin cuenta, servicios con cuenta, prioridad, traducción, descarga automática y manual |
| Masha | IA, voz, presencia, color de su estela, tiempo de juego exacto, privacidad |
| Acerca de | Versión, licencias, opciones de desarrollador |

Las claves de `SettingsStore` no cambian: solo cambia dónde se enseña cada opción.
La lógica pura (categorías, prioridad, grupos de sistemas, resúmenes) está en
`ui/SettingsNav.kt` y `ui/ConsoleLogic.kt`, con sus pruebas en `ConsoleLogicTest`.

## 9. Biblioteca y Carpeta: dock, carrusel y arte del hero

**Dónde va el dock** (`DockPlacement`, por ancho de ventana, nunca por tipo de
aparato). Se probaron tres sitios: en la barra del hero, flotando en la costura
hero/estante y la fila de siempre. La fila se descarta (es lo que se quería
quitar). Desde 760 dp de ancho (lo que pide un dock de cuatro secciones en
alemán junto al grupo de la derecha sin llegar al botón de Masha) el dock y el
botón de orden van **en la barra del hero, abriendo el grupo de la derecha**,
junto a la hora y "Abrir": en tableta quedan a mano del pulgar derecho y en el
mismo recorrido del mando. Centrados en la barra quedaban lejos de todo y
difíciles de alcanzar. Por debajo (móvil en vertical)
la barra no tiene sitio: el grupo flota **en la costura**, centrado, subiendo
dentro del hero solo lo que deja el margen del bloque de título (12 dp; 2 dp en
horizontal), así que no tapa titular, sinopsis ni pistas. El orden va junto al
dock: son los dos mandos de "qué se ve y cómo". Todo lleva el mismo cristal
oscuro que el resto de la barra (`darkGlass`).

**Carrusel** (`ShelfLayout.compute`, `ShelfLayoutTest`). El hero no cambia de
alto. El estante se queda con el alto de la antigua fila y la card crece hasta
un 10 % (topes: 185 dp de lado en Biblioteca, el de `metrics()` × 1,1 en
Carpeta) sin perder su proporción, y se centra entre el canto del hero (o el
dock de la costura, o las fichas de Carpeta) y la barra de pistas, con el
nombre debajo. Arriba se reservan los 10 dp que sube la seleccionada, lo que
crece (1,05) y 10 dp para su halo. Las pistas se reservan solo cuando se ven:
`metrics()` ya les cede ese alto (el hero encoge lo mismo), así que la card no
cambia de tamaño y la fila se desliza con muelle a su sitio.

**Arte del hero** (`Hero(extension)`, `ShelfLayout.artExtension`). La imagen
del juego (el mismo `AsyncImage`, anclada arriba y recortada; o el degradado
de reserva) baja por detrás del estante: el 45 % del estante en ventanas bajas
y el 58 % en altas, y nunca llega a la fila de nombres. Encima, con mezcla
normal y sin capas aparte, la franja oscura del titular se apaga en la primera
mitad de la extensión y el color del estante sube de transparente a liso; desde
ahí el estante sigue con ese mismo color, sin costura. Así los nombres, las
pistas y el texto de Carpeta caen siempre sobre el color liso (≥ 4,5:1 en los
dos temas, con cualquier arte), y el dock y las fichas llevan su propia lámina.
Cambia de juego con el mismo fundido y acercamiento lento del hero; el color del estante que sube se
pinta una sola vez encima del fundido, así la costura no parpadea al cambiar.
Sin cards (biblioteca o carpeta vacías) no hay extensión: el estado vacío va
sobre el estante liso.

## 10. Masha en la biblioteca

**Emblema** (`res/drawable/masha.xml`, vectorial; fuente en
`tools/masha_icon/`: `masha_emblem.svg`, la M calculada por `glyph.py` y
`svg2vector.py`, que genera el XML). Un orbe de cristal con aurora dentro
(índigo, cian y violeta, con un rescoldo champán abajo), reflejo arriba y canto
de luz; dentro, la M de Masha en un corte de alto contraste —trazos gruesos y
finos, terminales planos, como una Didot— y un destello de cuatro puntas. Se lee
de 24 a 300 dp, en claro y en oscuro. Es el mismo en el botón, "Masha recuerda",
la vista previa de su color, el widget y la sala del holograma.

**Botón**: fijo, la primera pieza de la barra del hero, arriba a la izquierda
(también la primera para el mando). Mide 40 dp —un avatar, algo mayor que las
píldoras de 34— pero en la barra cuenta como 34 para no desalinearla, y se toca
en 48 × 48. Ya no flota ni se arrastra: va dentro del layout y no puede tapar
el carrusel ni el hero. Alrededor flotan sus partículas (`mashaAura`): el
polvo estelar de la selección —mismos sprites, sin desenfoque—, del color de
Masha (Ajustes → Masha), 14 (8 en calidad ligera) que nacen en el canto del
orbe y suben con un rizo. Solo esa pieza tiene reloj; con "reducir
movimiento" quedan quietas. Su línea ambiental sale debajo,
alineada a su izquierda, sobre el arte del hero, y se recoge a los 14 s.

