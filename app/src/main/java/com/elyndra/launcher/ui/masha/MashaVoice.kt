package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.BuildConfig
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.elyndra.launcher.data.SettingsStore
import com.elyndra.launcher.ui.masha.lipsync.AudioRoute
import com.elyndra.launcher.ui.masha.lipsync.AudioRouteOffsets
import com.elyndra.launcher.ui.masha.lipsync.CmuDict
import com.elyndra.launcher.ui.masha.lipsync.G2p
import com.elyndra.launcher.ui.masha.lipsync.Utterance
import com.elyndra.launcher.ui.masha.lipsync.VowelProfile
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * La voz de Masha (texto a voz del sistema) con sincronía de labios.
 *
 * - Habla **mientras** llega la respuesta: cada frase completa se manda al
 *   motor en cuanto aparece, sin esperar al final del streaming.
 * - El motor **sintetiza** (`synthesizeToFile`) y su PCM llega por
 *   `onAudioAvailable` a trozos: se escribe al momento en un AudioTrack propio
 *   ([SpeechOutput]), que empieza a sonar con ~200 ms de margen. La frase
 *   siguiente se sintetiza mientras suena esta (la cola del motor), sin huecos.
 * - Con ese mismo PCM y los rangos de palabra (`onRangeStart`, con su trama de
 *   audio), un hilo de análisis construye las curvas de la boca de cada frase
 *   ([Utterance] → [LipSync.Item]); el render las lee al ritmo del reloj de audio.
 * - Si el motor no acepta `synthesizeToFile`, se habla con `speak()` como
 *   antes (el motor reproduce) y la boca va con el reloj de pared.
 * - Elige la mejor voz instalada del idioma de la app (local antes que en
 *   red, más calidad antes que menos) con un tono algo más alto.
 *
 * La voz es opcional: sin motor TTS, o con la voz apagada en su panel, Masha
 * sigue escribiendo igual. [presence] (hablando o no) se toca solo desde el
 * hilo principal.
 */
class MashaVoice(context: Context, private val presence: MashaPresence) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lip = presence.lipSync
    private val out = SpeechOutput(lip.config)

    /* Ajuste de sincronía por salida de audio (Ajustes → Desarrollador): se aplica al
       crear la voz y en vivo cuando cambia la salida (auriculares que entran o salen). */
    private val store = SettingsStore(app)
    private val routes = AudioRouteWatcher(app) { route ->
        refreshOffset()
        onRouteChanged?.invoke(route)
    }

    /** Salida de audio actual (hilo principal). */
    val audioRoute: AudioRoute get() = routes.current

    /** Aviso (hilo principal) cuando cambia la salida: para la pantalla de calibración. */
    var onRouteChanged: ((AudioRoute) -> Unit)? = null

    /** Relee el ajuste guardado para la salida actual y lo aplica a `audioOffsetMs` (hilo principal). */
    fun refreshOffset() {
        lip.config.audioOffsetMs = AudioRouteOffsets.select(store.voiceOffsets(), routes.current).toFloat()
    }

    private val worker = HandlerThread("masha-lipsync").apply { start() }
    private val work = Handler(worker.looper)

    private var ready = false
    private var released = false
    private var lang: String? = null
    private var seq = 0
    @Volatile private var gen = 0

    /** Mensaje que se está leyendo y hasta dónde se ha puesto en cola. */
    private var feedingId: Long = -1
    private var queuedUpTo = 0

    /** Una frase en curso: su modelo (hilo de análisis) y su item de render. */
    private inner class Job(val id: String, val text: String, val gen: Int, val sayNanos: Long) {
        val item = LipSync.Item(id)
        var utt: Utterance? = null // hilo de análisis
        var file: File? = null
        var pfd: ParcelFileDescriptor? = null
        @Volatile var chunks = 0
        @Volatile var firstChunkNanos = -1L
        @Volatile var synthDone = false
        /** El hilo de análisis ya mandó el final a la salida (tras leer el WAV si hacía falta). */
        @Volatile var outputEnded = false
        @Volatile var speakMode = false
        var heard = false // hilo principal
        var buildPending = false // hilo de análisis
        var lastBuild = 0L
    }

    private val jobs = ConcurrentHashMap<String, Job>()
    /** Orden de las frases (hilo principal). */
    private val order = ArrayList<Job>()

    // Hilo de análisis.
    private val profile = VowelProfile()
    private var secPerWeight = 0.075f
    @Volatile private var dict: CmuDict? = null
    @Volatile private var voiceLocale: Locale = Locale.getDefault()

    // Medida del tiempo hasta el primer sonido (hilo principal).
    private var runStartNanos = -1L
    private var runHeard = false

    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        main.post {
            if (released) return@post
            ready = status == TextToSpeech.SUCCESS
            if (ready) lang?.let { applyLanguage(it) }
        }
    }

    init {
        lip.clock = out
        refreshOffset()
        out.onPlay = { item ->
            val j = jobs[item.id]
            if (j != null) Log.i(TAG, "play ${item.id}: ${(System.nanoTime() - j.sayNanos) / 1_000_000} ms desde la frase (chunks=${j.chunks})")
        }
        // Solo cuenta para speak() (repuesto); la pista propia lleva sus atributos en SpeechOutput.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                val j = jobs[utteranceId] ?: return
                // En speak() el motor empieza a reproducir aquí: es el reloj del repuesto.
                if (j.speakMode) j.item.startNanos = System.nanoTime()
            }

            override fun onBeginSynthesis(utteranceId: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                val j = jobs[utteranceId] ?: return
                if (j.gen != gen) return
                if (j.speakMode) j.item.sampleRate = sampleRateInHz else out.begin(j.item, sampleRateInHz, audioFormat, channelCount)
                work.post { j.utt?.begin(sampleRateInHz, audioFormat, channelCount) }
            }

            override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
                val j = jobs[utteranceId] ?: return
                if (j.gen != gen) return
                if (j.chunks++ == 0) j.firstChunkNanos = System.nanoTime()
                if (!j.speakMode) out.data(j.item, audio)
                work.post {
                    j.utt?.audio(audio)
                    scheduleBuild(j, urgent = false)
                }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                val j = jobs[utteranceId] ?: return
                work.post {
                    j.utt?.range(start, end, frame)
                    scheduleBuild(j, urgent = false)
                }
            }

            override fun onDone(utteranceId: String) = synthesisEnded(utteranceId, ok = true)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = synthesisEnded(utteranceId, ok = false)

            override fun onError(utteranceId: String, errorCode: Int) = synthesisEnded(utteranceId, ok = false)

            override fun onStop(utteranceId: String, interrupted: Boolean) = synthesisEnded(utteranceId, ok = false)
        })
    }

    fun setLanguage(tag: String) {
        lang = tag
        if (ready) applyLanguage(tag)
    }

    private fun applyLanguage(tag: String) {
        val locale = Locale.forLanguageTag(tag)
        runCatching { tts.language = locale }
        pickVoice(locale)?.let { runCatching { tts.voice = it } }
        tts.setPitch(PITCH)
        tts.setSpeechRate(RATE)
        val vl = runCatching { tts.voice?.locale }.getOrNull() ?: locale
        warmUp()
        work.post {
            if (vl != voiceLocale) profile.reset()
            voiceLocale = vl
            if (vl.language == "en" && dict == null) loadDict()
        }
    }

    /**
     * El motor carga la voz la primera vez que sintetiza (~1 s medido en un Motorola
     * edge 50 fusion): una palabra a un fichero desechable al elegir idioma, para que
     * la primera frase de Masha no pague esa espera. El listener ignora este id.
     */
    private fun warmUp() {
        val f = File(File(app.cacheDir, "masha_tts").apply { mkdirs() }, "warm.wav")
        @Suppress("DEPRECATION")
        runCatching { tts.synthesizeToFile("a", Bundle(), f, "warm") }
    }

    private fun loadDict() {
        val t0 = SystemClock.elapsedRealtime()
        dict = runCatching { app.assets.open(CMUDICT).use { CmuDict.load(it) } }
            .onFailure { Log.w(TAG, "sin CMUdict ($CMUDICT): inglés por reglas", it) }
            .getOrNull()
        dict?.let { Log.i(TAG, "CMUdict: ${it.size} palabras en ${SystemClock.elapsedRealtime() - t0} ms") }
    }

    /**
     * La mejor voz del idioma: instalada, local, de más calidad y, si el motor
     * lo dice en el nombre, femenina (Masha es ella). Null = la del motor.
     */
    private fun pickVoice(locale: Locale): Voice? = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == locale.language }
            .filterNot { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty() }
            .maxByOrNull { v ->
                var score = v.quality
                if (!v.isNetworkConnectionRequired) score += 150
                if (v.locale.country == locale.country) score += 60
                if ("female" in v.name.lowercase() || "#female" in v.features.orEmpty().joinToString()) score += 220
                score - v.latency / 10
            }
    }.getOrNull()

    /**
     * Lee lo nuevo del mensaje [id] (texto completo hasta ahora). Mientras
     * llega ([final] = false) solo pone en cola frases terminadas; al final,
     * el resto.
     */
    fun feed(id: Long, text: String, final: Boolean) {
        if (id != feedingId) {
            feedingId = id
            queuedUpTo = 0
        }
        if (queuedUpTo >= text.length) return
        val end = if (final) text.length else lastSentenceEnd(text, queuedUpTo)
        if (end <= queuedUpTo) return
        val chunk = speakable(text.substring(queuedUpTo, end))
        queuedUpTo = end
        if (chunk.isNotBlank()) say(chunk)
    }

    private fun say(text: String) {
        if (!ready || released) return
        routes.refresh()
        val now = System.nanoTime()
        if (order.isEmpty()) {
            runStartNanos = now
            runHeard = false
        }
        val id = "masha-${seq++}"
        if (BuildConfig.DEBUG) Log.d(TAG, "say $id: \"$text\"")
        val j = Job(id, text, gen, now)
        jobs[id] = j
        order += j
        lip.add(j.item)
        // El modelo de la frase (G2P) se crea en el hilo de análisis, antes que cualquier aviso del motor.
        work.post {
            val g2p = G2p.forLocale(voiceLocale, dict)
            j.utt = Utterance(text, g2p, seed = id.hashCode())
        }
        val params = Bundle()
        val result = runCatching { synthesize(j, params) }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            // Repuesto: el motor reproduce (como antes).
            closeFile(j)
            j.speakMode = true
            tts.speak(text, TextToSpeech.QUEUE_ADD, params, id)
        }
        tick()
    }

    private fun synthesize(j: Job, params: Bundle): Int {
        val dir = File(app.cacheDir, "masha_tts").apply { mkdirs() }
        val f = File(dir, "${j.id}.wav")
        j.file = f
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
            j.pfd = pfd
            tts.synthesizeToFile(j.text, params, pfd, j.id)
        } else {
            @Suppress("DEPRECATION")
            tts.synthesizeToFile(j.text, params, f, j.id)
        }
    }

    /** Fin de la síntesis de una frase (cualquier hilo del motor). */
    private fun synthesisEnded(id: String, ok: Boolean) {
        val j = jobs[id] ?: return
        if (j.synthDone) return
        j.synthDone = true
        if (j.speakMode) {
            j.item.spokenDone = true
            work.post {
                j.utt?.finish()
                scheduleBuild(j, urgent = true)
            }
            main.post { tick() }
            return
        }
        work.post {
            closeFile(j)
            // Motores que no dan onAudioAvailable al sintetizar a fichero: se lee el WAV.
            if (ok && j.chunks == 0 && j.gen == gen) readWav(j)
            out.end(j.item)
            j.outputEnded = true
            j.file?.delete()
            j.utt?.finish()
            scheduleBuild(j, urgent = true)
            learn(j)
        }
        main.post { tick() }
    }

    private fun closeFile(j: Job) {
        runCatching { j.pfd?.close() }
        j.pfd = null
    }

    /** Lee el PCM de un WAV (cabecera RIFF) y lo pasa como si hubiera llegado a trozos. */
    private fun readWav(j: Job) {
        val f = j.file ?: return
        runCatching {
            RandomAccessFile(f, "r").use { raf ->
                val head = ByteArray(12)
                raf.readFully(head)
                if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return
                var sr = 0; var ch = 1; var bits = 16
                val hdr = ByteArray(8)
                while (raf.filePointer + 8 <= raf.length()) {
                    raf.readFully(hdr)
                    val tag = String(hdr, 0, 4)
                    val size = le32(hdr, 4)
                    if (tag == "fmt ") {
                        val fmt = ByteArray(size)
                        raf.readFully(fmt)
                        ch = le16(fmt, 2); sr = le32(fmt, 4); bits = le16(fmt, 14)
                    } else if (tag == "data") {
                        val len = minOf(size.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE, raf.length() - raf.filePointer).toInt()
                        val data = ByteArray(len)
                        raf.readFully(data)
                        val enc = when (bits) { 8 -> AudioFormat.ENCODING_PCM_8BIT; 32 -> AudioFormat.ENCODING_PCM_FLOAT; else -> AudioFormat.ENCODING_PCM_16BIT }
                        out.begin(j.item, sr, enc, ch)
                        j.utt?.begin(sr, enc, ch)
                        val step = sr / 10 * ch * (bits / 8)
                        var o = 0
                        while (o < data.size) {
                            val part = data.copyOfRange(o, minOf(data.size, o + step))
                            out.data(j.item, part)
                            j.utt?.audio(part)
                            o += step
                        }
                        j.chunks = 1
                        Log.i(TAG, "${j.id}: el motor no dio PCM al sintetizar; leído del WAV ($len B)")
                        return
                    } else {
                        raf.seek(raf.filePointer + size)
                    }
                }
            }
        }.onFailure { Log.w(TAG, "WAV ${j.id}", it) }
    }

    /* ── hilo de análisis ── */

    private fun scheduleBuild(j: Job, urgent: Boolean) {
        if (j.gen != gen) return
        val now = SystemClock.uptimeMillis()
        if (urgent || now - j.lastBuild >= BUILD_MS) {
            build(j)
        } else if (!j.buildPending) {
            j.buildPending = true
            work.postDelayed({ j.buildPending = false; build(j) }, BUILD_MS - (now - j.lastBuild))
        }
    }

    private fun build(j: Job) {
        val u = j.utt ?: return
        if (j.gen != gen) return
        j.lastBuild = SystemClock.uptimeMillis()
        j.item.track = runCatching { u.build(lip.config, profile, secPerWeight) }
            .onFailure { Log.w(TAG, "curvas ${j.id}", it) }
            .getOrNull() ?: return
    }

    private fun learn(j: Job) {
        val u = j.utt ?: return
        val f = u.features ?: return
        if (!u.audioOnly) profile.learn(u.segments, f)
        val spw = u.measuredSecPerWeight()
        if (!spw.isNaN()) secPerWeight += (spw.coerceIn(0.03f, 0.2f) - secPerWeight) * 0.3f
        Log.i(
            TAG,
            "${j.id}: ${u.tokens.size} palabras, rangos=${u.rangesSeen}, modo=${
                when {
                    u.audioOnly -> "solo audio"
                    u.rangesSeen > 0 -> "texto+rangos"
                    else -> "texto sin rangos"
                }
            }, ${f.count * 10} ms",
        )
    }

    /* ── estado de habla (hilo principal) ── */

    private val ticker = Runnable { tick() }

    /** Cada 50 ms mientras hay frases: quién suena, quién terminó, "hablando" y el primer sonido. */
    private fun tick() {
        main.removeCallbacks(ticker)
        if (released) return
        val now = System.nanoTime()
        var any = false
        val it = order.iterator()
        while (it.hasNext()) {
            val j = it.next()
            val t = out.seconds(j.item, now)
            if (!j.heard && (j.speakMode || out.isPlaying) && !t.isNaN() && t > 0.0) {
                j.heard = true
                if (!runHeard) {
                    runHeard = true
                    // Cuándo empezó a oírse de verdad: ahora menos lo que ya lleva sonando.
                    val heardAt = now - (t * 1e9).toLong()
                    val first = if (j.firstChunkNanos > 0) (j.firstChunkNanos - j.sayNanos) / 1_000_000 else -1
                    Log.i(TAG, "tiempo hasta el primer sonido: ${(heardAt - runStartNanos) / 1_000_000} ms (primer PCM a los $first ms, modo=${if (j.speakMode) "speak" else "stream"})")
                }
            }
            val done = j.gen != gen || (j.speakMode && j.synthDone) || (j.outputEnded && out.finished(j.item, now))
            if (done) {
                it.remove()
                jobs.remove(j.id)
                // Se quita del render un poco después: que la boca termine de cerrarse con su curva.
                main.postDelayed({ lip.remove(j.item) }, 600)
            } else if (j.heard) {
                any = true
            }
        }
        val speaking = order.isNotEmpty() && (any || runHeard)
        if (speaking != presence.speaking) presence.speaking = speaking
        if (order.isNotEmpty()) main.postDelayed(ticker, TICK_MS)
    }

    /** Calla ya (y olvida lo que quedaba en cola). */
    fun stop() {
        if (released) return
        gen++
        tts.stop()
        out.stop()
        for (j in order) {
            closeFile(j)
            j.file?.delete()
        }
        order.clear()
        jobs.clear()
        lip.clear()
        feedingId = -1
        speakingOff()
    }

    /** El mensaje ya leído no se vuelve a leer (p. ej. al volver a la pantalla). */
    fun skip(id: Long, length: Int) {
        feedingId = id
        queuedUpTo = length
    }

    fun release() {
        stop()
        released = true
        main.removeCallbacksAndMessages(null)
        runCatching { tts.shutdown() }
        out.release()
        routes.release()
        lip.clock = null
        worker.quitSafely()
        runCatching { File(app.cacheDir, "masha_tts").listFiles()?.forEach { it.delete() } }
    }

    private fun speakingOff() {
        main.removeCallbacks(ticker)
        presence.speaking = false
    }

    companion object {
        private const val TAG = "MashaVoice"
        const val PITCH = 1.06f
        const val RATE = 1.0f
        private const val CMUDICT = "lipsync/cmudict.txt"
        private const val TICK_MS = 50L
        private const val BUILD_MS = 40L

        // Los signos CJK (。！？) no llevan espacio detrás: cierran la frase por sí solos.
        private val SENTENCE_END = Regex("[.!?…]+[\"'»”)]*(\\s|$)|[。！？]+[」』）”]*|\\n+")
        private val URL = Regex("https?://\\S+")
        private val MARKUP = Regex("[*_#`>|~▍•]+")
        private val EMOJI = Regex("[\\p{So}\\p{Sk}\\x{FE0F}\\x{200D}\\x{20E3}]")
        private val SPACES = Regex("\\s+")

        /** Hasta dónde hay frases terminadas a partir de [from] (0 = ninguna). */
        fun lastSentenceEnd(text: String, from: Int): Int {
            var end = from
            for (m in SENTENCE_END.findAll(text, from)) {
                // Frases muy cortas ("Vale.") se juntan con la siguiente: suena más natural.
                if (spokenLength(text, from, m.range.last + 1) >= MIN_CHUNK || end > from) end = m.range.last + 1
            }
            return end
        }

        /** Largo "hablado": un carácter CJK (kana, kanji, hangul) vale ~una sílaba, como tres letras. */
        private fun spokenLength(text: String, from: Int, to: Int): Int {
            var n = 0
            for (i in from until to) n += if (text[i].code >= 0x2E80) 3 else 1
            return n
        }

        /** Lo que se lee: sin enlaces, marcas de formato ni emojis. */
        fun speakable(s: String): String =
            s.replace(URL, " ").replace(MARKUP, " ").replace(EMOJI, "").replace(SPACES, " ").trim()

        private const val MIN_CHUNK = 18

        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
    }
}

/**
 * La voz de Masha mientras la pantalla está en composición: se crea al entrar,
 * sigue el idioma de la app y se apaga (liberando el motor) al salir.
 */
@Composable
fun rememberMashaVoice(presence: MashaPresence, language: String, enabled: Boolean): MashaVoice {
    val context = LocalContext.current
    val voice = remember(presence) { MashaVoice(context, presence) }
    DisposableEffect(voice) {
        onDispose { voice.release() }
    }
    LaunchedEffect(voice, language) { voice.setLanguage(language) }
    LaunchedEffect(voice, enabled) { if (!enabled) voice.stop() }
    return voice
}
