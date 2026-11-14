package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.ui.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenReplyTest {

    @Test
    fun herReplyIsReadAsItArrives() {
        val m = ChatMessage(fromMasha = true, text = "Un mando por dentro es más simple de lo que parece.", pending = true)
        assertEquals(m.text, SpokenReply.of(m, alreadyShown = null, speakReplies = true))
    }

    @Test
    fun nothingIsReadWithTheVoiceOffOrForTheUsersMessages() {
        assertNull(SpokenReply.of(ChatMessage(fromMasha = true, text = "Hola"), alreadyShown = null, speakReplies = false))
        assertNull(SpokenReply.of(ChatMessage(fromMasha = false, text = "Hola"), alreadyShown = null, speakReplies = true))
    }

    /**
     * En la tablet: el usuario la corta, su mensaje pasa a ser solo lo que llegó a decir ("…") y ella
     * lo volvía a leer desde el principio mientras el usuario hablaba.
     */
    @Test
    fun aReplyCutOffByTheUserIsNotReadAgain() {
        val cut = ChatMessage(fromMasha = true, text = "Un mando por dentro es más simple de lo que parece. Cada botón…", interrupted = true)
        assertNull(SpokenReply.of(cut, alreadyShown = null, speakReplies = true))
    }
}
