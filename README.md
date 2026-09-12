# Elyndra — app Android nativa

Implementación nativa (Kotlin + Jetpack Compose) 

Elyndra unifica en una sola biblioteca los juegos Android instalados y las
carpetas de ROMs de emulador. Solo indexa y lanza títulos; nunca emula nada:
cada ROM se entrega al emulador que el usuario eligió para su carpeta.

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
  library/                     escáner SAF, hojas de disco, apps instaladas, nombres
  launch/                      planificador de intents, lanzador, RomProvider
  metadata/                    clientes ScreenScraper / IGDB / SteamGridDB /
                               RetroAchievements, hashes, caché de imágenes,
                               motor de metadatos y servicio en primer plano
  lucy/LucyClient.kt           Lucy (pendiente de rehacer)
  ui/
    ElyndraViewModel.kt        estado y navegación; Add/Settings/LucyController
    ElyndraApp.kt              pantallas + capas (diálogos, hojas, ficha, avisos)
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
