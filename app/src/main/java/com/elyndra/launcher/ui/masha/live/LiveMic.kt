package com.elyndra.launcher.ui.masha.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * El micrófono siempre abierto de la conversación en vivo (solo mientras está
 * encendida y la pantalla de Lucy se ve).
 *
 * Graba con la fuente de reconocimiento de voz (VOICE_RECOGNITION: sin modo
 * llamada, la voz de Lucy y la música siguen con calidad normal), con el
 * cancelador de eco y el supresor de ruido del sistema si este móvil los deja
 * usar fuera de una llamada. En su propio hilo, por cada tramo de 16 ms:
 * - lo guarda en un anillo de [PREROLL_MS] (las primeras palabras del usuario);
 * - [SileroVad] (o la energía, sin ONNX Runtime) da la probabilidad de voz;
 * - [LiveEar] la cruza con lo que suena por la voz de Lucy ([reference]) y
 *   decide si el usuario empieza o termina de hablar → [onSignal], en el hilo
 *   principal;
 * - si hay un reconocedor escuchando ([openStream]), le pasa el audio por un
 *   pipe (primero el anillo);
 * - cada segundo, un resumen al log ([MicMeter]: nivel, probabilidad de voz,
 *   eco esperado, tramos de voz y de silencio digital).
 *
 * [stop] suelta el micrófono antes de volver (para el reconocedor de siempre o
 * al salir). El acoplamiento aprendido del eco se conserva entre arranques.
 */
class LiveMic(
    context: Context,
    /** Nivel (dBFS) de lo que suena ahora por la voz de Lucy; lo llama el hilo del micrófono. */
    private val reference: () -> Float,
    private val onSignal: (SpeechSegmenter.Signal) -> Unit,
    /** Nivel del micrófono 0..1 (para el holograma), en el hilo principal, ~15 veces por segundo. */
    private val onLevel: (Float) -> Unit,
) : LiveVoice.Mic {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var couplingDb = EchoGate.DEFAULT_COUPLING_DB

    /** El oído del arranque en curso (el hilo del micrófono lo usa; [speechMs] lo lee el principal). */
    @Volatile private var ear: LiveEar? = null

    @Volatile private var pipe: PcmPipe? = null

    /** Estadística de la sesión, para medir el barge-in en el móvil (ver [report]). */
    @Volatile var stats = Stats()
        private set

    data class Stats(
        var framesWithLucy: Int = 0,
        var echoRejected: Int = 0,
        var startsOverLucy: Int = 0,
        var aec: Boolean = false,
        var ns: Boolean = false,
        var silero: Boolean = false,
    )

    val active: Boolean get() = running

    /** Voz acumulada del usuario en el turno en curso (ms). */
    override val speechMs: Int get() = ear?.segmenter?.speechMs ?: 0

    /** Silencio dentro del turno en curso (ms). */
    override val silenceMs: Int get() = ear?.segmenter?.silence ?: 0

    /** Hay un turno del usuario abierto (habló y aún no lleva el silencio de fin de turno). */
    override val inTurn: Boolean get() = ear?.segmenter?.inTurn == true

    /** Silencio digital seguido (ceros): Android le está dando el micrófono a otro. */
    @Volatile override var silencedMs = 0
        private set

    /** Audio en la cola del pipe que el reconocedor aún no ha leído. */
    override val backlogMs: Int get() = pipe?.backlogMs ?: 0

    override fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "lucy-live-mic").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    /** Suelta el micrófono ya (espera al hilo). */
    override fun stop() {
        running = false
        closeStream()
        // Si estaba esperando para reabrir, que no siga durmiendo (y no haya dos hilos al volver a empezar).
        thread?.interrupt()
        thread?.join(500)
        thread = null
    }

    /** El turno en curso se da por cerrado por otra vía (el reconocedor terminó). */
    override fun clearTurn() {
        ear?.segmenter?.clear()
    }

    /**
     * Un pipe nuevo para el reconocedor: devuelve el extremo de lectura (para
     * MashaEars.startStream). Lo primero que recibe es el último segundo del anillo.
     */
    fun openStream(): ParcelFileDescriptor? {
        closeStream()
        val (read, write) = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull()?.let { it[0] to it[1] } ?: return null
        pipe = PcmPipe(write, preroll = true)
        return read
    }

    /** Cierra el pipe: el reconocedor ve el final del audio y da su resultado. */
    override fun closeStream() {
        pipe?.close()
        pipe = null
    }

    override fun report(): String = stats.let {
        "frames con Lucy=${it.framesWithLucy}, eco descartado=${it.echoRejected}, voz sobre Lucy=${it.startsOverLucy}, " +
            "AEC=${it.aec}, NS=${it.ns}, silero=${it.silero}, acoplamiento=${"%.1f".format(couplingDb)} dB"
    }

    /**
     * Mientras siga encendido: si el micrófono no se puede abrir o deja de dar
     * audio (otro proceso se lo queda, error del driver), se suelta y se
     * reabre con una espera creciente (250 ms … 4 s). La sesión no termina.
     */
    private fun loop() {
        var attempt = 0
        while (running) {
            val healthy = recordOnce()
            if (!running) break
            if (healthy) attempt = 0
            val wait = (250L shl attempt.coerceAtMost(4)).coerceAtMost(4_000L)
            attempt++
            Log.w(TAG, "mic lost: reopening in ${wait}ms (attempt $attempt)")
            try {
                Thread.sleep(wait)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /** Un arranque del micrófono; true si llegó a dar audio (para reiniciar la espera). */
    @SuppressLint("MissingPermission") // LiveVoice solo arranca con el permiso concedido
    private fun recordOnce(): Boolean {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 2 * 8))
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "mic unavailable (AudioRecord not initialized)")
            record?.release()
            return false
        }
        val aec = runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
        val silero = runCatching { app.assets.open(SileroVad.ASSET).use { it.readBytes() } }.getOrNull()?.let { SileroVad.load(it) }
        val energy = VoiceActivity()
        val ear = LiveEar(EchoGate(initialCouplingDb = couplingDb)).also { this.ear = it }
        val meter = MicMeter(frameMs = FRAME * 1000 / SAMPLE_RATE)
        val vad = if (silero != null) "silero" else "energy"
        silencedMs = 0
        stats = Stats(aec = aec?.enabled == true, ns = ns?.enabled == true, silero = silero != null)
        Log.i(TAG, "micrófono abierto: AEC=${stats.aec} NS=${stats.ns} silero=${stats.silero}")
        val ring = PcmRing(SAMPLE_RATE * PREROLL_MS / 1000)
        val frame = ShortArray(FRAME)
        val window = ShortArray(SileroVad.WINDOW)
        var prob = 0f
        var n = 0
        var level = 0f
        var frames = 0
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "mic refused to start recording")
                return false
            }
            while (running) {
                var got = 0
                while (got < FRAME && running) {
                    val r = record.read(frame, got, FRAME - got)
                    // Negativo: ERROR_DEAD_OBJECT, ERROR_INVALID_OPERATION…; se reabre (antes giraba en vacío).
                    if (r < 0) throw IllegalStateException("AudioRecord.read error $r")
                    if (r == 0) break
                    got += r
                }
                if (got < FRAME) continue
                frames++
                ring.write(frame)
                pipe?.let { p ->
                    if (p.preroll) p.send(ring.last(SAMPLE_RATE * STREAM_PREROLL_MS / 1000)) else p.send(frame.copyOf())
                    p.preroll = false
                }
                val micDb = VoiceActivity.levelDb(frame)
                silencedMs = if (micDb <= MicMeter.SILENT_DB) silencedMs + FRAME * 1000 / SAMPLE_RATE else 0
                // Silero mira ventanas de 32 ms: cada dos tramos, los dos últimos.
                if (++n % 2 == 0) {
                    prob = if (silero != null) {
                        System.arraycopy(ring.last(SileroVad.WINDOW), 0, window, 0, SileroVad.WINDOW)
                        runCatching { silero.prob(window) }.getOrDefault(energy.prob(micDb))
                    } else {
                        energy.prob(micDb)
                    }
                }
                val refDb = runCatching { reference() }.getOrDefault(EchoGate.SILENT)
                val signal = ear.accept(micDb, refDb, prob)
                val s = stats
                if (refDb > EchoGate.REF_FLOOR) {
                    s.framesWithLucy++
                    if (ear.echoRejected) s.echoRejected++
                    if (signal == SpeechSegmenter.Signal.Started) s.startsOverLucy++
                }
                couplingDb = ear.gate.couplingDb
                meter.add(micDb, prob, refDb, ear.gate.predictedDb, ear.voiced, ear.echoRejected)?.let { Log.d(TAG, it.line(vad)) }
                if (signal != null) main.post { if (running) onSignal(signal) }
                level = level * 0.6f + ((micDb + 60f) / 45f).coerceIn(0f, 1f) * 0.4f
                if (n % 4 == 0) {
                    val l = if (ear.voiced) level else level * 0.3f
                    main.post { onLevel(l) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mic stopped with an error", e)
        } finally {
            runCatching { record.stop() }
            aec?.release()
            ns?.release()
            record.release()
            silero?.close()
            this.ear = null
            silencedMs = 0
            Log.i(TAG, "micrófono suelto: ${report()}")
            main.post { onLevel(0f) }
        }
        return frames > 0
    }

    /**
     * El audio hacia el reconocedor, por un pipe, desde un hilo propio: si el
     * reconocedor se atasca, el micrófono no (lo que no cabe se tira).
     */
    private class PcmPipe(fd: ParcelFileDescriptor, @Volatile var preroll: Boolean) {
        private val queue = LinkedBlockingQueue<ShortArray>(QUEUE_MAX)
        @Volatile private var open = true
        @Volatile private var sentSamples = 0L
        @Volatile private var dropped = 0

        /** Lo que espera en la cola (si el reconocedor no lee, crece: el pipe del sistema ya va lleno). */
        val backlogMs: Int get() = queue.size * FRAME * 1000 / SAMPLE_RATE
        private val out = ParcelFileDescriptor.AutoCloseOutputStream(fd)
        private val writer = Thread({ drain(out) }, "lucy-live-pipe").apply {
            isDaemon = true
            start()
        }

        fun send(samples: ShortArray) {
            if (open && !queue.offer(samples)) dropped++
        }

        fun close() {
            if (!open) return
            Log.i(TAG, "pipe closed: written=${sentSamples * 1000 / SAMPLE_RATE}ms backlog=${backlogMs}ms dropped=$dropped")
            open = false
            queue.offer(END)
        }

        private fun drain(out: FileOutputStream) {
            try {
                while (true) {
                    val s = queue.poll(1, TimeUnit.SECONDS) ?: if (open) continue else break
                    if (s === END) break
                    val bytes = ByteArray(s.size * 2)
                    for (i in s.indices) {
                        bytes[2 * i] = s[i].toByte()
                        bytes[2 * i + 1] = (s[i].toInt() shr 8).toByte()
                    }
                    out.write(bytes)
                    sentSamples += s.size
                }
            } catch (_: Exception) {
                // El reconocedor cerró su extremo: nada más que mandar.
            } finally {
                runCatching { out.close() }
            }
        }

        private companion object {
            val END = ShortArray(0)
            const val QUEUE_MAX = 400
        }
    }

    private companion object {
        const val TAG = "LucyLive"
        const val SAMPLE_RATE = 16_000
        const val FRAME = 256
        const val PREROLL_MS = 1_200
        const val STREAM_PREROLL_MS = 1_000
    }
}
