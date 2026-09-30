# Masha: sincronía de labios

## Morph contract

Contrato v2 de los morphs de `Masha_Head` (`assets/masha/masha.glb` y `masha_lite.glb`). La fuente
autoritativa es el bloque `lipsync` de `tools/masha_v2/face_contract.json`: nombre, índice, grupo,
`lipOnly`, `restUndo`, `recommendedMax`, `sourceJawOpen`, `sealWithJaw`, `minJawOpen`.
Lo genera `tools/masha_v2/face.py` (etapa `face`).

**Resuelve los morphs por nombre** (`extras.targetNames`). Los índices 0–30 son los de v1 y no cambian;
los 16 nuevos van al final (31–46). Si falta un nombre (GLB antiguo), degrada con elegancia.

Las 6 primitivas de la cabeza (Skin, MouthInterior, Teeth, Tongue, Brows, Lashes) llevan los 47 targets,
como exige Filament.

### Lista (orden del GLB)

| # | Nombre | Grupo | Máx. recomendado | Qué hace |
|---|---|---|---|---|
| 0–5 | eyeBlinkLeft/Right, eyeSquintLeft/Right, eyeWideLeft/Right | eyes | 1.0 | Sin cambios |
| 6–10 | browInnerUp, browDownLeft/Right, browOuterUpLeft/Right | brows | 1.0 | Sin cambios |
| 11–12 | cheekSquintLeft/Right | cheeks | 1.0 | Sin cambios |
| 13–16 | mouthSmileLeft/Right, mouthFrownLeft/Right | expression | 0.6 / 0.5 | Sin cambios (relativos) |
| 17 | jawOpen | jaw | 0.6 en el habla (1.0 = máximo natural) | **La única apertura de mandíbula**: mentón, dientes inferiores, lengua y suelo de la boca. Sin cambios respecto a v1 |
| 18 | mouthPucker | mouthUnit | 0.7 | Sin cambios (absoluto, `restUndo` 1) |
| 19 | mouthPress | mouthUnit | 0.6 | Sin cambios (relativo) |
| 20 | mouthLeft | mouthUnit | 0.5 | Sin cambios |
| 21 | viseme_aa | viseme | 0.75 | A: solo labios (labio superior algo elevado). La abertura la pone jawOpen |
| 22 | viseme_E | viseme | 0.75 | E: labios estirados, se ven los incisivos superiores |
| 23 | viseme_I | viseme | 0.7 | I: estirado fuerte |
| 24 | viseme_O | viseme | 0.8 | O: **redondeada y adelantada** (MPFB O + 0.3 funnel + 0.15 pucker) |
| 25 | viseme_U | viseme | 0.85 | U / W: redondeo cerrado y protrusión (MPFB U + 0.6 pucker + 0.3 funnel) |
| 26 | viseme_PP | viseme | 1.0 | P/B/M: labios **sellados y apretados**, algo recogidos (sellado a jawOpen 0; ver regla de sellado) |
| 27 | viseme_FF | viseme | 0.9 | F: labio inferior subido y **metido bajo los incisivos superiores** |
| 28 | viseme_SS | viseme | 0.8 | S (y Z/C con seseo): labios estirados, dientes juntos |
| 29 | viseme_DD | viseme | 0.8 | T/D: punta de la lengua al alveolo |
| 30 | viseme_CH | viseme | 0.8 | CH/SH/LL/Y: labios adelantados y cuadrados |
| 31 | viseme_kk | viseme | 0.7 | K/G/J: labios neutros-abiertos (la lengua trasera no se ve) |
| 32 | viseme_nn | viseme | 0.8 | N/L/Ñ: punta de la lengua al alveolo, labios relajados |
| 33 | viseme_RR | viseme | 0.75 | R de MPFB/Meta (r inglesa, algo redondeada) + punta de lengua arriba ×0.7. Para la r española usa labios de nn/DD con un toque corto de RR |
| 34 | viseme_TH | viseme | 0.85 | θ/ð (z/c de España, th inglesa): punta de la lengua entre los incisivos. **Necesita jawOpen ≥ 0.12** |
| 35 | mouthClose | mouthUnit | 1.0 | Labios cerrados sobre la mandíbula abierta: `mouthClose == jawOpen` los sella. También es el canal de corrección |
| 36 | mouthFunnel | mouthUnit | 0.6 | Embudo (redondeo abierto), relativo: sirve para anticipar O/U |
| 37 | mouthRollLower | mouthUnit | 0.5 | Labio inferior hacia dentro |
| 38 | mouthRollUpper | mouthUnit | 0.5 | Labio superior hacia dentro |
| 39 | mouthUpperUp | mouthUnit | 0.5 | Labio superior arriba (L+R) |
| 40 | mouthLowerDown | mouthUnit | 0.6 | Labio inferior abajo (L+R) |
| 41 | mouthShrugLower | mouthUnit | 0.5 | Labio inferior arriba/fuera |
| 42 | mouthShrugUpper | mouthUnit | 0.5 | Labio superior arriba/fuera |
| 43 | mouthStretch | mouthUnit | 0.5 | Comisuras estiradas hacia los lados y abajo (L+R) |
| 44 | mouthDimple | mouthUnit | 0.5 | Comisuras hacia atrás, hoyuelos (L+R) |
| 45 | mouthRight | mouthUnit | 0.5 | Boca hacia su derecha |
| 46 | tongueOut | tongue | 0.8 | Lengua fuera sobre el labio inferior (solo lengua). Úsala con jawOpen ≥ 0.3 |

Las unidades nuevas son **relativas** (`restUndo` 0): actúan sobre lo que ya hagan los labios, sea el
reposo sellado o un visema. Los máximos son orientativos para el habla. Nada se mantiene al 100 %,
salvo el cierre de PP, brevemente.

### Visemas "solo labios" (`lipOnly`)

A peso 1.0, ningún visema baja el mentón, los dientes inferiores ni la lengua: dientes 0.00 mm y
mentón ≤ 2 mm, frente a 18.7 mm del antiguo `viseme_aa`. **La mandíbula la pone solo `jawOpen`**, que
maneja el runtime.

Método, en `face.py`, `lip_only_setup` + `process_keys`:
1. Para cada visema (después de su receta de refuerzo) se ajusta por mínimos cuadrados cuánto de
   `jawOpen` lleva: el coeficiente `k` se calcula sobre los dientes inferiores, la única parte rígida que
   solo mueve la mandíbula.
2. Se resta `k · jawOpen` en todo. En los visemas de labios en contacto (PP) se resta la mandíbula
   *sellada*, `k · (jawOpen + mouthClose)`, para que sigan sellados sin mandíbula.
3. Limpieza: los dientes quedan en 0. La lengua solo lleva la pose procedural que le toque. En la piel,
   el residuo se desvanece donde se mueve la mandíbula lejos de los labios (7–20 mm), así que los labios
   conservan su forma exacta.

`sourceJawOpen` (en el JSON) es la mandíbula que llevaba el visema MPFB original, en unidades de
`jawOpen`:

| Visema | sourceJawOpen |
|---|---|
| aa | 0.86 |
| E | 0.14 |
| O | 0.19 |
| PP | 0.20 |
| TH | 0.08 |
| kk | 0.08 |
| DD, nn | 0.04 |
| el resto | 0 |

`viseme_X(1) + jawOpen(sourceJawOpen) + la corrección de abajo` reproduce el visema original: labios
exactos; mentón y dientes con 2–4 mm de diferencia por la limpieza. Es referencia, no obligación: el
runtime elige la mandíbula por fonema y por énfasis.

### Lengua y dientes

- **Lengua en reposo:** baja 5 mm. La de MPFB flotaba unos 15 mm sobre el suelo de la boca, con el dorso
  por encima del borde de los incisivos inferiores, y con cualquier apertura se veía una losa rosa. Solo
  cambia la posición de la primitiva Tongue; con los labios cerrados no se ve.
- **Poses procedurales**, solo en la lengua y en el marco de la mandíbula cerrada (jawOpen la arrastra
  consigo):
  - TH: punta adelante, entre los incisivos, estrecha.
  - DD y nn: punta al alveolo.
  - RR: la misma de DD/nn ×0.7.
- **TH con jawOpen < 0.12:** la punta atraviesa los dientes cerrados. Detrás de los labios casi no se ve,
  pero el runtime debe garantizar jaw ≥ 0.12 durante TH.
- **Dientes:** solo se mueven con `jawOpen`.
- **Materiales:** los del interior (Masha_MouthInterior, Teeth, Tongue) y el orden de las primitivas no
  cambian, así que HoloShader asigna igual.

### Regla de corrección del cierre en reposo (runtime)

La base tiene los labios cerrados: `C = 0.15 × mouthClose en crudo`, unos 5 mm en los labios. Las formas
**absolutas** deshacen `C` una vez a peso 1.0:

| Forma | restUndo |
|---|---|
| visemas | 1 |
| mouthPucker | 1 |
| jawOpen | 0.6 |
| todas las demás | 0 |

Si la suma ponderada `U = Σ restUndo_i · w_i` pasa de 1, se abrirían los labios `(U − 1)` veces de más.
La corrección es exacta porque todo es lineal:

    U = Σ visemas + mouthPucker + 0.6 · jawOpen
    si U > 1:  mouthClose += 0.2174 · (U − 1)        // 0.2174 = 0.15 / 0.69

Si la mezcla de visemas está normalizada (Σ ≤ 1) y no hay mandíbula, U ≤ 1 y no hace falta nada. Con
mandíbula, sí.

### Regla de sellado (PP / FF)

Los visemas de contacto están construidos sellados con jawOpen 0. Si hay mandíbula abierta mientras
están activos:

    mouthClose += jawOpen · (1.0 · w_PP + 0.6 · w_FF)

`mouthClose == jawOpen` cierra los labios sobre la mandíbula. Aun así, lo natural es poca mandíbula en
estos visemas: PP ≤ 0.2 y FF ≤ 0.15. FF con jawOpen 0.6 ya no sella.

**Orden final:** `mouthClose = autor + sellado + corrección de reposo`, recortado a [0, 1].

### Coste

- De 31 a 47 targets.
- GLB alta: 6.64 → 7.97 MB (+1.32 MB). Ligera: 6.41 → 7.73 MB.
- Morphs en GPU: unas 24 B × 7568 vértices × 47 ≈ 8.5 MB (antes 5.6 MB).
- El shader de Filament salta los targets con peso 0, pero cada target con peso ≠ 0 cuesta en GPU
  de gama media (ver "Mezcla a morphs": como mucho 3 visemas activos).
- **Formato:** Filament 1.56 no deforma la malla con accesores *sparse* sin `bufferView` (lo que
  escribe Blender con `export_try_sparse_sk`): los pesos solo cambiaban un poco la luz y la cara no se
  movía en el dispositivo. `export.py` pasa cada GLB por `tools/masha_v2/densify_morphs.py` (modo
  `shared`: siguen siendo *sparse*, con una base de ceros compartida; el contenido es idéntico y el GLB
  apenas crece, 7,97 → 8,07 MB). Para un GLB ya exportado:
  `python tools/masha_v2/densify_morphs.py in.glb out.glb shared`.

### Regenerar y revisar

Pipeline completo: ver `docs/MASHA.md` ("Modelo v2"). Los parámetros están en `face.py`:

| Parámetro | Qué controla |
|---|---|
| `VISEME_RECIPES` | Refuerzos de O/U/PP/FF |
| `TONGUE_POSES`, `VISEME_TONGUE`, `TONGUE_REST_OFFSET` | Lengua |
| `LIP_KEEP_DIST` | Limpieza de la zona de la mandíbula |
| `GAINS` | Ganancias |
| `MORPH_META`, `SEAL` | Metadatos del contrato |

Hojas de revisión (vistas frontal, 3/4, lateral y corte sagital; las combinaciones ya aplican las reglas
del runtime):

    blender -b -P tools/masha_v2/face_review/render.py -- <salida> vis,visjaw,range,units,jaw,seq app/src/main/assets/masha/masha.glb

Diferencias semánticas entre dos GLB (el cuerpo, el esqueleto y los clips deben salir idénticos):

    <python de Blender> tools/masha_v2/face_review/glbdiff.py viejo.glb nuevo.glb

Licencias: visemes02 y faceunits01 de MPFB son CC0. Las poses de lengua y las recetas son propias.

## Runtime

_Sección del runtime de la app (`app/.../ui/masha/`). El contrato de arriba es del pipeline del modelo._

### Resumen

Masha habla con la voz del sistema (`TextToSpeech`, normalmente Google TTS local), pero **ya no
deja que el motor reproduzca**: cada frase se sintetiza con `synthesizeToFile` y su PCM, que llega
a trozos por `onAudioAvailable`, se escribe al momento en un `AudioTrack` propio. Con ese mismo
PCM, el texto (fonemas) y los rangos de palabra del motor (`onRangeStart`, con su trama de audio)
se construyen unas curvas de 100 Hz por frase (visemas coarticulados, mandíbula, nivel de voz,
cejas, cabeceos, parpadeos). El render las lee en la posición que **se oye** (reloj de
`AudioTrack.getTimestamp`, con la latencia de la salida) más un adelanto visual de 60 ms.

```
 LLM (streaming) ─► MashaVoice.feed ─► frases terminadas ─► synthesizeToFile (cola del motor)
                                                            │
       ┌──────────── onBeginSynthesis / onAudioAvailable (PCM) / onRangeStart(frame) / onDone ─┐
       ▼                                                                                        ▼
 SpeechOutput (hilo "masha-voice-out")                         hilo "masha-lipsync" (Utterance)
   PCM → AudioTrack (MODE_STREAM, USAGE_ASSISTANT)              texto → Tokenizer → G2P (es/en/pt/fr/de)
   empieza con ≥ lookaheadMs de audio (200 ms)                  PCM → AudioFeatures (10 ms: dB, HF, ZCR, F1/F2)
   frases seguidas en la misma pista                            Aligner (rangos + DP sobre el audio)
   reloj: getTimestamp + extrapolación                          Coarticulator (dominancia + reglas duras)
       │                                                        → Track (curvas 100 Hz), se republica
       ▼                                                            al llegar más audio o rangos
 LipSync.sample(vsync, dt)  ◄──────────── LipSync.Item.track ◄──────┘
   posición oída + 60 ms → curvas → filtro de 1er orden (dt)
       │
       ▼  HoloRig.frame:  clips → fundidos → capas aditivas (+ cabeceo del habla) → mirada → pies
                           → cara (expresión + labios: FaceMorphs) → muelles → updateBoneMatrices
```

| Archivo | Qué hace |
|---|---|
| `ui/masha/MashaVoice.kt` | TTS, frases (`feed`/`lastSentenceEnd`/`speakable` igual que antes), `synthesizeToFile`, hilo de análisis, estado "hablando", registro del tiempo hasta el primer sonido |
| `ui/masha/SpeechOutput.kt` | `AudioTrack` propio, escritura sin bloquear, arranque con margen, reloj de reproducción |
| `ui/masha/LipSync.kt` | lado del render: elige la frase que suena, muestrea sus curvas, suavizado independiente de los fps. Sin objetos por fotograma |
| `ui/masha/FaceRig.kt` | `FaceMorphs`: canales → morphs del GLB (con y sin este contrato, ver abajo) |
| `ui/masha/lipsync/` | Kotlin puro, probado en la JVM: `Text` (tokens, números), `G2p` (español), `EnglishG2p` (+ `CmuDict`), `OtherG2p` (pt/fr/de), `AudioFeatures`, `Aligner`, `Coarticulation` (+ `AudioOnly`, `VowelProfile`, `Track`), `Utterance`, `LipSyncConfig` |
| `assets/lipsync/cmudict.txt` | CMUdict compacto (126 052 palabras, 2,0 MB; ≈ 0,76 MB comprimido en el APK) |
| `scripts/lipsync/build_cmudict.py` | regenera ese fichero desde `cmudict.dict` |

### Voz: arranque rápido y sin huecos

> Motores de voz (voz natural Supertonic 3 en el dispositivo + voz del sistema de repuesto), normalización del texto y licencias: [MASHA_VOICE.md](MASHA_VOICE.md). La voz natural va a 44,1 kHz y el análisis recibe una copia a 22,05 kHz.

- Cada frase terminada del streaming va al motor en cuanto aparece (como antes). El motor
  sintetiza en su cola: la frase N+1 se sintetiza mientras suena la N.
- `onAudioAvailable` entrega el PCM a trozos (normalmente mucho más rápido que el tiempo real).
  `SpeechOutput` los escribe al momento y **arranca la pista cuando hay `lookaheadMs` (200 ms)
  de audio** de la frase, o antes si la frase es más corta. Nunca espera al final de la respuesta
  ni al fichero.
- Todas las frases van a la misma pista, una detrás de otra: sin silencios añadidos.
- API ≥ 30: `synthesizeToFile(text, params, ParcelFileDescriptor, id)`; API 26–29: la variante con
  `File`. El fichero (en `cacheDir/masha_tts/`) solo existe porque la API lo pide; se borra al
  terminar. Si un motor no entrega PCM al sintetizar a fichero, se lee el WAV al acabar la frase
  (más lento, pero funciona).
- Si el motor rechaza `synthesizeToFile`, se habla con `speak()` como antes (el motor reproduce) y
  la boca sigue al reloj de pared desde `onStart` (repuesto).
- `stop()` corta de golpe: `tts.stop()`, se vacía la cola de audio y se libera la pista.
- **Medida** (logcat, etiqueta `MashaVoice`):
  `tiempo hasta el primer sonido: N ms (primer PCM a los M ms, modo=stream)` (desde que la primera
  frase va al motor hasta que su audio se presenta en la salida, según el reloj),
  `play masha-K: N ms desde la frase`, y por frase
  `masha-K: P palabras, rangos=R, modo=texto+rangos|texto sin rangos|solo audio, D ms`.

### Reloj

`SpeechOutput.position(now)`: `AudioTrack.getTimestamp` (trama presentada en la salida y su
`nanoTime`, consultado cada 100 ms) extrapolado al instante del fotograma, acotado a lo escrito.
Hasta que hay marca válida: `playbackHeadPosition − fallbackLatencyMs`. Más `audioOffsetMs` (ajuste
manual, p. ej. para auriculares Bluetooth que no declaran bien su latencia). Cada frase guarda la
trama de la pista donde empieza; el render calcula los segundos dentro de la frase.

### Texto → fonemas

`Tokenizer` da las palabras con sus rangos de caracteres (los mismos que `onRangeStart`) y la
puntuación que las sigue. Números: `NumberWords` (es, en).

- **Español** (`SpanishG2p`): reglas completas. Seseo o distinción según el país de la voz
  (`es-ES` → /θ/ = TH, resto → /s/ = SS); `ll`/`y` = /ʝ/ (Río de la Plata: /ʃ/); **b = v siempre
  bilabial** (oclusiva al principio y tras m/n, aproximante [β] entre vocales, nunca FF); h muda; ch;
  qu/gu(e,i) con u muda; gü; x = /ks/ (/x/ en México, Oaxaca, Texas…); r/rr (labios de nn con un toque
  de RR, sin redondeo, como pide el contrato); n → [m] ante p/b/m y [ŋ] ante k/g/j; d y g débiles
  entre vocales; diptongos e hiatos; acento por tilde o por la regla llana/aguda, monosílabos átonos
  sin acento.
- **Inglés** (`EnglishG2p`): CMUdict (acento incluido; diptongos partidos en vocal + semivocal);
  fuera del diccionario, reglas de letra a sonido. El diccionario se carga en el hilo de análisis
  al elegir inglés (≈ 2 MB en memoria, búsqueda binaria sobre los bytes, sin mapa).
- **Portugués, francés, alemán** (`OtherG2p`): reglas sencillas centradas en lo que se ve (v
  labiodental, nasales sin cierre de labios, finales mudas del francés, r uvular, ü/ö redondeadas…).
- **Japonés** (y cualquier idioma sin G2P): modo solo audio.

**Añadir un idioma:** una clase que implemente `G2p` (o herede `RuleG2p` con `step` y
`stressedVowel`) usando los fonemas de `Ph` (y, si hace falta, uno nuevo con su visema), una línea en
`G2p.forLocale` y, si procede, números en `NumberWords`. Pruebas en `G2pTest`.

### Alineado (`Aligner`)

1. Ventana de cada palabra: de su `onRangeStart` (trama de audio de la frase) a la siguiente. Palabras
   sin rango entre dos con rango: repartidas por peso. Con la frase aún llegando, la última palabra
   solo ocupa su duración esperada (acotada al audio que ya hay).
2. Se recorta el silencio del final de la ventana (y hasta un 30 % del principio).
3. Dentro, programación dinámica monótona sobre tramas de 10 ms: vocales en picos de energía,
   oclusivas (P/B/M, T/D, K/G) en los valles, sibilantes donde hay alta frecuencia, nasales y
   líquidas en energía media, con un coste por alejarse de la duración propia de cada fonema.
4. Huecos ≥ 80 ms entre palabras → silencio (la boca descansa); los menores se absorben.

**Repuesto A (sin `onRangeStart`):** las palabras se reparten por peso sobre los tramos sonoros del
audio (huecos < 120 ms unidos); una palabra seguida de puntuación no cruza una pausa larga. Luego, el
mismo paso 3. Mientras la frase llega, el eje se estima con el ritmo medido en frases anteriores.

**Repuesto B (sin texto fiable: japonés, sin G2P):** `AudioOnly`. La vocal sale de los formantes
F1/F2 (LPC propio) comparados con el perfil de vocales de la voz (`VowelProfile`, que se afina solo
con las frases alineadas con texto; idea de uLipSync, MIT, implementada aquí); la mandíbula, de la
apertura de esa vocal × energía; S/CH de la alta frecuencia; cierres de labios en los valles cortos de
energía dentro del habla; suavizado de fase cero. **No es abrir y cerrar con el volumen.**

### Coarticulación (`Coarticulator`)

- Dominancia de Cohen–Massaro por canal, labios y mandíbula por separado: cada segmento pesa
  `α·exp(−θ·distancia)` (con meseta dentro del segmento); el valor es la media de los objetivos
  pesada por la dominancia. Los vecinos se mezclan, no hay saltos.
- **Anticipación de redondeo:** O/U (y la R inglesa) dominan antes (a `anticipationMs` = 170 ms su
  dominancia aún vale 1/3). Las consonantes de lengua (t, d, n, l, k, r del español) casi no pesan en
  los labios: toman la forma de la vocal de al lado.
- **Reglas duras**, después de mezclar: P/B/M (oclusivas y m) → cierre completo ≥ `closureMinMs`
  (PP 0,9, resto × 0,15, mandíbula a 0) con rampas de 30 ms; F/V → labio a los dientes (FF ≥ 0,7,
  mandíbula ≤ 10 % de `jawMax`); S/CH → mandíbula casi cerrada. La [β] española no fuerza el cierre.
- **Mandíbula separada de los labios:** objetivo = apertura del visema (aa 1, O 0,6, E 0,55, I 0,35,
  U 0,3…) × `jawMax` × JA, con JA según la energía de la vocal respecto a la de la frase (z) y el
  acento. LI (fuerza de labios) igual, y baja un 20 % si se habla rápido (> 6 sílabas/s).
- **Topes:** nada llega a 1,0 (vocales aa 0,7, O 0,65, E/U 0,6, I 0,55; consonantes 0,45–0,75; solo el
  cierre de P llega a 0,9, y brevemente).
- **Pausas y final:** silencios con su propia dominancia → la boca se relaja en las pausas y se cierra
  al acabar (la pista lleva 400 ms de cola).

### Expresión al hablar

En las mismas curvas: **cejas y cabeceo** (2,2°) en la tónica de palabras con contenido cuya energía
destaca (o que acaban en "!"), como mucho uno cada 1,4 s; **cabeza arriba (3°) y cejas** al final de
una pregunta; cabeceo pequeño al final de una afirmación; **parpadeo** en comas y puntos (p = 0,5,
determinista por frase); **sonrisa del ánimo** que cede un 65 % en O/U/P/F. El cabeceo es una capa
aditiva de huesos (cuello 40 %, cabeza 60 %) aplicada en `MashaAnimator` después de la mirada (para que
la mirada no lo compense) y antes de muelles y matrices. Los gestos del cuerpo (`Talk_*`) siguen ahora
el **nivel real del audio** (`LipSync.Frame.env`: dBFS −45…−10 → 0…1) en vez de la suposición por letras.

### Mezcla a morphs (`FaceMorphs.V2`)

Morphs por nombre; lo que falta se ignora o se sustituye.

- **GLB con este contrato** (hay `viseme_kk`/`nn`/`RR`/`TH`): visemas solo de labios, `jawOpen` aparte
  (≤ 0,6), sin pucker/funnel extra (ya van en O/U). Se aplican las dos reglas de arriba: cierre de
  reposo (`mouthClose += 0,2174·(U − 1)` si U > 1) y sellado (`mouthClose += jawOpen·(PP + 0,6·FF)`).
  Límites de mandíbula en proporción al peso del visema: F/V ≤ 0,15, P/B/M ≤ 0,2, O ≥ 0,2 (si no,
  parece un beso) y TH ≥ 0,12 (el último, manda). Suma de visemas ≤ 1.
- **Coste de GPU:** como mucho `MAX_VISEMES` = 3 visemas activos a la vez (se resta el 4.º más fuerte y
  se reescala: continuo cuando se cruzan, sin saltos) y nada por debajo de `VISEME_FLOOR` = 0,03; en
  cualquier morph, nada por debajo de `MIN_WEIGHT` = 0,015. Medido en un Motorola edge 50 fusion
  (Snapdragon 7s Gen 2): con las colas de coarticulación activas (~14 morphs pequeños) hablar bajaba de
  ~53 a ~41 fps; con el límite, ~51 fps, igual que en reposo.
- **GLB anterior** (visemas con mandíbula horneada, sin los nuevos): `jawOpen` = mandíbula pedida −
  la que ya ponen los visemas (`sourceJawOpen`: aa 0,86, PP 0,20, O 0,19, E 0,14, DD 0,04) → sin doble
  mandíbula; kk/nn/RR/TH → DD (+ algo de FF en TH); suma de visemas ≤ 1 y de la boca ≤ 1,1 (sin
  `mouthClose` no se puede corregir el cierre de reposo de otro modo).
- Modelo v1 (`MouthOpen`, `V_AA`…): se mantiene la traducción antigua.

### Ajustes (`LipSyncConfig`)

| Ajuste | Por defecto | Qué hace |
|---|---|---|
| `visemeIntensity` | 1 | multiplica la forma de los labios (con los topes de arriba) |
| `jawAmount` | 1 | multiplica la apertura de la mandíbula |
| `jawMax` | 0,6 | `jawOpen` máximo al hablar (tope del contrato v2) |
| `smoothing` | 1 | velocidad del suavizado final (τ ≈ 18 ms labios, 11 ms cierre de P, 25 ms mandíbula, 40 ms cejas/cabeza) |
| `visualLeadMs` | 60 | la boca va por delante del audio |
| `audioOffsetMs` | 0 | latencia extra de la salida (calibración Bluetooth); puede ser negativa |
| `fallbackLatencyMs` | 60 | latencia supuesta hasta la primera marca válida de `getTimestamp` |
| `lookaheadMs` | 200 | audio acumulado antes de empezar a sonar |
| `anticipationMs` | 170 | anticipación del redondeo de O/U |
| `closureMinMs` | 60 | cierre mínimo de P/B/M |
| `fastSyllablesPerSecond` / `hypoArticulation` | 6 / 0,2 | por encima de ese ritmo, labios −20 % |
| `expressionIntensity` | 1 | cejas y cabeceos (0 = nada) |
| `nodDeg` / `browAccent` / `questionLiftDeg` | 2,2° / 0,32 / 3° | tamaño de los gestos |
| `punctuationBlink` | 0,5 | probabilidad de parpadeo en coma o punto |
| `smileRoundingCut` | 0,65 | cuánto cede la sonrisa en O/U/P/F |
| `accentGap` | 1,4 s | separación mínima entre acentos |

Para cambiarlos: `MashaPresence.lipSync = LipSync(LipSyncConfig(...))`. Las tablas por visema (topes,
apertura, dominancias) están en `Vis` (`lipsync/Phonemes.kt`).

### Rendimiento

En dispositivo (Motorola edge 50 fusion, Android 16, pantalla a 120 Hz): Masha en reposo ~53 fps y
hablando ~51 fps (ya estaba en ~53 en reposo antes del lip-sync); CPU del callback de fotograma
≈ 0,1 ms de lip-sync + ≈ 0,9 ms de cuerpo y cara (mediana). Primer sonido: 77–200 ms desde que llega
una frase completa (el motor se precalienta con una palabra a un fichero desechable al elegir idioma;
sin eso, la primera frase tras abrir la app tardaba ~1 s).

Render: `LipSync.sample` ≈ 0,4 µs por fotograma (JVM de escritorio), sin objetos. Análisis (hilo
propio): ≈ 5 ms por segundo de audio y ≈ 0,6 ms por reconstrucción de una frase de 3 s (máx. 25/s),
en escritorio; en un móvil medio, del orden de 5× más.

### Fluidez (fps) de la pantalla de Masha

Perfilado con Perfetto y A/B en un Motorola edge 50 fusion (pantalla de 120 Hz), 2026-09-27:

- **Cuello de botella:** la atmósfera 2D (`HoloAtmosphere`: motas, barrido, columnas) se redibujaba en cada
  vsync y obligaba a Android a recomponer toda la interfaz (~14 ms/fotograma en su RenderThread), que
  compite con Filament por la GPU. Sin ella: ~120 fps estables. SSAO, bloom, resolución, muelles, FXAA,
  niebla, viñeta y lip-sync apenas influían (no se ha quitado nada).
- **Arreglo:** la atmósfera avanza a `ATMOSPHERE_HZ` = 30 (64 → 119 fps en reposo, 59 → 119 hablando, a 120 Hz).
  Si se notan saltos, subirlo a 60 (medido: ~108 fps en reposo, ~91 hablando).
- **Límite a 60 fps:** en pantallas de 120 Hz, la pantalla de Masha y la de calibración piden 60 Hz
  (`SettingsController.frameRateFor` → `FrameRate.apply`), salvo que se active Ajustes → Pantalla →
  "Masha: alta fluidez (120 Hz)" (`masha.highRefresh`, apagado por defecto). Al salir vuelve el ajuste general.
- **Pendiente:** prueba de estabilidad de 10 minutos (fps y temperatura) con el límite a 60, y confirmar en
  el dispositivo que el modo de pantalla cambia a 60 Hz en la pantalla de Masha.

#### Pendiente (tarea aparte)

- **Partículas de la biblioteca** (`NeonParticles`, `MashaParticles`, `SettingsParticles`…): probablemente
  redibujan en cada vsync como hacía la atmósfera; medir y aplicar el mismo patrón (reloj a menos Hz).
- **Ambiente sonoro** (`AmbientSoundscape`, ExoPlayer): ~11 % de un núcleo de CPU decodificando sin parar
  (`doSomeWork` cada ~10 ms). No limita los fps de Masha, pero gasta batería.

### Pruebas

`app/src/test/.../ui/masha/lipsync/`: `G2pTest` (español, inglés con CMUdict y por reglas, pt/fr/de,
tokens), `LipSyncPipelineTest` (alineado con y sin rangos sobre voz sintética, cierre en P, topes,
anticipación de U, cierre final, solo audio, preguntas, streaming), y
`ui/masha/LipSyncRenderTest` (independencia de fps 30 vs 120, parpadeos, adelanto visual, mezcla a
morphs con los dos GLB y las reglas del contrato).

    ./gradlew :app:testDebugUnitTest --tests "com.elyndra.launcher.ui.masha.*"

### Licencias (runtime)

| Componente | Licencia | Notas |
|---|---|---|
| Voz | Supertonic 3 (OpenRAIL-M) sobre ONNX Runtime (MIT); repuesto: `TextToSpeech` del sistema | en el dispositivo, sin coste; detalle en [MASHA_VOICE.md](MASHA_VOICE.md) |
| CMUdict | BSD-2-Clause, © 1993-2015 Carnegie Mellon University | aviso completo en `assets/lipsync/CMUDICT_LICENSE.txt` (va dentro del APK); hay que reproducirlo también en los avisos de licencias de la app |
| Coarticulación | Cohen & Massaro (1993), modelo publicado | implementación propia |
| Solo audio | idea de uLipSync (MIT) | implementación propia (LPC/formantes), sin código copiado |
| JALI | patente US 10,839,825 | solo se usa la idea general de separar mandíbula y labios, no su procedimiento |
| GPL | — | nada de eSpeak NG, Phonemizer ni TarsosDSP |

### Límites conocidos

- Google TTS (voz local es-US, Motorola edge 50 fusion) **no** da `onRangeStart` al sintetizar a
  fichero (`rangos=0` en todas las pruebas): siempre funciona el repuesto A (palabras repartidas sobre
  el audio sonoro y ajustadas por energía). En la QA con altavoz y con Bluetooth la sincronía se vio bien.
- `getTimestamp` con Bluetooth: con unos realme Buds Air8 Pro la latencia llega bien (sin corrección
  manual). Para auriculares que la declaren mal: Ajustes → Acerca de → tocar 7 veces "Versión" →
  Desarrollador → "Calibrar sincronía de voz" (se guarda por salida, `AudioRouteOffsets`).
- Sin hueso de mandíbula (decisión del usuario): todo va por morphs.

### Herramientas de QA (solo builds debug)

Receptores de `adb` que no existen en release (`BuildConfig.DEBUG`):

    adb shell am broadcast -a com.elyndra.launcher.DEBUG_SAY --es text "'Hola. ¿Qué tal?'" --ei cps 60   # habla (cps = streaming simulado)
    adb shell am broadcast -a com.elyndra.launcher.DEBUG_STOP
    adb shell am broadcast -a com.elyndra.launcher.DEBUG_CAM --ef y 1.56 --ef z 0.9                  # primer plano de la cara
    adb shell am broadcast -a com.elyndra.launcher.DEBUG_POSE --es vis AA --ef w 0.6 --ef jaw 0.45    # fija la boca (sin extras: la suelta)
    adb shell am broadcast -a com.elyndra.launcher.DEBUG_POSE --es morph eyeBlinkLeft --ef w 1        # un morph crudo
    adb shell am broadcast -a com.elyndra.launcher.DEBUG_POSE --ez trace true                         # traza también sin hablar
    adb shell am broadcast -a com.elyndra.launcher.DEBUG_POSE --ez glitch true                        # glitch del holograma permanente
    adb shell am broadcast -a com.elyndra.launcher.DEBUG_PERF --ez atmo false --ez ssao false --ez bloom false --ef scale 0.75 --ei atmoHz 60 --ez springs false

Con Masha hablando (o `trace`), logcat `MashaFace` da por fotograma los pesos de boca y el tiempo de CPU
(`us_lip`, `us_body_face`); `MashaVoice` da el tiempo hasta el primer sonido y el modo de cada frase.
Con el fichero `cache/noholo` (`adb shell run-as com.elyndra.launcher touch cache/noholo`) el escenario
arranca sin HoloShader (materiales de gltfio), para aislar problemas del shader.

