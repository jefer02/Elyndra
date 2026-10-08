package com.elyndra.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.elyndra.launcher.data.AppLocale
import com.elyndra.launcher.data.BrandTokens
import com.elyndra.launcher.sound.UiSound
import com.elyndra.launcher.display.FrameRate
import com.elyndra.launcher.input.AxisGate
import com.elyndra.launcher.input.DirectionalRepeater
import com.elyndra.launcher.input.Gamepad
import com.elyndra.launcher.input.Pad
import com.elyndra.launcher.ui.ElyndraApp
import com.elyndra.launcher.ui.ElyndraViewModel
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.theme.LocalReducedMotion
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: ElyndraViewModel by viewModels()

    /**
     * Direcciones del mando (cruceta, hat y stick): un paso por pulsación y,
     * manteniendo, repetición controlada (350 ms de espera, luego cada 150 ms).
     */
    private val repeater = DirectionalRepeater(fire = ::deliverDirection)

    /**
     * De dónde vino la última dirección (fuente y dispositivo): las teclas de
     * cruceta que se reenvían al foco de Compose la llevan, así un campo de
     * texto distingue la cruceta de un mando (sale del campo) de las flechas
     * de un teclado físico (mueven el cursor).
     */
    private var directionSource = PAD_SOURCE
    private var directionDevice = -1

    /** A del mando que solo colocó el foco: su "soltar" no se reenvía. */
    private var swallowConfirmUp = false

    /** Zona muerta (~0.5) e histéresis de cada fuente analógica. */
    private val stickGate = AxisGate()
    private val hatGate = AxisGate()

    /** Mandos conectados ahora mismo (id de dispositivo → nombre). */
    private val gamepads = mutableMapOf<Int, String>()

    private val inputManager by lazy { getSystemService(InputManager::class.java) }

    /**
     * Mandos que se conectan y desconectan con la app abierta (Bluetooth o USB).
     * Al desconectarse se sueltan las direcciones sostenidas: si no, el último
     * empujón del stick se quedaría repitiendo sin mando.
     */
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val device = InputDevice.getDevice(deviceId) ?: return
            if (!Gamepad.isGamepad(device) || deviceId in gamepads) return
            gamepads[deviceId] = device.name
            vm.input.onGamepadConnected(device.name, announce = true)
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            if (gamepads.remove(deviceId) == null) return
            releaseDirections()
            vm.input.onGamepadDisconnected(anyLeft = gamepads.isNotEmpty())
        }

        override fun onInputDeviceChanged(deviceId: Int) {
            if (Gamepad.isGamepad(InputDevice.getDevice(deviceId))) onInputDeviceAdded(deviceId) else onInputDeviceRemoved(deviceId)
        }
    }

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
        // Splash del sistema sin icono (ver Theme.Elyndra.Starting): solo el
        // fondo de la intro, que empalma sin costura con su primer fotograma
        // (BootIntro). Se retira solo con el primer fotograma.
        installSplashScreen()
        val app = application as ElyndraApplication
        val dark = app.settings.darkMode
        // Borde a borde: el fondo "papel" y el hero llegan hasta el filo de la
        // pantalla, como en el diseño. Los insets se aplican en ElyndraApp.
        enableEdgeToEdge(barStyle(dark), barStyle(dark))
        super.onCreate(savedInstanceState)

        // La intro, solo en un arranque en frío: ni al girar ni al volver a
        // una Activity que el sistema recreó tras matar el proceso.
        val intro = app.introGate.claim(app.settings.introEnabled, restored = savedInstanceState != null)
        if (intro) vm.intro.start()

        // El modo oscuro es una opción de la app, no del sistema, así que no
        // puede venir de un `values-night`: se pinta aquí el fondo de ventana
        // para que el primer fotograma (antes de Compose) no dé un destello.
        window.setBackgroundDrawable(ColorDrawable(windowColor(dark, intro)))

        hideSystemBars()
        askForNotifications()

        // Al volver de un juego se cierra la sesión medida; al salir, se cancela un lanzamiento pendiente.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> vm.onForeground()
                    Lifecycle.Event.ON_STOP -> vm.onBackground()
                    else -> Unit
                }
            },
        )

        setContent {
            // 120 o 60 fps según Ajustes: se aplica al instante al cambiarlo.
            // En la pantalla de Masha, 60 salvo "alta fluidez" (ver SettingsController.frameRateFor).
            val fps = vm.settings.frameRateFor(vm.screen)
            LaunchedEffect(fps) { FrameRate.apply(this@MainActivity, fps) }
            val dark = vm.settings.darkMode
            val intro = vm.intro.visible
            LaunchedEffect(dark, intro) { applyTheme(dark, intro) }
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion()) { ElyndraApp(vm) }
        }

        // Llegando desde el widget o un aviso de Masha (y no al recrearse por un giro).
        if (savedInstanceState == null) handleShortcut(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.sound.setForeground(true)
        vm.music.setForeground(true)
        inputManager.registerInputDeviceListener(deviceListener, null)
        // Lo que se conectó o desconectó mientras la app estaba en segundo plano.
        val now = InputDevice.getDeviceIds().filter { Gamepad.isGamepad(InputDevice.getDevice(it)) }.toSet()
        (gamepads.keys - now).forEach { deviceListener.onInputDeviceRemoved(it) }
        now.forEach { id ->
            if (id in gamepads) return@forEach
            val device = InputDevice.getDevice(id) ?: return@forEach
            gamepads[id] = device.name
            // En silencio: un mando que ya estaba al abrir no es una novedad.
            vm.input.onGamepadConnected(device.name, announce = false)
        }
        // Al volver de un juego el modo de pantalla puede haber cambiado.
        FrameRate.apply(this, vm.settings.frameRateFor(vm.screen))
    }

    override fun onPause() {
        // Saliendo de la app (a un juego, al inicio) no suena nada más.
        vm.sound.setForeground(false)
        vm.music.setForeground(false)
        inputManager.unregisterInputDeviceListener(deviceListener)
        releaseDirections()
        super.onPause()
    }

    /**
     * Lo que el sistema pinta fuera de Compose sigue al tema de la app: el
     * fondo de la ventana, el color de los iconos de las barras (al deslizarlas)
     * y, desde Android 13, el splash del próximo arranque. Antes de Android 13
     * el splash sale de los recursos, así que sigue al tema del sistema.
     */
    private fun applyTheme(dark: Boolean, intro: Boolean) {
        window.setBackgroundDrawable(ColorDrawable(windowColor(dark, intro)))
        enableEdgeToEdge(barStyle(dark), barStyle(dark))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            splashScreen.setSplashScreenTheme(if (dark) R.style.Theme_Elyndra_Starting_Dark else R.style.Theme_Elyndra_Starting_Light)
        }
    }

    private fun windowColor(dark: Boolean, intro: Boolean): Int = when {
        intro -> if (dark) BrandTokens.INTRO_SMOKE else BrandTokens.INTRO_PEARL
        else -> if (dark) BrandTokens.DARK.paper else BrandTokens.LIGHT.paper
    }

    /** Iconos claros sobre el tema oscuro y oscuros sobre el claro. */
    private fun barStyle(dark: Boolean): SystemBarStyle =
        if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT) else SystemBarStyle.light(AndroidColor.TRANSPARENT, LIGHT_BARS_FALLBACK_SCRIM)

    /** "Quitar animaciones" del sistema: escala de animador a 0. */
    private fun reducedMotion(): Boolean =
        android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** Lo que piden el widget y los avisos: lanzar un juego, abrir su ficha o hablar con Masha. */
    private fun handleShortcut(intent: Intent?) {
        // Se llega a algo concreto: la intro no se interpone.
        if (intent?.action in SHORTCUT_ACTIONS) vm.intro.cancel()
        when (intent?.action) {
            ACTION_LAUNCH_GAME -> intent.getStringExtra(EXTRA_GAME_KEY)?.let { vm.launchFromShortcut(it) }
            ACTION_SHOW_GAME -> intent.getStringExtra(EXTRA_GAME_KEY)?.let { vm.showFromShortcut(it) }
            ACTION_OPEN_MASHA -> vm.go(Screen.Masha)
        }
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

    /* ── Mandos ───────────────────────────────────────────────── */

    /**
     * Botones del mando.
     *
     * Las direcciones (cruceta y flechas) no se entregan tal cual: pasan por
     * [repeater], que da un paso por pulsación y repite a ritmo controlado.
     * Las repeticiones automáticas del sistema se ignoran —eran las que hacían
     * que mantener la cruceta recorriese la lista a ~20 juegos por segundo—.
     *
     * El resto de botones los intenta primero Elyndra ([ElyndraViewModel.input]),
     * que sabe qué capa está arriba. Lo que no consuma se reenvía traducido a
     * la tecla equivalente del sistema, para que el foco de Compose mueva por
     * las pantallas de formulario: así Ajustes, Añadir y Masha se manejan con
     * el mando sin navegación propia.
     */
    // `super.dispatchKeyEvent` está marcado como restringido en ComponentActivity
    // (androidx.core), pero llamarlo desde el propio override es lo previsto.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val pad = Gamepad.actionFor(event.keyCode) ?: return super.dispatchKeyEvent(event)
        if (pad.isDirection) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    directionSource = event.source
                    directionDevice = event.deviceId
                    repeater.press(DirectionalRepeater.Channel.Keys, pad)
                }
                KeyEvent.ACTION_UP -> repeater.release(DirectionalRepeater.Channel.Keys, pad)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Mantener A no "pulsa" veinte veces por segundo.
            if (event.repeatCount > 0) return true
            // Un botón de mando deja el modo táctil, como lo haría la cruceta.
            if (Gamepad.isGamepadSource(event.source)) enterKeyboardMode()
            if (vm.input.handle(pad)) {
                padSound(pad)
                return true
            }
            // Va al foco de Compose: con el foco perdido (capa recién abierta o
            // cerrada, se tocó la pantalla), esta pulsación solo lo coloca.
            if (pad == Pad.Confirm) {
                enterKeyboardMode()
                if (vm.input.wakeFocus()) {
                    swallowConfirmUp = true
                    vm.sound.play(UiSound.Navigate)
                    return true
                }
            }
        } else if (event.action == KeyEvent.ACTION_UP && pad == Pad.Confirm && swallowConfirmUp) {
            swallowConfirmUp = false
            return true
        }
        val system = Gamepad.systemKey(event.keyCode)
        val handled = if (system != null) {
            // Con su fuente y su dispositivo: el foco sabe que viene de un mando.
            super.dispatchKeyEvent(
                KeyEvent(
                    event.downTime, event.eventTime, event.action, system, event.repeatCount,
                    event.metaState, event.deviceId, event.scanCode, event.flags, event.source,
                ),
            )
        } else {
            super.dispatchKeyEvent(event)
        }
        if (handled && event.action == KeyEvent.ACTION_DOWN) padSound(pad)
        return handled
    }

    /**
     * El único enganche de sonido del mando: aceptar y volver. Lo que abren o
     * cierran (menús, diálogos) suena desde el estado —ver `UiSoundEffects`—
     * y manda sobre esto, así que A que abre un menú suena a "abrir".
     */
    private fun padSound(pad: Pad) {
        when (pad) {
            Pad.Confirm -> vm.sound.play(UiSound.Select)
            Pad.Back -> vm.sound.play(UiSound.Back)
            else -> Unit
        }
    }

    /**
     * Sticks y cruceta analógica ("hat").
     *
     * Se consumen **siempre** que vengan de un mando: si no, Android convierte
     * él solo el stick en pulsaciones de cruceta (con su propia repetición
     * rápida), que se sumaban a las de Elyndra y hacían saltar varios juegos
     * de un solo empujón.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!Gamepad.isGamepadSource(event.source) || event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        directionSource = PAD_SOURCE
        directionDevice = event.deviceId
        val (hx, hy) = Gamepad.hat(event)
        updateAxis(DirectionalRepeater.Channel.Hat, hatGate.update(hx, hy))
        val (sx, sy) = Gamepad.leftStick(event)
        updateAxis(DirectionalRepeater.Channel.Stick, stickGate.update(sx, sy))
        return true
    }

    private fun updateAxis(channel: DirectionalRepeater.Channel, pad: Pad?) {
        if (pad == null) repeater.release(channel) else repeater.press(channel, pad)
    }

    /**
     * Un paso en una dirección: primero Elyndra (carrusel, barra, menús); si
     * no lo quiere, la tecla de cruceta equivalente para el foco de Compose.
     */
    @SuppressLint("RestrictedApi")
    private fun deliverDirection(pad: Pad) {
        enterKeyboardMode()
        vm.input.repeats = repeater.repeats
        // El paso suena si alguien lo ha movido (el carrusel o el foco de Compose).
        if (vm.input.handle(pad)) {
            vm.sound.play(UiSound.Navigate)
            return
        }
        val key = Gamepad.systemKeyFor(pad) ?: return
        // Sin nada señalado, la primera pulsación coloca el foco en la capa de arriba.
        if (vm.input.wakeFocus()) {
            vm.sound.play(UiSound.Navigate)
            return
        }
        val now = SystemClock.uptimeMillis()
        val source = directionSource
        val device = directionDevice
        val moved = super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, 0, device, 0, 0, source))
        super.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, 0, device, 0, 0, source))
        if (moved) vm.sound.play(UiSound.Navigate)
    }

    /**
     * Sale del modo táctil, como lo haría una cruceta de verdad.
     *
     * Tras tocar la pantalla Android está en modo táctil y ahí los botones de
     * Compose no aceptan el foco. Una cruceta física lo deja al pasar por la
     * ventana, pero el stick (eventos de movimiento) y las teclas que se
     * reenvían desde aquí no: sin esto, después de tocar la pantalla el stick
     * no movía nada en Ajustes ni en Añadir.
     */
    private fun enterKeyboardMode() {
        if (!window.decorView.isInTouchMode) return
        val target = currentFocus ?: findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        target?.requestFocusFromTouch()
    }

    private fun releaseDirections() {
        repeater.releaseAll()
        stickGate.reset()
        hatGate.reset()
    }

    /** Tocar la pantalla apaga los resaltes del mando (vuelven con la próxima pulsación). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) vm.input.onTouch()
        return super.dispatchTouchEvent(ev)
    }

    /** Al volver de un juego (o del teclado) las barras reaparecen: se ocultan otra vez. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars() else releaseDirections()
    }

    companion object {
        const val ACTION_LAUNCH_GAME = "com.elyndra.launcher.LAUNCH_GAME"
        const val ACTION_SHOW_GAME = "com.elyndra.launcher.SHOW_GAME"
        const val ACTION_OPEN_MASHA = "com.elyndra.launcher.OPEN_MASHA"
        const val EXTRA_GAME_KEY = "gameKey"

        private val SHORTCUT_ACTIONS = setOf(ACTION_LAUNCH_GAME, ACTION_SHOW_GAME, ACTION_OPEN_MASHA)

        /** Fuente de las direcciones que no traen tecla (stick, hat): un mando con cruceta. */
        private const val PAD_SOURCE = InputDevice.SOURCE_DPAD or InputDevice.SOURCE_GAMEPAD

        /** Velo de las barras donde el sistema no sabe pintar iconos oscuros (navegación en Android 8.0). */
        private const val LIGHT_BARS_FALLBACK_SCRIM = 0x801B1B1B.toInt()

        private fun base(context: Context, action: String) = Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun launchGameIntent(context: Context, gameKey: String): Intent =
            base(context, ACTION_LAUNCH_GAME).putExtra(EXTRA_GAME_KEY, gameKey)
                // Cada juego con su propio intent: si no, el sistema reutilizaría el primero para todos.
                .setData(Uri.parse("elyndra://game/" + Uri.encode(gameKey)))

        fun showGameIntent(context: Context, gameKey: String): Intent =
            base(context, ACTION_SHOW_GAME).putExtra(EXTRA_GAME_KEY, gameKey)
                .setData(Uri.parse("elyndra://details/" + Uri.encode(gameKey)))

        fun openMashaIntent(context: Context): Intent = base(context, ACTION_OPEN_MASHA)
    }
}
