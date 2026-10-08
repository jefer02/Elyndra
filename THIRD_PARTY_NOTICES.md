# Third-party notices

Elyndra itself is © 2026 Jeferson Manuel Morillo Vallejo, all rights reserved
(see [LICENSE](LICENSE)). It is built on the third-party components below,
which keep their own licenses. The same list is shown in the app under
**Settings → About → Open-source licenses**.

Versions are the ones pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml)
and [`app/build.gradle.kts`](app/build.gradle.kts).

## Libraries bundled in the APK

| Component | Version | Copyright | License |
|---|---|---|---|
| AndroidX Core, Activity, Lifecycle, SplashScreen | 1.15.0 / 1.9.3 / 2.8.7 / 1.0.1 | The Android Open Source Project | Apache-2.0 |
| AndroidX Room, WorkManager, Glance, Hilt-Work | 2.6.1 / 2.10.1 / 1.1.1 / 1.2.0 | The Android Open Source Project | Apache-2.0 |
| Jetpack Compose (UI, Foundation, Material 3, Google Fonts) | BOM 2024.12.01 | The Android Open Source Project | Apache-2.0 |
| AndroidX Media3 (ExoPlayer, UI) | 1.4.1 | The Android Open Source Project | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | 2.0.21 / (transitive) / 1.7.3 | JetBrains s.r.o. and contributors | Apache-2.0 |
| Dagger / Hilt | 2.52 | Google LLC | Apache-2.0 |
| OkHttp, Okio | 4.12.0 | Square, Inc. | Apache-2.0 |
| Coil | 2.7.0 | Coil Contributors | Apache-2.0 |
| SceneView | 2.3.0 | SceneView contributors | Apache-2.0 |
| Filament (via SceneView) | 1.56 | Google LLC | Apache-2.0 |
| ONNX Runtime (`onnxruntime-android`) | 1.28.0 | Microsoft Corporation | MIT |
| ML Kit Translation | 17.0.3 | Google LLC | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) |

The Apache License 2.0 is at <https://www.apache.org/licenses/LICENSE-2.0>.

## Code ported into Elyndra

| Source | Used in | Copyright | License |
|---|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) `offline-tts-supertonic-impl.cc`, `offline-tts-supertonic-unicode-processor.cc` | `SupertonicModel.kt`, `SupertonicText.kt` | © 2026 zengyw (sherpa-onnx, Xiaomi Corporation) | Apache-2.0 |
| [Supertonic](https://github.com/supertone-inc/supertonic) `py/helper.py` | `SupertonicModel.kt`, `SupertonicText.kt` | © 2025 Supertone Inc. | MIT |

## Data

| Data | Used in | Copyright | License |
|---|---|---|---|
| [ES-DE](https://gitlab.com/es-de/emulationstation-de) Android configuration (`es_find_rules.xml`, `es_systems.xml`): emulator intents, extensions and system names | `data/Emulators.kt`, `data/Systems.kt` | © 2024-2026 Northwestern Software AB, © 2020-2024 Leon Styhre, © 2014 Alec Lofquist | MIT |
| [CMUdict](https://github.com/cmusphinx/cmudict), compacted | `assets/lipsync/cmudict.txt` (lip-sync for English) | © 1993-2015 Carnegie Mellon University | BSD-2-Clause, full notice in [`app/src/main/assets/lipsync/CMUDICT_LICENSE.txt`](app/src/main/assets/lipsync/CMUDICT_LICENSE.txt) |

## Fonts

| Font | Copyright | License |
|---|---|---|
| Poppins (`res/font/poppins_*.ttf`) | © 2020 The Poppins Project Authors (Indian Type Foundry) | SIL Open Font License 1.1, see [`POPPINS-OFL.txt`](POPPINS-OFL.txt) |
| Cinzel Bold, Latin subset (`res/font/cinzel_bold.ttf`) | © 2020 The Cinzel Project Authors | SIL Open Font License 1.1, see [`CINZEL-OFL.txt`](CINZEL-OFL.txt) |

## 3D assets

Masha's 3D model (`assets/masha/*.glb` and textures) is generated with
[MPFB2](https://github.com/makehumancommunity/mpfb2) in Blender from the
MakeHuman system assets, `faceunits01`, `visemes02` and `hair01`, all
**CC0 1.0**. MPFB2 and Blender are GPL tools; their license does not cover
what they produce, and neither is distributed with Elyndra. The animations,
the control-room environment (`room.hdr`), the holographic materials, the UI
sounds and music, and Masha's ambient loop are made for Elyndra by the scripts
in [`tools/`](tools/).

## Machine-learning model downloaded at runtime

| Model | Copyright | License |
|---|---|---|
| Supertonic 3 voice weights (int8 export by sherpa-onnx), downloaded on demand from Hugging Face; not inside the APK | © Supertone Inc. | [BigScience OpenRAIL-M](https://huggingface.co/Supertone/supertonic-3/blob/main/LICENSE) |

OpenRAIL-M carries use-based restrictions (Attachment A of the license). By
using Masha's natural voice you agree not to use it, among others, to break
the law, harm minors, impersonate people without their consent, spread false
information to harm others, harass others, or present the generated speech
without disclosing that it is machine-generated. The voice is synthetic
(AI-generated), which the app also states in Settings → Masha.

## Online services

Elyndra can talk to these services. Their content (cover art, descriptions,
achievements, AI replies) belongs to their owners and is not part of the app:
ScreenScraper, IGDB (Twitch), SteamGridDB, RetroAchievements, the libretro
thumbnails server, Google Play and Steam store pages (descriptions for Android
and PC games), DeepSeek (Masha's online AI) and the GitHub API (update
checks).
