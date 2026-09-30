package com.elyndra.launcher.sound

import com.elyndra.launcher.sound.SoundScheduler.Companion.DEFER
import com.elyndra.launcher.sound.SoundScheduler.Companion.DEFER_MS
import com.elyndra.launcher.sound.SoundScheduler.Companion.DROP
import com.elyndra.launcher.sound.SoundScheduler.Companion.PLAY
import com.elyndra.launcher.ui.Screen
import com.elyndra.launcher.ui.UiSoundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundSchedulerTest {

    private val s = SoundScheduler()

    @Test
    fun navigationIsThrottledTo60ms() {
        assertEquals(PLAY, s.offer(UiSound.Navigate, 1_000))
        assertEquals(DROP, s.offer(UiSound.Navigate, 1_030))
        assertEquals(DROP, s.offer(UiSound.Navigate, 1_059))
        assertEquals(PLAY, s.offer(UiSound.Navigate, 1_060))
    }

    @Test
    fun holdingTheStickBendsThePitchAndSingleStepsDoNot() {
        assertEquals(PLAY, s.offer(UiSound.Navigate, 0))
        assertEquals(1f, s.rate, 0f)
        // Repetición del stick (cada 150 ms): la racha sube el tono.
        s.offer(UiSound.Navigate, 150)
        assertNotEquals(1f, s.rate)
        val rates = (2..6).map { s.offer(UiSound.Navigate, it * 150L); s.rate }
        assertTrue(rates.all { it in 1f..1.1f })
        // Un paso suelto, pasado un rato: tono original.
        s.offer(UiSound.Navigate, 5_000)
        assertEquals(1f, s.rate, 0f)
    }

    @Test
    fun selectWaitsAndIsReplacedByAStrongerSound() {
        assertEquals(DEFER, s.offer(UiSound.Select, 0))
        assertNull(s.due(10))
        // A abrió un menú: suena "abrir", no "aceptar".
        assertEquals(PLAY, s.offer(UiSound.Open, 16))
        assertNull(s.pending)
        assertNull(s.due(DEFER_MS + 5))
    }

    @Test
    fun selectAloneSoundsAfterTheWait() {
        assertEquals(DEFER, s.offer(UiSound.Select, 0))
        assertNull(s.due(DEFER_MS - 1))
        assertEquals(UiSound.Select, s.due(DEFER_MS))
        assertNull(s.due(DEFER_MS + 10))
    }

    @Test
    fun weakerSoundRightAfterAStrongOneIsDropped() {
        assertEquals(PLAY, s.offer(UiSound.Close, 0))
        // La pantalla cambió a la vez que se cerró el menú: una sola señal.
        assertEquals(DEFER, s.offer(UiSound.Back, 10))
        assertNull(s.due(10 + DEFER_MS))
        assertEquals(DROP, s.offer(UiSound.ToggleOn, 20))
        assertEquals(DROP, s.offer(UiSound.Navigate, 30))
        // Uno más fuerte sí pasa.
        assertEquals(PLAY, s.offer(UiSound.Error, 40))
    }

    @Test
    fun launchSilencesWhatComesAfter() {
        assertEquals(PLAY, s.offer(UiSound.Launch, 0))
        assertEquals(DROP, s.offer(UiSound.Navigate, 200))
        assertEquals(DROP, s.offer(UiSound.Close, 500))
        assertEquals(DROP, s.offer(UiSound.Error, 1_000))
        assertEquals(PLAY, s.offer(UiSound.Navigate, SoundScheduler.LAUNCH_MUTE_MS + 1))
    }

    @Test
    fun muteDropsPendingAndUnmuteRestores() {
        s.offer(UiSound.Select, 0)
        s.muteFor(10, 10_000)
        assertNull(s.due(100))
        assertEquals(DROP, s.offer(UiSound.Open, 200))
        s.unmute()
        assertEquals(PLAY, s.offer(UiSound.Open, 300))
    }

    @Test
    fun volumeCurveIsMonotonicAndBounded() {
        assertEquals(0f, SoundManager.gainFor(0), 0f)
        assertEquals(1f, SoundManager.gainFor(100), 1e-6f)
        assertEquals(1f, SoundManager.gainFor(150), 1e-6f)
        var prev = -1f
        for (v in 0..100) {
            val g = SoundManager.gainFor(v)
            assertTrue(g >= prev)
            prev = g
        }
    }
}

class SoundSettingsTest {

    private class MapKeyValues : KeyValues {
        val map = HashMap<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun getString(key: String) = map[key] as? String
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test
    fun defaultsWhenNothingStored() {
        val s = SoundSettings(MapKeyValues())
        assertTrue(s.enabled)
        assertEquals(SoundSettings.DEFAULT_VOLUME, s.volume)
        assertTrue(s.navigation)
        assertEquals(SoundPack.Console, s.pack)
        UiSound.entries.forEach { assertNull(s.custom(it)) }
    }

    @Test
    fun valuesSurviveANewInstance() {
        val kv = MapKeyValues()
        SoundSettings(kv).apply {
            enabled = false
            volume = 35
            navigation = false
            pack = SoundPack.Retro
            setCustom(UiSound.Launch, "custom_launch.ogg")
        }
        val again = SoundSettings(kv)
        assertEquals(false, again.enabled)
        assertEquals(35, again.volume)
        assertEquals(false, again.navigation)
        assertEquals(SoundPack.Retro, again.pack)
        assertEquals("custom_launch.ogg", again.custom(UiSound.Launch))
        assertNull(again.custom(UiSound.Select))
        again.setCustom(UiSound.Launch, null)
        assertNull(SoundSettings(kv).custom(UiSound.Launch))
    }

    @Test
    fun musicSettingsDefaultAndPersist() {
        val kv = MapKeyValues()
        val s = SoundSettings(kv)
        assertTrue(s.musicEnabled)
        assertEquals(SoundSettings.DEFAULT_MUSIC_VOLUME, s.musicVolume)
        assertNull(s.musicUri)
        s.musicEnabled = false
        s.musicVolume = 120
        s.musicUri = "content://docs/audio/7"
        val again = SoundSettings(kv)
        assertEquals(false, again.musicEnabled)
        assertEquals(100, again.musicVolume)
        assertEquals("content://docs/audio/7", again.musicUri)
        again.musicUri = null
        assertNull(SoundSettings(kv).musicUri)
    }

    @Test
    fun volumeIsClampedAndUnknownPackFallsBack() {
        val kv = MapKeyValues()
        val s = SoundSettings(kv)
        s.volume = 180
        assertEquals(100, s.volume)
        s.volume = -3
        assertEquals(0, s.volume)
        kv.map["sound.pack"] = "vaporwave"
        assertEquals(SoundPack.DEFAULT, s.pack)
        s.pack = SoundPack.Off
        assertEquals(SoundPack.Off, s.pack)
    }

    @Test
    fun everyPackHasAResourceNameForEveryEventExceptOff() {
        for (pack in SoundPack.entries) for (sound in UiSound.entries) {
            val name = pack.rawName(sound)
            if (pack == SoundPack.Off) assertNull(name) else assertEquals("ui_${pack.id}_${sound.id}", name)
        }
    }
}

class CustomSoundRulesTest {

    @Test
    fun acceptsShortSmallDecodableAudio() {
        assertNull(CustomSoundRules.check(UiSound.Select, 40_000, 300, decodable = true))
        assertNull(CustomSoundRules.check(UiSound.Launch, 400_000, 3_500, decodable = true))
    }

    @Test
    fun rejectsWithTheRightReason() {
        assertEquals(CustomSoundRules.Problem.TooBig, CustomSoundRules.check(UiSound.Select, CustomSoundRules.MAX_BYTES + 1, 200, true))
        assertEquals(CustomSoundRules.Problem.NotAudio, CustomSoundRules.check(UiSound.Select, 1_000, null, true))
        assertEquals(CustomSoundRules.Problem.NotAudio, CustomSoundRules.check(UiSound.Select, 1_000, 200, false))
        assertEquals(CustomSoundRules.Problem.TooLong, CustomSoundRules.check(UiSound.Navigate, 10_000, 1_501, true))
        assertEquals(CustomSoundRules.Problem.TooShort, CustomSoundRules.check(UiSound.Navigate, 500, 5, true))
        // El de lanzar puede ser más largo, pero no infinito.
        assertNull(CustomSoundRules.check(UiSound.Launch, 10_000, 2_000, true))
        assertEquals(CustomSoundRules.Problem.TooLong, CustomSoundRules.check(UiSound.Launch, 10_000, 4_001, true))
    }

    @Test
    fun sizeIsCheckedBeforeDecoding() {
        assertEquals(CustomSoundRules.Problem.TooBig, CustomSoundRules.check(UiSound.Select, 5_000_000, null, false))
    }

    @Test
    fun extensionComesFromTheNameOrTheType() {
        assertEquals("ogg", CustomSoundRules.extensionFor("click.OGG", null))
        assertEquals("mp3", CustomSoundRules.extensionFor("sin extension", "audio/mpeg"))
        assertEquals("wav", CustomSoundRules.extensionFor(null, "audio/x-wav"))
        assertEquals("bin", CustomSoundRules.extensionFor("../../raro.exe-x", "application/octet-stream"))
    }
}

class UiSoundStateTest {

    private fun st(overlays: Int = 0, error: Boolean = false, screen: Screen = Screen.Library) = UiSoundState(overlays, error, screen)

    @Test
    fun overlaysOpenCloseAndErrors() {
        assertEquals(UiSound.Open, UiSoundState.soundFor(st(), st(overlays = 1)))
        assertEquals(UiSound.Close, UiSoundState.soundFor(st(overlays = 2), st(overlays = 1)))
        assertEquals(UiSound.Error, UiSoundState.soundFor(st(), st(overlays = 1, error = true)))
        // Cerrar el diálogo de error suena a cerrar.
        assertEquals(UiSound.Close, UiSoundState.soundFor(st(overlays = 1, error = true), st()))
        assertNull(UiSoundState.soundFor(st(overlays = 1), st(overlays = 1)))
    }

    @Test
    fun screensForwardAndBack() {
        assertEquals(UiSound.Select, UiSoundState.soundFor(st(), st(screen = Screen.Folder)))
        assertEquals(UiSound.Back, UiSoundState.soundFor(st(screen = Screen.Folder), st()))
        assertEquals(UiSound.Select, UiSoundState.soundFor(st(screen = Screen.Settings), st(screen = Screen.VoiceSync)))
        assertEquals(UiSound.Back, UiSoundState.soundFor(st(screen = Screen.Licenses), st(screen = Screen.Settings)))
        assertEquals(UiSound.Select, UiSoundState.soundFor(st(screen = Screen.Folder), st(screen = Screen.Settings)))
    }
}
