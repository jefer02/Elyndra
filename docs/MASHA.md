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
    # copiar out/masha_v2/masha*.glb a app/src/main/assets/masha/
    # y out/masha_v2/runtime_textures/*.png a app/src/main/assets/masha/textures/

Requisitos: Blender 4.2 LTS + extensión MPFB 2.0.x con los paquetes CC0 `makehuman_system_assets`,
`faceunits01`, `visemes02` y `hair01` instalados. Unos 4 min.

| Etapa | Archivo | Qué hace |
|---|---|---|
| body | `body.py` | humano MPFB (proporciones en `MACRO`/`DETAIL_TARGETS`), esqueleto Mixamo, huesos blandos `masha:breast.*`, `masha:glute.*`, `masha:hair.0-3`, máscaras del traje |
| face | `face.py` | 31 morphs (ARKit + visemas, contrato en `face_contract.json`), boca cerrada en reposo, `Masha_Head` separada por el anillo `NECK_SEAM_VERTS`, huesos de ojos |
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

- `app/src/main/assets/masha/masha.glb` — calidad alta (~6,0 MB, ~57k triángulos)
- `app/src/main/assets/masha/masha_lite.glb` — ligera (~3,9 MB, ~33k triángulos), para gama media/baja
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
