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
| Menú de pulsación larga | Rótulos a una línea (se cortan), imágenes sin estado y la zona de borrado mezclada con las imágenes | Rejilla de 2 líneas, imágenes con "Añadir/Cambiar" y un "Quitar" corto bajo cada una (el mando baja de cada imagen a su "Quitar"); quitar el juego aparte tras un filo rojo, en ancho en la columna de las imágenes. X de cerrar en la banda, y banda más baja en un móvil apaisado. |
| Ajustes | Dos columnas largas y desequilibradas | Raíl de categorías + panel (ancho) o lista → página (estrecho). |
| Sonidos propios | 9 bloques de dos botones de texto | Sección plegable con filas compactas de iconos. |
| Prioridad de fuentes | 12 filas con flechas sueltas | Panel compacto plegable, con arrastre, estado y resumen. |
| APIs de metadatos | Todos los campos siempre abiertos | Acordeón con chip de estado. |
| Añadir → Carpeta de ROMs | Tres paneles en rejilla y una tarjeta vacía enorme | Pasos verticales con resumen, sistemas por fabricante y barra de acción fija. |
| Diálogos, ficha, selector de arte | Correctos | Solo accesibilidad y pistas de mando. |
| Ficha del juego | "Jugar" grande arriba y, en ancho, una columna llena y otra medio vacía | Sin "Jugar" (se juega desde la card): fila compacta de actualizar metadatos y editar nombre bajo la cabecera, y en ancho dos columnas equilibradas por lo que ocupa cada bloque (`DetailsLayout`). |
| Pantalla de Lucy, calibración de voz | Estética de holograma propia | Fuera de alcance: se dejan igual. |

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
  con tope duro. La estela de Lucy usa los mismos sprites.
- Con "reducir movimiento" el marco queda quieto: sin encendido, respiración,
  brillo ni partículas.
- Barra de pistas (`PadHints`): solo con un mando conectado
  (`InputController.gamepadPresent`). Biblioteca y Carpeta le reservan su alto
  en `metrics()`; los menús la llevan dentro del panel. En la biblioteca cambia
  con lo señalado: dock (A Seleccionar · LB/RB Sección · B Atrás), botón de
  orden y su menú (A Seleccionar · B Cerrar).
- Biblioteca (`LibraryFocus`, `LibraryFocusTest`): arriba desde el carrusel va
  al dock (al buscador si está abierto). Con el dock en la barra todo es una
  fila en el orden en que se ve (Lucy, secciones, orden, Abrir, buscar,
  Ajustes); en la costura son dos (la barra y, debajo, secciones y orden).
  En Meridian también es una fila en el orden en que se ve (secciones, Lucy,
  Abrir, orden, buscar, Ajustes: `LibraryFocus.meridianRows`); desde la rueda,
  izquierda va al dock y derecha a "Abrir". En esa fila las pistas dicen
  A Seleccionar · ←→ Mover · B Atrás.
  En el dock, izquierda/derecha recorren los puntos —el señalado enseña su
  rótulo un momento— y por los extremos se sale a la pieza de al lado; A elige.
  LB/RB cambian de sección desde cualquier sitio. El menú de orden se maneja
  con arriba/abajo, A aplica y B cierra (`BackPriority`: después de la ficha).
- Ajustes: la cruceta recorre con el foco de Compose; LB/RB cambian de
  categoría, salvo si hay una fuente "cogida" en la prioridad: entonces la mueven.
- Selector de arte (carátula, fondo, logo, icono: una sola hoja,
  `ArtPickerSheet`): lo lleva el `InputController` (`artFocus`), no el foco de
  Compose. La rejilla se pinta con las columnas que da `GridNav.columns` (la
  cuenta de `GridCells.Adaptive`) y el mando se mueve con esas mismas
  (`GridNav`, `GridNavTest`): sin vuelta en los cantos, abajo hacia una última
  fila incompleta cae en el último elemento, arriba desde la primera fila se
  queda (no hay cabecera con botones). Empieza en la imagen puesta (si está en
  la lista) o en la primera. A pone · B cierra sin cambios y vuelve a la hoja
  de servicios con el foco en el que se abrió · LB/RB saltan una pantalla de
  filas. La señalada lleva el marco de selección de las cards; la puesta, filo
  de acento, insignia ✓ y "En uso". Buscando, sin resultados o con error hay
  botones (Reintentar / Cerrar) y el foco va a ellos. Mantener la cruceta
  desplaza sin animar, para no ir por detrás de la repetición.

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
  `TextMeasurer`; solo por encima de 152 dp de píldora se cortan (o si no hay
  más sitio: en la cabecera de la rueda, bajo la curva de la niebla). Todas las
  secciones abren la misma píldora, la del rótulo más ancho
  (`DotTabsLayout.uniformPill`): cambiar de sección mueve la píldora, nunca
  cambia el ancho del dock ni empuja al botón de orden o a la barra.
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
| Pantalla y tema | Modo oscuro, intro, fotogramas por segundo, alta fluidez de Lucy, rendimiento (Auto / Calidad / Rendimiento), hora y batería (apagada de serie), idioma |
| Sonido | Sonidos de la interfaz, volumen, sonido al navegar, paquete, sonidos propios (plegados) |
| Música y fondo | Música del menú, fondo de la interfaz |
| Apariencia | Estilo de la lista (Meridian o Clásico, con miniaturas), color de fondo adaptable (Meridian), paleta de firma, color de acento, liquid glass, selección (halo y partículas, con su color) |
| Biblioteca | Ordenar por, reescanear, imágenes descargadas, build de BannerHub |
| Metadatos | Fuentes sin cuenta, servicios con cuenta, prioridad, traducción, descarga automática y manual |
| Lucy | IA, voz, presencia, color de su estela, tiempo de juego exacto, privacidad |
| Acerca de | Actualizaciones (GitHub Releases), autor y enlaces, versión, licencia, licencias de terceros, opciones de desarrollador |

Las claves de `SettingsStore` no cambian: solo cambia dónde se enseña cada opción.
Las únicas claves nuevas son `layout.style` ("meridian" o "classic"; sin valor, Meridian),
`meridian.adaptiveColor` (encendida de serie) y `perf.mode` ("quality" o "performance";
sin valor, Auto).
La lógica pura (categorías, prioridad, grupos de sistemas, resúmenes) está en
`ui/SettingsNav.kt` y `ui/ConsoleLogic.kt`, con sus pruebas en `ConsoleLogicTest`.

## 9. Biblioteca y Carpeta: dock, carrusel y arte del hero

**Dónde va el dock** (`DockPlacement`, por ancho de ventana, nunca por tipo de
aparato). Se probaron tres sitios: en la barra del hero, flotando en la costura
hero/estante y la fila de siempre. La fila se descarta (es lo que se quería
quitar). Desde 760 dp de ancho (lo que pide un dock de cuatro secciones en
alemán junto al grupo de la derecha sin llegar al botón de Lucy) el dock y el
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

**Clásico: reparto y compactación** (`ClassicLayout`, `ClassicLayoutTest`).
Sustituye en este modo al carrusel de `ShelfLayout.compute` y al hero fijo de
`metrics()`: antes el bloque de título iba pegado al pie del hero sin saber de
la barra, y en un móvil apaisado (360 dp) se subía encima de volver / "Abrir" /
el emulador, con carátulas de ~110 dp. Ahora:

- La carátula seleccionada manda: 48 % del alto en una ventana baja (≤ 480 dp),
  bajando con suavidad al 36 % en una alta (≥ 900 dp), 30 % en vertical; topes
  por ancho (22 % de la ventana en apaisado; en vertical, 42 % de la fila la
  carátula y 46 % el icono) y absolutos (240 dp carátula, 200 dp icono
  cuadrado, que va al 85 % del alto de una carátula). Las vecinas, al 90 % (94 %
  en vertical), encogen **desde el pie** dentro de la misma
  caja; la seleccionada sube 10 dp. Las dos cosas van en la capa
  (`classicCard`), así que todos los nombres van en una línea y nada recompone.
- El estante mide exactamente la fila (halo, subida, card, nombre, margen y
  pistas) y el hero se queda el resto, nunca menos que la barra más el bloque
  compacto.
- El bloque del hero empieza, como muy arriba, bajo la barra (6 dp de aire) y
  se compacta en este orden: (1) fuera la pista táctil o la sinopsis; (2) el
  logo encoge hasta el 60 %; (3) las fichas se funden en una línea de texto;
  (4) la ficha de la carpeta ("4 ROMs · 0h 0m") sube a la barra, junto al
  sistema, como en Meridian. La pista táctil nunca sale con mando.
- En apaisado la fila llena el ancho: el aire entre cards (14–40 dp) deja k
  cards enteras y media más asomando, y al moverse queda una card delante de la
  seleccionada.
- En vertical manda el aire **a la vista**, de filo dibujado a filo dibujado
  con la vecina ya encogida: 9 dp en un móvil y 12 en tableta
  (`ClassicLayout.rowStyle`). Las vecinas van al 94 % (no al 90 %) y la maqueta
  pone solo lo que falta tras lo que encogen por su lado (~4–5 dp en un
  móvil). Antes se fijaba el aire de maqueta (16 dp) y, sumado a los ~6 dp que
  la vecina al 90 % dejaba libres, a la vista quedaban ~22 dp. La card es una
  fracción de la fila: carátulas al 42 % (~136 dp en un móvil de 360) e iconos
  al 46 % (~149 dp), unas 2,1–2,3 a la vista; en tableta crecen hasta los
  topes de siempre. El halo de la seleccionada (estela y luz en el estante) se
  recorta al aire menos 2 dp (`selectionFrame(haloSpread)`) y los nombres van
  del ancho de lo que se ve de la card. Apaisado no cambia
  (`ClassicLandscapeRegressionTest`). La seleccionada va en el primer hueco con 24 dp de la anterior
  asomando; la primera, pegada al margen, y al final la fila se para en el
  margen derecho. Antes el aire de "llenar la fila" se iba a 40 dp con una card
  y media por pantalla y, con una card entera delante, la seleccionada quedaba
  cortada por la derecha (`ClassicLayout.anchor`, `selectedSpan`,
  `ClassicPortraitTest`).
- Con el dock en la costura (vertical), el bloque del hero guarda 10 dp sobre
  él; la sinopsis pierde líneas (3 → 1) antes de irse.
- El pie del estante es opaco (`shelfFootColor`): tinta algo más honda en
  oscuro y perla con un poco más de acento en claro. El pie translúcido dejaba
  ver el fondo como una franja gris lavada.
- El arte del hero se pide al tamaño de su caja y en ARGB_8888
  (`HeroArtQuality`); si hay que ampliarlo más de 1,8 veces, se suaviza
  (desenfoque leve con RenderEffect y un velo algo más oscuro) en vez de
  enseñar los píxeles.

**Rendimiento** (Ajustes → Pantalla y tema → Rendimiento: Auto / Calidad /
Rendimiento; `PerformanceTier`, `SocCatalog`, `DevicePerformance`,
`LocalPerformance`). Auto elige Rendimiento en un equipo modesto:
`isLowRamDevice`; chip de una familia de entrada (Helio, Unisoc, Snapdragon
4xx/6xx bajos, cajas de TV…), salvo gama alta conocida o clase de rendimiento ≥
Android 12; < 4 GB; ningún núcleo a 2,2 GHz o < 6 núcleos; o < 5,5 GB sin una
CPU de 2,6 GHz. También con el ahorro de batería, y como último recurso si la
biblioteca va a tirones sostenidos (`JankFallback`, `JankMonitor`: p90 > 25 ms
en 4 ventanas seguidas de 5 s, sin contar arranque, intro, Lucy ni
descargas); eso se guarda (`perf.stutter`), se avisa una vez y se deshace
eligiendo Auto otra vez. Bajo Auto, Ajustes dice el motivo.
Es la misma app: el cristal va por su camino teñido (el de Android 8–11), el
fondo vivo se decodifica a 32 px y se amplía con bilineal en vez de
desenfocarse con RenderEffect, la flotación de logos, la respiración de la
pista y los acercamientos lentos se quedan quietos en su pose, la pantalla va a
60 fps salvo un 120 elegido a mano, Lucy usa `masha_lite.glb` y su voz se
suelta a los 10 s de salir de su pantalla.

## 10. Lucy en la biblioteca

**Emblema** (Holotank L; todo sale de `tools/masha_icon/lucy_emblem.py`, que
dibuja la geometría y escribe los XML: no se editan a mano). Un L macizo cuyo pie
es la placa emisora del holotanque; la punta del fuste se materializa en líneas
de escaneo que adelgazan hacia arriba. Lo envuelve un prisma de cristal con la
base plana que se abre arriba a la derecha —el hueco del anillo de foco, con un
remate de luz en cada extremo del canto—, y en esa abertura asoma ella: el rombo
champán al que sube el haz cian. Paleta Plasma; el cristal oscuro hace que se lea
igual en claro y en oscuro.
- `masha.xml`: a color, para 40 dp o más (el botón, la sala del holograma).
- `masha_small.xml`: el glifo para 32 dp o menos ("Lucy recuerda", la vista
  previa de su color, el widget): tres franjas opacas en vez de seis
  translúcidas y sin el filo interior, para que la punta del L siga nítida.
- `lucy_mono.xml` e `ic_stat_lucy.xml`: plano, de un color (el segundo es el
  icono pequeño de sus notificaciones). El icono de la app sigue siendo el de
  Elyndra.

**Botón**: fijo, la primera pieza de la barra del hero, arriba a la izquierda
(también la primera para el mando). Mide 40 dp —un avatar, algo mayor que las
píldoras de 34— pero en la barra cuenta como 34 para no desalinearla, y se toca
en 48 × 48. Ya no flota ni se arrastra: va dentro del layout y no puede tapar
el carrusel ni el hero. Alrededor flotan sus partículas (`mashaAura`): el
polvo estelar de la selección —mismos sprites, sin desenfoque—, del color de
Lucy (Ajustes → Lucy), 14 (8 en calidad ligera) que nacen en el canto del
orbe y suben con un rizo. Solo esa pieza tiene reloj; con "reducir
movimiento" quedan quietas. Su línea ambiental sale debajo,
alineada a su izquierda, sobre el arte del hero, y se recoge a los 14 s.
En Meridian el botón es la primera pieza del grupo de arriba a la derecha
(ver §11); ahí, con la píldora de hora a 8 dp, sus partículas no pasan de 6 dp
a los lados del orbe (`AuraHalo`: se apagan antes y lo que asome se recorta;
hacia arriba siguen subiendo).

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
(280–520 dp). La línea de foco va en el centro. Arriba de la rueda, solo el
dock de secciones, en la misma fila que la barra de la derecha
(`MeridianTopRow`: el mismo tope, 8 dp o 6 en ventana baja, el mismo alto de
34 y la misma línea central). La barra es `[Masha] [hora · batería] [Abrir]
[orden] [buscar] [Ajustes]` (la píldora de hora y batería viene apagada de
serie y se enciende en Ajustes; sin ella la barra es más corta): Lucy va ahí y no encima del dock (sola en la
cabecera se veía apretada y sin alinear con nada, y su fila le quitaba 48 dp de
alto a la rueda, que ahora los recupera). La barra compacta como en el Clásico
(`TopBarLayout`: "Abrir" a su icono, la píldora sin el tanto por ciento, sin
píldora); Lucy y Ajustes nunca se van. Su ancho máximo
(`MeridianTopRow.barMaxWidth`) deja al menos 8 dp hasta donde puede asomar el
dock. A la derecha, el arte del
juego a sangre (`HeroArtLayer`), ensanchado un 35 % y anclado a la izquierda
(`MeridianArtFrame`), el bloque del hero abajo (margen inferior del 8 % del
alto; 6 % en ventana baja) y la barra arriba a la derecha. Entre el dial y el
bloque, 48 dp (`HERO_GAP`): eran 96 para el contador del nodo; sin él, el
hueco solo separa el dial del bloque, y no baja de 48 para que el velo ya esté
por debajo de 0,25 bajo el texto blanco del hero (en claro el velo es perla) y
para que el dock de la cabecera, que puede asomar sobre ese hueco, tenga en un
móvil al menos el sitio que tenía con el orden al lado. Los insets (barras del sistema y recortes)
ya los respeta la raíz de la app (`SafeAreaInsets` = `systemBars ∪ displayCutout`). Los fondos sí
pasan bajo la muesca hasta el filo de la ventana (`EdgeBleed.kt`): el arte del hero (también arriba,
bajo la cámara de un móvil en vertical; sus velos cuentan con esa franja), el fondo vivo y
el arte de Meridian crecen (`edgeBleed`); el cristal y el velo de la rueda reflejan su canto
(`edgeMirror`); el estante estira su degradado. Barra, rueda, cards y texto se quedan en el área segura.

**Tarjetas y profundidad** (`WheelTransform`, tablas por fila de distancia
0, ±1, ±2, ±3, ±4, interpoladas en línea):

| | 0 | ±1 | ±2 | ±3 | ±4 |
|---|---|---|---|---|---|
| Escala | 1 | 0,72 | 0,52 | 0,37 | 0,28 |
| Opacidad | 1 | 0,85 | 0,5 | 0,25 | 0 |
| Giro Y | 0° | 6° | 12° | 18° | 18° |
| Niebla | 0 | 0,12 | 0,26 | 0,42 | 0,55 |
| Texto | sí | sí (2 líneas) | no | no | no |

La tarjeta enfocada mide el 39 % del alto de la rueda (carátulas, como mucho
el 24 % del ancho de la ventana) o el 38 % (iconos y apaisadas), y nunca más
ancha que el 42 % de la rueda (al nombre le queda sitio); entre 88 y 380 dp.
Nunca tan alta que una vecina (±1) se salga de la rueda (`NEIGHBOR_REACH`):
con el 48 % de antes, en tableta las carátulas vecinas se cortaban en seco
contra la cabecera y el pie. Se leen tres filas (la enfocada y sus vecinas,
enteras) y, con iconos, dos tenues más. El margen izquierdo (`insetFor`)
cuenta con que la fila encoge desde su canto: la vecina, encogida y corrida
por la curva, queda al menos a 8 dp del filo (`NEIGHBOR_START`).

**Carátulas en un móvil apaisado** (`MeridianCoverLayout`, `WheelProfile`).
En ventana baja (`MeridianMode.compact`) la rueda lleva otro reparto: la
carátula enfocada mide lo que deja asomar 32 dp de cada vecina (`PEEK_MIN`,
con 8 dp de aire, `PEEK_GAP`): ~289 dp en 1600 × 720 px, el 70 % del alto
(antes ~191, y al principio ~147). Va en el centro exacto de la rueda y las
dos vecinas son simétricas por construcción: misma escala (72 %), misma
distancia, misma opacidad, el mismo eje vertical que la enfocada (sin la
curva del dial) y el mismo fundido en los dos cantos (la máscara, sin apagar
la fila entera). Las listas cortas no se centran en un móvil: la enfocada
sigue en el centro y solo falta la vecina que no hay. Tabletas y tele siguen
con el 43 %, las vecinas enteras sobre el arco. Los iconos de la biblioteca
toman el mismo reparto, pero sin pasar del ancho de la columna de siempre
(su barra de la derecha lo necesita). Cada carátula se encaja entera con su
proporción en un hueco de 0,70 (`SLOT_ASPECT`): PSP, Switch, 2:3 y PS2 miden
lo mismo de alto, centradas; las más anchas encajan enteras, más bajas. Sin
carátula, la reserva es una caja 2:3 a todo el alto, con el título encogido a
su caja (`PlaceholderTitleLayout`) y la extensión arriba a la derecha. Las
filas encogen desde el centro de su hueco y quedan a 16 dp del filo como poco.

Al moverla con el dedo, la fila que se queda 70 ms en la línea de foco ya se
selecciona (`WheelEffects`): el hero cambia mientras se recorre, no al
pararse del todo; a toda velocidad no se queda ninguna y el hero no parpadea.
Mientras el dedo la mueve, el muelle no persigue la selección nueva. Cada fila sigue el **círculo**
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
dibujar, sin objetos por fotograma. Sin contador de posición ("11 / 13"): el
dial y su muesca ya dicen dónde se está, y la cápsula al lado del nodo solo
añadía ruido entre la rueda y el hero.

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
graduada `(1 − smoothstep)^1,7` en una banda fija de 88 dp (`FADE_DP`; antes
el 16 % del ancho, hasta ~200 dp en tableta): densa junto a la rueda, un tercio
a mitad del tramo, y sin canto (por debajo de ~80 dp bajaría más de 0,02 por
dp). La máscara se calcula en dp.

**El cristal claro** (`WheelGlassLayer`, el mismo en Biblioteca y Carpeta)
no lleva una imagen propia: el arte de la escena se graba en una capa
(`ArtBackdrop`) y el cristal la vuelve a pintar en su sitio, avivada y
desenfocada (API 31+). Así, zoom de llegada, paralaje, fundidos, reserva y
escenario vacío son los de fuera por construcción: antes, la copia propia no
llevaba el zoom de llegada (1,04 → 1) y en tableta el arte de dentro iba
corrido ~35 dp. El desenfoque y el halo crecen con el ancho de la ventana
(`GlassPanelLayout`). Todos los valores del aspecto están en `GlassTokens`:
desenfoque 18 dp a 900 dp de ancho (14–28; la primera versión 20, 16–32; la
"más transparente" 14, 10–22), tinte del juego 0,10 (0,06 → 0,03 → 0,10), un
velo blanco nuevo de 0,07 en claro y 0,035 en oscuro (lo que la hace
esmerilada), filo 0,30 (0,16 → 0,24 → 0,30) y halo 0,06. El ajuste
"Desenfoque" lo escala desde ahí (18 px = 1×, como mucho 2×; a 0, sin
desenfocar). `GlassPanelLayout.resolve` elige el camino: RenderEffect en
Android 12+ en cualquier móvil (ya no depende de la RAM: la calidad ligera de
Lucy dejaba sin desenfoque a los móviles de menos de 5,5 GB), o, en Android
8–11, el arte nítido con tinte 0,18 y velo 0,14 (0,07 en oscuro). El cristal
es el fondo de la rueda de serie en los dos temas: antes el oscuro llevaba el
difuminado, y en un móvil en tema oscuro el cristal nuevo no aparecía nunca.
Una migración única (`SettingsStore.migrateGlass`, `glass.version` = 2) borra
lo guardado que era el valor por defecto de antes (el fondo de la rueda igual
al de su tema, "Desenfoque" en 18) y respeta lo demás. En depuración,
`GlassDebug` (src/debug) escribe en el log `ElyndraGlass` el camino y los
valores, y los pinta en pantalla con `cache/glass_debug`. El canto
se recoge siempre al menos un 6 % de medio alto en los filos (12–48 dp): una
cabecera ancha lo abre, pero ya no lo endereza.

En claro, el cristal curvo pasa 24 dp del eje
(el dial con su nodo dentro; antes 84, para la cápsula del contador). Lo que va
encima de la niebla (el dock de la cabecera, el bocadillo de Lucy) se mide
contra su canto *visible* y curvo a su altura (`MeridianFog.visibleEdge`): el
filo del cristal en claro y, en oscuro, donde el velo ya ha bajado a la mitad
(~34 dp pasado el eje, `WASH_REACH`), no los 88 dp donde ya es transparente.
El dock de la cabecera va a la izquierda (`START`, 22 dp), no pegado al canto
de la columna, y no pasa de ese canto, menos 16 dp, ni a la altura de
su borde de arriba ni del de abajo (`RailHeaderLayout.plan`). Si a 22 dp
pasaría de la curva, antes de nada se corre hacia el filo, hasta 8 dp
(`MIN_START`): así, en un móvil, el cristal no se abomba arriba. Si así sus
rótulos no caben enteros, puede pasar hasta 12 dp más (`LABEL_STRETCH`), lo
justo; más allá se recortan. En ese caso, o si ni en su ancho mínimo cabe, la
niebla se ensancha solo en la banda de la cabecera (`MeridianFog.Band`) hasta
el dock y vuelve a la curva en 32 dp. La curva la calcula la propia cabecera
antes de que la rueda se mida (`FogArc.forRail`, con el alto que dejan la
cabecera y las pistas, que la columna mide primero): el dock tiene su ancho
final desde el primer fotograma. El perfil se curva con el círculo de la
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
zona). La cabecera de Carpeta: el emulador de la carpeta, el nombre en una
línea y la ruta en otra recortada por el centro, a 12 dp de la píldora de la
batería (`FolderBarLayout`, la misma en el Clásico). El emulador va ahí, con
el sistema que nombra la carpeta, y no junto a volver y "Abrir": en la
cabecera de la rueda obligaba al cristal a abrirse hasta enderezarse. En
Meridian lleva además, a la izquierda del emulador y en la misma fila, la
ficha de la carpeta ("4 ROMs · 0h 16m"), que iba bajo volver y "Abrir" y le
quitaba a la rueda ~28 dp de alto; en la misma fila y no bajo la ruta, para
que el grupo no baje hasta los chips del hero en un móvil. Si no cabe, se
compacta en este orden: el nombre y la ruta encogen hasta 96 dp (la ruta por
el centro), la ficha pierde el tiempo ("4 ROMs"), el rótulo del emulador se
recorta (hasta 56 dp) y se queda en su icono (con el nombre para el lector de
pantalla) y, en lo más justo, la ficha se quita; la píldora de estado no cede. El mando
la recorre igual: volver, Abrir, emulador. Todas las píldoras de
cristal oscuro (volver, Abrir, emulador, hora, buscar, Ajustes)
llevan en Meridian un suelo de tinta de 0,66 (`MeridianGlass`, `LocalGlassFloor`):
texto ≥ 4,5:1 e iconos ≥ 3:1 sobre cualquier fondo. Se tocan en 48 dp
aunque se vean de 34 (`touchTarget`).

**Bocadillo de Lucy** (`BubbleSlot.underGroup`): debajo del grupo de arriba a
la derecha, 8 dp bajo Lucy, sobre el arte. Si es más ancho que lo que va de
Lucy al canto del grupo, pegado a ese canto; si no, alineado a Lucy: siempre
queda debajo de ella. Una línea con una X al lado (22 dp, tocable en 48 × 48,
descrita como "Ahora no"), hasta 560 dp: en un móvil apaisado (892 × 411) la
línea entera cabe con ~200 dp de sobra en español. Si no cabe (640 dp con la
letra a 1,3), el texto se parte en dos líneas y "Ahora no" baja debajo, en vez
de cortarse. Nunca tapa la barra, el dock (si asomara debajo, empieza pasado
él) ni el bloque del hero (fichas, título, Jugar/Detalles/Opciones); si no hay
sitio que no lo tape, no se enseña. La rueda ya no le deja sitio: no va sobre
ella. En el Clásico, debajo de Lucy y, si el dock queda justo debajo (sección
vacía), por debajo del dock.

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

**Calidad ligera** (la de Lucy, `MashaQuality.Lite`): solo ±2 filas, sin
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
