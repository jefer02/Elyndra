package com.elyndra.launcher.ui.masha

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.elyndra.launcher.ui.masha.lipsync.LipSyncConfig
import com.elyndra.launcher.ui.masha.live.PlaybackEnvelope
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Salida de la voz: el PCM que da el motor (frase a frase, según llega) se
 * escribe en un [AudioTrack] propio desde un hilo aparte.
 *
 * - **Arranque rápido:** la pista empieza a sonar en cuanto hay
 *   `lookaheadMs` (200 ms) de audio de la frase, o la frase entera si es más
 *   corta; nunca espera a la respuesta completa ni a un fichero.
 * - **Sin huecos:** las frases se escriben seguidas en la misma pista; el
 *   motor sintetiza la siguiente mientras suena esta.
 * - **Reloj ([LipSync.Clock]):** posición oída = `AudioTrack.getTimestamp`
 *   (trama presentada en la salida, con la latencia del altavoz o Bluetooth)
 *   extrapolada al instante del fotograma; hasta que hay marca válida, la
 *   cabeza de reproducción menos una latencia supuesta. Más el ajuste manual
 *   `audioOffsetMs`.
 *
 * El PCM se convierte siempre a 16 bits (8 bits y float incluidos).
 *
 * Para la conversación en vivo: [hold]/[resume] pausan la voz a media frase
 * (el usuario empezó a hablar) sin perder lo que queda, y [soundingLevelDb] es
 * el nivel de lo que sale por el altavoz ahora mismo ([envelope]), la
 * referencia para no confundir su propia voz con la del usuario.
 */
internal class SpeechOutput(private val config: LipSyncConfig) : LipSync.Clock {

    private class Msg(val kind: Int, val gen: Int, val item: LipSync.Item?, val data: ByteArray?, val a: Int, val b: Int, val c: Int)

    private val queue = LinkedBlockingQueue<Msg>()
    @Volatile private var gen = 0
    @Volatile private var quit = false

    // Estado de la pista (lo escribe el hilo de salida; el reloj lo lee desde el render).
    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackId = 0
    @Volatile private var rate = 0
    @Volatile private var chans = 1
    @Volatile private var written = 0L
    @Volatile private var playing = false
    private var pausedAt = 0L
    private var idleSince = -1L

    /** La pista está sonando (no en pausa ni llenando el margen inicial). */
    val isPlaying: Boolean get() = playing && track != null && !held

    /** Lo que se ha ido escribiendo en la pista, en niveles por bloque (hilo de salida → micrófono). */
    val envelope = PlaybackEnvelope()

    /** En pausa a petición (el usuario habla): ni suena ni arranca hasta [resume]. */
    @Volatile var held = false
        private set
    @Volatile private var heldPos = Double.NaN

    /** Se llama (hilo de salida) cuando la pista arranca de verdad (para medir el tiempo hasta el primer sonido). */
    @Volatile var onPlay: ((LipSync.Item) -> Unit)? = null

    private val thread = Thread({ loop() }, "masha-voice-out").apply {
        priority = Thread.MAX_PRIORITY - 1
        isDaemon = true
        start()
    }

    /* ── lo que llega del motor (cualquier hilo) ── */

    fun begin(item: LipSync.Item, sampleRate: Int, encoding: Int, channels: Int) =
        queue.offer(Msg(BEGIN, gen, item, null, sampleRate, encoding, channels))

    fun data(item: LipSync.Item, bytes: ByteArray) = queue.offer(Msg(DATA, gen, item, bytes, 0, 0, 0))

    fun end(item: LipSync.Item) = queue.offer(Msg(END, gen, item, null, 0, 0, 0))

    /** Corta ya: descarta lo pendiente y vacía la pista. */
    fun stop() {
        gen++
        queue.clear()
        queue.offer(Msg(FLUSH, gen, null, null, 0, 0, 0))
    }

    /** Pausa ya, a media frase (la cola y lo escrito se guardan). */
    fun hold() {
        if (held) return
        heldPos = computePosition(System.nanoTime())
        held = true
        track?.let { t -> if (playing) runCatching { t.pause() } }
    }

    /** Sigue donde se pausó. */
    fun resume() {
        if (!held) return
        held = false
        tsValid = false
        track?.let { t -> if (playing) runCatching { t.play() } }
    }

    private val micTs = AudioTimestamp()

    /**
     * Nivel (dBFS) de lo que suena ahora mismo por la salida, o silencio. Lo
     * llama el hilo del micrófono: la trama presentada sale de su propia marca
     * de tiempo (sin el ajuste manual de los labios, que es para el ojo).
     */
    fun soundingLevelDb(now: Long = System.nanoTime()): Float {
        val t = track ?: return PlaybackEnvelope.SILENT
        val sr = rate
        if (!playing || held || sr <= 0) return PlaybackEnvelope.SILENT
        val frame = synchronized(micTs) {
            val ok = try {
                t.getTimestamp(micTs)
            } catch (_: IllegalStateException) {
                false
            }
            if (ok && micTs.nanoTime > 0) {
                micTs.framePosition + (now - micTs.nanoTime) * sr / 1_000_000_000L
            } else {
                head(t) - (config.fallbackLatencyMs * sr / 1000).toLong()
            }
        }
        return envelope.levelAt(frame)
    }

    fun release() {
        quit = true
        stop()
        runCatching { thread.join(300) }
        releaseTrack()
    }

    /* ── hilo de salida ── */

    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var srcChannels = 1

    private fun loop() {
        while (!quit) {
            val m = try {
                queue.poll(50, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
            if (m == null) {
                idle()
                continue
            }
            if (m.kind == FLUSH) {
                releaseTrack()
                held = false
                continue
            }
            if (m.gen != gen) continue
            try {
                when (m.kind) {
                    BEGIN -> {
                        val item = m.item!!
                        encoding = m.b
                        srcChannels = m.c.coerceIn(1, 2)
                        ensureTrack(m.a, srcChannels, m.gen)
                        item.sampleRate = m.a
                    }
                    DATA -> {
                        val item = m.item!!
                        val t = track ?: continue
                        if (item.startFrame < 0) {
                            item.outputId = trackId
                            item.startFrame = written
                        }
                        val pcm = toPcm16(m.data!!)
                        envelope.push(pcm, chans)
                        write(t, pcm, m.gen)
                        maybePlay(t, item, force = false)
                    }
                    END -> {
                        val item = m.item!!
                        val t = track
                        if (item.startFrame < 0) {
                            item.outputId = trackId
                            item.startFrame = written
                        }
                        item.endFrame = written
                        if (t != null) maybePlay(t, item, force = true)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "salida de voz", e)
            }
        }
    }

    private fun ensureTrack(sampleRate: Int, channels: Int, g: Int) {
        val t = track
        if (t != null && rate == sampleRate && chans == channels) return
        // Otro formato (cambio de voz): que acabe lo que suena y otra pista.
        if (t != null && playing) {
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (gen == g && !quit && head(t) < written && SystemClock.elapsedRealtime() < deadline) Thread.sleep(10)
        }
        releaseTrack()
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // Medio segundo de búfer: de sobra para el margen inicial; la latencia la mide el reloj.
        val size = max(min, sampleRate * 2 * channels / 2)
        val nt = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        rate = sampleRate
        chans = channels
        envelope.reset(sampleRate)
        written = 0L
        pausedAt = 0L
        playing = false
        tsValid = false
        trackId++
        track = nt
    }

    private fun releaseTrack() {
        val t = track ?: return
        track = null
        playing = false
        written = 0L
        pausedAt = 0L
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }

    private fun toPcm16(src: ByteArray): ByteArray = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> ByteArray(src.size * 2).also { out ->
            for (i in src.indices) {
                val s = ((src[i].toInt() and 0xFF) - 128) shl 8
                out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte()
            }
        }
        AudioFormat.ENCODING_PCM_FLOAT -> ByteArray(src.size / 2).also { out ->
            for (i in 0 until src.size / 4) {
                val o = 4 * i
                val bits = (src[o].toInt() and 0xFF) or ((src[o + 1].toInt() and 0xFF) shl 8) or
                    ((src[o + 2].toInt() and 0xFF) shl 16) or ((src[o + 3].toInt() and 0xFF) shl 24)
                val s = (Float.fromBits(bits).coerceIn(-1f, 1f) * 32767f).toInt()
                out[2 * i] = s.toByte(); out[2 * i + 1] = (s shr 8).toByte()
            }
        }
        else -> src
    }

    /** Escribe entero sin bloquear la pista (así [stop] corta enseguida). */
    private fun write(t: AudioTrack, bytes: ByteArray, g: Int) {
        val frameBytes = 2 * chans
        var off = 0
        val len = bytes.size - bytes.size % frameBytes
        while (off < len && gen == g && !quit) {
            val n = t.write(bytes, off, len - off, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) {
                Log.w(TAG, "AudioTrack.write = $n")
                return
            }
            off += n
            written += n / frameBytes
            if (n == 0) {
                // Búfer lleno: si aún no suena (margen mayor que el búfer), que empiece (salvo en pausa).
                if (!playing && !held) start(t)
                Thread.sleep(5)
            }
        }
    }

    private fun maybePlay(t: AudioTrack, item: LipSync.Item, force: Boolean) {
        idleSince = -1L
        if (playing) return
        val buffered = written - pausedAt
        if (held) {
            // En pausa: arranca "sonando" para que resume() la ponga en marcha.
            playing = true
            return
        }
        if (force || buffered >= config.lookaheadMs / 1000f * rate) {
            start(t)
            onPlay?.invoke(item)
        }
    }

    private fun start(t: AudioTrack) {
        if (playing) return
        runCatching { t.play() }
        playing = true
        tsValid = false
    }

    /** Sin nada que escribir: tras 1,5 s con todo ya sonado, la pista se pausa (no deja el audio despierto). */
    private fun idle() {
        val t = track ?: return
        if (!playing || held) return
        if (head(t) < written) { idleSince = -1L; return }
        val now = SystemClock.elapsedRealtime()
        if (idleSince < 0) { idleSince = now; return }
        if (now - idleSince > 1500) {
            // Atascos (el motor no llegó a tiempo): cada uno es un hueco audible. Para la QA de voces.
            runCatching { Log.i(TAG, "pista en pausa: ${written * 1000 / rate} ms escritos, underruns=${t.underrunCount}") }
            runCatching { t.pause() }
            playing = false
            pausedAt = written
            idleSince = -1L
        }
    }

    private fun head(t: AudioTrack): Long = try {
        t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    } catch (_: IllegalStateException) {
        0L
    }

    /* ── reloj (hilo de render) ── */

    private val ts = AudioTimestamp()
    @Volatile private var tsValid = false
    private var tsFrame = 0L
    private var tsNanos = 0L
    private var lastPoll = 0L
    private var cacheNow = Long.MIN_VALUE
    private var cachePos = Double.NaN

    /** Trama oída en [now] (NaN sin pista). */
    fun position(now: Long): Double {
        if (now == cacheNow) return cachePos
        cacheNow = now
        cachePos = computePosition(now)
        return cachePos
    }

    private fun computePosition(now: Long): Double {
        val t = track ?: return Double.NaN
        val sr = rate
        if (sr <= 0) return Double.NaN
        val w = written
        if (held) return if (heldPos.isNaN()) pausedAt.toDouble() - offsetFrames(sr) else heldPos
        if (!playing) return pausedAt.toDouble() - offsetFrames(sr)
        if (now - lastPoll > POLL_NS || !tsValid) {
            lastPoll = now
            val ok = try {
                t.getTimestamp(ts)
            } catch (_: IllegalStateException) {
                false
            }
            if (ok && ts.nanoTime > 0) {
                tsValid = true
                tsFrame = ts.framePosition
                tsNanos = ts.nanoTime
            }
        }
        val pos = if (tsValid) {
            tsFrame + (now - tsNanos) * sr / 1e9
        } else {
            head(t) - config.fallbackLatencyMs / 1000.0 * sr
        }
        return pos.coerceAtMost(w.toDouble()) - offsetFrames(sr)
    }

    private fun offsetFrames(sr: Int) = config.audioOffsetMs / 1000.0 * sr

    override fun seconds(item: LipSync.Item, nowNanos: Long): Double {
        val sr = item.sampleRate
        if (sr <= 0) return Double.NaN
        if (item.startNanos >= 0) {
            // Repuesto: el motor reproduce; reloj de pared desde que empezó a sonar.
            return (nowNanos - item.startNanos) / 1e9 - (config.fallbackLatencyMs + config.audioOffsetMs) / 1000.0
        }
        val start = item.startFrame
        if (start < 0 || item.outputId != trackId || track == null) return Double.NaN
        val pos = position(nowNanos)
        if (pos.isNaN()) return Double.NaN
        return (pos - start) / sr
    }

    /** ¿Ha terminado de sonar [item]? (fin conocido y ya oído). */
    fun finished(item: LipSync.Item, nowNanos: Long): Boolean {
        if (item.startNanos >= 0) return item.spokenDone
        val end = item.endFrame
        if (end < 0) return false
        if (item.outputId != trackId || track == null) return true
        val sr = item.sampleRate
        if (sr <= 0 || end <= item.startFrame) return true
        val t = seconds(item, nowNanos)
        // Sin el ajuste manual: con la pista en pausa o al final, la posición no pasa de lo escrito.
        return t.isNaN() || (t + config.audioOffsetMs / 1000.0) * sr >= end - item.startFrame - sr / 50
    }

    private companion object {
        const val TAG = "MashaVoice"
        const val BEGIN = 1
        const val DATA = 2
        const val END = 3
        const val FLUSH = 4
        const val POLL_NS = 100_000_000L
    }
}
