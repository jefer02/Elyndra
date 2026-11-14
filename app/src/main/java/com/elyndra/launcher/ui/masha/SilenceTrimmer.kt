package com.elyndra.launcher.ui.masha

import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Recorta el silencio que el motor de voz pone al principio y al final de
 * cada frase (PCM de 16 bits, por trozos según llega; pura).
 *
 * El TTS del sistema deja ~20–50 ms callado antes y ~340 ms después de cada
 * frase (medido en la tablet): entre frase y frase sonaba más de medio
 * segundo de nada y parecía que se detenía. Se dejan [LEAD_KEEP_MS] antes y
 * [TAIL_KEEP_MS] después (unos 200 ms entre frases, como al hablar); las
 * pausas de dentro de la frase se quedan enteras. Lo recortado al principio
 * ([droppedLeadFrames]) corre las marcas de tiempo del motor.
 */
class SilenceTrimmer(sampleRate: Int, channels: Int = 1, private val threshold: Int = THRESHOLD) {
    private val frameBytes = 2 * channels
    private val leadKeep = sampleRate * LEAD_KEEP_MS / 1000 * frameBytes
    private val tailKeep = sampleRate * TAIL_KEEP_MS / 1000 * frameBytes

    /** Tramas quitadas del principio (para correr las marcas del motor). */
    var droppedLeadFrames = 0
        private set

    private var started = false

    /** Antes de la voz: el silencio más reciente (como mucho [leadKeep]). */
    private val lead = ByteArrayOutputStream()

    /** Ya con voz: el silencio que lleva desde la última muestra con sonido (se suelta si vuelve la voz). */
    private val pending = ByteArrayOutputStream()

    /** Media trama del trozo anterior. */
    private var carry = ByteArray(0)

    /** Lo que ya puede sonar de este trozo. */
    fun process(pcm: ByteArray): ByteArray {
        val data = if (carry.isEmpty()) pcm else carry + pcm
        val whole = data.size - data.size % frameBytes
        carry = data.copyOfRange(whole, data.size)
        val out = ByteArrayOutputStream(whole)
        var f = 0
        while (f < whole) {
            if (voiced(data, f)) {
                if (!started) {
                    started = true
                    if (lead.size() > leadKeep) dropLead()
                    lead.writeTo(out)
                    lead.reset()
                }
                pending.writeTo(out)
                pending.reset()
                out.write(data, f, frameBytes)
            } else if (started) {
                pending.write(data, f, frameBytes)
            } else {
                lead.write(data, f, frameBytes)
                if (lead.size() > leadKeep * 2) dropLead()
            }
            f += frameBytes
        }
        if (!started && lead.size() > leadKeep) dropLead()
        return out.toByteArray()
    }

    /** Al acabar la frase: lo que queda del silencio final, hasta [TAIL_KEEP_MS]. Sin voz, nada. */
    fun finish(): ByteArray {
        if (!started) return ByteArray(0)
        val tail = pending.toByteArray()
        pending.reset()
        return tail.copyOfRange(0, minOf(tail.size, tailKeep))
    }

    private fun voiced(data: ByteArray, at: Int): Boolean {
        var i = at
        while (i < at + frameBytes) {
            val s = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
            if (abs(s.toShort().toInt()) > threshold) return true
            i += 2
        }
        return false
    }

    private fun dropLead() {
        val bytes = lead.toByteArray()
        val keep = bytes.size.coerceAtMost(leadKeep)
        droppedLeadFrames += (bytes.size - keep) / frameBytes
        lead.reset()
        lead.write(bytes, bytes.size - keep, keep)
    }

    companion object {
        const val LEAD_KEEP_MS = 40
        const val TAIL_KEEP_MS = 150

        /** Por debajo, silencio (~−50 dBFS de pico). */
        const val THRESHOLD = 100
    }
}
