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
| Pestañas de filtro | Subrayado por pestaña que se enciende y apaga | Un solo indicador que se desliza. |
| Card seleccionada | Escala 1,14 y marco de 6 dp: tapa a las vecinas | Muelle a 1,05, marco fino y halo. |
| Biblioteca vacía | Titular gigante y una card "+" suelta | Estado vacío con icono, texto y botón principal. |
| Menú de pulsación larga | Rótulos a una línea (se cortan), imágenes sin estado y la zona de borrado mezclada con las imágenes | Rejilla de 2 líneas, imágenes con "Añadir/Cambiar" y borrado en un pie aparte. |
| Ajustes | Dos columnas largas y desequilibradas | Raíl de categorías + panel (ancho) o lista → página (estrecho). |
| Sonidos propios | 9 bloques de dos botones de texto | Sección plegable con filas compactas de iconos. |
| Prioridad de fuentes | 12 filas con flechas sueltas | Panel compacto plegable, con arrastre, estado y resumen. |
| APIs de metadatos | Todos los campos siempre abiertos | Acordeón con chip de estado. |
| Añadir → Carpeta de ROMs | Tres paneles en rejilla y una tarjeta vacía enorme | Pasos verticales con resumen, sistemas por fabricante y barra de acción fija. |
| Diálogos, ficha, selector de arte | Correctos | Solo accesibilidad y pistas de mando. |
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
- Estante (`shelfSurface()`): la mitad baja de Biblioteca y Carpeta. Perla en
  claro (papel con un velo del acento), tinta profunda en oscuro; un filo de luz
  arriba y la sombra que el hero proyecta sobre él.
- Filas de Ajustes: planas, sin tarjeta, separadas por `SettingsDivider`.

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
  en `metrics()`; los menús la llevan dentro del panel.
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
  líneas y las filas crecen en vez de truncar.

## 7. Piezas (`ui/components/ConsoleKit.kt`)

| Pieza | Uso |
|---|---|
| `SectionHeader` | Cabecera de sección en versalitas (`SectionLabel` de Ajustes la usa) |
| `SettingRow`, `SwitchRow`, `NavRow` | El patrón de fila: rótulo y descripción a la izquierda, control a la derecha |
| `SegmentedControl` | Pocas opciones cortas (fotogramas, píldora de estado, paquete de sonidos, textos/imágenes) |
| `AccordionHeader` + `Expandable` | Sonidos propios, prioridad de fuentes, servicios con cuenta, credenciales de desarrollador |
| `IconAction` | Botón de icono con descripción y 48 dp de zona táctil |
| `SlidingTabs` | Pestañas de filtro de la biblioteca, con un solo indicador |
| `EmptyState` | Biblioteca, sección o carpeta vacías (en fila si el hueco es bajo) |
| `ActionBar` | Barra fija de Añadir: estado a la izquierda, botón a la derecha, el porqué si está apagado |
| `PadHints` | Pistas del mando, solo con un mando conectado |
| `ConsoleGlyph` | Glifos de categorías y acciones, familia de `SheetGlyph` |

Vistas previas en claro y oscuro: `ConsolePreviews.kt` (anotación `@ConsolePreviews`).

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

