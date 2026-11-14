package com.elyndra.launcher.ui.masha

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lo que el reconocedor avisa llega una vez a quien escucha. El fin de voz
 * (onEndOfSpeech) se llamaba a sí mismo en vez de al callback: la app se
 * cerraba (StackOverflowError) en cuanto el usuario dejaba de hablar.
 */
class MashaEarsTest {

    @Test
    fun endOfSpeechReachesTheCallbackOnce() {
        var ends = 0
        val ears = MashaEars(ContextWrapper(null), MashaPresence(), {}, {}, { _, _ -> }, onSpeechEnd = { ends++ })
        ears.Listener(onDevice = false).onEndOfSpeech()
        assertEquals(1, ends)
    }
}
