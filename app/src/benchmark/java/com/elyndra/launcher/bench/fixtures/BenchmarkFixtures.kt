package com.elyndra.launcher.bench.fixtures

import android.content.Context
import android.util.Log
import com.elyndra.launcher.ElyndraApplication
import com.elyndra.launcher.metadata.Service
import com.elyndra.launcher.sound.PrefsKeyValues
import com.elyndra.launcher.sound.SoundSettings
import com.elyndra.launcher.ui.meridian.LayoutStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * La entrada única de la biblioteca de prueba (solo variantes del perfil base).
 *
 * - [prepare], en cada arranque: apaga lo que haría las pasadas distintas o
 *   lentas (intro, aviso de notificaciones, música, vídeo de fondo, metadatos
 *   automáticos, avisos y sugerencias de Masha, el actualizador y las fuentes
 *   sin clave que irían a la red). Son los mismos ajustes que el usuario puede
 *   cambiar en la app: no se toca ningún camino de código compartido.
 * - [seedIfEmpty], una vez: si la biblioteca está vacía, pinta las imágenes y
 *   mete la biblioteca de [FixtureLibrary] de una sola vez (o está entera o no
 *   está). Sin red, sin selector de carpetas, sin claves y sin ROMs de verdad.
 */
object BenchmarkFixtures {

    private const val TAG = "ElyndraBench"

    fun prepare(app: ElyndraApplication) {
        applySettings(app)
        app.scope.launch(Dispatchers.IO) { seedIfEmpty(app) }
    }

    private fun applySettings(app: ElyndraApplication) {
        val s = app.settings
        // La intro (animación con tiempo propio) y el diálogo del permiso de
        // notificaciones taparían la biblioteca al arrancar.
        s.introEnabled = false
        s.notificationsAsked = true
        // Nada en segundo plano que dependa de la red o del reloj.
        s.autoMeta = false
        s.mashaNudges = false
        s.mashaAmbient = false
        s.mashaOnline = false
        s.updateAutoCheck = false
        // Las fuentes sin clave irían a Internet al volver a la app (descripciones de Google Play).
        // Steam se queda: es la fuente con la que el generador abre el selector de arte (cubre
        // cualquier juego, también los Android), y en estas variantes el selector solo enseña
        // imágenes locales (BenchmarkHooks.artCandidates). Sola no pregunta nada: solo describe
        // juegos con appid de Steam, y los de prueba no tienen.
        s.setKeylessEnabled(Service.GooglePlay.id, false)
        s.setKeylessEnabled(Service.Libretro.id, false)
        s.setKeylessEnabled(Service.Steam.id, true)
        // Fondo y música: un vídeo o un audio en marcha cambian el coste de cada fotograma.
        s.backgroundEnabled = false
        s.backgroundIsVideo = false
        SoundSettings(PrefsKeyValues(app.getSharedPreferences("settings", Context.MODE_PRIVATE))).musicEnabled = false
        // Meridian en apaisado (el Clásico manda solo en vertical) y sin tirones heredados:
        // el modo de rendimiento lo fija cada prueba (BenchmarkMode).
        s.layoutStyle = LayoutStyle.Meridian.id
        s.performanceStutter = false
    }

    /** Mete la biblioteca de prueba si no hay nada. Se puede llamar más de una vez. */
    suspend fun seedIfEmpty(app: ElyndraApplication) {
        val repo = app.library
        repo.awaitLoaded()
        if (!repo.current.isEmptyLibrary()) return
        val started = System.currentTimeMillis()
        val fixture = FixtureLibrary.build(now = started)
        // Primero las imágenes: cuando la biblioteca aparezca, ya está todo en disco.
        FixtureArt.render(app.filesDir, FixtureLibrary.art)
        repo.update { current -> if (current.isEmptyLibrary()) fixture else current }
        repo.flush()
        Log.i(
            TAG,
            "Fixture library seeded: ${fixture.apps.size} apps, ${fixture.folders.size} folders, " +
                "${fixture.roms.size} ROMs, ${FixtureLibrary.art.size} images in ${System.currentTimeMillis() - started} ms",
        )
    }

    private fun com.elyndra.launcher.data.Library.isEmptyLibrary() = folders.isEmpty() && apps.isEmpty() && roms.isEmpty()
}
