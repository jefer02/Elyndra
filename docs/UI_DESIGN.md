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

**Intro** — Sin color elegido en Ajustes, Plasma en los dos temas
(`IntroColor.DEFAULT`). Lo elegido antes (oro, "Igual que el acento"…) se respeta.

**Paletas de firma** (`data/SignaturePalette.kt`) — Cada una es un par de luz:
primario (filos, eje, nodo, partículas), secundario (halo, degradados) y
destello (núcleos, brillos, barridos).

| Paleta | Primario | Secundario | Destello |
|---|---|---|---|
| Plasma (de partida) | `#7C5CFF` | `#2BD9FF` | `#FFF1C9` |
| Ember | `#FF8A1F` | `#FF3D5A` | `#FFE2B8` |
| Aurora | `#19E3A5` | `#3AA8FF` | `#E6FFF6` |
| Neon Rose | `#FF4FA3` | `#B15CFF` | `#FFE6F3` |
| Solar | el oro de siempre (`#E9B44C`, acento "oro", intro "gold") | `#F3CE7A` | `#FFF1D6` |

Un color propio (deslizador de tono) completa su par solo: secundario con el
tono +35° y destello claro. En claro cada tono se vuelve algo más saturado y
hondo hasta el 3:1 frente a la perla, el papel y la superficie; el texto sale
de `SignaturePalettes.text` (4,5:1). En oscuro la luz suma tal cual. De
partida: acento Plasma, halo y partículas de la selección Plasma e intro
Plasma; nada guardado se pisa. El acento Plasma arranca su relleno en
`#7958FF` (no `#7C5CFF`) para que el blanco de los botones llegue al 4,5:1.

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
| Apariencia | Estilo de la lista (Meridian o Clásico, con miniaturas), color de fondo adaptable (Meridian), paleta de firma, color de acento, liquid glass, selección (halo y partículas, con su color) |
| Biblioteca | Ordenar por, reescanear, imágenes descargadas, build de BannerHub |
| Metadatos | Fuentes sin cuenta, servicios con cuenta, prioridad, traducción, descarga automática y manual |
| Masha | IA, voz, presencia, color de su estela, tiempo de juego exacto, privacidad |
| Acerca de | Actualizaciones (GitHub Releases), autor y enlaces, versión, licencia, licencias de terceros, opciones de desarrollador |

Las claves de `SettingsStore` no cambian: solo cambia dónde se enseña cada opción.
Las únicas claves nuevas son `layout.style` ("meridian" o "classic"; sin valor, Meridian) y
`meridian.adaptiveColor` (encendida de serie).
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

## 11. Meridian: la rueda vertical

Con la ventana apaisada y de al menos 640 dp de ancho (`MeridianMode`, por
tamaño de ventana, nunca por tipo de aparato) la lista de Biblioteca y de
Carpeta deja el carrusel por una rueda vertical. En vertical o en una ventana
estrecha el carrusel sigue igual. Ajustes → Apariencia → "Estilo de la lista"
permite quedarse siempre con el clásico. Selección, sección, orden y búsqueda
viven en el ViewModel, así que girar, plegar o cambiar el tamaño no pierde
nada: la rueda (y el carrusel) nacen ya en la selección (`WheelMath.restoreIndex`).

**Reparto** (`MeridianGeometry`): la rueda ocupa ~42 % del ancho (300–600 dp);
en una ventana baja (< 480 dp de alto, `MeridianMode.compact`) el 40 %
(280–520 dp). La línea de foco va en el centro. Arriba de la rueda, el
emblema de Masha y el dock de secciones y el orden. A la derecha, el arte del
juego a sangre (`HeroArtLayer`), ensanchado un 35 % y anclado a la izquierda
(`MeridianArtFrame`), el bloque del hero abajo (margen inferior del 8 % del
alto; 6 % en ventana baja) y la barra arriba a la derecha. Entre el dial y el
bloque, 96 dp: ahí va el contador. Los insets (barras del sistema y recortes)
ya los respeta la raíz de la app (`systemBars ∪ displayCutout`).

**Tarjetas y profundidad** (`WheelTransform`, tablas por fila de distancia
0, ±1, ±2, ±3, ±4, interpoladas en línea):

| | 0 | ±1 | ±2 | ±3 | ±4 |
|---|---|---|---|---|---|
| Escala | 1 | 0,64 | 0,46 | 0,32 | 0,24 |
| Opacidad | 1 | 0,85 | 0,5 | 0,25 | 0 |
| Giro Y | 0° | 6° | 12° | 18° | 18° |
| Niebla | 0 | 0,12 | 0,26 | 0,42 | 0,55 |
| Texto | sí | sí (2 líneas) | no | no | no |

La tarjeta enfocada mide el 48 % del alto de la rueda (carátulas, como mucho
el 24 % del ancho de la ventana) o el 38 % (iconos y apaisadas), y nunca más
ancha que el 42 % de la rueda (al nombre le queda sitio); entre 88 y 380 dp.
Se leen tres filas (la enfocada y sus vecinas, que pueden asomar recortadas
en los cantos) y, con iconos, dos tenues más. Cada fila sigue el **círculo**
de `WheelArc`: su centro queda fuera de la pantalla, a la izquierda, a la
altura de la línea de foco (radio 1,6 altos de la rueda); el dial usa la misma
función, así que filas y arco nunca se separan. La niebla es el color del
fondo. Titular de la enfocada a 20–24 sp (18–20 en ventana baja), dos líneas,
sobre un óvalo suave del color del fondo que se apaga hacia sus bordes (no es
una placa); el de las vecinas, 20 sp escalado (~13 sp), dos líneas. En la
enfocada, como mucho dos fichas (plataforma y cantidad; el emulador va en el
hero), que saltan de línea en vez de cortarse. La fila de "Añadir" lleva un
rótulo corto ("Añadir"; el largo es para el lector de pantalla).

**Listas cortas** (`ShortList`): con 1 a 4 filas la pila entera se centra en
la rueda (con 5, a medias): la línea de foco se mueve entre su sitio y la que
centra la pila, en función continua de la posición (filas, nodo y dial van
juntos, sin saltos) y sin que la enfocada se salga.

**Tarjetas claras**: todas llevan un filo de 1 dp del tema y una sombra de
contacto suave; los dos se refuerzan cuando la luminancia del canto de la
tarjeta se parece a la del fondo (`ArtWash.tileEdgeStrength`, leída de una
copia de 16 px fuera del hilo principal).

**El dial orbital** (`MeridianDial`): un arco fino (1,5 dp) del primario al
secundario con resplandor de dos trazos, que se apaga hacia las puntas; una
marca por juego, perpendicular al arco, que rueda con la lista como un dial
físico (16 dp entre marcas hasta 24 juegos, 11 dp hasta 120 con mayores cada
5, 6 dp pintando una de cada 2 hasta 600 y 4 dp una de cada 3 más allá:
`DialScale`); una muesca fija en la línea de foco con el nodo de luz, que late
con cada cambio de selección. La marca que pasa por la muesca se alarga y se
enciende; las lejanas siguen la caída de opacidad de la rueda. Con pocos
juegos, un arco corto; sin juegos, nada. El nodo va en "línea de foco +
desplazamiento", el mismo valor que coloca la tarjeta: con la rueda parada,
en su centro exacto. En claro, marcas de grafito; en oscuro, claras y el
resplandor suma (≥ 3:1 sobre el fondo). Un solo lienzo que lee la rueda al
dibujar, sin objetos por fotograma. Al lado, el **contador** (`DialCounterChip`):
cápsula de cristal con cifras tabulares, la posición grande y el total
apagado, siempre con las mismas cifras (2 a 4, `DialCounter`), que ruedan como
un cuentakilómetros al cambiar (fundido con "reducir movimiento"); en
"Añadir", un "+".

**Fondo adaptable** (`ArtWash`, `MeridianWash`; Ajustes → Apariencia →
"Color de fondo adaptable", encendido de serie). Del arte enfocado se pide a
Coil una copia de software de 48 px (nunca se leen píxeles de un bitmap de
hardware) y, fuera del hilo principal, se saca de una muestra de 24 × 24 el
dominante (cubetas de tono pesadas por saturación y medios tonos), un
secundario, 5 colores de la franja que cae detrás de la rueda y sus píxeles
más claro y más oscuro; la copia de 48 px se desenfoca una vez. Todo queda
en una caché en memoria por ruta. Detrás de la rueda, en una capa del ancho
justo: el arte desenfocado alineado con el de verdad (un desenfoque gratis,
sin desenfocar por fotograma), el tono del velo encima (oscuro: luminosidad
8–16 %, saturación viva; claro: pastel al 80–88 %, saturación 30–45 %), una
modulación vertical con los colores del canto del arte, grano fijo al 3,5 %
y la máscara de la niebla (`MeridianFog`): 0,86 desde el canto hasta el eje
(detrás de las tarjetas no hace falta más) y, pasado el eje, una caída
graduada `(1 − smoothstep)^1,7` hasta el 58 % del ancho (densa junto a la
rueda, un tercio a mitad del tramo). El perfil se curva con el círculo de la
rueda (`WheelArc`): a cada altura se corre lo mismo que la fila que pasa por
ahí, así que el texto de cada fila tiene detrás el mismo velo que la fila
enfocada y, arriba y abajo, el arte queda más limpio (una media luna, no una
banda). Es una imagen pequeña (celdas de 6 dp) calculada una vez por tamaño y
ampliada con filtrado. Lo que cubre el velo se calcula para que los
nombres lleguen al 4,5:1 sobre el peor píxel de la franja; si no llegaría ni
cubriendo del todo, el velo neutro del tema. Sin arte o con arte gris, el
tono sale del primario de la paleta de firma. Al cambiar de selección los
colores se funden en 520 ms (al instante con "reducir movimiento"). Apagado
el ajuste, el velo neutro de siempre (perla cálida en claro, tinta en oscuro).

**Barra y píldoras**: arriba, un velo de la tinta honda del arte (luminosidad
10 %) que se mantiene lo que mide la barra y se apaga en 24 dp, con la
opacidad justa para el texto blanco (≥ 4,5:1 sobre el píxel más claro de esa
zona). La cabecera de Carpeta: nombre en una línea, ruta en otra recortada
por el centro, a 12 dp de la píldora de la batería. Todas las píldoras de
cristal oscuro (volver, Abrir, emulador, hora, buscar, Ajustes, contador)
llevan en Meridian un suelo de tinta de 0,66 (`MeridianGlass`, `LocalGlassFloor`):
texto ≥ 4,5:1 e iconos ≥ 3:1 sobre cualquier fondo. Se tocan en 48 dp
aunque se vean de 34 (`touchTarget`).

**Bocadillo de Masha** (`BubbleSlot`): va en la columna de la rueda, bajo el
botón de Masha, sin pisar el dock ni el orden (si el dock baja a otra línea,
el bocadillo se coloca a su lado o debajo). Mientras se ve, la rueda le deja
sitio con un muelle: baja la mitad de lo que ocupa y las filas que quedarían
debajo se apagan antes de tocarlo (`RailEdge`). En ventana baja, una sola
línea con "Ahora no" al lado.

**Bloque del hero**: fichas (saltan de línea), logo (o titular), sinopsis (dos
líneas) y las acciones, con ritmo de 8 dp. El logo se encaja (`LogoFit`) en una
caja de ancho min(60 % de la zona del hero, 640 dp, el bloque) y alto entre
el 14 % y el 26 % de la ventana, sin deformar; si es pequeño se amplía al doble
(hasta 2,5 veces en pantallas densas, sin pasar de 1,25 dp por píxel); se pide
la imagen al tamaño de la caja. En ventana baja: dos fichas en una línea, sin
sinopsis, logo hasta el 20 % del alto y botones de 40 dp. Los tres botones
miden lo mismo y crecen juntos con la letra grande. Los glifos A, X, Y (y la B
de "Volver" en Carpeta) solo salen con un mando conectado.

**Tocar para abrir** (Biblioteca y Carpeta, rueda y carrusel; `TapGate`,
`OpenGuard`): un toque selecciona; un toque en lo seleccionado lo abre. Dos
toques rápidos en el mismo sitio abren lo que seleccionó el primero. Un toque
con la lista moviéndose solo la para. Tras abrir algo, 600 ms en los que no se
abre nada más. Ajustes → Biblioteca → "Tocar lo seleccionado para abrir".

**Calidad ligera** (la de Masha, `MashaQuality.Lite`): solo ±2 filas, sin
giro, sin niebla y sin grano; el arco sin su resplandor ancho.

**Movimiento** (`MeridianMotion`): al entrar, el dial se enciende desde la
muesca mientras se funde la intro, las 8 primeras filas entran en cascada y el
arte se funde. Al moverse, filas con muelle (acelera al mantener la cruceta),
marcas que ruedan, nodo que late, arte con fundido y paralaje, colores del
fondo con fundido, titular con barrido de máscara y brillo. Cambiar de sección
desliza la rueda en vertical. Con "reducir movimiento": sin giro, paralaje ni
cascada; fundidos, escala, niebla y la misma curva (es geometría, no
movimiento).

### 11.x Estado vacío (Meridian y clásico)

Un solo bloque para los tres diseños (`ui/components/EmptyLibrary.kt`):

- **Escenario** (`EmptyStageBackdrop`, colores en `EmptyStage`): sustituye al
  arte cuando no hay juego (biblioteca o sección vacía, fila "Añadir"). Fondo
  hondo teñido con el tono del primario de la paleta de firma (HSL 14→6 % de
  noche, 30→18 % de día, saturación ≤ 58 %), resplandor del primario, halo
  del secundario, tres órbitas finas del destello con su nodo (eco del dial),
  sombra de suelo y grano. Estático (`drawWithCache`), sin animación por
  fotograma. El blanco llega al 4,5:1 en su punto más claro: si una paleta no
  lo cumple se baja la luz. En el clásico lleva un filo de luz en la costura
  con el estante.
- **Bloque** (`EmptyLibraryHero`): titular del hero en dos líneas del mayor
  cuerpo que cabe (`TitleFit`; si ni 18 sp caben, una tercera línea; nunca
  puntos suspensivos), la línea de qué hacer y la acción principal de
  Meridian ("Añadir juegos o ROMs", con la A si hay mando).
- **Tarjeta "Añadir"**: la de cada lista (fila de la rueda o card del
  carrusel), señalada cuando es lo único que hay; el mando ya tiene el foco en
  ella y la A abre Añadir.
- Sin arte, el óvalo oscuro y la franja de arriba de Meridian se apagan
  (`meridianScrims(artShown)`): eran las manchas grises sobre el cristal claro.
