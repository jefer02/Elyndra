package com.elyndra.launcher.ui.masha

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * Sincronía de labios aproximada.
 *
 * El motor de voz avisa de cada palabra al empezar a decirla
 * (`UtteranceProgressListener.onRangeStart`). Con eso se reparte la palabra
 * en visemas —uno por letra que se note en la boca— a lo largo de lo que se
 * calcula que dura, y el render muestrea el resultado en cada fotograma.
 *
 * Si el motor no da rangos (algunos no lo hacen), mientras suena la voz se
 * mueve la boca con un patrón silábico: no es exacto, pero no se queda muda.
 *
 * La tabla letra → visema es la del contrato de la cara v2
 * (`face_contract.json`, `grapheme_to_viseme`): dígrafos primero, vocales
 * más largas que las consonantes (que son cortas y se funden con la vocal
 * siguiente). En español la "v" es bilabial (como la "b"); en inglés, labiodental.
 *
 * Todo se usa desde el hilo principal: los avisos del motor se pasan a él
 * antes de llegar aquí.
 */
class LipSync {

    /**
     * Índices de los visemas en [Weights]. [Open] es cuánto baja la mandíbula
     * (lo suman todos según [OPEN]). [EE] es el visema "e/i" genérico del
     * modelo antiguo (lo usa el patrón silábico de repuesto); las letras usan
     * ya [E] e [I] por separado. Los nuevos van al final para no mover los
     * ordinales de los antiguos.
     */
    enum class V { Open, AA, O, EE, FV, MBP, E, I, U, SS, DD, CH }

    class Weights {
        val v = FloatArray(V.entries.size)
        fun clear() = v.fill(0f)
    }

    private class Key(val at: Long, val viseme: V, val amount: Float)

    private val keys = ArrayList<Key>(64)
    private var wordEnd = 0L
    private var lastRange = 0L
    private var speakingSince = 0L
    private var speaking = false
    /** ms por letra a velocidad 1: una media razonable para español/inglés. */
    private var msPerChar = BASE_MS_PER_CHAR
    /** Español (y portugués): la "v" se dice con los labios juntos. */
    private var bilabialV = true

    /** Sube con cada palabra: el rig la usa para acentos de ceja. */
    var wordSeq = 0
        private set

    fun setRate(rate: Float) {
        msPerChar = BASE_MS_PER_CHAR / rate.coerceIn(0.5f, 2f)
    }

    /** Idioma de la voz (etiqueta BCP 47): decide cómo se dice la "v". */
    fun setLanguage(tag: String) {
        val lang = tag.substringBefore('-').substringBefore('_').lowercase()
        bilabialV = lang == "es" || lang == "pt"
    }

    fun onSpeechStart(now: Long) {
        speaking = true
        speakingSince = now
        keys.clear()
    }

    fun onSpeechEnd() {
        speaking = false
        keys.clear()
    }

    /** Empieza la palabra [word] en el instante [now] (System.nanoTime). */
    fun onWord(word: String, now: Long) {
        lastRange = now
        keys.clear()
        val letters = normalize(word).filter { it.isLetter() }
        if (letters.isEmpty()) return
        wordSeq++
        val step = msPerChar * 1_000_000f
        var t = now
        var i = 0
        while (i < letters.length) {
            val g = grapheme(letters, i, bilabialV)
            if (g.viseme != null) {
                keys += Key(t, g.viseme, g.amount)
                // Vocales largas, consonantes cortas (40–70 ms a velocidad 1).
                t += (step * if (g.vowel) VOWEL_LENGTH else CONSONANT_LENGTH).toLong()
            }
            i += g.length
        }
        keys += Key(t, V.Open, 0.05f)
        wordEnd = t
    }

    /**
     * Pesos de la boca en [now]: interpola entre visemas con una ventana corta
     * (la boca no salta de forma a forma). [out] se reutiliza para no crear
     * basura en cada fotograma.
     */
    fun sample(now: Long, out: Weights) {
        out.clear()
        if (!speaking) return
        if (keys.isNotEmpty() && now <= wordEnd + 90_000_000L) {
            val blend = 55_000_000f
            // Índices y no iterador: esto corre en cada fotograma.
            for (i in 0 until keys.size) {
                val k = keys[i]
                val d = (now - k.at).toFloat()
                // Triángulo centrado en cada clave: sube antes, cae después.
                val w = 1f - abs(d) / blend
                if (w > 0f) {
                    val o = k.viseme.ordinal
                    out.v[o] = max(out.v[o], k.amount * w)
                    out.v[V.Open.ordinal] = max(out.v[V.Open.ordinal], k.amount * w * OPEN[o])
                }
            }
            return
        }
        // Sin rangos del motor: sílabas de ~180 ms con algo de variación.
        if (now - lastRange > 400_000_000L) {
            val s = (now - speakingSince) / 1_000_000_000.0
            val syll = 0.5 + 0.5 * sin(s * 2 * Math.PI * 5.4)
            val var2 = 0.5 + 0.5 * sin(s * 2 * Math.PI * 1.7 + 1.3)
            val open = (0.15 + 0.55 * syll * (0.6 + 0.4 * var2)).toFloat()
            out.v[V.Open.ordinal] = open
            out.v[V.AA.ordinal] = open * 0.6f
            out.v[V.EE.ordinal] = (1 - var2).toFloat() * 0.3f
        }
    }

    /** Un grafema: cuántas letras ocupa y qué visema (null = mudo, sin tiempo). */
    internal class Grapheme(val length: Int, val viseme: V?, val amount: Float, val vowel: Boolean)

    internal companion object {
        const val BASE_MS_PER_CHAR = 62f
        const val VOWEL_LENGTH = 1.25f
        const val CONSONANT_LENGTH = 0.8f

        /** Cuánto abre la mandíbula cada visema (además de su forma), por ordinal de [V]. */
        val OPEN = floatArrayOf(
            1f, // Open
            0.85f, // AA
            0.45f, // O
            0.25f, // EE
            0.05f, // FV
            0f, // MBP
            0.35f, // E
            0.18f, // I
            0.2f, // U
            0.08f, // SS
            0.2f, // DD
            0.12f, // CH
        )

        private val MARKS = Regex("\\p{Mn}+")

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

        private const val VOWELS = "aeiou"

        /**
         * El grafema que empieza en [i] de [s] (ya normalizado: minúsculas, sin
         * tildes; la ñ llega como n). Tabla del contrato de la cara v2.
         */
        fun grapheme(s: String, i: Int, bilabialV: Boolean): Grapheme {
            val c = s[i]
            val next = s.getOrNull(i + 1)
            val after = s.getOrNull(i + 2)
            // Dígrafos primero.
            when {
                (c == 'c' || c == 's') && next == 'h' -> return Grapheme(2, V.CH, 1f, false)
                c == 'l' && next == 'l' -> return Grapheme(2, V.CH, 0.7f, false)
                c == 'r' && next == 'r' -> return Grapheme(2, V.DD, 0.8f, false)
                c == 't' && next == 'h' -> return Grapheme(2, V.DD, 0.7f, false)
                // "qu" y "gue/gui": la u no suena.
                c == 'q' && next == 'u' -> return Grapheme(2, V.DD, 0.6f, false)
                c == 'g' && next == 'u' && (after == 'e' || after == 'i') -> return Grapheme(2, V.DD, 0.6f, false)
            }
            return when (c) {
                'a' -> Grapheme(1, V.AA, 1f, true)
                'e' -> Grapheme(1, V.E, 1f, true)
                'i' -> Grapheme(1, V.I, 1f, true)
                'o' -> Grapheme(1, V.O, 1f, true)
                'u' -> Grapheme(1, V.U, 1f, true)
                // La "y" final o entre consonantes suena a "i"; delante de vocal es consonante (CH suave).
                'y' -> if (next != null && next in VOWELS) Grapheme(1, V.CH, 0.6f, false) else Grapheme(1, V.I, 0.8f, true)
                'w' -> Grapheme(1, V.U, 0.9f, false)
                'm', 'b', 'p' -> Grapheme(1, V.MBP, 1f, false)
                'v' -> if (bilabialV) Grapheme(1, V.MBP, 0.7f, false) else Grapheme(1, V.FV, 1f, false)
                'f' -> Grapheme(1, V.FV, 1f, false)
                's' -> Grapheme(1, V.SS, 1f, false)
                'z' -> Grapheme(1, V.SS, 0.9f, false)
                // "ce/ci" es /s/ o /θ/; "ca/co/cu" y la c final, /k/.
                'c' -> if (next == 'e' || next == 'i') Grapheme(1, V.SS, 0.8f, false) else Grapheme(1, V.DD, 0.6f, false)
                'x' -> Grapheme(1, V.SS, 0.7f, false)
                'j' -> Grapheme(1, V.CH, 0.6f, false)
                'g', 'k', 'q' -> Grapheme(1, V.DD, 0.6f, false)
                't', 'd' -> Grapheme(1, V.DD, 1f, false)
                'n', 'l' -> Grapheme(1, V.DD, 0.8f, false)
                'r' -> Grapheme(1, V.DD, 0.6f, false)
                // La h no suena: ni forma ni tiempo.
                'h' -> Grapheme(1, null, 0f, false)
                // Kana/kanji y el resto: abrir un poco.
                else -> Grapheme(1, V.Open, 0.35f, true)
            }
        }
    }
}
