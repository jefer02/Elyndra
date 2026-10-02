package com.elyndra.launcher.ui.masha.voice.supertonic

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * Texto → ids de caracteres de Supertonic 3 (Kotlin puro, sin `android.*`).
 *
 * El modelo lee caracteres Unicode sueltos (NFKD) envueltos en etiquetas de idioma:
 * `<es>Hola, soy Masha.</es>`. Las etiquetas son texto normal (`<`, `e`, `s`, `>`);
 * no hay fonemas ni espeak.
 *
 * Portado de sherpa-onnx `offline-tts-supertonic-unicode-processor.cc` (Apache-2.0,
 * © 2026 zengyw) y de `py/helper.py` de Supertone (MIT, © 2025 Supertone Inc.). Se
 * sigue el orden de sherpa (limpieza → etiquetas → NFKD) para dar los mismos ids.
 *
 * Diferencia deliberada: los caracteres que el índice no conoce (-1) se quitan; sherpa
 * los pasa como -1 (que el embedding lee como la última fila, U+FFFD).
 *
 * Etiquetas de idioma MEZCLADAS en una misma llamada (`<es>…</es><en>…</en>`) NO
 * funcionan: el modelo balbucea (probado con Whisper, WER ≈ 0.95). Para mezclar
 * idiomas se sintetiza cada tramo por separado ([SupertonicModel.synthesizeMixed]).
 */
object SupertonicText {

    /** Idiomas que entiende el modelo (`na` = que lo deduzca él). */
    val LANGS: Set<String> = setOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et",
        "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl",
        "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi", "na",
    )

    /** Longitud máxima (caracteres) por llamada, como la referencia: 120 en ko/ja, 300 el resto. */
    fun maxLen(lang: String): Int = if (lang == "ko" || lang == "ja") 120 else 300

    private val REPLACEMENTS = arrayOf(
        "–" to "-", "‑" to "-", "—" to "-", "_" to " ",
        "“" to "\"", "”" to "\"", "‘" to "'", "’" to "'",
        "´" to "'", "`" to "'", "[" to " ", "]" to " ", "|" to " ", "/" to " ",
        "#" to " ", "→" to " ", "←" to " ",
        "♥" to "", "☆" to "", "♡" to "", "©" to "", "\\" to "",
        "@" to " at ", "e.g.," to "for example, ", "i.e.," to "that is, ",
    )

    /** Signos que ya cierran la frase (si no, se añade un punto). */
    private const val END_ASCII = ".!?;:,'\")]}>"
    private const val END_OTHER = "…。」』】〉》›»“”‘’"

    /** Limpieza de la referencia: símbolos, emoji, espacios ante puntuación, comillas dobles y espacios repetidos. */
    fun clean(text: String): String {
        var s = text
        for ((a, b) in REPLACEMENTS) if (s.contains(a)) s = s.replace(a, b)
        val sb = StringBuilder(s.length)
        // Emoji y demás fuera del plano básico: el modelo no los conoce.
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp <= 0xFFFF) sb.append(cp.toChar())
            i += Character.charCount(cp)
        }
        s = sb.toString(); sb.setLength(0)
        // " ," → ","  (y . ! ? ; : ')
        i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ' ' && i + 1 < s.length && s[i + 1] in ",.!?;:'") { sb.append(s[i + 1]); i += 2; continue }
            sb.append(c); i++
        }
        s = sb.toString(); sb.setLength(0)
        // "" → "  y  '' → '
        i = 0
        while (i < s.length) {
            val c = s[i]
            if ((c == '"' || c == '\'') && i + 1 < s.length && s[i + 1] == c) { sb.append(c); i += 2; continue }
            sb.append(c); i++
        }
        s = sb.toString(); sb.setLength(0)
        // Espacios ASCII repetidos → uno; recorte.
        var lastSpace = false
        for (c in s) {
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C') {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else { sb.append(c); lastSpace = false }
        }
        return sb.toString().trim(' ', '\t', '\n', '\r', '\u000B', '\u000C')
    }

    /** ¿Termina en puntuación (o comilla / cierre)? */
    fun endsWithPunct(s: String): Boolean {
        if (s.isEmpty()) return false
        val c = s[s.length - 1]
        return c in END_ASCII || c in END_OTHER
    }

    /**
     * Texto listo para el modelo: limpio, con punto final si no cierra ya, y envuelto en
     * `<lang>…</lang>`. Vacío si no queda nada que decir. [addPeriod] = false deja la frase
     * abierta (tramos intermedios de una frase partida por idioma).
     */
    fun tag(text: String, lang: String, addPeriod: Boolean = true): String {
        require(lang in LANGS) { "Idioma no soportado por Supertonic: $lang" }
        var s = clean(text)
        if (s.isEmpty()) return ""
        if (addPeriod && !endsWithPunct(s)) s += "."
        return "<$lang>$s</$lang>"
    }

    /** NFKD y paso a ids con el índice (`unicode_indexer.bin`); se quitan los desconocidos. */
    fun toIds(tagged: String, indexer: IntArray): LongArray {
        val nfkd = Normalizer.normalize(tagged, Normalizer.Form.NFKD)
        val out = LongArray(nfkd.length)
        var n = 0
        for (c in nfkd) {
            if (Character.isSurrogate(c)) continue
            val id = if (c.code < indexer.size) indexer[c.code] else -1
            if (id >= 0) out[n++] = id.toLong()
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    /** Atajo: [tag] + [toIds]. Vacío si no hay texto. */
    fun encode(text: String, lang: String, indexer: IntArray, addPeriod: Boolean = true): LongArray {
        val t = tag(text, lang, addPeriod)
        return if (t.isEmpty()) LongArray(0) else toIds(t, indexer)
    }

    /** Lee `unicode_indexer.bin`: 65536 int32 little-endian (código BMP → id, -1 si no existe). */
    fun loadIndexer(file: File): IntArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0 && bytes.isNotEmpty()) { "unicode_indexer.bin inválido (${bytes.size} B)" }
        val out = IntArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(out)
        return out
    }

    private const val SENTENCE_END = ".!?…。！？"

    /**
     * Parte un texto largo en trozos de como mucho [maxLen] caracteres: por frases
     * (tras `. ! ? …` + espacio), y las frases demasiado largas por el último espacio
     * o coma antes del límite. Los trozos cortos seguidos se juntan mientras quepan.
     */
    fun chunk(text: String, maxLen: Int): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length <= maxLen) return listOf(t)
        val sentences = ArrayList<String>()
        var start = 0
        for (i in t.indices) {
            if (t[i] in SENTENCE_END && (i + 1 == t.length || t[i + 1].isWhitespace())) {
                sentences += t.substring(start, i + 1).trim(); start = i + 1
            }
        }
        if (start < t.length) sentences += t.substring(start).trim()
        val pieces = ArrayList<String>()
        for (s in sentences) {
            var rest = s
            while (rest.length > maxLen) {
                var cut = -1
                for (k in maxLen downTo 1) {
                    val c = rest[k - 1]
                    if (c == ' ' || c == ',' || c == ';' || c == ':') { cut = k; break }
                }
                if (cut <= 0) cut = maxLen
                pieces += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) pieces += rest
        }
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (p in pieces) {
            if (p.isEmpty()) continue
            if (cur.isEmpty()) cur.append(p)
            else if (cur.length + 1 + p.length <= maxLen) cur.append(' ').append(p)
            else { out += cur.toString(); cur.setLength(0); cur.append(p) }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
