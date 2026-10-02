package com.elyndra.launcher.sound

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Qué se acepta como sonido propio. Un sonido de interfaz tiene que ser corto
 * (el de lanzar puede durar algo más) y pequeño: SoundPool lo decodifica
 * entero en memoria.
 */
object CustomSoundRules {

    const val MAX_BYTES = 1_048_576L
    const val MIN_MS = 20L
    const val MAX_MS = 1_500L
    const val MAX_LAUNCH_MS = 4_000L

    enum class Problem { TooBig, TooLong, TooShort, NotAudio }

    fun maxMs(sound: UiSound): Long = if (sound == UiSound.Launch) MAX_LAUNCH_MS else MAX_MS

    /**
     * El primer problema de un archivo, o null si vale. [durationMs] y
     * [decodable] salen del propio archivo (null/false si no se pudo leer).
     */
    fun check(sound: UiSound, sizeBytes: Long, durationMs: Long?, decodable: Boolean): Problem? = when {
        sizeBytes > MAX_BYTES -> Problem.TooBig
        !decodable || durationMs == null -> Problem.NotAudio
        durationMs > maxMs(sound) -> Problem.TooLong
        durationMs < MIN_MS -> Problem.TooShort
        else -> null
    }

    /** Extensión segura para el archivo copiado. */
    fun extensionFor(displayName: String?, mime: String?): String {
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{2,4}")) }
        return fromName ?: when (mime) {
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/mpeg" -> "mp3"
            "audio/x-wav", "audio/wav" -> "wav"
            "audio/flac" -> "flac"
            "audio/mp4", "audio/aac" -> "m4a"
            else -> "bin"
        }
    }
}

/**
 * Importa un sonido elegido con el selector del sistema (SAF): lo copia a
 * `filesDir/sounds/custom_<evento>.<ext>` —el permiso del documento no hace
 * falta después— y comprueba que es audio que se puede decodificar, corto y
 * pequeño. Sin permisos nuevos.
 */
class CustomSoundImporter(private val context: Context) {

    sealed interface Result {
        data class Ok(val fileName: String) : Result
        data class Rejected(val problem: CustomSoundRules.Problem) : Result
        data object Failed : Result
    }

    val dir: File get() = File(context.filesDir, "sounds").apply { mkdirs() }

    fun fileOf(name: String): File = File(dir, name)

    /** Bloquea: llamar fuera del hilo principal. */
    fun import(sound: UiSound, uri: Uri): Result = runCatching {
        val resolver = context.contentResolver
        var size = -1L
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                if (!c.isNull(0)) size = c.getLong(0)
                name = c.getString(1)
            }
        }
        if (size > CustomSoundRules.MAX_BYTES) return Result.Rejected(CustomSoundRules.Problem.TooBig)
        val ext = CustomSoundRules.extensionFor(name, resolver.getType(uri))
        val tmp = File(dir, "import_${sound.id}.tmp")
        var copied = 0L
        resolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    copied += n
                    // Por si el proveedor no dijo el tamaño: se corta al pasarse.
                    if (copied > CustomSoundRules.MAX_BYTES) break
                    out.write(buf, 0, n)
                }
            }
        } ?: return Result.Failed
        val (duration, decodable) = probe(tmp)
        val problem = CustomSoundRules.check(sound, copied, duration, decodable)
        if (problem != null) {
            tmp.delete()
            return Result.Rejected(problem)
        }
        // Un solo archivo por evento: fuera los de otra extensión.
        dir.listFiles { f -> f.name.startsWith("custom_${sound.id}.") }?.forEach { it.delete() }
        val finalName = "custom_${sound.id}.$ext"
        if (!tmp.renameTo(File(dir, finalName))) {
            tmp.delete()
            return Result.Failed
        }
        Result.Ok(finalName)
    }.getOrElse { Result.Failed }

    fun delete(name: String?) {
        if (name != null) fileOf(name).delete()
    }

    /** Duración (ms) y si hay un decodificador para su pista de audio. */
    private fun probe(file: File): Pair<Long?, Boolean> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1000 else null
                // El decodificador no admite la velocidad de muestreo en el formato de consulta en algunos equipos.
                val decoder = runCatching { codecs.findDecoderForFormat(format) }.getOrNull()
                    ?: runCatching { codecs.findDecoderForFormat(MediaFormat.createAudioFormat(mime, format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))) }.getOrNull()
                return duration to (decoder != null || mime == "audio/raw")
            }
            null to false
        } catch (_: Exception) {
            null to false
        } finally {
            extractor.release()
        }
    }
}
