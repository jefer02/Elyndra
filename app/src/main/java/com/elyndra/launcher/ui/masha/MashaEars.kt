package com.elyndra.launcher.ui.masha

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.elyndra.launcher.ui.masha.live.RecognizerErrors

/**
 * Los oídos de Lucy: reconocimiento de voz.
 *
 * Una frase por escucha: los resultados parciales se ven en el campo de texto
 * mientras se habla y el final se entrega solo. El nivel del micrófono llega a
 * [MashaPresence.micLevel] (el holograma reacciona a él).
 *
 * Desde Android 12, si el móvil reconoce **en el propio dispositivo**, se usa
 * ese reconocedor: el audio no sale del móvil y funciona sin conexión. Si no lo
 * hay, o no tiene el idioma, se usa el servicio de reconocimiento del sistema
 * (que suele mandar el audio a su servidor); [processedBySystem] lo dice para
 * que la interfaz lo avise.
 *
 * Desde Android 13, [startStream] escucha **nuestro** audio (un pipe con PCM
 * de 16 kHz) en vez de abrir el micrófono: la conversación en vivo graba sin
 * parar y le pasa al reconocedor el último segundo antes de que el usuario
 * empezara a hablar, así no se pierden sus primeras palabras. Si el
 * reconocedor no acepta audio de fuera, falla enseguida con
 * [Failure.StreamUnsupported] y quien llama vuelve a [start].
 *
 * Cada escucha se apunta en el log (`LucyLive`): con qué reconocedor y qué
 * audio, cuándo está listo, si oyó voz, el nivel máximo, los parciales y cómo
 * terminó (sin el texto).
 *
 * Tiene que usarse desde el hilo principal (lo exige SpeechRecognizer).
 */
class MashaEars(
    context: Context,
    private val presence: MashaPresence,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (Failure, Int) -> Unit,
    /** El reconocedor cree que el usuario terminó de hablar (su resultado llega después, o no). */
    private val onSpeechEnd: () -> Unit = {},
) {
    enum class Failure { Unavailable, Permission, NoMatch, Network, Busy, StreamUnsupported }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    private val onDeviceAvailable: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)
    private val systemAvailable: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /** El reconocedor del dispositivo falló con este idioma: en lo que queda de sesión, el del sistema. */
    private var onDeviceFailed = false

    val available: Boolean = onDeviceAvailable || systemAvailable

    /** La última escucha la hace el servicio del sistema (el audio puede salir del móvil). */
    var processedBySystem by mutableStateOf(!onDeviceAvailable)
        private set

    private var language = ""
    private var source: ParcelFileDescriptor? = null
    private var startedAt = 0L

    fun start(language: String) = listen(language, null)

    /** Escucha el PCM (16 kHz, mono, 16 bits) que llega por [source] en vez del micrófono. Android 13+. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun startStream(language: String, source: ParcelFileDescriptor) = listen(language, source)

    private fun listen(language: String, source: ParcelFileDescriptor?) {
        if (!available) {
            onError(Failure.Unavailable, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        stop()
        this.language = language
        this.source = source
        startedAt = SystemClock.elapsedRealtime()
        val onDevice = onDeviceAvailable && !onDeviceFailed
        val r = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        processedBySystem = !onDevice
        recognizer = r
        r.setRecognitionListener(Listener(onDevice))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Pistas para cerrar antes la frase en una conversación (muchos reconocedores las ignoran).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            if (onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (source != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, STREAM_RATE)
            }
        }
        presence.listening = true
        Log.i(TAG, "recognizer listen onDevice=$onDevice audio=${if (source != null) "stream" else "mic"} lang=$language")
        r.startListening(intent)
    }

    /** Deja de escuchar (lo dicho hasta ahora aún puede llegar como resultado final). */
    fun finish() {
        recognizer?.stopListening()
    }

    /** Suelta el micrófono ya. */
    fun stop() {
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
        source?.let { runCatching { it.close() } }
        source = null
        presence.listening = false
        presence.micLevel = 0f
    }

    internal inner class Listener(private val onDevice: Boolean) : RecognitionListener {
        private var rmsMax = Float.NEGATIVE_INFINITY
        private var rmsCount = 0
        private var partials = 0
        private var ready = false
        private var began = false

        private fun at() = "+${SystemClock.elapsedRealtime() - startedAt}ms"
        private fun summary() = "ready=$ready speechSeen=$began rmsMax=${if (rmsCount == 0) "none" else "%.1f".format(rmsMax)} rmsEvents=$rmsCount partials=$partials"

        override fun onReadyForSpeech(params: Bundle?) {
            ready = true
            Log.i(TAG, "recognizer ready ${at()}")
            presence.listening = true
        }

        override fun onBeginningOfSpeech() {
            began = true
            Log.i(TAG, "recognizer beginningOfSpeech ${at()}")
        }

        override fun onRmsChanged(rmsdB: Float) {
            rmsCount++
            if (rmsdB > rmsMax) rmsMax = rmsdB
            // −2..10 dB aprox. → 0..1, suavizado.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            presence.micLevel = presence.micLevel * 0.6f + level * 0.4f
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            Log.i(TAG, "recognizer endOfSpeech ${at()} ${summary()}")
            presence.micLevel = 0f
            // El callback de MashaEars (onSpeechEnd), no este método: con el mismo nombre se llamaba a sí mismo.
            onSpeechEnd()
        }

        override fun onError(error: Int) {
            Log.i(TAG, "recognizer onError code=$error (${RecognizerErrors.name(error)}) ${at()} onDevice=$onDevice audio=${if (source != null) "stream" else "mic"} ${summary()}")
            // Audio de fuera rechazado nada más empezar: este reconocedor no lo acepta.
            if (source != null && error in STREAM_ERRORS && SystemClock.elapsedRealtime() - startedAt < STREAM_PROBE_MS) {
                stop()
                onError(Failure.StreamUnsupported, error)
                return
            }
            // El del dispositivo no tiene este idioma (o no está descargado): se repite con el del sistema.
            if (onDevice && systemAvailable && error in LANGUAGE_ERRORS) {
                onDeviceFailed = true
                val again = source
                source = null // listen() no debe cerrar el pipe que va a reutilizar
                recognizer?.destroy()
                recognizer = null
                listen(language, again)
                return
            }
            val failure = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Failure.Permission
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Failure.NoMatch
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> Failure.Network
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Failure.Busy
                in LANGUAGE_ERRORS -> Failure.Unavailable
                else -> Failure.NoMatch
            }
            stop()
            onError(failure, error)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            Log.i(TAG, "recognizer results chars=${text.length} ${at()} ${summary()}")
            stop()
            if (text.isNotBlank()) onFinal(text) else onError(Failure.NoMatch, SpeechRecognizer.ERROR_NO_MATCH)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    if (partials++ == 0) Log.i(TAG, "recognizer first partial ${at()}")
                    onPartial(it)
                }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val TAG = "LucyLive"

        /** ERROR_LANGUAGE_NOT_SUPPORTED (12) y ERROR_LANGUAGE_UNAVAILABLE (13), de Android 12. */
        val LANGUAGE_ERRORS = setOf(12, 13)

        /** ERROR_AUDIO (3), ERROR_CLIENT (5) y ERROR_CANNOT_CHECK_SUPPORT (14): no sabe leer el pipe. */
        val STREAM_ERRORS = setOf(SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_CLIENT, 14)
        const val STREAM_PROBE_MS = 1_500L
        const val STREAM_RATE = 16_000
    }
}

@Composable
fun rememberMashaEars(
    presence: MashaPresence,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (MashaEars.Failure, Int) -> Unit,
    onEndOfSpeech: () -> Unit = {},
): MashaEars {
    val context = LocalContext.current
    val partial by rememberUpdatedState(onPartial)
    val final by rememberUpdatedState(onFinal)
    val error by rememberUpdatedState(onError)
    val endOfSpeech by rememberUpdatedState(onEndOfSpeech)
    val ears = remember(presence) {
        MashaEars(context, presence, { partial(it) }, { final(it) }, { f, code -> error(f, code) }, { endOfSpeech() })
    }
    DisposableEffect(ears) {
        onDispose { ears.stop() }
    }
    return ears
}
