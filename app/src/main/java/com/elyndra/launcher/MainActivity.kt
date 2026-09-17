package com.elyndra.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.ui.ElyndraApp
import com.elyndra.launcher.ui.ElyndraViewModel

class MainActivity : ComponentActivity() {

    private val vm: ElyndraViewModel by viewModels()

    /**
     * El único permiso que Elyndra pide al sistema.
     *
     * Todo lo demás lo concede el propio usuario al usar la app: las carpetas
     * de juegos llegan por SAF (`ACTION_OPEN_DOCUMENT_TREE`, con permiso
     * persistente por carpeta) y la lista de apps y emuladores instalados sale
     * del bloque `<queries>` del manifiesto, que no se pide, se declara.
     */
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /** Antes de Android 13 el idioma elegido en la app se aplica envolviendo el contexto. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Borde a borde: el fondo "papel" y el hero llegan hasta el filo de la
        // pantalla, como en el diseño. Los insets se aplican en ElyndraApp.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // El modo oscuro es una opción de la app, no del sistema, así que no
        // puede venir de un `values-night`: se pinta aquí el fondo de ventana
        // para que el primer fotograma (antes de Compose) no dé un destello claro.
        val dark = (application as ElyndraApplication).settings.darkMode
        window.setBackgroundDrawable(ColorDrawable(if (dark) 0xFF13161A.toInt() else 0xFFF6F8F9.toInt()))

        hideSystemBars()
        askForNotifications()

        // Al volver de un juego se cierra la sesión medida; al salir, se retira el velo de lanzamiento.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> vm.onForeground()
                    Lifecycle.Event.ON_STOP -> vm.onBackground()
                    else -> Unit
                }
            },
        )

        setContent { ElyndraApp(vm) }
    }

    /**
     * Permiso de notificaciones, una vez y al empezar.
     *
     * Sin él, "aplicar metadatos a toda la biblioteca" corre igual —es un
     * servicio en primer plano— pero en silencio: ni progreso ni aviso al
     * terminar. Se pide aquí para que la primera descarga larga ya se vea.
     */
    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val settings = (application as ElyndraApplication).settings
        if (settings.notificationsAsked) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        settings.notificationsAsked = true
        if (!granted) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Pantalla completa de verdad: sin barra de estado ni de navegación.
     * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` las deja aparecer un momento al
     * deslizar desde el borde y las vuelve a esconder solas.
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Al volver de un juego (o del teclado) las barras reaparecen: se ocultan otra vez. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}
