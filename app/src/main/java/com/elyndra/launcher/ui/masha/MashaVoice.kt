package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.BuildConfig
import android.content.Context
import android.media.AudioFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
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
import com.elyndra.launcher.ui.masha.voice.HalfbandDecimator
import com.elyndra.launcher.ui.masha.voice.NeuralVoiceEngine
import com.elyndra.launcher.ui.masha.voice.SpeechNormalizer
import com.elyndra.launcher.ui.masha.voice.SynthesisCallback
import com.elyndra.launcher.ui.masha.voice.SynthesisRequest
import com.elyndra.launcher.ui.masha.voice.SystemTtsEngine
import com.elyndra.launcher.ui.masha.voice.VoiceInfo
import com.elyndra.launcher.ui.masha.voice.VoiceRouter
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * La voz de Masha con sincronía de labios.
 *
 * - Habla **mientras** llega la respuesta: cada frase completa se manda al
 *   motor en cuanto aparece, sin esperar al final del streaming.
 * - **Motores** (ui/masha/voice, docs/MASHA_VOICE.md): la voz natural en el propio
 *   móvil (Supertonic 3) si está instalada y el móvil puede con ella; si no, o si
 *   falla en una frase, la voz del sistema. Los dos entregan PCM a trozos con la
 *   misma forma ([SynthesisCallback]).
 * - El PCM se escribe al momento en un AudioTrack propio ([SpeechOutput]), que
 *   empieza a sonar con ~200 ms de margen. La frase siguiente se sintetiza
 *   mientras suena esta, sin huecos.
 * - Con ese mismo PCM (y los rangos de palabra si el motor los da), un hilo de
 *   análisis construye las curvas de la boca de cada frase ([Utterance] →
 *   [LipSync.Item]); el render las lee al ritmo del reloj de audio. La voz natural
 *   va a 44,1 kHz: el análisis recibe una copia a la mitad, que es para lo que
 *   está afinado.
 * - Si el motor del sistema no acepta `synthesizeToFile`, habla con `speak()` y
 *   la boca va con el reloj de pared.
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

    private var released = false
    private var lang: String? = null
    private var seq = 0
    @Volatile private var gen = 0

    /** Mensaje que se está leyendo y hasta dónde se ha puesto en cola. */
    private var feedingId: Long = -1
    private var queuedUpTo = 0

    /** Frases que llegaron antes de que hubiera motor: se dicen en cuanto lo hay. */
    private val waiting = ArrayList<String>()

    /** Una frase en curso: su modelo (hilo de análisis) y su item de render. */
    private inner class Job(val id: String, val text: String, val gen: Int, val sayNanos: Long, val voice: VoiceInfo) {
        val item = LipSync.Item(id)
        var utt: Utterance? = null // hilo de análisis
        /** Copia a media frecuencia para el análisis (voz a 44,1 kHz), o null. */
        @Volatile var decimator: HalfbandDecimator? = null
        @Volatile var rate = 0
        @Volatile var chunks = 0
        @Volatile var firstChunkNanos = -1L
        @Volatile var synthDone = false
        /** El hilo de análisis ya mandó el final a la salida. */
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
    private var secPerWeight = SEC_PER_WEIGHT
    @Volatile private var dict: CmuDict? = null
    private var voiceId: String? = null
    private var voiceLocale: Locale = Locale.getDefault()

    // Medida del tiempo hasta el primer sonido (hilo principal).
    private var runStartNanos = -1L
    private var runHeard = false

    private val system = SystemTtsEngine(app).apply { onChanged = { flushWaiting() } }
    private val engine = VoiceRouter(NeuralVoiceEngine(app), system)

    /** Lo que avisa el motor de cada frase (hilos del motor). */
    private val callback = object : SynthesisCallback {
        override fun onSelfPlayback(id: String) {
            jobs[id]?.speakMode = true
        }

        override fun onPlaybackStart(id: String) {
            val j = jobs[id] ?: return
            // En speak() el motor empieza a reproducir aquí: es el reloj del repuesto.
            if (j.speakMode) j.item.startNanos = System.nanoTime()
        }

        override fun onBegin(id: String, sampleRate: Int, encoding: Int, channels: Int) {
            val j = jobs[id] ?: return
            if (j.gen != gen) return
            if (j.speakMode) j.item.sampleRate = sampleRate else out.begin(j.item, sampleRate, encoding, channels)
            j.rate = sampleRate
            val half = sampleRate >= 32_000 && encoding == AudioFormat.ENCODING_PCM_16BIT && channels == 1
            j.decimator = if (half) HalfbandDecimator() else null
            val analysisRate = if (half) sampleRate / 2 else sampleRate
            work.post { j.utt?.begin(analysisRate, encoding, channels) }
        }

        override fun onAudio(id: String, pcm: ByteArray) {
            val j = jobs[id] ?: return
            if (j.gen != gen) return
            if (j.chunks++ == 0) j.firstChunkNanos = System.nanoTime()
            if (!j.speakMode) out.data(j.item, pcm)
            work.post {
                // Fuera del hilo del motor (binder): escribir aquí retrasaba sus avisos.
                if (BuildConfig.DEBUG) dump(j, pcm)
                val d = j.decimator
                j.utt?.audio(if (d != null) d.process(pcm) else pcm)
                scheduleBuild(j, urgent = false)
            }
        }

        override fun onRange(id: String, start: Int, end: Int, frame: Int) {
            val j = jobs[id] ?: return
            val f = if (j.decimator != null) frame / 2 else frame
            work.post {
                j.utt?.range(start, end, f)
                scheduleBuild(j, urgent = false)
            }
        }

        override fun onEnd(id: String, ok: Boolean) = synthesisEnded(id)
    }

    init {
        lip.clock = out
        refreshOffset()
        out.onPlay = { item ->
            val j = jobs[item.id]
            if (j != null) Log.i(TAG, "play ${item.id}: ${(System.nanoTime() - j.sayNanos) / 1_000_000} ms desde la frase (chunks=${j.chunks}, motor=${j.voice.engineId})")
        }
        applySettings()
    }

    /** Relee los ajustes de voz (voz natural, hablante, velocidad). */
    fun applySettings() {
        engine.naturalEnabled = store.mashaVoiceNatural
        engine.neural.speaker = store.mashaVoiceSpeaker
        system.setRate(store.mashaVoiceRate)
    }

    fun setLanguage(tag: String) {
        lang = tag
        engine.setLanguage(tag)
        engine.warmUp()
        flushWaiting()
    }

    private fun flushWaiting() {
        if (released || !engine.ready || waiting.isEmpty()) return
        val pending = waiting.toList()
        waiting.clear()
        pending.forEach(::say)
    }

    private fun loadDict() {
        val t0 = SystemClock.elapsedRealtime()
        dict = runCatching { app.assets.open(CMUDICT).use { CmuDict.load(it) } }
            .onFailure { Log.w(TAG, "sin CMUdict ($CMUDICT): inglés por reglas", it) }
            .getOrNull()
        dict?.let { Log.i(TAG, "CMUdict: ${it.size} palabras en ${SystemClock.elapsedRealtime() - t0} ms") }
    }

    /**
     * Lee lo nuevo del mensaje [id] (texto completo hasta ahora). Mientras
     * llega ([final] = false) solo pone en cola frases terminadas; al final,
     * el resto, también frase a frase (la primera suena antes).
     */
    fun feed(id: Long, text: String, final: Boolean) {
        if (id != feedingId) {
            feedingId = id
            queuedUpTo = 0
        }
        if (queuedUpTo >= text.length) return
        val end = if (final) text.length else lastSentenceEnd(text, queuedUpTo)
        if (end <= queuedUpTo) return
        for (s in sentences(text, queuedUpTo, end)) {
            val chunk = speakable(s)
            if (chunk.isNotBlank()) say(chunk)
        }
        queuedUpTo = end
    }

    private fun say(raw: String) {
        if (released) return
        val voice = engine.voice()
        if (voice == null) {
            // Aún sin motor (arrancando): se dice en cuanto lo haya, no se pierde.
            if (waiting.size < MAX_WAITING) waiting += raw
            return
        }
        val text = if (voice.wantsNormalizedText) {
            runCatching { SpeechNormalizer.normalize(raw, voice.locale.language, region(voice.locale.language)) }.getOrDefault(raw)
        } else {
            raw
        }
        routes.refresh()
        val now = System.nanoTime()
        if (order.isEmpty()) {
            runStartNanos = now
            runHeard = false
        }
        val id = "masha-${seq++}"
        if (BuildConfig.DEBUG) Log.d(TAG, "say $id [${voice.engineId}]: \"$text\"")
        val j = Job(id, text, gen, now, voice)
        jobs[id] = j
        order += j
        lip.add(j.item)
        // El modelo de la frase (G2P) se crea en el hilo de análisis, antes que cualquier aviso del motor.
        work.post {
            if (voice.voiceId != voiceId || voice.locale != voiceLocale) {
                // Otra voz: lo aprendido de la anterior (vocales, ritmo) no vale.
                profile.reset()
                secPerWeight = SEC_PER_WEIGHT
                voiceId = voice.voiceId
                voiceLocale = voice.locale
            }
            if (voiceLocale.language == "en" && dict == null) loadDict()
            val g2p = G2p.forLocale(voiceLocale, dict)
            j.utt = Utterance(text, g2p, seed = id.hashCode())
        }
        if (!engine.synthesize(SynthesisRequest(id, text, rate = moodRate()), voice, callback)) {
            synthesisEnded(id)
        }
        tick()
    }

    /**
     * Región para leer números y siglas ("3,5" = "tres coma cinco" en España, "punto" en
     * México): el país del primer idioma del sistema que coincide; si no hay, ninguna (neutra).
     */
    private fun region(language: String): String? {
        // Los idiomas del sistema, no los de la app: el de la app es solo "es" (sin país).
        val system = android.content.res.Resources.getSystem().configuration.locales
        for (i in 0 until system.size()) {
            val l = system[i]
            if (l.language == language && l.country.isNotEmpty()) return l.country
        }
        return null
    }

    /**
     * Un matiz de ritmo según el ánimo (el motor natural no tiene tono ni estilo):
     * algo más viva cuando juega, más pausada cuando consuela o piensa.
     */
    private fun moodRate(): Float = when (presence.mood) {
        MashaMood.Playful -> 1.04f
        MashaMood.Concerned -> 0.95f
        MashaMood.Warm, MashaMood.Thinking -> 0.97f
        else -> 1f
    } * (if (engine.voice()?.engineId == engine.neural.id) store.mashaVoiceRate else 1f)

    /** Fin de la síntesis de una frase (cualquier hilo del motor). */
    private fun synthesisEnded(id: String) {
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
            out.end(j.item)
            j.outputEnded = true
            j.utt?.finish()
            scheduleBuild(j, urgent = true)
            learn(j)
        }
        main.post { tick() }
    }

    /**
     * QA (solo debug): con `cache/voice_dump.on` presente, el PCM de cada frase tal como va
     * a la salida se guarda en `cache/voice_dump/<frase>_<frecuencia>.pcm` (16 bits mono).
     * Android no deja grabar la voz (USAGE_ASSISTANT), así se escucha y se mide en escritorio.
     */
    private fun dump(j: Job, pcm: ByteArray) {
        if (!File(app.cacheDir, "voice_dump.on").exists()) return
        val sr = j.rate.takeIf { it > 0 } ?: return
        runCatching {
            val dir = File(app.cacheDir, "voice_dump").apply { mkdirs() }
            File(dir, "${j.id}_${j.voice.engineId}_$sr.pcm").appendBytes(pcm)
        }
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
            }, ${f.count * 10} ms, motor=${j.voice.engineId}",
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
                    Log.i(TAG, "tiempo hasta el primer sonido: ${(heardAt - runStartNanos) / 1_000_000} ms (primer PCM a los $first ms, modo=${if (j.speakMode) "speak" else "stream"}, motor=${j.voice.engineId})")
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
        engine.stop(jobs.keys.toList())
        out.stop()
        order.clear()
        jobs.clear()
        waiting.clear()
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
        engine.release()
        out.release()
        routes.release()
        lip.clock = null
        worker.quitSafely()
    }

    private fun speakingOff() {
        main.removeCallbacks(ticker)
        presence.speaking = false
    }

    companion object {
        private const val TAG = "MashaVoice"
        private const val CMUDICT = "lipsync/cmudict.txt"
        private const val TICK_MS = 50L
        private const val BUILD_MS = 40L
        private const val SEC_PER_WEIGHT = 0.075f
        private const val MAX_WAITING = 12

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

        /**
         * [from, to) partido en frases (las muy cortas, con la siguiente). Lo que
         * queda sin punto al final va como última frase.
         */
        fun sentences(text: String, from: Int, to: Int): List<String> {
            val out = ArrayList<String>()
            var start = from
            for (m in SENTENCE_END.findAll(text.substring(0, to), from)) {
                val end = m.range.last + 1
                if (spokenLength(text, start, end) >= MIN_CHUNK) {
                    out += text.substring(start, end)
                    start = end
                }
            }
            if (start < to) {
                val tail = text.substring(start, to)
                // Un final corto ("¡Suerte!") se une a la frase anterior.
                if (out.isNotEmpty() && spokenLength(text, start, to) < MIN_CHUNK) out[out.lastIndex] += tail else out += tail
            }
            return out
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
