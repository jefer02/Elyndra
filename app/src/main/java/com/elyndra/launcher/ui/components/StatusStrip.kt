package com.elyndra.launcher.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.elyndra.launcher.R
import com.elyndra.launcher.core.device.BatteryInfo
import com.elyndra.launcher.core.device.StatusFormat
import com.elyndra.launcher.core.device.StatusMode
import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.ui.theme.darkGlass
import java.util.Calendar

/* ─────────────────────────────────────────────────────────────
   Píldora de estado: hora y batería, en cristal oscuro, a la
   altura de la barra del hero.

   Su estado vive aquí dentro y en nada más: el paso del minuto o
   un cambio de batería repintan solo la píldora, nunca el carrusel
   ni el ViewModel. Los avisos del sistema se escuchan solo mientras
   la pantalla está a la vista (ON_START…ON_STOP):
     · la hora, con ACTION_TIME_TICK (llega al cambiar de minuto) y
       los cambios de hora y de zona horaria;
     · la batería, con ACTION_BATTERY_CHANGED, que es "pegajoso": al
       registrarse ya devuelve el último valor.
   ───────────────────────────────────────────────────────────── */

/** Texto claro sobre el cristal oscuro; los estados, en sus variantes para fondo oscuro. */
private val TEXT = Color.White
private val LOW = Color(BrandTokens.DARK.error)
private val CHARGING = Color(BrandTokens.DARK.success)

@Composable
fun StatusStrip(mode: StatusMode, modifier: Modifier = Modifier) {
    val time = if (mode.showsTime) rememberClockText() else null
    val battery = if (mode.showsBattery) rememberBattery() else null

    val timeText = time?.value
    val info = battery?.value
    val a11y = buildString {
        if (timeText != null) append(stringResource(R.string.status_a11y_time, timeText))
        if (info != null) {
            if (isNotEmpty()) append(". ")
            append(stringResource(R.string.status_a11y_battery, info.percent))
            if (info.charging) append(stringResource(R.string.status_a11y_charging))
            if (info.low) append(stringResource(R.string.status_a11y_low))
        }
    }
    if (timeText == null && info == null) return

    Row(
        modifier
            .height(HeroBarHeight)
            .darkGlass(RoundedCornerShape(12.dp))
            .clearAndSetSemantics { contentDescription = a11y }
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (timeText != null) {
            ElyText(timeText, size = 11f, weight = FontWeight.SemiBold, color = TEXT, maxLines = 1)
        }
        if (info != null) {
            if (timeText != null) Box(Modifier.size(3.dp).drawBehind { drawCircle(Color.White.copy(alpha = 0.5f)) })
            val tone = when {
                info.low -> LOW
                info.charging -> CHARGING
                else -> TEXT
            }
            BatteryGlyph(info, tone)
            ElyText("${info.percent}%", size = 11f, weight = FontWeight.SemiBold, color = tone, maxLines = 1)
        }
    }
}

/** La pila: contorno, borne y el nivel dentro; con el rayo encima si carga. */
@Composable
private fun BatteryGlyph(info: BatteryInfo, tone: Color) {
    Canvas(Modifier.size(width = 20.dp, height = 11.dp)) {
        val stroke = 1.3.dp.toPx()
        val nub = 2.dp.toPx()
        val body = Size(size.width - nub - stroke, size.height - stroke)
        val r = CornerRadius(2.5.dp.toPx())
        drawRoundRect(tone.copy(alpha = 0.9f), topLeft = Offset(stroke / 2, stroke / 2), size = body, cornerRadius = r, style = Stroke(stroke))
        drawRoundRect(
            tone.copy(alpha = 0.9f),
            topLeft = Offset(body.width + stroke, size.height * 0.32f),
            size = Size(nub, size.height * 0.36f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        val inset = stroke + 1.2.dp.toPx()
        val fullW = body.width - inset * 2 + stroke
        val w = (fullW * info.percent / 100f).coerceAtLeast(if (info.percent > 0) 1.5.dp.toPx() else 0f)
        drawRoundRect(
            tone,
            topLeft = Offset(inset, inset),
            size = Size(w, size.height - inset * 2),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        if (info.charging) {
            val cx = body.width / 2 + stroke / 2
            val h = size.height
            val bolt = Path().apply {
                moveTo(cx + h * 0.10f, h * 0.10f)
                lineTo(cx - h * 0.22f, h * 0.56f)
                lineTo(cx + h * 0.02f, h * 0.56f)
                lineTo(cx - h * 0.10f, h * 0.90f)
                lineTo(cx + h * 0.22f, h * 0.44f)
                lineTo(cx - h * 0.02f, h * 0.44f)
                close()
            }
            drawPath(bolt, Color.White)
            drawPath(bolt, Color(BrandTokens.SHADE), style = Stroke(0.8.dp.toPx()))
        }
    }
}

/**
 * Registra [receiver] para [filter] solo mientras la pantalla está a la
 * vista, y avisa con [onStart] al entrar (para leer el valor actual).
 */
@Composable
private fun LifecycleReceiver(filter: () -> IntentFilter, onStart: (Intent?) -> Unit, onEvent: (Intent) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    // El registro sobrevive a las recomposiciones: siempre llama a las lambdas de ahora.
    val start by rememberUpdatedState(onStart)
    val event by rememberUpdatedState(onEvent)
    DisposableEffect(owner, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = event(intent)
        }
        var registered = false
        fun register() {
            if (registered) return
            val sticky = ContextCompat.registerReceiver(context, receiver, filter(), ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            start(sticky)
        }
        fun unregister() {
            if (!registered) return
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> register()
                Lifecycle.Event.ON_STOP -> unregister()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
}

/** La hora del sistema (12/24 h y el idioma de la app); cambia solo al pasar el minuto. */
@Composable
private fun rememberClockText(): State<String> {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    fun now(): String {
        val c = Calendar.getInstance()
        return StatusFormat.time(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), systemUses24h(context), locale)
    }
    val text = remember(locale) { mutableStateOf(now()) }
    LifecycleReceiver(
        filter = {
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
        },
        // Al volver a la pantalla puede haber cambiado la hora (o el ajuste 12/24 h).
        onStart = { text.value = now() },
        onEvent = { text.value = now() },
    )
    return text
}

/**
 * El formato de hora **del dispositivo**. `DateFormat.is24HourFormat(context)`
 * no sirve tal cual: con el ajuste en "usar el del idioma" decide por el
 * idioma del contexto, que en Elyndra es el de la app (español → 24 h) y no el
 * del sistema (p. ej. es-US → 12 h). Aquí manda siempre el sistema.
 */
private fun systemUses24h(context: Context): Boolean {
    val setting = runCatching { android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24) }.getOrNull()
    // Con idioma propio de la app (Android 13+), `Resources.getSystem()` ya viene con
    // el de la app: el del sistema de verdad lo da LocaleManager.
    val systemLocale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(android.app.LocaleManager::class.java)?.systemLocales?.takeIf { !it.isEmpty }?.get(0)
    } else {
        null
    } ?: android.content.res.Resources.getSystem().configuration.locales[0]
    val pattern = runCatching { DateFormat.getBestDateTimePattern(systemLocale, "jm") }.getOrDefault("H:mm")
    return StatusFormat.uses24h(setting, pattern)
}

/** La batería, del aviso pegajoso del sistema; null hasta la primera lectura. */
@Composable
private fun rememberBattery(): State<BatteryInfo?> {
    val state = remember { mutableStateOf<BatteryInfo?>(null) }
    fun read(intent: Intent?) {
        intent ?: return
        BatteryInfo.from(
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )?.let { if (it != state.value) state.value = it }
    }
    LifecycleReceiver(
        filter = { IntentFilter(Intent.ACTION_BATTERY_CHANGED) },
        onStart = ::read,
        onEvent = ::read,
    )
    return state
}
