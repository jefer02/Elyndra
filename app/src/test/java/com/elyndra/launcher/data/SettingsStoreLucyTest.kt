package com.elyndra.launcher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los ajustes de Lucy v3: chat oculto, subtítulos y cortar con la voz por defecto; lo elegido se recuerda. */
class SettingsStoreLucyTest {

    @Test
    fun `fresh install shows Lucy, not the chat, with subtitles and voice interruptions on`() {
        val store = SettingsStore(MemoryPrefs())
        assertFalse(store.lucyChatVisible)
        assertTrue(store.lucySubtitles)
        assertTrue(store.lucyVoiceInterrupt)
    }

    @Test
    fun `the choice is remembered`() {
        val prefs = MemoryPrefs()
        SettingsStore(prefs).apply {
            lucyChatVisible = true
            lucySubtitles = false
            lucyVoiceInterrupt = false
        }
        val again = SettingsStore(prefs)
        assertTrue(again.lucyChatVisible)
        assertFalse(again.lucySubtitles)
        assertFalse(again.lucyVoiceInterrupt)
    }
}
