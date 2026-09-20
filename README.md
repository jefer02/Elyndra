# Elyndra

**Lanzador nativo para Android — Kotlin + Jetpack Compose**

Elyndra unifica en una sola biblioteca los juegos Android instalados y las
carpetas de ROMs de emulador, con una interfaz pensada para mando y para
usarse desde el sofá. La app **solo indexa y lanza títulos: nunca emula
nada**; cada ROM se entrega al emulador que el usuario eligió para su
carpeta, a través del intent exacto de ese emulador.

## Características

- **Biblioteca unificada.** Apps Android y carpetas de ROMs conviven en un
  único carrusel, con filtros por tipo (Todos / Android / Emuladores) y
  varios criterios de orden.
- **Metadatos automáticos.** Carátulas, logos, capturas, sinopsis, fechas,
  géneros y logros de hasta cuatro servicios (ScreenScraper, IGDB,
  SteamGridDB, RetroAchievements), identificados por hash del archivo y
  aplicables a toda la biblioteca de una pasada, en segundo plano.
- **~130 perfiles de emulador** ya configurados (componente, acción, extras),
  con instalación asistida cuando falta el emulador elegido.
- **Carpetas de ROMs vía SAF**, sin permisos de almacenamiento: detección
  automática del sistema por el nombre de carpeta, alta masiva de carpetas
  raíz con subcarpetas por sistema, y un escaneo que ignora bios/saves/media
  y agrupa correctamente discos multipista y juegos de PS3.
- **Pensada para mando.** Toda la app —biblioteca, carpetas, ajustes,
  diálogos— se maneja con cruceta y sticks; funciona igual con Xbox,
  PlayStation, Switch Pro, mandos genéricos y el mando a distancia de una
  tele.
- **Tiempo de juego real**, medido entre el lanzamiento de cada título y la
  vuelta a Elyndra.
- **Multiidioma**: español, inglés, portugués, francés, alemán y japonés,
  sin reiniciar la app.
- **Lucy**, un asistente conversacional integrado (opcional) con estadísticas
  reales de juego.
- **Interfaz "liquid glass"** hecha a mano en Compose: degradados,
  desenfoques, auroras y animaciones de entrada calcadas del diseño
  original.

## Compilar

```
./gradlew assembleDebug
./gradlew installDebug        # con un dispositivo o emulador conectado
./gradlew testDebugUnitTest   # pruebas unitarias (hashes, parsers, lanzador…)
```

Requiere JDK 17+ y el SDK de Android (compileSdk 35, minSdk 26).

Para probar en el emulador usa una imagen de sistema estándar (por ejemplo
"Google Play · x86_64", Android 15). La imagen experimental "16 KB Page Size"
de x86_64 es inestable en Windows (WHPX): cierra al azar cualquier proceso
(sistema, Servicios de Google Play, la propia app) a los pocos segundos. No
hace falta para comprobar la compatibilidad con 16 KB: la única librería
nativa del APK (`libandroidx.graphics.path.so`, de Compose) ya está alineada a
16 KB.

## `local.properties`

No se versiona. Además de `sdk.dir=...`, admite:

```
lucy.apiKey=...                  # Lucy (sin clave: modo demo)
screenscraper.devId=...          # credenciales de DESARROLLADOR de ScreenScraper
screenscraper.devPassword=...
screenscraper.softname=Elyndra   # opcional
```

Las credenciales de desarrollador de ScreenScraper se piden en el foro de
screenscraper.fr presentando la app; sin ellas la API no responde a ninguna
llamada. Si no se compilan, el usuario puede escribirlas en Ajustes.

## Servicios de metadatos (Ajustes → APIs de metadatos)

Cada panel tiene sus campos, "Probar conexión" (una llamada real), el enlace
a la página donde se consiguen las claves y el estado (conectado, sin
verificar, error concreto). Todo se guarda cifrado con AES-GCM y una clave
del Android Keystore (`SecretStore`).

| Servicio | Qué pide el usuario | Dónde se consigue | Qué aporta |
|---|---|---|---|
| ScreenScraper | usuario + contraseña (opcional, sube el cupo) | screenscraper.fr | identificación por hash (CRC/MD5/SHA-1 + nombre + tamaño), carátulas, logos, capturas, sinopsis |
| IGDB | Client ID + Client Secret | dev.twitch.tv/console/apps (redirect `http://localhost`) | textos, fechas, géneros, estudios, carátulas; token OAuth client-credentials renovado solo |
| SteamGridDB | API key | steamgriddb.com → Preferences → API | grids, heroes y logos (sobre todo para apps Android) |
| RetroAchievements | usuario + Web API Key | retroachievements.org → Settings → Keys | juego y progreso de logros; hash rcheevos o, si no se puede, título |

"Aplicar metadatos a toda la biblioteca" recorre ROMs y apps en este orden:
hash del archivo → ScreenScraper (`jeuInfos`, y `jeuRecherche` si no hay
coincidencia) → IGDB para lo que falte → SteamGridDB para el arte que falte →
RetroAchievements. Respeta los límites de cada API (1 hilo en ScreenScraper,
4 req/s en IGDB, reintentos ante 429) y deja de usar un servicio en la pasada
si se queda sin cupo o las credenciales fallan, indicándolo en Ajustes. La
pasada corre en un servicio en primer plano (`ScrapeService`, tipo dataSync)
para que siga aunque se salga a jugar.

Los hashes de RetroAchievements replican rcheevos: cartuchos con cabeceras
(NES/FDS, SNES, Lynx, 7800, PC Engine), Nintendo 64 en cualquier orden de
bytes, Nintendo DS, arcade por nombre de set, y discos PS1/PS2/PSP en
ISO o BIN/CUE. Para CHD, CSO o 7z se usa el título.

## Carpetas de ROMs

- Se eligen con el selector del sistema (Storage Access Framework): no hace
  falta ningún permiso de almacenamiento y el acceso se conserva.
- El sistema se detecta por el nombre de la carpeta ("psp", "Game Boy
  Advance"…). Si se elige una carpeta raíz con subcarpetas por sistema,
  "Añadir todos" las da de alta de una vez.
- El escaneo oculta las pistas de `.cue`/`.gdi`/`.m3u`/`.ccd`, trata como un
  juego las carpetas de PS3 (JB) y se salta `bios`, `media`, `saves`…
- Se reescanea al abrir la app (cada 6 h como mucho) o a mano.

## Lanzamiento

`data/Emulators.kt` tiene ~130 perfiles con el intent exacto de cada
emulador (componente, acción, categoría, `data` y extras), sacados de la
configuración Android de ES-DE. Cada perfil recibe la ROM de una de estas
formas: URI SAF con permiso de lectura, URI de `RomProvider` (equivalente al
`%ROMPROVIDER%` de ES-DE) o ruta absoluta (RetroArch y otros). También se puede
elegir cualquier app instalada como emulador ("Otra app…"). Si falta el
emulador, la app ofrece instalarlo o elegir otro.

El tiempo de juego se mide entre el lanzamiento y la vuelta a Elyndra.

## Mando

Elyndra normaliza cualquier mando —Xbox, PlayStation, Switch Pro, clónicos
genéricos y el mando a distancia de una tele— a un único juego de acciones
(`input/Gamepad.kt`), así que la app no distingue de qué mando viene la
pulsación:

- **Cruceta / stick izquierdo**: mover la selección en el carrusel de la
  biblioteca o de una carpeta, una card a la vez.
- **L1/R1**: cambiar de categoría en la biblioteca (Todos / Android /
  Emuladores) o saltar de página dentro de una carpeta.
- **A**: abrir. **B**: volver. **X**: ficha del juego. **Y**: menú del
  juego. **Start**: menú de la app. **Select**: buscador.

Los sticks llegan como movimiento continuo, no como pulsaciones discretas:
`StickRepeater` los convierte en una pulsación por inclinación y, si se
mantiene, en repeticiones espaciadas, con un umbral e histéresis pensados
para que un solo gesto mueva un solo elemento, incluso con un mando algo
gastado.

## Idiomas

Español, inglés, portugués, francés, alemán y japonés (`res/values-*`). El
idioma se cambia en Ajustes sin reiniciar la app; en Android 13+ usa el
idioma por aplicación del sistema (`res/xml/locales_config.xml`). Para
añadir uno: copiar `values/strings.xml` a `values-xx/`, traducir y añadirlo a
`AppLocale.SUPPORTED` y a `locales_config.xml`.

## Estructura

```
app/src/main/java/com/elyndra/launcher/
  ElyndraApplication.kt        contenedor de dependencias
  MainActivity.kt              arranque, idioma, ciclo de vida (sesiones de juego)
  data/                        modelos, sistemas, perfiles de emuladores, repositorio
                               JSON de la biblioteca, ajustes, secretos cifrados, idioma
  input/                       normalización de mandos (botones, sticks, repetición)
  library/                     escáner SAF, hojas de disco, apps instaladas, nombres
  launch/                      planificador de intents, lanzador, RomProvider
  metadata/                    clientes ScreenScraper / IGDB / SteamGridDB /
                               RetroAchievements, hashes, caché de imágenes,
                               motor de metadatos y servicio en primer plano
  lucy/LucyClient.kt           Lucy (pendiente de rehacer)
  ui/
    ElyndraViewModel.kt        estado y navegación; Add/Settings/LucyController
    ElyndraApp.kt              pantallas + capas (diálogos, hojas, ficha, avisos)
    InputController.kt         traduce el mando a acciones según la capa activa
    theme/                     tipografía, degradados CSS, cristal, animaciones
    components/                texto, controles, hero, carátulas, capas
    screens/                   Library, Folder, Add, Settings (+APIs), Details, Lucy
```

## Cómo se tradujo el diseño

Las medidas del diseño son px CSS sobre un lienzo de 412 de ancho, que es el
ancho en dp de un móvil corriente, así que **los números pasan a dp/sp tal
cual**: `heroH` 330, `tileH` 96, `pad` 18, radios 16/17/18, etc. La bandera `L`
del diseño (el layout ancho de 892×412) aquí es la orientación real del
dispositivo, no un par de botones.

Piezas que había que construir a mano porque Compose no las trae:

- **Degradados CSS.** `linear-gradient(150deg, …)` mide el ángulo en sentido
  horario desde "hacia arriba"; Compose quiere dos puntos. `Paint.kt` hace la
  conversión, incluida la longitud de la línea de degradado.
- **Carátulas procedurales** (`art()`): el degradado a 150° más la trama de
  rayas a 115°. Siguen debajo de cada imagen descargada: se ven mientras carga
  o si un juego no tiene carátula.
- **Aurora**: degradados radiales que caen a transparente en lugar del
  `blur(64px)`.
- **Slider** e **iconos** dibujados a mano, como en el diseño.
- **Animación de entrada**: fundido, acercamiento sutil y una pequeña subida
  con la curva `cubic-bezier(.2,.8,.2,1)` de todo el diseño (`Anim.kt`), para
  que el primer fotograma al abrir la app se sienta pulido y no un simple
  parpadeo.

**Tipografía.** Poppins va incluida (`res/font/`, licencia OFL en
`POPPINS-OFL.txt`). Para japonés Android usa la fuente CJK del sistema.

**Liquid glass.** Compose no tiene backdrop-filter; se replican tinte,
opacidad, borde, sombra y realce, y el desenfoque se traduce en lechosidad.

## Lucy y la API key

`LucyClient` lee la clave de `local.properties` (`lucy.apiKey=...`) vía
`BuildConfig`. Sin clave responde con los textos locales del prototipo y la
cabecera dice "MODO DEMO". Llamar a la API desde el móvil vale para probar,
**no** para publicar: en producción la llamada debe salir de un backend propio.
Las tarjetas de estadísticas de Lucy ya usan el tiempo de juego real.
