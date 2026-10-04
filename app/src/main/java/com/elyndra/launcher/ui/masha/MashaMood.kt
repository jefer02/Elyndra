package com.elyndra.launcher.ui.masha

import com.elyndra.launcher.masha.MashaTools

/**
 * El ánimo de Masha, tal como se ve: el tono de su piel holográfica, el color
 * de su brillo y cuánta energía (partículas, pulso) desprende. La cara de cada
 * ánimo (qué morphs mueve) está en `MoodFace`.
 *
 * Frío y azul cuando analiza; lavanda cuando juega o se emociona. Los colores
 * son ARGB y están pensados para mezclarse entre sí sin pasar por grises.
 */
enum class MashaMood(val skin: Long, val glow: Long, val energy: Float) {
    Neutral(0xFF4A92FF, 0xFF86D6FF, 0.50f),
    Analytical(0xFF3AA2FF, 0xFF62EAFF, 0.55f),
    Playful(0xFF8F72FF, 0xFFD0B2FF, 0.85f),
    Warm(0xFFA57BFF, 0xFFE8B6FF, 0.65f),
    Thinking(0xFF4A86FF, 0xFF8DF5FF, 0.95f),
    Concerned(0xFF5068D8, 0xFF9DB0FF, 0.35f),
    /** Pregunta algo y espera la respuesta: interesada. Azul claro, entre Neutral y Thinking. */
    Curious(0xFF4C9BFF, 0xFF9FE6FF, 0.70f),
    ;

    companion object {
        /** Herramientas de consulta: la respuesta es de datos, así que analiza. */
        private val ANALYTIC_TOOLS = setOf(
            MashaTools.GET_STATS,
            MashaTools.CURATION_REPORT,
            MashaTools.GET_GAME_PROFILE,
            MashaTools.SUGGEST_EMULATOR,
            MashaTools.FIND_GAMES,
            MashaTools.GET_ARCS,
            MashaTools.PLAN_SESSION,
        )

        private val PLAYFUL = listOf(
            "jaja", "jeje", "haha", "hehe", "lol", "xd", "mdr", "kkk", "rsrs", "www", "笑",
            "😏", "😄", "😉", "😂", "😜", "🎮", "✨", "por supuesto que sí", "obviously", "évidemment",
        )

        private val WARM = listOf(
            "lo siento", "siento que", "ánimo", "tranquil", "entiendo", "no pasa nada", "me alegra",
            "sorry", "i understand", "don't worry", "glad", "proud of you",
            "désolé", "je comprends", "ne t'inquiète", "desculpa", "entendo", "fico feliz",
            "tut mir leid", "ich verstehe", "keine sorge", "ごめん", "大丈夫", "嬉しい",
        )

        /**
         * Lee el ánimo de una respuesta: primero lo que hizo (herramientas), luego
         * el tono del texto. Si no hay otra señal y termina preguntando, curiosa;
         * si no, neutral. Es barato y local: no cambia nada de cómo se habla con la IA.
         */
        fun read(text: String, tools: Collection<String>, failed: Boolean): MashaMood {
            if (failed) return Concerned
            val t = text.lowercase()
            val playful = PLAYFUL.count { t.contains(it) } + if (t.count { it == '!' } >= 2) 1 else 0
            val warm = WARM.count { t.contains(it) }
            val digits = t.count { it.isDigit() }
            val analytic = tools.count { it in ANALYTIC_TOOLS } * 2 + if (digits >= 6) 1 else 0
            return when {
                warm > 0 && warm >= playful -> Warm
                playful > 0 && playful >= analytic -> Playful
                analytic > 0 -> Analytical
                asks(t) -> Curious
                else -> Neutral
            }
        }

        /** ¿Acaba preguntando? (la última frase lleva "?", "？" o "؟"; ignora emojis y espacios finales). */
        private fun asks(t: String): Boolean {
            val end = t.trimEnd { !it.isLetterOrDigit() && it != '?' && it != '？' && it != '؟' }
            return end.endsWith('?') || end.endsWith('？') || end.endsWith('؟')
        }
    }
}
