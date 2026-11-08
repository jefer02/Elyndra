# Reglas de R8 para la release (minify + recorte de recursos).
#
# La app no usa reflexión propia (ni Class.forName ni JNI propio). Casi todas
# las bibliotecas traen sus reglas en el AAR/JAR y R8 las aplica solas:
#   - kotlinx.serialization: META-INF/com.android.tools/r8/kotlinx-serialization-*.pro
#   - Room (`-keep class * extends RoomDatabase`, para el _Impl generado)
#   - WorkManager (nombres de los Worker, que se guardan en su base de datos)
#   - Hilt / hilt-work (código generado, sin reflexión)
#   - Glance (`ActionCallback`, que se instancia por nombre)
#   - Media3, ML Kit, OkHttp
#   - Filament / gltfio / filament-utils (`@UsedBy*`) y SceneView, que además
#     guarda `com.google.android.filament.**` entero
# Lo que falta está aquí abajo.

# ── ONNX Runtime (voz neuronal de Masha) ────────────────────────────────
# onnxruntime-android 1.28.0 no trae reglas, y su capa JNI busca por nombre
# clases, constructores y campos de ai.onnxruntime (OnnxTensor, OrtSession,
# TensorInfo, OrtException…). Renombradas o quitadas, la voz natural se cae
# en tiempo de ejecución aunque la compilación vaya bien.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── Trazas legibles ─────────────────────────────────────────────────────
# Números de línea en los informes de fallos (se desofuscan con mapping.txt,
# que hay que guardar junto a cada APK publicado).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
