package com.elyndra.launcher.library

/* ─────────────────────────────────────────────────────────────
   ¿El nombre de un juego sirve para buscarlo y enseñarlo?

   Hay juegos que entran sin nombre de verdad: una app sin etiqueta
   (sale su paquete, "com.studio.game"), una carpeta de PS4 sin
   PARAM.SFO ("CUSA01715"), un volcado con su número de serie
   ("SLUS_012.34"), un hash, solo cifras, "Unknown" o el nombre del
   archivo con su extensión. Con eso los metadatos no encuentran
   nada: se marcan como "sin nombre" para que el usuario los
   identifique (ver IdentifyController).

   Kotlin puro: se prueba en la JVM.
   ───────────────────────────────────────────────────────────── */

object NameCheck {

    /** Palabras que no son un nombre de juego por sí solas. */
    private val PLACEHOLDERS = setOf(
        "unknown", "untitled", "game", "default", "null", "none", "rom", "noname", "no name", "new folder",
        "nueva carpeta", "sin titulo", "sin título", "desconocido", "app", "test", "temp", "tmp", "?", "-",
    )

    /** Extensiones que no tienen que quedar al final de un nombre. */
    private val EXTENSIONS = setOf(
        "iso", "bin", "cue", "chd", "cso", "pbp", "img", "nsp", "xci", "nca", "nro", "zip", "7z", "rar", "apk", "exe",
        "nes", "sfc", "smc", "gba", "gbc", "gb", "nds", "3ds", "cia", "n64", "z64", "v64", "md", "gen", "sms", "gg",
        "wbfs", "rvz", "gcz", "wad", "pkg", "elf", "vpk", "xex", "lnk", "bat", "m3u", "gdi", "cdi", "ps3", "psvita",
    )

    /** Id de paquete de Android: segmentos en minúsculas separados por puntos ("com.studio.game"). */
    private val PACKAGE_ID = Regex("""^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*){2,}$""")
    /** Primeros segmentos típicos de un paquete: con ellos, la propuesta es el último trozo. */
    private val PACKAGE_ROOTS = setOf("com", "org", "net", "io", "jp", "de", "fr", "es", "br", "ru", "cn", "uk", "co", "me", "tv", "air", "games", "app")
    private val HEX_HASH = Regex("""^[0-9a-f]{16,}$""", RegexOption.IGNORE_CASE)
    private val UUID = Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""", RegexOption.IGNORE_CASE)
    /** Números de serie de consola: CUSA01715, SLUS_012.34, BLES-01234, PCSE00123, ULUS10041… */
    private val SERIAL = Regex("""^[A-Z]{4}([-_ ]\d{3}\.\d{2}|[-_ ]?\d{5})$""", RegexOption.IGNORE_CASE)
    /** Id de título de Switch (0100…), o de Wii U/3DS en hexadecimal largo. */
    private val TITLE_ID = Regex("""^0[01]0[0-9a-f]{13}$""", RegexOption.IGNORE_CASE)
    private val TRAILING_EXT = Regex("""\.([a-z0-9]{1,6})$""", RegexOption.IGNORE_CASE)

    /** ¿Sirve [name] como nombre de juego? */
    fun isNameUsable(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        val lower = n.lowercase()
        if (lower in PLACEHOLDERS) return false
        if (n.count { it.isLetter() } < 2) return false
        if (UUID.matches(n) || HEX_HASH.matches(n) || TITLE_ID.matches(n)) return false
        if (SERIAL.matches(n)) return false
        if (PACKAGE_ID.matches(n)) return false
        TRAILING_EXT.find(n)?.let { if (it.groupValues[1].lowercase() in EXTENSIONS) return false }
        // Nombre de archivo en crudo: sin espacios y troceado con _ o . ("super_mario_kart_usa").
        if (!n.contains(' ') && n.count { it == '_' || it == '.' } >= 2) return false
        return true
    }

    private val BRACKETS = Regex("""\([^)]*\)|\[[^\]]*\]|\{[^}]*\}""")
    private val SPACES = Regex("""\s+""")

    /**
     * Una propuesta de nombre a partir del crudo: sin extensión, sin
     * etiquetas de región o revisión, con _ y . convertidos en espacios.
     * "super_mario_bros_(USA).nes" → "Super Mario Bros"; un paquete, su
     * último trozo ("com.studio.space_runner" → "Space Runner").
     */
    fun guess(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return ""
        TRAILING_EXT.find(s)?.let { if (it.groupValues[1].lowercase() in EXTENSIONS) s = s.substring(0, it.range.first) }
        if (PACKAGE_ID.matches(s) && s.substringBefore('.') in PACKAGE_ROOTS) s = s.substringAfterLast('.')
        s = BRACKETS.replace(s, " ")
        // Los puntos de en medio son separadores ("Super.Mario.Bros"), pero no el de "Dr. Mario".
        if (!s.contains(' ')) s = s.replace('.', ' ')
        s = s.replace('_', ' ')
        s = SPACES.replace(s, " ").trim().trim('-', ' ')
        if (s.isEmpty()) return ""
        // Todo en minúsculas o todo en mayúsculas: se ponen mayúsculas de título.
        val letters = s.filter { it.isLetter() }
        if (letters.isNotEmpty() && (letters.all { it.isLowerCase() } || letters.all { it.isUpperCase() })) {
            s = s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
        }
        return s
    }
}
