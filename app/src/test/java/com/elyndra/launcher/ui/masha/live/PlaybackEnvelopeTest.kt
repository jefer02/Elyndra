package com.elyndra.launcher.ui.masha.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** La referencia de lo que suena: un nivel por bloque, buscado por la trama que suena. */
class PlaybackEnvelopeTest {

    @Test
    fun levelsAreIndexedByOutputFrame() {
        val env = PlaybackEnvelope(blockMs = 10)
        env.reset(sampleRate = 1_000) // bloques de 10 tramas
        env.push(ShortArray(10) { 16384 }) // −6 dBFS
        env.push(ShortArray(10)) // silencio
        env.push(ShortArray(5) { 3277 }) // bloque a medias: aún no cuenta
        assertEquals(-6f, env.levelAt(0), 0.1f)
        assertEquals(-6f, env.levelAt(9), 0.1f)
        assertEquals(PlaybackEnvelope.SILENT, env.levelAt(10))
        assertEquals(PlaybackEnvelope.SILENT, env.levelAt(25))
        env.push(ShortArray(5) { 3277 })
        assertEquals(-20f, env.levelAt(25), 0.1f)
    }

    @Test
    fun bytesInStereoAreAveraged() {
        val env = PlaybackEnvelope(blockMs = 10)
        env.reset(sampleRate = 100) // bloque de una trama
        // Una trama estéreo: izquierda a media escala, derecha en silencio → mitad de energía (−9 dBFS).
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0x00)
        env.push(pcm, channels = 2)
        assertEquals(-9f, env.levelAt(0), 0.1f)
    }

    @Test
    fun oldOrFutureFramesAreSilentAndResetStartsOver() {
        val env = PlaybackEnvelope(blockMs = 10, capacity = 4)
        env.reset(sampleRate = 1_000)
        repeat(6) { env.push(ShortArray(10) { 16384 }) }
        assertEquals(PlaybackEnvelope.SILENT, env.levelAt(0)) // ya se salió del anillo
        assertEquals(-6f, env.levelAt(55), 0.1f)
        assertEquals(PlaybackEnvelope.SILENT, env.levelAt(60))
        assertEquals(PlaybackEnvelope.SILENT, env.levelAt(-1))
        env.reset(sampleRate = 1_000)
        assertEquals(PlaybackEnvelope.SILENT, env.levelAt(5))
    }
}
