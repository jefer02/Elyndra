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

    @Test
    fun `v4 hides the chat once for existing installs, then respects the choice`() {
        val prefs = MemoryPrefs()
        // Instalación de antes de v4 con el chat guardado a la vista.
        prefs.edit().putBoolean("lucy.chatVisible", true).apply()
        val store = SettingsStore(prefs)
        assertFalse(store.lucyChatVisible)
        assertTrue(prefs.getBoolean(SettingsStore.LUCY_CHAT_RESET_KEY, false))
        store.lucyChatVisible = true
        assertTrue(SettingsStore(prefs).lucyChatVisible)
    }

    @Test
    fun `suggestions are hidden and the mic is unlocked by default`() {
        val store = SettingsStore(MemoryPrefs())
        assertFalse(store.lucySuggestions)
        assertFalse(store.lucyMicLocked)
        assertFalse(store.lucyMicAsked)
    }
}
