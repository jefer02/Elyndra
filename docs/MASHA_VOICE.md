# Masha: voz

Masha habla con una **voz natural generada en el propio dispositivo** (Supertonic 3, ONNX Runtime) y,
cuando no se puede, con la **voz del sistema** (Google TTS, Samsung…). Todo es gratis, sin red al hablar
y sin GPL. La sincronía de labios ([MASHA_LIPSYNC.md](MASHA_LIPSYNC.md)) no cambia: recibe el PCM de
cualquiera de las dos voces igual que antes.

## Arquitectura

```
MashaScreen ──feed(texto, final)──▶ MashaVoice ──▶ VoiceRouter ──▶ NeuralVoiceEngine (Supertonic 3)
   (LLM, respuestas                  │  frases,        │  elige por frase;   └▶ SystemTtsEngine (repuesto)
    offline, saludo)                 │  normalizador   │  si la neuronal falla
                                     │                 │  sin audio, repite con la del sistema
                                     ▼                 ▼
                           Utterance (labios)   SynthesisCallback: onBegin/onAudio/onEnd
                                     ▲                 │
                                     └── copia 22 kHz ─┤
                                                       ▼
                                                 SpeechOutput (AudioTrack propio, 44,1 kHz)
```

| Pieza | Fichero | Qué hace |
|---|---|---|
| Contrato | `ui/masha/voice/VoiceEngine.kt` | `VoiceEngine`, `VoiceInfo`, `SynthesisRequest`, `SynthesisCallback`. Mismo orden de avisos que `UtteranceProgressListener`: onBegin → onAudio* → onEnd, FIFO entre frases |
| Enrutador | `voice/VoiceRouter.kt` | Voz natural si está activada, instalada, el móvil puede y el idioma está; si no, sistema. Fallo sin audio → la misma frase por la voz del sistema. Tras `stop()` no repite nada |
| Voz natural | `voice/NeuralVoiceEngine.kt` | Hilo propio, cola FIFO, parte frases largas por signos (primera parte corta y cada parte ≤ ~1/RTF veces la anterior: sin atascos), títulos en inglés con la misma voz (`LanguageSpans`), recorte de silencio, pausa según la puntuación, sonoridad, PCM16 a 44,1 kHz, RTF por frase en el log |
| Modelo en memoria | `voice/NeuralRuntime.kt` | Un modelo por proceso; se libera 90 s después de dejar de usarse o con `onTrimMemory`. Decide si el móvil puede: proceso de 64 bits, ≥ 2,5 GiB (móviles de "3 GB") y velocidad medida (`RtfGate`) |
| Inferencia | `voice/supertonic/SupertonicModel.kt`, `SupertonicText.kt` | Supertonic 3 int8 sobre `onnxruntime-android` (sin sherpa ni espeak). Texto → ids Unicode (NFKD, etiquetas `<es>…</es>`) → duración → codificador → 5 pasos de flow matching → vocoder |
| Voz del sistema | `voice/SystemTtsEngine.kt` | El código anterior de `MashaVoice` (synthesizeToFile, lectura del WAV, `speak()` de repuesto), con tono 1.0 y voces femeninas por nombre |
| Descarga | `voice/VoicePack.kt` | 8 ficheros, 145 MB, revisión fija de Hugging Face, SHA-256 por fichero, reanudable, instalación atómica |
| Texto | `voice/SpeechNormalizer.kt`, `voice/LanguageSpans.kt` | Números, fechas, horas, %, monedas, unidades, versiones, siglas, romanos → palabras (6 idiomas). Tramos en inglés dentro de frases es/pt/fr/de |
| PCM | `voice/Pcm.kt` | Recorte de silencio, sonoridad (-16 dBFS activo, pico ≤ -1 dBFS), PCM16, diezmador 2:1 para el análisis |
| Prueba en Ajustes | `voice/VoicePreview.kt` | Frase de muestra con la voz y velocidad elegidas |

### Cómo sigue funcionando la sincronía de labios

- **Mismo texto:** el normalizador se aplica **antes** de crear la `Utterance`, así la voz y los labios
  leen la misma cadena ("veintitrés por ciento", no "23 %"): mejor G2P y mejor reparto de palabras.
- **Sin rangos de palabra**, como Google (que nunca los dio en `synthesizeToFile`): modo "texto sin rangos".
- **Frecuencia:** la voz suena a 44,1 kHz, pero el análisis (LPC de orden 12, rejilla de formantes,
  umbral de agudos) está afinado a ~24 kHz. `MashaVoice` le pasa una copia diezmada a 22,05 kHz
  (`HalfbandDecimator`). Prueba: `LipSyncAtNeuralRateTest` (fonemas en el mismo sitio ±20 ms, misma energía).
- **Sonoridad:** el canal de energía (gestos, brillo) y la puerta de formantes van en dBFS absolutos;
  la voz natural se lleva a -16 dBFS activos, el nivel de la voz de Google.
- **Silencios:** Supertonic deja ~0,4–0,7 s antes y después de hablar. Se recorta a 30 ms delante y, detrás,
  a la pausa natural según la puntuación: 300 ms tras `. ! ? …`, 150 ms tras coma, 110 ms si no hay signo.
  El primer sonido no se retrasa y la boca tiene tiempo de cerrarse entre frases (≥ 80 ms la cierra).
- **Cambio de voz:** si cambia `VoiceInfo.voiceId` (otra voz o motor), se olvida lo aprendido
  (`VowelProfile`, ritmo).
- **Anticipación y pausas:** sin cambios (`LipSyncConfig`); la voz natural va a un ritmo similar
  (el ritmo se aprende por frase).

### Frases y latencia

- Mientras llega la respuesta del LLM, cada frase terminada se sintetiza en cuanto aparece; la siguiente
  se prepara mientras suena la actual.
- **Cambio:** los mensajes que llegan enteros (saludo, respuestas sin conexión, el final de la respuesta)
  también se parten por frases (`MashaVoice.sentences`), para que la primera suene antes.
- **Cambio:** las frases que llegan antes de que haya motor ya no se pierden: esperan (hasta 12).
- **Partes dentro de una frase** (`NeuralVoiceEngine.parts`): solo por signos (`, ; : —` y `. ! ? …`
  interiores); mínimo 20 caracteres tras una coma, 8 tras `. ! ?` ("¡Claro que sí!"), la última ≥ 25;
  primera parte ≤ 70. Cada parte puede medir como mucho `0,7 / RTF` veces la anterior (entre 1,6 y 3;
  2,2 sin medida): así se sintetiza mientras suena la anterior sin huecos. Si no hay un corte así, la frase
  va entera (mejor tardar un poco más que cortarse a mitad).
- El modelo se carga y se "calienta" al elegir idioma (al entrar en la pantalla de Masha); si ya espera una
  frase de verdad, el calentamiento se salta (ella misma lo hace).

### Ánimo y prosodia

Supertonic no tiene control de tono ni estilo. Se usa lo que sí tiene:

- **Ritmo por ánimo:** Playful ×1,04, Warm/Thinking ×0,97, Concerned ×0,95 (sobre la velocidad elegida).
  En respuestas del LLM el ánimo se conoce al final, así que solo afecta a las frases siguientes.
- **Preguntas y exclamaciones:** el modelo las entona solo por la puntuación (se conserva `¿?¡!`).
- **Pausas:** las da la puntuación; frases cortas se juntan con la siguiente (más natural).
- **Etiquetas expresivas** (`<laugh>`, `<breath>`, `<sigh>`): existen, pero en español a veces se leen en
  voz alta. **No se usan.**

## Voces e idiomas

Una sola voz femenina para los seis idiomas (Masha suena igual en todos): Supertonic 3, hablante 0 (F1),
elegida a ciegas. En Ajustes se puede cambiar entre las 5 voces femeninas (1–5 = sid 0–4) y la velocidad
(80–125 %).

| Idioma | Voz natural | Normalizador | Títulos en inglés | Notas |
|---|---|---|---|---|
| es | ✅ (es-MX para el G2P de labios: seseo) | completo (regiones ES/MX/AR…) | tramos en inglés | prioridad |
| en | ✅ | completo (US/GB) | — | |
| pt | ✅ (pt-BR) | números, fechas, horas, unidades | tramos en inglés | "Masha" → "Macha" para la voz |
| fr | ✅ | números, fechas, horas, unidades | tramos en inglés | "Masha" se escribe "Macha" para la voz (se oía "Machin") |
| de | ✅ | números, fechas, horas, unidades | tramos en inglés (conservador) | "Masha" → "Mascha" para la voz |
| ja | ✅ | numerales en kanji | — | labios solo por audio (como antes) |

Calidad medida (Whisper, escritorio) en [QA](#qa).

**Región del normalizador:** el país del primer idioma **del sistema** que coincide con el de la app
("3,5" → "tres coma cinco" en España, "tres punto cinco" en México/EE. UU.); si no hay, la forma neutra.
(El idioma elegido dentro de la app no lleva país.)

### ¿Puede este móvil? (`RtfGate`)

Cada frase de ≥ 1 s registra su factor de tiempo real (síntesis / audio). Para que un pico puntual (otra app,
la recreación de la actividad) no apague la voz:

- las 2 primeras frases tras cargar el modelo no cuentan;
- cada sesión (una carga del modelo) se resume con el percentil 30 de sus frases (mínimo 3);
- se guardan las 3 últimas sesiones; "demasiado lento" = al menos 2 sesiones y mediana > 0,8;
- la medida caduca a los 14 días y con cada versión de la app; en Ajustes, "Reintentar" la borra.

### Voz del sistema (repuesto)

- `setPitch(1.0)`: con otro tono (antes 1.06) Google deja su voz neuronal ("seanet") por la antigua ("lstm").
- La API no dice el género y todas las voces de Google declaran igual calidad: se prefieren por nombre
  (`SystemTtsEngine.FEMALE`). es/en comprobadas en el emulador por su tono medio; pt/fr/de/ja **pendientes
  de confirmar en un móvil real**.

## Cómo añadir o cambiar voces

- **Otra de las 5 voces:** Ajustes → Masha → Voz (o `SettingsStore.mashaVoiceSpeaker`).
- **Otro modelo Supertonic** (p. ej. una versión nueva): cambiar `VoicePack.BASE` (revisión fija) y la
  lista `FILES` (tamaño y SHA-256 de cada fichero: `sha256sum *`), y `VoicePack.NAME` para que no se mezcle
  con la carpeta anterior. Pasar `SupertonicModelTest` con `MASHA_SUPERTONIC_DIR=<carpeta>`.
- **Otro motor:** implementar `VoiceEngine` (PCM mono 16 bits o float, frecuencia fija por voz, tramas
  enteras, FIFO, `stop()` inmediato) y enchufarlo en `VoiceRouter`. Si el motor da > 32 kHz, `MashaVoice`
  ya diezma la copia del análisis. Mantener la sonoridad en ~-16 dBFS y el silencio de cabeza ≤ 50 ms.
- **Réplica propia del modelo:** subir los 8 ficheros a cualquier alojamiento gratuito (Hugging Face,
  GitHub Releases) y cambiar `BASE`; los hashes garantizan que son los mismos ficheros. Recomendado: el
  repositorio de Supertonic está archivado.
- **Play Asset Delivery** (on-demand) sería la alternativa a la descarga propia si la app se publica
  en Google Play como AAB; hoy se descarga con OkHttp.

## Licencias

| Componente | Licencia | Uso comercial | Notas |
|---|---|---|---|
| ONNX Runtime (`onnxruntime-android` 1.28.0) | MIT | ✅ | **Fijado en 1.28.0**: desde 1.29 el AAR añade un `TelemetryInitializer` que arranca con la app (comprobado en el manifiesto fusionado). Solo se empaqueta el de 64 bits (arm64-v8a, x86_64) |
| Código de inferencia portado | Apache-2.0 (sherpa-onnx, © zengyw/Xiaomi) + MIT (Supertone `helper.py`) | ✅ | Atribución en el KDoc de `SupertonicModel`/`SupertonicText` |
| Pesos de Supertonic 3 | OpenRAIL-M | ✅ con restricciones de uso | Hay que trasladar sus restricciones de uso (Anexo A) a los términos de la app y avisar de que la voz es sintética (hecho en Ajustes: `settings_masha_voice_notice`). Restricciones trasladadas a los términos (2026-10-08): punto 5 de `LICENSE`, `THIRD_PARTY_NOTICES.md` y Ajustes → Acerca de → Licencias (`licenses_voice_notice`, con enlace a la licencia) |
| Datos de entrenamiento de Supertonic | no publicados | ⚠️ desconocido | Riesgo bajo-medio: los publica el propio titular (Supertone) bajo OpenRAIL-M |
| CMUdict (detección de inglés) | BSD-2 | ✅ | Ya estaba en assets |
| Listas de palabras de `LanguageSpans` | propias | ✅ | Hechas a mano, sin fuente con licencia |
| espeak-ng / sherpa-onnx AAR / Piper | GPL-3 / — | ❌ | **No se usan.** El AAR de sherpa-onnx enlaza espeak-ng estáticamente |

Descartados: Piper (casi todas las voces femeninas derivan de datasets no comerciales, y usa espeak),
Kokoro (espeak para no-inglés; datos con audio sintético de TTS comerciales), clones de voces reales.

## QA

> Emulador x86_64 en un PC de escritorio: **ningún tiempo de aquí vale para un móvil**. Ver "Pendiente en
> un móvil real".

Emulador Elyndra_API_35 (x86_64, 4 núcleos, arrancado con `-memory 4096`), Supertonic hablante 0, 5 pasos,
2 hilos de inferencia. Inteligibilidad con faster-whisper large-v3-turbo sobre el PCM volcado. Audios para
escuchar en `Documents\Masha_Voice\qa\` (fuera del repo).

| Medida | Voz natural | Voz del sistema (Google seanet) |
|---|---|---|
| Tiempo hasta el primer sonido, en caliente, frase corta | 450–600 ms | 85–125 ms |
| Ídem, frase media / con títulos en inglés | 490–875 ms / ~850 ms | 100–160 ms |
| Ídem, en frío (la frase llega mientras carga el modelo) | 3,1–3,5 s (4,4 s el primer arranque tras encender) | ~620 ms |
| Carga del modelo | 0,9–2,1 s | — |
| RTF (síntesis/audio), mediana en caliente | ≈ 0,30 (0,20–0,50; > 1 con el sistema cargado) | — |
| Atascos (huecos audibles) en respuestas es/en/pt/fr/de/ja | 0 (tras los arreglos) | 0 |
| Silencio de cabeza / entre frases | 30 ms / ≈ 330 ms (≈ 180 ms en comas) | 20 ms / ≈ 700 ms |
| Sonoridad activa / pico | -16,0 dBFS / ≤ -1 dBFS, 0 muestras saturadas | -18,0 dBFS / ≤ -2 dBFS |
| WER es/en (saludo, preguntas, explicación, respuesta larga) | ≈ 0 (errores sueltos de una palabra) | 0 |
| Números ("12 horas y 45 minutos… 23 %… 2.1.4… 3,5 GB") | correctos (tras normalizar) | correctos |
| Títulos en inglés dentro del español | correctos ("Hollow Knight", "The Legend of Zelda…", "Call of Duty") | correctos |
| Labios: modo | "texto sin rangos" (es/en/pt/fr/de) | igual |
| Labios: desfase mandíbula ↔ envolvente del audio | mediana -10 ms (correlación 0,49) | -7 ms (0,49) |
| Parar a media frase | corta al momento, sin audio posterior | igual |
| Memoria (PSS) con el modelo cargado / hablando | +~215 MB de heap nativo (460–500 MB la app) | — |
| Memoria 105 s después de salir de Masha | liberada (192 MB la app) | — |
| CPU mientras habla | ~0,6 s de CPU por segundo de voz (RTF × 2 hilos) | — |
| Gama baja (emulador con 2 GB) | no se usa: habla la voz del sistema; Ajustes lo explica | ✅ |

**Tamaño del APK** (debug universal): 143,3 MB, de ellos ONNX Runtime 63,5 MB (arm64-v8a 28,6 MB +
x86_64 34,6 MB, sin comprimir). Base sin la voz ≈ 79,8 MB. En un móvil arm64 instalado desde Google Play
(AAB, una sola ABI) la voz añade **≈ 28,7 MB**; el modelo (145 MB) solo si el usuario lo descarga.

**Errores encontrados por la QA y corregidos:** la medida de velocidad apagaba la voz para siempre tras un
pico de carga (ahora `RtfGate`); huecos a mitad de frase por partes desequilibradas; el calentamiento
retrasaba la primera frase real; pausas entre frases demasiado cortas (140 ms) con la boca sin cerrar;
"Masha" en francés; región del normalizador; ONNX Runtime de 32 bits en el APK (-72 MB); estados de Ajustes;
Google TTS perdía PCM cuando se le encolaban varias frases ya en caché (ahora una síntesis a la vez).

## Pendiente en un móvil real

- RTF de Supertonic (5 pasos) en gama media (Snapdragon 7-series / Helio G99) y baja: debe quedar ≤ 0,5
  para no tener atascos; la app mide y pasa a la voz del sistema por encima de 0,8.
- Tiempo hasta el primer sonido (en frío y en caliente) frente a la voz del sistema.
- Memoria (PSS) con el modelo cargado, temperatura y batería en una conversación de 10 minutos.
- Voces femeninas de Google en pt/fr/de/ja (`SystemTtsEngine.FEMALE`).
- Sincronía de labios a ojo con la voz natural, altavoz y Bluetooth (los ajustes por salida no cambian).
- Descarga del modelo con datos móviles / Wi-Fi lenta y reanudación.

## Herramientas de QA (solo debug)

- **Volcado del PCM:** `adb shell run-as com.elyndra.launcher touch cache/voice_dump.on` → cada frase en
  `cache/voice_dump/<frase>_<motor>_<frecuencia>.pcm` (16 bits mono). Android no deja grabar la voz
  (USAGE_ASSISTANT).
- **Modelo sin descargar:** `adb push` de los 8 ficheros a
  `/sdcard/Android/data/com.elyndra.launcher/files/voices/supertonic-3-int8/`.
- **Logs** (`adb logcat -s MashaVoice`): `say masha-N [motor]: "texto normalizado"`, `primer audio neuronal a
  los X ms`, `synth masha-N: audio=…, síntesis=…, RTF=…`, `tiempo hasta el primer sonido … motor=…`,
  `pista en pausa: … underruns=N` (cada atasco es un hueco audible), `voz natural cargada en … ms`.
- **Emulador con memoria suficiente:** `emulator -avd Elyndra_API_35 -memory 4096` (con 2 GB, el perfil de
  gama baja, se usa la voz del sistema).
- **Pruebas JVM:** `gradlew :app:testDebugUnitTest --tests "com.elyndra.launcher.ui.masha.voice.*"`;
  con el modelo: `MASHA_SUPERTONIC_DIR=<carpeta>` (y `MASHA_SUPERTONIC_BENCH=1` para el benchmark).
