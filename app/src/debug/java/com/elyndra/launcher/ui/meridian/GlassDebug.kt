package com.elyndra.launcher.ui.meridian

import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.elyndra.launcher.ui.components.ElyText
import java.io.File
import java.util.Locale

/**
 * Solo en builds de depuración (src/debug): qué cristal pinta Meridian y con
 * qué valores, para comprobarlo en cada móvil. La de src/release no hace nada.
 *
 * Siempre al log (`adb logcat -s ElyndraGlass`) cuando cambia. En pantalla,
 * abajo a la derecha, solo si se pide (como la calidad de Masha):
 *   adb shell "run-as com.elyndra.launcher sh -c 'echo on > cache/glass_debug'"   (borrar el archivo = sin rótulo)
 */
object GlassDebug {

    private const val TAG = "ElyndraGlass"

    @Composable
    fun Report(spec: GlassPanelLayout.Spec, lite: Boolean) {
        val context = LocalContext.current
        val preview = LocalInspectionMode.current
        val line = remember(spec, lite) {
            String.format(
                Locale.ROOT,
                "glass=%s (%s) blur=%.1fdp tint=%.3f frost=%.3f rim=%.2f glow=%.1fdp | API %d %s %s, masha=%s",
                spec.path, spec.reason, spec.blurDp, spec.tint, spec.frost, spec.rim, spec.glow,
                Build.VERSION.SDK_INT, Build.MANUFACTURER, Build.MODEL, if (lite) "lite" else "high",
            )
        }
        LaunchedEffect(line) { if (!preview) Log.i(TAG, line) }
        val overlay = remember { !preview && runCatching { File(context.cacheDir, "glass_debug").exists() }.getOrDefault(false) }
        if (!overlay) return
        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.BottomEnd) {
            ElyText(
                line,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 3.dp),
                size = 8f,
                color = Color.White,
            )
        }
    }
}
