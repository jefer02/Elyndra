package com.elyndra.launcher

import android.content.Context
import android.os.Bundle
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

    /** Antes de Android 13 el idioma elegido en la app se aplica envolviendo el contexto. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Borde a borde: el fondo "papel" y el hero llegan hasta el filo de la
        // pantalla, como en el diseño. Los insets se aplican en ElyndraApp.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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
}
