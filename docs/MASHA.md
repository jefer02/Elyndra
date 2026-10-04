# Masha — holotanque 3D

La pantalla de Masha es un holograma 3D en tiempo real (SceneView 2.3 / Filament 1.56)
con voz, oído, ánimo visible y un ambiente sonoro propio. **Lo que Masha sabe hacer no ha
cambiado de sitio**: la conversación, las herramientas, la memoria y DeepSeek siguen en
`masha/` y `ui/MashaController.kt`; la pantalla nueva solo los presenta de otra manera.

## Modelo v2 (el actual)

`assets/masha/masha.glb` y `masha_lite.glb` salen del pipeline v2 (`tools/masha_v2/`), basado
en MPFB2 (MakeHuman para Blender). Sustituye al personaje procedural de `tools/masha/`, del
que solo se reutiliza el holotanque.

    blender -b -P tools/masha_v2/build.py -- --stages body,face,hands,shoulders,export --qa 0 --out out/masha_v2
    # sin --factory-startup: desactivaría la extensión MPFB
    # Blender 4.2 LTS de este equipo: C:\Users\jefer\AppData\Local\bl423\blender.exe
    # la salida es determinista: sin cambios en el código, masha.glb sale idéntico byte a byte
    # export.py pasa cada GLB por densify_morphs.py (morphs sparse con base de ceros): sin eso,
    # Filament 1.56 no deforma la cara en el dispositivo (ver docs/MASHA_LIPSYNC.md, "Coste")
    # copiar out/masha_v2/masha*.glb a app/src/main/assets/masha/
    # y out/masha_v2/runtime_textures/*.png a app/src/main/assets/masha/textures/

Requisitos: Blender 4.2 LTS + extensión MPFB 2.0.x con los paquetes CC0 `makehuman_system_assets`,
`faceunits01`, `visemes02` y `hair01` instalados, más las descargas de Mixamo y `base_pose.json` que
usa la etapa export (ver `export.py`). Unos 5 min.

Solo cara/boca: se ajusta en `face.py` (recetas de visemas, poses de lengua), se reconstruye con el mismo
comando y se revisa con `tools/masha_v2/face_review/` (hojas de render y diff semántico de GLB). El
contrato de morphs para la sincronía de labios está en `docs/MASHA_LIPSYNC.md`.

| Etapa | Archivo | Qué hace |
|---|---|---|
| body | `body.py` | humano MPFB (proporciones en `MACRO`/`DETAIL_TARGETS`), esqueleto Mixamo, huesos blandos `masha:breast.*`, `masha:glute.*`, `masha:hair.0-3`, máscaras del traje |
| face | `face.py` | 47 morphs (ARKit + 14 visemas solo-labios; contrato v2 en `face_contract.json` y `docs/MASHA_LIPSYNC.md`), lengua en reposo bajada 5 mm, boca cerrada en reposo, `Masha_Head` separada por el anillo `NECK_SEAM_VERTS`, huesos de ojos |
| hands | `hands.py` | huesos de giro de antebrazo/brazo, pesos, poses de mano (`hand_poses.json`) |
| export | `export.py` | quita la geometría auxiliar, nombres/materiales del contrato, clips Idle/Talk/Listen/Think/Explain/Wave, holotanque v1, GLB alta + LOD ligera, texturas de ejecución |

Materiales: `tools/masha_v2/compile_materials.ps1` compila `materials/*.mat` con el `matc` de
Filament 1.56 (el de SceneView 2.3). En la app, `HoloShader` los pone en lugar de los del GLB
cuando el modelo es v2 (tiene `jawOpen`); `HoloRig` anima la cara con `FaceRig` y
`SpringBonesFilament` mueve pecho, glúteos, coleta y huesos de giro. Con un GLB antiguo todo
vuelve al comportamiento v1.

Ajustes: materiales en `HoloShader.HoloShaderParams`/`HoloEyeParams`/`HoloCardParams`; física en
`SpringConfig` (`SpringBones.kt`); poses de los clips en `export.py` (`base`, `clips`, `ARM_DOWN`).

Licencias: MPFB2 (código GPL, **salida y assets CC0**), MakeHuman system assets, faceunits01,
visemes02 y hair01: CC0. Blender: GPL (herramienta; no afecta a lo generado). Animaciones:
propias (procedurales en `export.py`), sin Mixamo. Filament/SceneView: Apache 2.0.

## Qué hay y dónde

| Pieza | Archivo |
|---|---|
| Pantalla (layout, panel, dock, sonido) | `ui/screens/MashaScreen.kt` |
| Escenario 3D, entorno, luces, posproceso, ciclo de vida nativo | `ui/masha/MashaStage.kt` |
| Animación en vivo: clips con fundido, visemas, parpadeo, ánimo, glitch | `ui/masha/HoloRig.kt` |
| Expresión de la cara: ánimo → morphs, microexpresiones, pupila, gestos de cabeza | `ui/masha/MashaExpression.kt` |
| Canales → morphs del GLB, parpadeo, acoplamiento párpados–mirada | `ui/masha/FaceRig.kt` |
| Materiales del holograma (cuerpo, cara, ojos, pelo) | `ui/masha/HoloShader.kt` + `tools/masha_v2/materials/*.mat` |
| Estado compartido (habla / escucha / piensa / ánimo / gestos) | `ui/masha/MashaPresence.kt` |
| Ánimo → color y energía (local, sin tocar la IA) | `ui/masha/MashaMood.kt` |
| Voz (TTS, frase a frase mientras llega el streaming) | `ui/masha/MashaVoice.kt` |
| Sincronía de labios | `ui/masha/LipSync.kt` |
| Micrófono (STT) | `ui/masha/MashaEars.kt` |
| Ambiente sonoro (Media3, lazo sin costura, ducking) | `ui/masha/AmbientSoundscape.kt` |
| Partículas, banda de escaneo, fallback 2D | `ui/masha/HoloAtmosphere.kt` |
| Mensajes y tarjetas (juegos, plan, arco, mención) | `ui/masha/MashaCards.kt` |
| Generador del modelo + HDR (Blender) | `tools/masha/build_masha.py` |
| Generador del ambiente (Python) | `tools/masha/generate_ambient.py` |

Assets (ya generados y en el repo):

- `app/src/main/assets/masha/masha.glb` — calidad alta (~8,1 MB, ~57k triángulos)
- `app/src/main/assets/masha/masha_lite.glb` — ligera (~7,8 MB, ~33k triángulos), para gama media/baja
- `app/src/main/assets/masha/room.hdr` — sala de control (IBL + fondo)
- `app/src/main/res/raw/masha_ambient.ogg` — 96 s, lazo perfecto (~1,1 MB)

`MashaQuality.detect()` elige el modelo: alta con ≥ 5,5 GB de RAM o clase de rendimiento ≥ S.

## DeepSeek y capacidades

Nada cambia en cómo se llama a DeepSeek: la clave sigue leyéndose de `local.properties`
(`masha.apiKey`) o de Ajustes → Masha (cifrada), `DeepSeekMashaAI` sigue siendo el proveedor
y `MashaController.send()` el único punto de entrada — también para lo que se dice por voz.
Siguen todas las herramientas (juegos más jugados, recomendaciones, planes de sesión, arcos,
listas, limpieza, metadatos, emuladores, memoria…) y el modo sin conexión.

Cambios de comportamiento:

- `MashaPrompt` (VERSION 4): personalidad (leal, empática, humor seco) y "tus respuestas se
  leen en voz alta". Subir la versión invalida la caché de respuestas antigua.
- `MashaController.mood`: el ánimo se deduce en local de la respuesta y de las herramientas usadas.

## El modelo: cara y manos

`tools/masha/anatomy.py` (usado por `build_masha.py`) construye cada pieza así:

    JAULA de quads con buen flujo de aristas
      ├─ ALTA = jaula + Multires (3 niveles) + esculpido (Multires Reshape)
      └─ BAJA = jaula + Subdivision Surface (aplicado) + Weighted Normals  → GLB
         └─ se hornean NORMAL (tangente, OpenGL) y AO de la ALTA sobre la BAJA

- **Cara**: esfera de quads con agujeros en ojos, nariz y boca rellenos con anillos
  concéntricos (inset): reborde orbitario, pliegue del párpado, párpados y borde libre;
  labios, bermellón y línea de cierre; anillos de la nariz. La abertura de la boca lleva
  saco bucal y la de los ojos, cuenca con el globo ocular. La forma es analítica en
  (u, v) y cada vértice se recoloca exacto tras subdividir (capa UV "Param").
- **Manos**: palma 8×3×2 y dedos de sección de 8 vértices, 3 bucles por falange más el
  de la articulación, nudillos, uña con inset y pliegue, pulgar desde la eminencia tenar.
- **Esculpido**: el pincel de Sculpt Mode no funciona sin ventana, así que el detalle fino
  (pliegue palpebral, línea de pestañas, bermellón, nasolabial, cejas, poros; arrugas de
  nudillos, pliegues palmares, tendones) se calcula y se escribe en los niveles de Multires
  con `multires_reshape`. Para esculpir a mano: guardar el .blend antes del horneado,
  retocar el objeto `*_high` y volver a llamar a `bake_maps()`.

Mapas (dentro del GLB, en JPEG; copia en PNG en `tools/masha/textures/`):

| Material | Normal | AO (oclusión + color base) | UV |
|---|---|---|---|
| `Holo_Face` | `masha_face_normal` | `masha_face_ao` | TEXCOORD_1 |
| `Holo_Hands` | `masha_hands_normal` | `masha_hands_ao` | TEXCOORD_1 |
| `Holo_Skin` (cuerpo) | `masha_body_normal` | `masha_body_ao` | TEXCOORD_1 |

La emisión con el código que fluye va en TEXCOORD_0. El GLB lleva tangentes (MikkTSpace,
las mismas del horneado). Presupuesto: ~57k triángulos (alta) y ~33k (ligera).

En la app, el botón de encuadre alterna entre ella entera y un retrato de la cara.

## Regenerar los assets

Modelo y sala (Blender 4.2 LTS o superior; ~30 s en GPU):

```
blender -b --factory-startup -P tools/masha/build_masha.py
blender -b -P tools/masha/build_masha.py -- --only high --no-env --preview out/preview.png
```

En Windows, si numpy falla al cargar ("nombre de archivo demasiado largo"), Blender está en
una ruta demasiado profunda: muévelo a una corta.

Ambiente sonoro:

```
pip install numpy soundfile
python tools/masha/generate_ambient.py [--seconds 96] [--wav]
```

El script comprueba que el salto del final al principio no sea mayor que un paso normal de
la señal y aborta si no. Por construcción no hay costura: tonos con ciclos enteros en el
lazo, ruido sintetizado en frecuencia, eventos escritos módulo N y reverb circular.

### Alternativas para el sonido

- Diseñarlo a mano con Vital o Surge XT (gratis): un pad de sierra muy filtrado en Re menor,
  un zumbido de 55 Hz con armónicos, ruido rosa en banda (200–900 Hz) con LFO lento, pitidos
  de 1,5–4 kHz muy cortos y una reverb de ~3 s. El lazo se cierra con un fundido cruzado
  entre el final y el principio y se exporta en Ogg Vorbis (calidad 4–5).
- Prompt para herramientas de audio con IA:
  *"Seamless loop, 90 seconds, sci-fi AI core room ambience: soft constant electronic ship hum
  at 55 Hz, deep evolving drone pads in D minor, gentle energy-field shimmer, sparse quiet
  data-processing clicks and short high beeps, wide stereo, 3-second spatial reverb, no melody,
  no drums, very low intensity, background for dialogue."*
  Revisa siempre la licencia del resultado y que el lazo no chasquee.

## Ajustes finos

- Encuadre y órbita: `TARGET`, `HOME`, `LANDSCAPE_SHIFT`, `MIN_DISTANCE`/`MAX_DISTANCE` en `MashaStage.kt`.
- Brillo del holograma: `Kind` (alfa y emisión por material) en `HoloRig.kt`; exposición 1
  en la cámara (los valores se leen tal cual).
- Colores por ánimo: `MashaMood`.
- Volumen: `AmbientSoundscape.MAX_GAIN` (techo del deslizador) y `DUCK` (lo que queda mientras
  habla). Voz: `MashaVoice.PITCH`/`RATE`.
- Rendimiento: la calidad ligera desactiva la oclusión ambiental y activa la resolución
  dinámica (`configureView`).

## Expresión de la cara y ojos

Objetivo: que la cara tenga carácter y los ojos se vean vivos **sin perder el holograma** (paleta
azul, Fresnel, scanlines, pulsos de circuito y el tinte del ánimo siguen igual).

### Ojos (`masha_holo_eye.mat`, `HoloShader.HoloEyeParams`)

Todo es emisión encima del ojo iluminado de antes (no es sombreado fotorrealista):

| Efecto | Cómo | Ajuste |
|---|---|---|
| Iris más legible | más claro y con más tono del ánimo; limbo (borde) oscurecido | `irisBrightness` 1,15, `irisTint` 0,3, `irisEmission` 0,42, `limbusDark` 0,3 |
| Anillo holográfico | banda fina emisiva justo dentro del limbo (+ una tenue en el borde de la pupila), color del brillo del ánimo, late con la voz y la atención | `ringGlow` 0,35 |
| Pupila que se dilata | el iris de la textura se deforma radialmente alrededor de la pupila de su isla UV (las fibras se comprimen como en un iris real) | `FaceExpression.pupil` → `pupilDilation` |
| Brillo fijo a la cámara | reflejo de una "caja de luz" virtual arriba a la izquierda de quien mira, dado en espacio de la vista: se queda quieto en pantalla aunque el ojo gire. Se calcula sobre la esfera del globo alrededor del hueso del ojo (donde iría la córnea): el iris de los ojos MPFB es cóncavo y su normal lo reflejaba al otro lado de la pupila. Antialias con `fwidth`; si es menor que un píxel se reparte (no parpadea en el plano entero) | `catchX/Y/Z` (−0,32, 0,24, 1), `catchSize` (≈ 2,3°), `catchIntensity` 1,2 |
| Córnea húmeda | lóbulo suave alrededor del brillo y un brillo rasante en el borde | `wetIntensity` 0,18 |
| Ojos legibles con glitch | alrededor de cada ojo (2,2 cm entero, nada a 4,5 cm) el glitch desgarra solo el 30 %, en el ojo y en la cara a la vez (misma fórmula en los dos materiales: párpados y ojos van juntos); la cara apaga ahí scanlines, barrido y parpadeo del glitch (`eyeScanCalm` 0,85); las pestañas casi no parpadean con él | `HoloShader.EYE_CALM_*`, `EYE_GLITCH` |

Geometría del iris (medida en `masha_eye_basecolor.png`/`irismask.png`): centros de pupila
(0,7067, 0,2962) y (0,2937, 0,7020), radio de pupila 0,031 UV, de iris 0,104 UV. Dientes y lengua
usan el mismo material con todo lo de ojo apagado (sus PNG son opacos y entran por la rama del iris).

### Cara: contornos (`masha_holo.mat`, instancia `FACE`)

Fresnel algo más fuerte (`rimIntensity` 0,6 → 0,7) y un segundo lóbulo ancho (`contourPower` 2,
`contourIntensity` 0,22): donde la superficie se aparta de la cámara —nariz, borde de los labios,
pómulos, mandíbula— la cara se ilumina desde dentro y los rasgos se leen a través del holograma.

### Ánimo → morphs (`MoodFace`)

Cada ánimo es una combinación de morphs ARKit de `Masha_Head` (L/R = lado de ella; asentado, sin ruido):

| Ánimo | Morphs | Pupila | Cabeza |
|---|---|---|---|
| Neutral | sonrisa 0,07/0,055 | 0 | — |
| Analytical | browDown 0,16/0,11, eyeSquint 0,15/0,12, mouthPress 0,10 | −0,10 | barbilla 1° abajo |
| Playful | **Duchenne**: mouthSmile 0,58/0,48 + cheekSquint 0,40/0,32 + eyeSquint 0,30/0,24; browOuterUp L 0,14; mouthDimple 0,12 | +0,18 | inclinada 3° |
| Warm | Duchenne suave 0,36/0,31 + 0,22/0,19 + 0,17/0,15; browInnerUp 0,14 | +0,20 | inclinada 2,5° |
| Curious | browInnerUp 0,32 + browOuterUp 0,30/0,20 + eyeWide 0,24/0,20; sonrisa 0,10/0,07 | +0,28 | inclinada 4,5° |
| Thinking | browDown L 0,38 (un lado), browInnerUp 0,14, eyeSquint 0,22/0,09, mouthPress 0,30, mouthLeft 0,22, párpados 0,07 abajo; la mirada se va a un lado (`Saccades`) | +0,12 | inclinada 3°, barbilla 2° arriba |
| Concerned | browInnerUp 0,58 + browDown 0,15/0,13, mouthFrown 0,44/0,40, mouthPress 0,12 | +0,06 | barbilla 1,5° abajo |
| Escuchando (encima del ánimo) | browInnerUp +0,16, browOuterUp +0,10/0,07, eyeWide +0,12/0,11, sonrisa +0,06/0,05 | +0,22 | inclinada 5° y asiente en las pausas |

`Curious` es nuevo: `MashaMood.read` lo da cuando la respuesta acaba preguntando y no hay otra señal
(cálida, juguetona, analítica). Color entre Neutral y Thinking.

### Dinámica (`FaceExpression`, `HeadGestures`, `Blink`)

- **Transiciones:** aparece en ~0,16 s y se va en ~0,42 s; al cambiar de ánimo, un pico de +35 % (ápice)
  que se asienta en ~2 s: el cambio se lee y luego no exagera.
- **Nunca quieta:** ruido lento (±14 %, ~0,23 Hz) multiplicativo e independiente por lado: la
  asimetría también vive y no enciende morphs apagados.
- **Microexpresiones** cada 2,5–7 s (2–5 escuchando, 3–8 hablando): ráfaga de cejas, una ceja, media
  sonrisa, entrecerrar, apretar labios; la mezcla depende del ánimo; hablando, solo la parte de arriba.
- **Cejas del habla:** acentos fuertes y pequeños de `LipSync` (ver [MASHA_LIPSYNC.md](MASHA_LIPSYNC.md),
  "Expresión al hablar"), repartidos entre browInnerUp y browOuterUp según el ánimo.
- **Al hablar** la boca de la expresión (apretar, de lado, hoyuelos) cede ante los visemas; la sonrisa
  cede en O/U/P/F como antes; el ceño baja un 40 %.
- **Párpados y mirada:** el GLB no trae eyeLookUp/Down (la mirada es de huesos), así que el pitch del
  ojo mueve los párpados: abajo baja el superior (`eyeBlink`, 0,5 a 20°, casi 1:1 con el iris), arriba
  lo abre (`eyeWide`, 0,4 a 15°) y desde 6° alza un poco las cejas.
- **Parpadeo** (`Blink.Mode`): intervalos sesgados (mínimo + exponencial, no un metrónomo):

  | Modo | Media | Incompletos | Dobles | Otros |
  |---|---|---|---|---|
  | Reposo | 3,8 s | 10 % (0,65–0,85) | 15 % | |
  | Hablando | 2,7 s | 12 % | 18 % | + comas, puntos y final de frase |
  | Escuchando | 4,6 s | 8 % | 10 % | |
  | Pensando | 3,0 s | **55 % medios (0,40–0,62)** | 8 % | |
  | Cálida | 4,2 s | 10 % | 12 % | 30 % lentos (~0,3 s cerrados) |

- **Cabeza:** capa aditiva después de la mirada (40 % cuello, 60 % cabeza): inclinación del ánimo,
  5° atenta al escuchar con cabeceos de 1,6–2,4° en las pausas de quien habla (p 0,65, ≥ 2,2 s entre
  ellos, a veces doble), al hablar cada acento fuerte cambia de lado la inclinación (1–2,5°) y la
  cabeza deriva ±1,2° en yaw; los cabeceos de los acentos y de las preguntas siguen viniendo de `LipSync`.

### Coste de GPU: morphs activos

Cada morph con peso ≠ 0 cuesta en el vertex shader de los 7568 vértices de la cabeza en cada pasada
(Filament salta los de peso 0), y cuesta lo mismo pese 0,02 o 0,6. Medido en una tablet con Snapdragon
8 Gen 3 a 120 Hz (`DEBUG_POSE --ei load N`, reposo, calidad alta): ~16 morphs activos → 120 fps (GPU
ocupada 80 %), 28 → 115 fps (95 %), 47 → 74 fps: **~0,25 ms de GPU por morph y fotograma** con la GPU
al límite (en un Snapdragon 7s Gen 2, del orden del doble). Topes: visemas ≤ 3 (sin cambios), nada
< 0,015, morphs de expresión < 0,03 fuera (`FaceMorphs.EXPR_MIN_WEIGHT`), y como mucho
`HoloRig.EXPR_BUDGET_HIGH` = 10 / `EXPR_BUDGET_LITE` = 7 de expresión a la vez (se resta el siguiente
en fuerza y se reescala, sin saltos). Activos (expresión + párpados + boca):

| Estado | Expresión | Párpados | Boca | Total |
|---|---|---|---|---|
| Reposo neutral | 2 (antes 6, casi invisibles) | 0–2 | 0 | 2–4 |
| Reposo con otro ánimo | 5–8 | 0–2 | 0 | 5–10 |
| Escuchando | 7–10 (ligera: ≤ 7) | 2 al parpadear | 0 | ≤ 12 |
| Hablando + acento + parpadeo | 5–10 (ligera: ≤ 7) | 2 | 5 (3 visemas, jawOpen, mouthClose) | 12–17 (antes ~16) |

Una microexpresión añade 1–3 durante 0,5–1 s (dentro del presupuesto). Los efectos del shader del ojo
solo cuestan en los píxeles del ojo; los de la cara, unas operaciones por píxel.

Medido (log `MashaPerf`, misma tablet, 120 Hz): de media, 8,0 → 4,1 morphs activos en reposo y
11,0 → 8,1 hablando con el ánimo neutral; el peor caso (Playful hablando) 12,9 de media y 17 como
mucho. fps presentados (SurfaceFlinger) antes → después, alta y ligera: 120,0 → 120,0 en reposo y
119,7–119,8 → 119,6–119,8 hablando a 120 Hz; 60,0 → 60,0 (59,9 → 59,9 hablando en ligera) a 60 Hz.
CPU de `HoloRig.frame` ≈ 1,0–1,1 ms en los dos (+0–40 µs).

### Probar la expresión

- Debug: chip "FX" a la derecha de la pantalla de Masha (Auto → cada ánimo → Listening), o
  `adb shell am broadcast -a com.elyndra.launcher.DEBUG_MOOD --es mood Curious` (`auto` vuelve).
- Primer plano: `adb shell am broadcast -a com.elyndra.launcher.DEBUG_CAM --ef y 1.56 --ef z 0.9`.
- Coste de cada morph: `DEBUG_POSE --ei load N` enciende N morphs más a 0,02 (invisibles).
- Pruebas: `MashaExpressionTest` (ánimo → morphs, atención, pupila, ápice, ruido, microexpresiones,
  presupuesto, parpadeo, párpados–mirada, cabeza, `Curious`, y que cara + parpadeo + voz + cabeza no
  crean objetos en 20 000 fotogramas).

## Probar

1. `./gradlew :app:assembleDebug` e instalar; abrir Masha (botón de la biblioteca o el
   intent `com.elyndra.launcher.OPEN_MASHA`).
2. Debe materializarse y saludar (gesto Wave). Preguntar "¿qué he jugado más?" → piensa,
   habla (estado "Hablando"), mueve la boca y el ambiente baja.
3. Micrófono: al primer toque pide permiso; lo dicho aparece en el campo y se envía solo.
4. Salir y volver varias veces: no debe cerrarse (ver "Ciclo de vida").

## Ciclo de vida nativo (importante)

`MashaStage` es dueño de todos los objetos de Filament (`Gpu`) y los libera en un único sitio,
en orden. Dos trampas que ya están resueltas y que no hay que reintroducir:

- No usar los `remember*` de SceneView para el motor, los cargadores o los nodos: cada uno se
  libera por su cuenta y el asset glTF acababa usado después de liberado.
- Antes de liberar el asset, quitarle a la `SceneView` sus nodos y su `onFrame`: si su vista se
  vuelve a enganchar mientras Compose la retira, vuelve a pedir fotogramas, y cada uno recorre
  sus nodos. Sin esto, salir de la pantalla daba SIGSEGV en `libgltfio-jni.so`.

## Límites conocidos y siguientes pasos

- El personaje es procedural: estilizado y ligero, no fotorrealista. Para más detalle (manos,
  cara), el camino recomendado es partir de una base CC0 (MPFB2/MakeHuman) o VRoid, y pasar por
  `build_masha.py` los materiales, morphs de cara, pelo, esqueleto y animaciones.
- SceneView 4.x (Filament más nuevo) necesita subir Kotlin a 2.4.
- Ideas: versión AR (SceneView `ARScene`), voz neuronal en la nube, visemas desde el audio,
  mirada que sigue al dedo, variaciones del ambiente por hora del día, más gestos por herramienta.
