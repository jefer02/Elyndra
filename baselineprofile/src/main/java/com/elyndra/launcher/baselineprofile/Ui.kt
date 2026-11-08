package com.elyndra.launcher.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * El paquete que se perfila y se mide: las variantes del perfil base de :app
 * (nonMinifiedRelease y benchmarkRelease) llevan el sufijo `.benchmark` y se
 * llaman "Elyndra Bench" (app/build.gradle.kts). Nunca `com.elyndra.launcher`:
 * Macrobenchmark mata el proceso y reinicia la compilación del paquete que se
 * le da, y eso no puede tocar la app de verdad que hay en el móvil.
 */
const val PACKAGE = "com.elyndra.launcher.benchmark"

/**
 * El plugin del perfil base pasa el applicationId de la variante instalada como
 * argumento de la instrumentación. Si no es [PACKAGE] (alguien quitó el sufijo
 * en app/build.gradle.kts), se para aquí con un mensaje claro antes de tocar
 * nada del móvil.
 */
fun checkTargetPackage() {
    val installed = InstrumentationRegistry.getArguments().getString(TARGET_PACKAGE_ARG) ?: return
    check(installed == PACKAGE) {
        "The app under test is '$installed', expected '$PACKAGE'. The benchmark variants of :app must keep " +
            "applicationIdSuffix = \".benchmark\" so the real Elyndra install is never touched."
    }
}

private const val TARGET_PACKAGE_ARG = "androidx.benchmark.targetPackageName"

/** Al acabar cada prueba: el giro vuelve a ser el del sistema. */
fun unfreezeRotation() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).unfreezeRotation()

/**
 * El modo de rendimiento de la pasada (Ajustes → Pantalla → Rendimiento). Lo
 * aplica la app al arrancar, solo en las variantes del perfil base
 * (app/src/benchmark/…/BenchmarkMode.kt, mismo nombre de extra).
 */
enum class PerfMode(val id: String) {
    Auto("auto"),
    Quality("quality"),
    Performance("performance"),
    ;

    companion object {
        const val EXTRA = "elyndra.bench.perfMode"
    }
}

/**
 * Los testTag de la app (`ui/UiTags.kt`), que salen como resource-id. Si se
 * renombran allí, hay que cambiarlos aquí.
 */
object Tags {
    const val INTRO = "boot_intro"
    const val SHELF = "classic_shelf"
    const val FOLDER_CARD = "library_folder"
    const val APP_CARD = "library_app"
    const val SETTINGS = "settings_button"
    const val WHEEL = "meridian_wheel"
    const val SEARCH_FIELD = "library_search"
    const val DETAILS = "details_sheet"
    const val ACTION_SHEET = "action_sheet"
    const val ART_PICKER = "art_picker"
    const val ART_CANDIDATE = "art_candidate"
    const val PERFORMANCE_MODE = "settings_performance_mode"
    const val SETTINGS_DISPLAY = "settings_cat_display"
    const val ART_ACTION_HERO = "art_action_hero"
    const val ART_SOURCE_STEAM = "art_source_steam"
    const val LIBRARY = "screen_library"
    const val FOLDER = "screen_folder"
    const val SETTINGS_SCREEN = "screen_settings"

    /** El dock lleva la sección elegida (`UiTags.libraryFilter`). */
    const val TAB_ALL = "library_filter_all"
    const val TAB_ANDROID = "library_filter_android"
    const val TAB_CONSOLES = "library_filter_consoles"
}

/**
 * Cuánto se espera a lo que debe aparecer. Generoso a propósito: en el Tecno,
 * en Calidad, tras girar o ya caliente, la app tarda varios segundos en atender
 * una pulsación (la ficha salía a los 5 s justos y la prueba ya había fallado).
 */
private const val WAIT_MS = 15_000L

/** Cuánto se espera a lo que abre una tecla antes de pulsarla otra vez ([pressFor]). */
private const val RETRY_AFTER_MS = 7_000L

/** Tras cada pulsación de R1 en [goToTab]: si se pulsa antes, las pulsaciones en cola se pasan de sección. */
private const val TAB_WAIT_MS = 3_000L

/** Tras girar la pantalla, la actividad se rehace: se deja que se asiente antes de pulsar nada. */
private const val ROTATE_SETTLE_MS = 1_500L

/** Cuánto puede tardar la primera vez la biblioteca de prueba (pinta ~170 imágenes en el móvil). */
private const val SEED_WAIT_MS = 180_000L

/** Cualquier fila de servicio en "Personalizar fondo/carátula…" (`UiTags.artSource`). */
private val ART_SOURCE = Pattern.compile("art_source_.+")

/** Cualquier ficha de la biblioteca: un juego o una carpeta. */
private val LIBRARY_CARD = Pattern.compile("${Tags.APP_CARD}|${Tags.FOLDER_CARD}")

/** Entre pulsaciones de la cruceta: como alguien que recorre la lista, no como una repetición. */
private const val STEP_MS = 220L

/**
 * Lo que la prueba necesita sí o sí. Si no aparece, falla en el acto con un
 * mensaje que dice qué faltaba: un paso que se salta en silencio deja un perfil
 * que no cubre lo que se cree.
 */
fun MacrobenchmarkScope.require(tag: String, timeout: Long = WAIT_MS, why: String = ""): UiObject2 =
    device.wait(Until.findObject(By.res(tag)), timeout)
        ?: throw AssertionError(
            "Required UI element '$tag' did not appear within $timeout ms" + (if (why.isNotEmpty()) " ($why)" else "") +
                ". If the test tags changed, update ui/UiTags.kt and baselineprofile/…/Ui.kt together.",
        )

private fun MacrobenchmarkScope.requireGone(tag: String, timeout: Long = WAIT_MS) {
    if (!device.wait(Until.gone(By.res(tag)), timeout)) throw AssertionError("UI element '$tag' did not close within $timeout ms")
}

/** Las capas que tapan la biblioteca: con una abierta, `screen_library` sigue en el árbol por debajo. */
private val OVERLAYS = listOf(Tags.ART_PICKER, Tags.ACTION_SHEET, Tags.DETAILS, Tags.SEARCH_FIELD)

/**
 * Lo que hay que cerrar para estar en la biblioteca: las capas y las otras
 * pantallas. Una carpeta o Ajustes también cuentan: en una carpeta, si el foco
 * está en su barra, el primer Atrás solo lo quita de ahí y no sale.
 */
private val NOT_LIBRARY = OVERLAYS + listOf(Tags.FOLDER, Tags.SETTINGS_SCREEN)

/**
 * Atrás hasta la biblioteca sin nada encima. Tras cada pulsación se espera a
 * que se cierre lo que estaba abierto: una segunda pulsación con la capa o la
 * pantalla aún animándose llegaría a la biblioteca y sacaría de la app.
 */
fun MacrobenchmarkScope.backToLibrary(maxPresses: Int = 6) {
    repeat(maxPresses) {
        val open = NOT_LIBRARY.firstOrNull { device.hasObject(By.res(it)) }
        if (open == null && device.hasObject(By.res(Tags.LIBRARY))) {
            device.waitForIdle()
            return
        }
        device.pressBack()
        if (open != null) device.wait(Until.gone(By.res(open)), 5_000) else device.wait(Until.hasObject(By.res(Tags.LIBRARY)), 5_000)
    }
    val open = NOT_LIBRARY.filter { device.hasObject(By.res(it)) }
    if (open.isNotEmpty() || !device.hasObject(By.res(Tags.LIBRARY))) {
        throw AssertionError("Could not get back to the library after $maxPresses Back presses (still open: $open)")
    }
}

/**
 * Pulsa [keyCode] para abrir lo que lleva [tag] y lo espera. Si a los
 * [RETRY_AFTER_MS] no ha salido, se pulsa una vez más: en el Tecno, ya caliente
 * y tras varias pasadas, alguna pulsación se perdía. Solo para teclas cuya
 * segunda pulsación, en el peor caso, cierra lo abierto (y la prueba falla con
 * el nombre de la etiqueta); nunca para A, que dentro de una carpeta lanzaría
 * una ROM.
 */
private fun MacrobenchmarkScope.pressFor(keyCode: Int, tag: String, why: String): UiObject2 {
    press(keyCode)
    device.wait(Until.findObject(By.res(tag)), RETRY_AFTER_MS)?.let { return it }
    press(keyCode)
    return require(tag, why = why)
}

private fun MacrobenchmarkScope.press(keyCode: Int, times: Int = 1) {
    repeat(times) {
        device.pressKeyCode(keyCode)
        Thread.sleep(STEP_MS)
    }
    device.waitForIdle()
}

private fun MacrobenchmarkScope.settleAfterRotation() {
    device.waitForIdle()
    Thread.sleep(ROTATE_SETTLE_MS)
}

/**
 * Lleva a la sección [tab] (`Tags.TAB_*`) con R1, mirando la etiqueta del dock.
 * Por nombre y no contando pulsaciones: hay cuatro secciones en círculo (con la
 * de "sin nombre", que en la biblioteca de prueba son ROMs) y una pulsación
 * perdida mientras se cierra una hoja descuadraría todo lo que sigue; en "sin
 * nombre", A lanzaría una ROM en vez de abrir una carpeta.
 */
fun MacrobenchmarkScope.goToTab(tab: String) {
    repeat(6) {
        if (device.wait(Until.hasObject(By.res(tab)), TAB_WAIT_MS)) {
            device.waitForIdle()
            return
        }
        // Abajo antes de cada R1: devuelve el foco a la lista si se quedó en la
        // barra o en el dock (en la lista solo mueve uno; si deja "Añadir", R1 lo quita).
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_BUTTON_R1)
    }
    throw AssertionError("Could not reach the library section '$tab' with R1")
}

/**
 * Antes de medir o perfilar: la primera vez que se abre "Elyndra Bench", la
 * app pinta las imágenes y mete la biblioteca de prueba (BenchmarkFixtures).
 * Se espera aquí, fuera de lo que se mide, a que se vea una ficha (juego o
 * carpeta) en el Clásico (vertical); las siguientes veces ya está y es
 * inmediato. Vale cualquiera de las dos: en Todo las carpetas pueden quedar
 * fuera de pantalla y entonces no están en el árbol.
 */
fun seedFixtureLibrary() {
    val scope = MacrobenchmarkScope(PACKAGE, launchWithClearTask = true)
    with(scope) {
        pressHome()
        device.setOrientationPortrait()
        startActivityAndWait { it.putExtra(PerfMode.EXTRA, PerfMode.Auto.id) }
        device.wait(Until.findObject(By.res(LIBRARY_CARD)), SEED_WAIT_MS)
            ?: throw AssertionError(
                "No library card ('${Tags.APP_CARD}' or '${Tags.FOLDER_CARD}') appeared within $SEED_WAIT_MS ms " +
                    "(the fixture library never showed up; is '$PACKAGE' the benchmark variant (src/benchmark)?)",
            )
        device.unfreezeRotation()
        pressHome()
    }
}

/**
 * 1. Arranque en frío hasta la biblioteca, en apaisado (Meridian) y con el
 * modo de rendimiento pedido. Sin intro ni aviso de notificaciones (los apaga
 * la biblioteca de prueba); si aun así sale el aviso, se rechaza.
 */
fun MacrobenchmarkScope.coldStartToLibrary(mode: PerfMode) {
    killProcess()
    pressHome()
    device.setOrientationLandscape()
    settleAfterRotation()
    startActivityAndWait { it.putExtra(PerfMode.EXTRA, mode.id) }
    device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_deny_button")), 500)?.click()
    if (device.hasObject(By.res(Tags.INTRO))) requireGone(Tags.INTRO, timeout = 10_000)
    require(Tags.LIBRARY)
    require(Tags.WHEEL, why = "Meridian should be the library layout in landscape")
    device.waitForIdle()
}

/** 2. La rueda de Meridian: ~15 pasos con la cruceta (cambia el fondo, carga arte, se mueve el cristal). */
fun MacrobenchmarkScope.walkMeridianWheel(steps: Int = 15) {
    require(Tags.WHEEL)
    press(KeyEvent.KEYCODE_DPAD_DOWN, steps)
    press(KeyEvent.KEYCODE_DPAD_UP, 3)
}

/** 3. Pestañas con LB/RB: Todo → Android → Consolas y vuelta a Todo. */
fun MacrobenchmarkScope.switchTabs() {
    press(KeyEvent.KEYCODE_BUTTON_R1)
    press(KeyEvent.KEYCODE_DPAD_DOWN, 3)
    press(KeyEvent.KEYCODE_BUTTON_R1)
    press(KeyEvent.KEYCODE_DPAD_DOWN, 2)
    press(KeyEvent.KEYCODE_BUTTON_L1, 2)
    goToTab(Tags.TAB_ALL)
    require(Tags.WHEEL)
}

/** 4. Búsqueda: abrirla (Select), escribir algo corto, ver el filtrado y cerrarla. */
fun MacrobenchmarkScope.searchAndClose(query: String = "ae") {
    val field = pressFor(KeyEvent.KEYCODE_BUTTON_SELECT, Tags.SEARCH_FIELD, why = "Select should open the library search")
    field.text = query
    device.waitForIdle()
    // Sin cruceta aquí: con un solo resultado, abajo pasaría a "Añadir" y la
    // selección se quedaría allí al cerrar (X e Y no hacen nada en "Añadir").
    // Atrás cierra el teclado y luego la búsqueda.
    backToLibrary()
}

/**
 * 5. La ficha (X) y el menú de opciones (Y) de un juego Android, y cerrarlos.
 * Se hace en la pestaña Android: en Todo la selección puede ser una carpeta
 * (las carpetas no tienen ficha) o "Añadir" (ni ficha ni opciones).
 */
fun MacrobenchmarkScope.openDetailsAndOptions() {
    // Abajo saca el foco de la barra de arriba (allí L1/R1 siguen valiendo,
    // pero A pulsaría un botón); R1 lleva a Android y quita "Añadir".
    press(KeyEvent.KEYCODE_DPAD_DOWN)
    goToTab(Tags.TAB_ANDROID)
    pressFor(KeyEvent.KEYCODE_BUTTON_X, Tags.DETAILS, why = "X should open the details of the selected game")
    device.waitForIdle()
    device.pressBack()
    requireGone(Tags.DETAILS)
    pressFor(KeyEvent.KEYCODE_BUTTON_Y, Tags.ACTION_SHEET, why = "Y should open the options of the selected game")
    device.waitForIdle()
    device.pressBack()
    requireGone(Tags.ACTION_SHEET)
    goToTab(Tags.TAB_ALL)
}

/**
 * 6. Una carpeta en Meridian: pestaña Consolas (solo carpetas, así A abre una
 * carpeta y nunca lanza nada), recorrer su rueda con la cruceta y a golpe de
 * dedo, y volver a Todo.
 */
fun MacrobenchmarkScope.openFolderAndScrollWheel() {
    // Abajo saca el foco de la barra (con él allí, A pulsaría un botón de la
    // barra); si deja la selección en "Añadir", cambiar de pestaña la quita.
    press(KeyEvent.KEYCODE_DPAD_DOWN)
    goToTab(Tags.TAB_CONSOLES)
    press(KeyEvent.KEYCODE_BUTTON_A)
    require(Tags.FOLDER, why = "A on the Consoles tab should open a ROM folder")
    require(Tags.WHEEL)
    device.waitForIdle()
    press(KeyEvent.KEYCODE_DPAD_DOWN, 10)
    press(KeyEvent.KEYCODE_DPAD_UP, 4)
    flingWheel()
    backToLibrary()
    goToTab(Tags.TAB_ALL)
}

/**
 * 7. Vertical (el Clásico): el estante de la biblioteca con la cruceta y a
 * golpe de dedo, una carpeta y su estante, y vuelta a apaisado.
 *
 * Las secciones se cambian siempre en apaisado (Meridian): en el Tecno, en
 * vertical y tras deslizar el estante, R1 a veces no llegaba a cambiar de
 * sección. Por eso se gira a apaisado para ir a Consolas (solo carpetas: la
 * carpeta no puede quedar fuera del árbol y A nunca lanza nada) y se vuelve a
 * vertical para abrirla.
 */
fun MacrobenchmarkScope.classicShelves() {
    device.setOrientationPortrait()
    settleAfterRotation()
    require(Tags.SHELF, why = "the Classic layout should show the shelf in portrait")
    device.waitForIdle()
    // Foco fuera de la barra (en el estante abajo no hace nada).
    press(KeyEvent.KEYCODE_DPAD_DOWN)
    press(KeyEvent.KEYCODE_DPAD_RIGHT, 8)
    flingShelf()

    device.setOrientationLandscape()
    settleAfterRotation()
    require(Tags.WHEEL)
    goToTab(Tags.TAB_CONSOLES)
    device.setOrientationPortrait()
    settleAfterRotation()
    require(Tags.FOLDER_CARD, why = "the Consoles tab shows the four fixture ROM folders")
    // Con A, no tocando: la ficha que encuentra UiAutomator puede ser una medio
    // fuera de pantalla, y un toque justo tras deslizar solo frena el estante.
    // Abajo antes, para que A no pulse un botón de la barra.
    press(KeyEvent.KEYCODE_DPAD_DOWN)
    press(KeyEvent.KEYCODE_BUTTON_A)
    require(Tags.FOLDER, why = "A on the Consoles tab should open the selected folder")
    require(Tags.SHELF)
    device.waitForIdle()
    press(KeyEvent.KEYCODE_DPAD_RIGHT, 8)
    flingShelf()
    backToLibrary()

    device.setOrientationLandscape()
    settleAfterRotation()
    require(Tags.WHEEL)
    goToTab(Tags.TAB_ALL)
    device.waitForIdle()
}

/**
 * Un golpe de dedo sobre lo que lleva [tag], buscándolo justo antes: la rueda
 * y el estante se recomponen al moverse (la rueda va dentro de un
 * AnimatedContent) y un UiObject2 de antes puede haberse quedado sin nodo.
 */
private fun MacrobenchmarkScope.flingTag(tag: String, direction: Direction, margin: Int) {
    repeat(2) { attempt ->
        try {
            val target = require(tag)
            target.setGestureMargin(margin)
            target.fling(direction)
            device.waitForIdle()
            return
        } catch (e: StaleObjectException) {
            if (attempt == 1) throw e
        }
    }
}

/** El estante del Clásico: dos golpes hacia un lado y uno de vuelta. */
fun MacrobenchmarkScope.flingShelf() {
    val margin = device.displayWidth / 5
    repeat(2) { flingTag(Tags.SHELF, Direction.RIGHT, margin) }
    flingTag(Tags.SHELF, Direction.LEFT, margin)
}

/** La rueda de Meridian: un golpe hacia abajo y otro hacia arriba. */
fun MacrobenchmarkScope.flingWheel() {
    val margin = device.displayHeight / 6
    flingTag(Tags.WHEEL, Direction.DOWN, margin)
    flingTag(Tags.WHEEL, Direction.UP, margin)
}

/**
 * Desplaza lo que se pueda desplazar hasta que [tag] esté a la vista: lo que
 * queda fuera de pantalla no sale en el árbol de UiAutomator.
 */
private fun MacrobenchmarkScope.scrollToTag(tag: String) {
    if (device.hasObject(By.res(tag))) return
    device.findObjects(By.scrollable(true)).forEach { scrollable ->
        if (scrollDownTo(scrollable, tag)) return
    }
}

/**
 * Baja por [scrollable] hasta ver [tag], a pasos. No con `scrollUntil`: con las
 * listas de Compose no sabe cuándo ha llegado al final y se rinde tras un solo
 * deslizamiento, a veces antes de que asome lo que se busca.
 */
private fun MacrobenchmarkScope.scrollDownTo(scrollable: UiObject2, tag: String): Boolean {
    repeat(6) {
        if (device.hasObject(By.res(tag))) return true
        try {
            scrollable.scroll(Direction.DOWN, 0.8f)
        } catch (e: StaleObjectException) {
            return device.hasObject(By.res(tag))
        }
        device.waitForIdle()
    }
    return device.hasObject(By.res(tag))
}

/** 8. Ajustes → Pantalla → Rendimiento (sin cambiar nada) y atrás. */
fun MacrobenchmarkScope.openPerformanceSettings() {
    pressFor(KeyEvent.KEYCODE_BUTTON_START, Tags.SETTINGS_SCREEN, why = "Start should open Settings")
    require(Tags.SETTINGS_DISPLAY).click()
    device.waitForIdle()
    // En un móvil la fila queda más abajo: se desplaza hasta verla.
    scrollToTag(Tags.PERFORMANCE_MODE)
    require(Tags.PERFORMANCE_MODE, why = "Display settings should contain the performance mode")
    device.waitForIdle()
    backToLibrary()
}

/**
 * 9. El selector de arte de un juego Android (pestaña Android): opciones (Y)
 * → Fondo → Steam. En las variantes del perfil base el selector solo enseña
 * imágenes locales de la biblioteca de prueba; nada va a la red y nunca se
 * aplica ninguna. Se recorren unas miniaturas con la cruceta y se cierra.
 */
fun MacrobenchmarkScope.browseArtPicker() {
    // Como en openFolderAndScrollWheel: abajo saca el foco de la barra y R1 quita "Añadir".
    press(KeyEvent.KEYCODE_DPAD_DOWN)
    goToTab(Tags.TAB_ANDROID)
    // Arriba, no abajo: Android recuerda el último juego elegido y desde el
    // último abajo pasa a "Añadir" (sin opciones). Arriba desde "Añadir" vuelve
    // a un juego, y desde el primero sube a la barra, donde Y sigue valiendo.
    press(KeyEvent.KEYCODE_DPAD_UP, 2)
    pressFor(KeyEvent.KEYCODE_BUTTON_Y, Tags.ACTION_SHEET, why = "Y should open the options of the selected game")
    require(Tags.ART_ACTION_HERO, why = "the options sheet should offer changing the background").click()
    // Steam va debajo de la galería y de los servicios con clave: hay que bajar
    // hasta verlo, en la lista de servicios (no en la hoja de opciones, que puede
    // seguir saliendo y también es una `action_sheet`).
    val sources = device.wait(Until.findObject(By.scrollable(true).hasDescendant(By.res(ART_SOURCE))), WAIT_MS)
        ?: throw AssertionError("The art source sheet (rows '$ART_SOURCE') did not appear within $WAIT_MS ms")
    device.waitForIdle()
    scrollDownTo(sources, Tags.ART_SOURCE_STEAM)
    require(Tags.ART_SOURCE_STEAM, why = "the art source sheet should list Steam (enabled by the fixture seed)").click()
    require(Tags.ART_PICKER)
    require(Tags.ART_CANDIDATE, why = "the picker should show the local fixture images")
    device.waitForIdle()
    press(KeyEvent.KEYCODE_DPAD_RIGHT, 3)
    press(KeyEvent.KEYCODE_DPAD_DOWN)
    press(KeyEvent.KEYCODE_DPAD_LEFT, 2)
    device.pressBack()
    requireGone(Tags.ART_PICKER)
    // Al cerrar el selector puede volver la hoja de origen: atrás hasta la biblioteca.
    backToLibrary()
    goToTab(Tags.TAB_ALL)
}
