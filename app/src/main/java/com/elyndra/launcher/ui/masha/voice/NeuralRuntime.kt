package com.elyndra.launcher.ui.masha.voice

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.elyndra.launcher.BuildConfig
import com.elyndra.launcher.ui.masha.lipsync.CmuDict
import com.elyndra.launcher.ui.masha.voice.supertonic.SupertonicModel

/**
 * El modelo de la voz natural, uno por proceso: cargarlo cuesta ~1–2 s y ~200 MB,
 * así que no se repite cada vez que se entra en la pantalla de Masha. Se libera
 * [LINGER_MS] después de que la última voz lo suelte (o al momento si el sistema
 * pide memoria).
 *
 * También decide si este móvil puede con ella: 64 bits, memoria suficiente y un
 * factor de tiempo real medido aquí mismo (la síntesis debe ir bastante más rápida
 * que lo que dura el audio: la salida no tiene colchón para los atascos).
 */
object NeuralRuntime {

    private const val TAG = "MashaVoice"
    private const val LINGER_MS = 90_000L

    /** Por encima, la voz natural no llega a tiempo y se usa la del sistema. */
    const val MAX_RTF = RtfGate.MAX_RTF

    private val lock = Any()
    private var model: SupertonicModel? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())
    private val closer = Runnable { synchronized(lock) { if (users == 0) closeLocked() } }

    @Volatile private var dict: CmuDict? = null
    @Volatile private var dictTried = false

    /** Hilos de inferencia: la mitad de los núcleos (el holograma también necesita CPU), entre 2 y 4. */
    val threads: Int get() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

    /** Carga (si hace falta) y devuelve el modelo. Hilo del motor, nunca el principal. */
    fun acquire(ctx: Context): SupertonicModel? = synchronized(lock) {
        main.removeCallbacks(closer)
        users++
        model?.let { return it }
        if (!hardwareOk(ctx)) return null
        val dir = VoicePack.dir(ctx) ?: return null
        val t0 = SystemClock.elapsedRealtime()
        model = runCatching { SupertonicModel(dir, threads) }
            .onFailure { Log.w(TAG, "no se pudo cargar la voz natural", it) }
            .getOrNull()
        model?.let { Log.i(TAG, "voz natural cargada en ${SystemClock.elapsedRealtime() - t0} ms ($threads hilos)") }
        model
    }

    fun release() = synchronized(lock) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) main.postDelayed(closer, LINGER_MS)
    }

    /** Memoria justa (onTrimMemory) o modelo borrado: fuera ya si nadie lo usa. */
    fun closeNow() = synchronized(lock) {
        main.removeCallbacks(closer)
        if (users == 0) closeLocked()
    }

    private fun closeLocked() {
        model?.let { runCatching { it.close() } }
        model = null
        // La próxima carga es otra sesión de medida.
        synchronized(sessionRtf) { sessionRtf.clear(); sinceLoad = 0 }
        sessionStored = false
    }

    /** CMUdict para distinguir títulos en inglés dentro de frases en otro idioma. */
    fun englishDict(ctx: Context): CmuDict? {
        if (!dictTried) synchronized(this) {
            if (!dictTried) {
                dict = runCatching { ctx.assets.open("lipsync/cmudict.txt").use { CmuDict.load(it) } }.getOrNull()
                dictTried = true
            }
        }
        return dict
    }

    /* ── ¿puede este móvil? ── */

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("masha_voice", Context.MODE_PRIVATE)

    /**
     * Requisitos fijos: proceso de 64 bits (el APK solo trae ONNX Runtime de 64 bits) y un
     * móvil de "3 GB" o más (el sistema declara ~2,7 GiB en esos; el modelo ocupa ~215 MB).
     */
    fun hardwareOk(ctx: Context): Boolean {
        if (!android.os.Process.is64Bit()) return false
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return true
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return !am.isLowRamDevice && mi.totalMem >= 2_560L * 1024 * 1024
    }

    /** RTF de las frases de esta sesión (ya sin las primeras tras cargar). */
    private val sessionRtf = ArrayList<Float>()

    /** Factor de tiempo real que se da por bueno en este móvil (NaN = sin medir, caducado o de otra versión). */
    fun measuredRtf(ctx: Context): Float = RtfGate.estimate(storedSessions(ctx))

    private fun storedSessions(ctx: Context): List<Float> {
        val p = prefs(ctx)
        if (p.getInt("rtf.version", -1) != BuildConfig.VERSION_CODE) return emptyList()
        if (System.currentTimeMillis() - p.getLong("rtf.time", 0L) > RtfGate.EXPIRE_MS) return emptyList()
        return p.getString("rtf.sessions", "").orEmpty().split(',').mapNotNull { it.toFloatOrNull() }
    }

    /**
     * Una frase medida (hilo del motor). Un pico de carga (otra app, la recreación de la
     * actividad) no debe apagar la voz: la sesión se resume con un percentil bajo y solo se
     * da por lento el móvil si lo es en varias sesiones (ver [RtfGate]).
     */
    fun recordRtf(ctx: Context, rtf: Float) {
        if (rtf.isNaN() || rtf <= 0f) return
        val est = synchronized(sessionRtf) {
            // Las primeras frases tras cargar van más lentas (cachés frías, JIT): no cuentan.
            if (++sinceLoad <= RtfGate.SKIP_AFTER_LOAD) return
            sessionRtf += rtf
            RtfGate.session(sessionRtf)
        } ?: return
        val old = storedSessions(ctx)
        // La sesión en curso sustituye a su propia entrada (la primera de la lista).
        val sessions = (listOf(est) + (if (sessionStored) old.drop(1) else old)).take(RtfGate.KEEP)
        sessionStored = true
        prefs(ctx).edit()
            .putString("rtf.sessions", sessions.joinToString(","))
            .putLong("rtf.time", System.currentTimeMillis())
            .putInt("rtf.version", BuildConfig.VERSION_CODE)
            .apply()
    }

    @Volatile private var sessionStored = false
    private var sinceLoad = 0

    /** La voz natural resultó lenta en varias sesiones: habla la del sistema. */
    fun tooSlow(ctx: Context): Boolean = RtfGate.tooSlow(storedSessions(ctx))

    /** "Reintentar" en Ajustes: se olvida la medida y la voz natural vuelve a probarse. */
    fun resetRtf(ctx: Context) {
        synchronized(sessionRtf) { sessionRtf.clear() }
        sessionStored = false
        prefs(ctx).edit().remove("rtf.sessions").remove("rtf.time").apply()
    }

    /** ¿Usar la voz natural? Instalada, con hardware suficiente y sin haber resultado lenta. */
    fun usable(ctx: Context): Boolean {
        if (VoicePack.dir(ctx) == null || !hardwareOk(ctx)) return false
        return !RtfGate.tooSlow(storedSessions(ctx))
    }
}

/**
 * Decide si la voz natural va demasiado lenta en este móvil (puro: se prueba en la JVM).
 *
 * - Por sesión (desde que se carga el modelo): el percentil 30 de las frases medidas, con
 *   al menos [MIN_SAMPLES]; así un pico puntual no cuenta.
 * - Se guardan las [KEEP] últimas sesiones; "lento" = al menos dos sesiones y la mediana
 *   por encima de [MAX_RTF]. La medida caduca a los [EXPIRE_MS] y con cada versión de la app.
 */
object RtfGate {
    const val MAX_RTF = 0.8f
    const val MIN_SAMPLES = 3
    const val SKIP_AFTER_LOAD = 2
    const val KEEP = 3
    const val EXPIRE_MS = 14L * 24 * 3600 * 1000

    fun session(samples: List<Float>): Float? {
        if (samples.size < MIN_SAMPLES) return null
        val s = samples.sorted()
        return s[((s.size - 1) * 0.3f).toInt()]
    }

    fun estimate(sessions: List<Float>): Float {
        if (sessions.isEmpty()) return Float.NaN
        val s = sessions.sorted()
        return s[(s.size - 1) / 2]
    }

    fun tooSlow(sessions: List<Float>): Boolean = sessions.size >= 2 && estimate(sessions) > MAX_RTF
}
