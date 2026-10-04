package com.elyndra.launcher.ui.masha

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.elyndra.launcher.ui.masha.lipsync.Vis
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_POSE --ei load 8      (8 morphs más activos, invisibles: coste de GPU)
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_POSE --ef catchX -0.3 --ef catchY 0.2 --ef catchI 1.2   (brillo del ojo; catchI -1 = de fábrica)
 *
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_MOOD --es mood Playful    (vista previa de un ánimo)
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_MOOD --es mood Listening  (atención, como con el micrófono)
 *   adb shell am broadcast -a com.elyndra.launcher.DEBUG_MOOD --es mood auto       (vuelve al de la conversación)
 *
 * `cps` > 0 simula el streaming del modelo (caracteres por segundo), como en una respuesta real;
 * 0 entrega el texto entero de golpe. La vista previa de ánimo también está en la pantalla
 * ([MashaDebugMoodChip]).
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
                if (intent.action == ACTION_MOOD) {
                    MashaDebugPose.preview(intent.getStringExtra("mood"))
                    return
                }
                if (intent.action == ACTION_POSE) {
                    if (intent.hasExtra("load")) {
                        MashaDebugPose.load = intent.getIntExtra("load", 0).coerceAtLeast(0)
                        return
                    }
                    if (intent.hasExtra("catchX") || intent.hasExtra("catchI")) {
                        // Afinar el brillo del ojo en vivo: dirección (espacio de la vista) e intensidad.
                        // Lo que no se pase queda el de fábrica; catchI negativo vuelve a los de fábrica.
                        val i = intent.getFloatExtra("catchI", Float.NaN)
                        val reset = i < 0f
                        MashaDebugPose.catchX = if (reset) Float.NaN else intent.getFloatExtra("catchX", Float.NaN)
                        MashaDebugPose.catchY = if (reset) Float.NaN else intent.getFloatExtra("catchY", Float.NaN)
                        MashaDebugPose.catchI = if (reset) Float.NaN else i
                        return
                    }
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
            addAction(ACTION_MOOD)
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
            // La vista previa no sobrevive a la pantalla.
            MashaDebugPose.preview(null)
        }
    }
}

/**
 * Solo debug: chip que recorre los ánimos para verlos en la cara (Auto → Neutral → … →
 * Listening → Auto). Lo mismo que `DEBUG_MOOD` por adb.
 */
@Composable
fun MashaDebugMoodChip(modifier: Modifier = Modifier) {
    var index by remember { mutableIntStateOf(0) }
    val label = MashaDebugPose.PREVIEWS[index]
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC0A1A33))
            .border(1.dp, Color(0x8086D6FF), RoundedCornerShape(12.dp))
            .clickable {
                index = (index + 1) % MashaDebugPose.PREVIEWS.size
                MashaDebugPose.preview(MashaDebugPose.PREVIEWS[index])
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText("FX · $label", style = TextStyle(color = Color(0xFFBFEAFF), fontSize = 11.sp))
    }
}

private const val ACTION_SAY = "com.elyndra.launcher.DEBUG_SAY"
private const val ACTION_STOP = "com.elyndra.launcher.DEBUG_STOP"
private const val ACTION_POSE = "com.elyndra.launcher.DEBUG_POSE"
private const val ACTION_MOOD = "com.elyndra.launcher.DEBUG_MOOD"

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
    /** Morphs apagados que se encienden a 0,02 (invisibles) para medir el coste de GPU de cada morph activo. */
    @Volatile var load = 0
    /** Brillo del ojo (`DEBUG_POSE --ef catchX -0.3 --ef catchY 0.2 --ef catchI 1.3`); NaN = el de fábrica. */
    @Volatile var catchX = Float.NaN
    @Volatile var catchY = Float.NaN
    @Volatile var catchI = Float.NaN
    /** Vista previa de un ánimo (null = el de la conversación) y de la atención al escuchar. */
    @Volatile var mood: MashaMood? = null
    @Volatile var listen = false

    /** Lo que recorre el chip: "Auto", cada ánimo y "Listening". */
    val PREVIEWS: List<String> = listOf("Auto") + MashaMood.entries.map { it.name } + "Listening"

    /** Pone la vista previa por nombre ("Playful", "Listening"…); null, "auto" o un nombre desconocido la quitan. */
    fun preview(name: String?) {
        listen = name.equals("Listening", ignoreCase = true)
        mood = MashaMood.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
