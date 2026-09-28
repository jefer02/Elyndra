package com.elyndra.launcher.ui.masha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.elyndra.launcher.ui.masha.lipsync.Vis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Herramienta de QA (solo builds debug): hace hablar a Masha sin pasar por el chat.
 *
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_SAY --es text "Hola. ¿Qué tal?" [--ei cps 40]
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_STOP
 *
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_POSE --es vis AA --ef w 0.6 --ef jaw 0.45   (fija la boca)
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_POSE                                          (la suelta)
 *
 * `cps` > 0 simula el streaming del modelo (caracteres por segundo), como en una respuesta real;
 * 0 entrega el texto entero de golpe.
 */
@Composable
fun MashaDebugSay(voice: MashaVoice) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    DisposableEffect(voice) {
        var nextId = -1_000_000L
        var feeding: Job? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action == ACTION_POSE) {
                    if (intent.hasExtra("glitch")) {
                        MashaDebugPose.glitch = intent.getBooleanExtra("glitch", false)
                        return
                    }
                    if (intent.hasExtra("trace")) {
                        MashaDebugPose.traceAll = intent.getBooleanExtra("trace", false)
                        return
                    }
                    MashaDebugPose.raw = intent.getStringExtra("morph")?.let { it to intent.getFloatExtra("w", 1f) }
                    if (MashaDebugPose.raw != null) return
                    MashaDebugPose.lips.fill(0f)
                    val vis = intent.getStringExtra("vis")
                    val jaw = intent.getFloatExtra("jaw", -1f)
                    if (vis == null && jaw < 0f) {
                        MashaDebugPose.jaw = null
                        return
                    }
                    if (vis == "ALL") MashaDebugPose.lips.fill(intent.getFloatExtra("w", 0.05f))
                    else vis?.let { name -> Vis.entries.firstOrNull { it.name == name }?.let { MashaDebugPose.lips[it.ordinal] = intent.getFloatExtra("w", 0.6f) } }
                    MashaDebugPose.wobble = intent.getBooleanExtra("wobble", false)
                    MashaDebugPose.jaw = jaw.coerceAtLeast(0f)
                    return
                }
                feeding?.cancel()
                if (intent.action == ACTION_STOP) {
                    voice.stop()
                    return
                }
                val text = intent.getStringExtra("text") ?: return
                val cps = intent.getIntExtra("cps", 60)
                val id = nextId--
                Log.i("MashaVoice", "DEBUG_SAY cps=$cps \"$text\"")
                feeding = scope.launch {
                    if (cps <= 0) {
                        voice.feed(id, text, final = true)
                    } else {
                        var n = 0
                        while (n < text.length) {
                            n = minOf(text.length, n + maxOf(1, cps / 20))
                            voice.feed(id, text.substring(0, n), final = false)
                            delay(50)
                        }
                        voice.feed(id, text, final = true)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_SAY)
            addAction(ACTION_STOP)
            addAction(ACTION_POSE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            feeding?.cancel()
            context.unregisterReceiver(receiver)
        }
    }
}

private const val ACTION_SAY = "com.elyndra.launcher.DEBUG_SAY"
private const val ACTION_STOP = "com.elyndra.launcher.DEBUG_STOP"
private const val ACTION_POSE = "com.elyndra.launcher.DEBUG_POSE"

/** Solo debug: boca fijada por adb (null = la del habla). La lee [HoloRig] en cada fotograma. */
internal object MashaDebugPose {
    val lips = FloatArray(Vis.COUNT)
    @Volatile var jaw: Float? = null
    /** Un morph crudo por nombre (`--es morph eyeBlinkLeft --ef w 1`), después del mezclador. */
    @Volatile var raw: Pair<String, Float>? = null
    /** Traza de la cara también sin hablar (`--ez trace true`): mide los fps en reposo. */
    @Volatile var traceAll = false
    /** La pose fija oscila (0..1 a ~1,6 Hz): mide el coste de cambiar pesos cada fotograma. */
    @Volatile var wobble = false
    /** Glitch del holograma permanente (`--ez glitch true`), para revisar el desgarro. */
    @Volatile var glitch = false
    /** Perfilado (DEBUG_PERF): sin atmósfera 2D / sin muelles. */
    @Volatile var noAtmo = false
    @Volatile var noSprings = false
    /** Hz de la atmósfera 2D para probar (0 = el de producción, 30). */
    @Volatile var atmoHz = 0
}
