package com.elyndra.launcher.ui.masha

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Los oídos de Masha: reconocimiento de voz del sistema.
 *
 * Una frase por pulsación del micrófono: los resultados parciales se ven en el
 * campo de texto mientras se habla y el final se envía solo. El nivel del
 * micrófono llega a [MashaPresence.micLevel] (el holograma reacciona a él).
 *
 * Tiene que usarse desde el hilo principal (lo exige SpeechRecognizer).
 */
class MashaEars(
    context: Context,
    private val presence: MashaPresence,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (Failure) -> Unit,
) {
    enum class Failure { Unavailable, Permission, NoMatch, Network, Busy }

    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start(language: String) {
        if (!available) {
            onError(Failure.Unavailable)
            return
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = r
        r.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        presence.listening = true
        r.startListening(intent)
    }

    /** Deja de escuchar (lo dicho hasta ahora aún puede llegar como resultado final). */
    fun finish() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
        presence.listening = false
        presence.micLevel = 0f
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            presence.listening = true
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // −2..10 dB aprox. → 0..1, suavizado.
            val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            presence.micLevel = presence.micLevel * 0.6f + level * 0.4f
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            presence.micLevel = 0f
        }

        override fun onError(error: Int) {
            val failure = when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> Failure.Permission
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Failure.NoMatch
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> Failure.Network
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Failure.Busy
                else -> Failure.NoMatch
            }
            stop()
            onError(failure)
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            stop()
            if (text.isNotBlank()) onFinal(text) else onError(Failure.NoMatch)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(onPartial)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}

@Composable
fun rememberMashaEars(
    presence: MashaPresence,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (MashaEars.Failure) -> Unit,
): MashaEars {
    val context = LocalContext.current
    val partial by rememberUpdatedState(onPartial)
    val final by rememberUpdatedState(onFinal)
    val error by rememberUpdatedState(onError)
    val ears = remember(presence) {
        MashaEars(context, presence, { partial(it) }, { final(it) }, { error(it) })
    }
    DisposableEffect(ears) {
        onDispose { ears.stop() }
    }
    return ears
}
