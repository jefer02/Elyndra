package com.elyndra.launcher.ui.masha.voice

import android.content.Context
import android.util.Log
import com.elyndra.launcher.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * El modelo de la voz natural (Supertonic 3, int8, ~145 MB): no va en el APK, se
 * descarga cuando el usuario lo pide (Ajustes → Masha → Voz natural).
 *
 * - Ficheros sueltos de una revisión fija de Hugging Face, cada uno comprobado con
 *   su SHA-256: un fichero corrupto o cambiado nunca se carga.
 * - Descarga reanudable (Range) a una carpeta temporal; al terminar todo, se
 *   renombra a la definitiva (instalación atómica).
 * - En depuración también vale una copia puesta a mano con `adb push` en la carpeta
 *   externa de la app (ver docs/MASHA_VOICE.md).
 *
 * Licencias: código de Supertonic MIT; pesos OpenRAIL-M (uso comercial permitido
 * con restricciones de uso, que la app traslada en sus términos).
 */
object VoicePack {

    const val NAME = "supertonic-3-int8"

    /** Revisión fija (commit) del repositorio con los ficheros: nunca "main". */
    private const val BASE =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4/"

    class Entry(val name: String, val size: Long, val sha256: String)

    val FILES = listOf(
        Entry("duration_predictor.int8.onnx", 3_700_147, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        Entry("text_encoder.int8.onnx", 36_416_150, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        Entry("vector_estimator.int8.onnx", 78_400_833, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        Entry("vocoder.int8.onnx", 25_991_073, "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
        Entry("tts.json", 8_253, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        Entry("unicode_indexer.bin", 262_144, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
        Entry("voice.bin", 517_168, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
        Entry("LICENSE", 1_070, "0dfe0d0ba84416fe3879d9a34f4909d8d0137c78d1e95834177b0414ac096fa2"),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.size }

    sealed interface State {
        data object Missing : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Installed : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Missing)
    val state: StateFlow<State> = _state

    private fun installDir(ctx: Context) = File(ctx.filesDir, "voices/$NAME")
    private fun partDir(ctx: Context) = File(ctx.filesDir, "voices/$NAME.part")

    /** Copia de depuración: `adb push <carpeta> /sdcard/Android/data/<paquete>/files/voices/supertonic-3-int8`. */
    private fun debugDir(ctx: Context) = ctx.getExternalFilesDir(null)?.let { File(it, "voices/$NAME") }

    /** La carpeta del modelo instalado, o null. Comprueba tamaños (el hash, al instalar). */
    fun dir(ctx: Context): File? {
        val dirs = listOfNotNull(installDir(ctx), if (BuildConfig.DEBUG) debugDir(ctx) else null)
        return dirs.firstOrNull { d -> FILES.all { File(d, it.name).length() == it.size } }
    }

    fun refresh(ctx: Context) {
        if (_state.value is State.Downloading) return
        _state.value = if (dir(ctx) != null) State.Installed else State.Missing
    }

    /**
     * Descarga e instala (suspende hasta terminar; cancelable). Lo ya bajado de un
     * intento anterior se reaprovecha.
     */
    suspend fun download(ctx: Context, http: OkHttpClient = defaultClient()) = withContext(Dispatchers.IO) {
        if (dir(ctx) != null) { _state.value = State.Installed; return@withContext }
        val part = partDir(ctx).apply { mkdirs() }
        try {
            var done = FILES.sumOf { e -> File(part, e.name).length().coerceAtMost(e.size) }
            _state.value = State.Downloading(done, TOTAL_BYTES)
            for (e in FILES) {
                val f = File(part, e.name)
                if (f.length() == e.size && sha256(f) == e.sha256) continue
                if (f.length() >= e.size) { done -= f.length(); f.delete() }
                val from = f.length()
                val req = Request.Builder().url(BASE + e.name).apply { if (from > 0) header("Range", "bytes=$from-") }.build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} (${e.name})")
                    val append = from > 0 && resp.code == 206
                    if (!append) done -= from
                    val body = resp.body ?: throw IOException("sin cuerpo (${e.name})")
                    FileOutputStream(f, append).use { out ->
                        val buf = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                _state.value = State.Downloading(done, TOTAL_BYTES)
                            }
                        }
                    }
                }
                if (sha256(f) != e.sha256) {
                    f.delete()
                    throw IOException("SHA-256 no coincide (${e.name})")
                }
            }
            val dst = installDir(ctx)
            dst.deleteRecursively()
            if (!part.renameTo(dst)) throw IOException("no se pudo instalar")
            _state.value = State.Installed
            Log.i(TAG, "voz natural instalada en $dst")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                _state.value = State.Missing
                throw e
            }
            Log.w(TAG, "descarga de la voz natural", e)
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Borra el modelo (y cualquier descarga a medias). */
    fun delete(ctx: Context) {
        NeuralRuntime.closeNow()
        installDir(ctx).deleteRecursively()
        partDir(ctx).deleteRecursively()
        _state.value = State.Missing
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun defaultClient() = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val TAG = "MashaVoice"
}
