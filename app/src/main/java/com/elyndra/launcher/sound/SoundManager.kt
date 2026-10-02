package com.elyndra.launcher.sound

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Los sonidos de la interfaz, en un solo sitio: `play(evento)`.
 *
 *  · SoundPool con `USAGE_GAME` / `CONTENT_TYPE_SONIFICATION`: suena con el
 *    volumen multimedia, que es el que se toca en una tableta, y **nunca**
 *    pide el foco de audio, así que la música de fondo no se para.
 *  · Callado con el teléfono en silencio o vibración, mientras Masha habla o
 *    escucha, con la app en segundo plano y justo después de lanzar un juego.
 *  · Los sonidos se cargan fuera del hilo principal y la SoundPool se libera
 *    al apagar los sonidos.
 *
 * Solo se llama desde el hilo principal (como toda la interfaz).
 */
class SoundManager(
    private val context: Context,
    val settings: SoundSettings,
    private val scope: CoroutineScope,
    private val importer: CustomSoundImporter,
) {

    private val scheduler = SoundScheduler()
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var pool: SoundPool? = null
    /** Id de SoundPool por evento (0 = sin cargar), indexado por ordinal. */
    private val ids = IntArray(UiSound.entries.size)
    private var loadJob: Job? = null

    private var foreground = true
    private var mashaBusy = false
    private var silentRinger = false
    private var gain = 0f

    private val flush = Runnable { flushPending() }

    private val ringer = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = readRinger()
    }

    init {
        readRinger()
        ContextCompat.registerReceiver(
            context,
            ringer,
            IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        reload()
    }

    /* ── estado de la app ─────────────────────────────────────── */

    /** La Activity está delante. Detrás no suena nada (ni lo que estaba en espera). */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) scheduler.unmute() else scheduler.muteFor(SystemClock.uptimeMillis(), 0)
    }

    /** Masha habla o el micrófono graba: silencio. */
    fun setMashaBusy(value: Boolean) {
        mashaBusy = value
    }

    private fun readRinger() {
        silentRinger = audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }

    private val quiet: Boolean get() = !foreground || mashaBusy || silentRinger || gain <= 0f || pool == null

    /* ── reproducir ───────────────────────────────────────────── */

    fun play(sound: UiSound) {
        if (quiet) return
        if (sound == UiSound.Navigate && !settings.navigation) return
        val now = SystemClock.uptimeMillis()
        when (scheduler.offer(sound, now)) {
            SoundScheduler.PLAY -> fire(sound, scheduler.rate)
            SoundScheduler.DEFER -> {
                main.removeCallbacks(flush)
                main.postDelayed(flush, SoundScheduler.DEFER_MS)
            }
        }
    }

    private fun flushPending() {
        val sound = scheduler.due(SystemClock.uptimeMillis()) ?: return
        if (!quiet) fire(sound, 1f)
    }

    /** Suena [sound] ya, sin mirar los ajustes de navegación ni el reloj (vista previa). */
    fun preview(sound: UiSound) {
        if (silentRinger || pool == null) return
        fire(sound, 1f, previewGain())
    }

    private fun fire(sound: UiSound, rate: Float, volume: Float = gain) {
        val id = ids[sound.ordinal]
        if (id != 0) pool?.play(id, volume, volume, sound.priority, 0, rate)
    }

    private fun previewGain(): Float = if (gain > 0f) gain else gainFor(SoundSettings.DEFAULT_VOLUME)

    /* ── carga ────────────────────────────────────────────────── */

    /**
     * Vuelve a leer los ajustes y carga lo que toque: el paquete elegido y,
     * encima, los sonidos propios. Sin sonidos (apagados o "Sin sonido") y
     * sin sonidos propios, la SoundPool se libera.
     */
    fun reload() {
        gain = if (settings.enabled) gainFor(settings.volume) else 0f
        val pack = settings.pack
        val customs = UiSound.entries.associateWith { settings.custom(it) }
        val needed = settings.enabled && (pack != SoundPack.Off || customs.values.any { it != null })
        loadJob?.cancel()
        release()
        if (!needed) return
        val soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        pool = soundPool
        loadJob = scope.launch {
            val loaded = withContext(Dispatchers.IO) { UiSound.entries.map { it to load(soundPool, pack, it, customs[it]) } }
            withContext(Dispatchers.Main) {
                if (pool === soundPool) loaded.forEach { (sound, id) -> ids[sound.ordinal] = id }
            }
        }
    }

    /** Recarga solo el volumen (el deslizador no tiene por qué recargar los sonidos). */
    fun updateVolume() {
        gain = if (settings.enabled) gainFor(settings.volume) else 0f
    }

    @SuppressLint("DiscouragedApi") // los nombres salen del paquete: `ui_<paquete>_<evento>`
    private fun load(soundPool: SoundPool, pack: SoundPack, sound: UiSound, custom: String?): Int {
        custom?.let { name ->
            val file = importer.fileOf(name)
            if (file.exists()) {
                val id = runCatching { soundPool.load(file.path, 1) }.getOrDefault(0)
                if (id != 0) return id
            }
        }
        val raw = pack.rawName(sound) ?: return 0
        val res = context.resources.getIdentifier(raw, "raw", context.packageName)
        if (res == 0) {
            Log.w(TAG, "Falta el sonido $raw")
            return 0
        }
        return runCatching { soundPool.load(context, res, 1) }.getOrDefault(0)
    }

    private fun release() {
        main.removeCallbacks(flush)
        pool?.release()
        pool = null
        ids.fill(0)
    }

    companion object {
        private const val TAG = "SoundManager"
        private const val MAX_STREAMS = 4

        /** Volumen 0…100 → ganancia, con curva: la mitad del deslizador suena a la mitad. */
        fun gainFor(volume: Int): Float {
            val v = volume.coerceIn(0, 100) / 100f
            return v * kotlin.math.sqrt(v)
        }
    }
}
