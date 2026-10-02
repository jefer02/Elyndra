package com.elyndra.launcher.ui.masha.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SpeechNormalizerTest {

    private fun n(text: String, lang: String, region: String? = null) = SpeechNormalizer.normalize(text, lang, region)

    /** Comprueba toda la tabla y falla con la lista de diferencias (no solo la primera). */
    private fun check(lang: String, region: String?, table: List<Pair<String, String>>) {
        val bad = table.mapNotNull { (input, want) ->
            val got = n(input, lang, region)
            if (got != want) "  [$lang] \"$input\"\n     quiere: \"$want\"\n     sale:   \"$got\"" else null
        }
        assertTrue("${bad.size} diferencias:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    /* ── frases del encargo ── */

    @Test
    fun `frase de ejemplo en espanol`() {
        assertEquals(
            "Llevas doce horas y cuarenta y cinco minutos jugando; tu batería está al veintitrés por ciento. " +
                "La actualización dos punto uno punto cuatro pesa tres coma cinco gigas.",
            n("Llevas 12 horas y 45 minutos jugando; tu batería está al 23 %. La actualización 2.1.4 pesa 3,5 GB.", "es", "ES"),
        )
    }

    @Test
    fun `frase de ejemplo en ingles`() {
        assertEquals(
            "You've been playing for twelve hours and forty-five minutes; your battery is at twenty-three percent. " +
                "Update two point one point four is three point five gigabytes.",
            n("You've been playing for 12 hours and 45 minutes; your battery is at 23%. Update 2.1.4 is 3.5 GB.", "en", "US"),
        )
    }

    /* ── tablas ── */

    @Test
    fun `espanol tabla`() = check("es", "ES", ES)

    @Test
    fun `ingles tabla`() = check("en", "US", EN)

    @Test
    fun `portugues tabla`() = check("pt", "BR", PT)

    @Test
    fun `frances tabla`() = check("fr", "FR", FR)

    @Test
    fun `aleman tabla`() = check("de", "DE", DE)

    @Test
    fun `japones tabla`() = check("ja", "JP", JA)

    /* ── variantes regionales ── */

    @Test
    fun `espanol de America`() {
        check(
            "es", "MX",
            listOf(
                "Del 1 de enero." to "Del primero de enero.",
                "Cuesta \$10." to "Cuesta diez pesos.",
                "Pesa 1.5 GB." to "Pesa uno punto cinco gigas.",
                "Tengo 1,000 monedas." to "Tengo mil monedas.",
                "Mira la TV." to "Mira la te ve.",
                "El 27/09/2026." to "El veintisiete de septiembre de dos mil veintiséis.",
            ),
        )
        assertEquals("Mira la te uve.", n("Mira la TV.", "es", "ES"))
        assertEquals("Cuesta diez dólares.", n("Cuesta \$10.", "es", "ES"))
    }

    @Test
    fun `ingles britanico y fechas ambiguas`() {
        assertEquals("On May fourth, twenty twenty-six.", n("On 04/05/2026.", "en", "GB"))
        assertEquals("On April fifth, twenty twenty-six.", n("On 04/05/2026.", "en", "US"))
        assertEquals("On April fifth, twenty twenty-six.", n("On 04/05/2026.", "en", null))
        // Si una parte pasa de 12, esa es el día, sea cual sea la región.
        assertEquals("On September twenty-seventh, twenty twenty-six.", n("On 27/09/2026.", "en", "US"))
        assertEquals("one hundred and twenty-five", n("125", "en", "GB"))
        assertEquals("one hundred twenty-five", n("125", "en", "US"))
    }

    @Test
    fun `portugues de Portugal`() {
        assertEquals("dezasseis, dezassete e dezanove.", n("16, 17 e 19.", "pt", "PT"))
        assertEquals("dezesseis, dezessete e dezenove.", n("16, 17 e 19.", "pt", "BR"))
        assertEquals("mil milhões", n("1.000.000.000", "pt", "PT"))
        assertEquals("um bilhão", n("1.000.000.000", "pt", "BR"))
    }

    @Test
    fun `region en otros formatos`() {
        assertEquals(n("Del 1 de enero.", "es", "MX"), n("Del 1 de enero.", "es-MX", "es-MX"))
        assertEquals(n("Del 1 de enero.", "es", "MX"), n("Del 1 de enero.", "ES", "mx"))
    }

    /* ── concordancia y números grandes ── */

    @Test
    fun `espanol genero y apocope`() {
        check(
            "es", "ES",
            listOf(
                "1 hora" to "una hora",
                "21 horas" to "veintiuna horas",
                "31 partidas" to "treinta y una partidas",
                "1 juego" to "un juego",
                "21 juegos" to "veintiún juegos",
                "31 juegos" to "treinta y un juegos",
                "200 partidas" to "doscientas partidas",
                "200 juegos" to "doscientos juegos",
                "21.000 personas" to "veintiuna mil personas",
                "21.000 jugadores" to "veintiún mil jugadores",
                "1 de 3" to "uno de tres",
                "Vas 1 a 0." to "Vas uno a cero.",
                "a la 1 de la tarde" to "a la una de la tarde",
                "las 3" to "las tres",
            ),
        )
    }

    @Test
    fun `numeros grandes`() {
        assertEquals("mil millones", n("1.000.000.000", "es"))
        assertEquals("un billón", n("1.000.000.000.000", "es"))
        assertEquals("dos mil millones quinientos mil", n("2.000.500.000", "es"))
        assertEquals("one billion two hundred million", n("1,200,000,000", "en"))
        assertEquals("un milliard", n("1 000 000 000", "fr"))
        assertEquals("eine Milliarde", n("1.000.000.000", "de"))
        assertEquals("一兆", n("1000000000000", "ja"))
        // Más de 15 cifras: cifra a cifra, sin romperse.
        assertEquals(
            "uno dos tres cuatro cinco seis siete ocho nueve cero uno dos tres cuatro cinco seis",
            n("1234567890123456", "es"),
        )
    }

    @Test
    fun `separadores robustos`() {
        assertEquals("tres coma cinco gigas", n("3,5 GB", "es"))
        assertEquals("tres coma cinco gigas", n("3.5 GB", "es"))
        assertEquals("mil", n("1.000", "es"))
        assertEquals("mil", n("1,000", "es"))
        assertEquals("mil doscientos treinta y cuatro coma cinco", n("1.234,5", "es"))
        assertEquals("mil doscientos treinta y cuatro coma cinco", n("1,234.5", "es"))
        assertEquals("one thousand two hundred thirty-four point five", n("1,234.5", "en"))
        assertEquals("three point five", n("3,5", "en"))
        assertEquals("dos punto uno punto cuatro", n("2.1.4", "es"))
        assertEquals("uno, dos, tres", n("1,2,3", "es"))
        assertEquals("cero cero siete", n("007", "es"))
        assertEquals("tres coma cero cinco", n("3,05", "es"))
    }

    /* ── propiedades ── */

    private val ALL by lazy { listOf("es" to ES, "en" to EN, "pt" to PT, "fr" to FR, "de" to DE, "ja" to JA) }

    @Test
    fun `idempotente en todas las tablas`() {
        for ((lang, table) in ALL) for ((input, _) in table) {
            val once = n(input, lang)
            assertEquals("[$lang] $input", once, n(once, lang))
        }
    }

    @Test
    fun `sin cifras en la salida`() {
        for ((lang, table) in ALL) for ((input, _) in table) {
            assertFalse("[$lang] $input", n(input, lang).any { it in '0'..'9' })
        }
    }

    @Test
    fun `texto sin nada que normalizar no cambia`() {
        for (s in listOf("Hola, ¿qué tal?", "¡Vamos allá!", "Hollow Knight es precioso…", "", "   ", "Ñandú", "こんにちは。")) {
            for (lang in listOf("es", "en", "pt", "fr", "de", "ja")) assertEquals(s, n(s, lang))
        }
    }

    @Test
    fun `idioma desconocido devuelve el texto`() {
        assertEquals("Tengo 3 vidas.", n("Tengo 3 vidas.", "xx"))
        assertEquals("Tengo 3 vidas.", n("Tengo 3 vidas.", ""))
    }

    @Test
    fun `nunca lanza y es idempotente con basura`() {
        val r = Random(7)
        val atoms = listOf(
            "12", "3,5", "2.1.4", "1.000", "21:45", "27/09/2026", "%", " %", "€", "\$", "GB", " GB", "x", "p", "K", "k", "-",
            "–", "/", ":", ".", ",", "º", "ª", "er", "st", "th", "VII", "V", "X", "I", "DLC", "PS5", "Xbox", "Series", "Final",
            "Fantasy", " ", " ", "\u00A0", "de", "la", "horas", "hora", "juego", "a. m.", "pm", "h", "&", "+", "=", "#", "~", "@",
            "é", "ñ", "The", "of", "¿", "?", "¡", "!", "…", "\n", "１２", "時間", "％", "0", "007", "99999999999999999999", "v2",
            "1er", "e.g.", "etc.", "Dr.", "No.", "°C", "°", "(", ")", "\"", "'", "’", "am", "10", "1", "21", "200", "5M", "4x4",
            "1920x1080", "1080p", "masha@elyndra.app", "https://x.io/a?b=1", "\uD83C\uDFAE",
        )
        val langs = listOf("es", "en", "pt", "fr", "de", "ja")
        val regions = listOf(null, "ES", "MX", "US", "GB", "BR", "PT")
        repeat(5000) {
            val sb = StringBuilder()
            repeat(1 + r.nextInt(8)) {
                sb.append(atoms[r.nextInt(atoms.size)])
                if (r.nextBoolean()) sb.append(' ')
            }
            if (r.nextInt(8) == 0) repeat(6) { sb.append((r.nextInt(0xFFFF) + 1).toChar()) }
            val s = sb.toString()
            val lang = langs[r.nextInt(langs.size)]
            val region = regions[r.nextInt(regions.size)]
            val once = n(s, lang, region)
            assertEquals("[$lang/$region] <$s>", once, n(once, lang, region))
        }
    }

    /* ── tablas (entrada → salida) ── */

    private val ES = listOf(
        "Tengo 3 vidas." to "Tengo tres vidas.",
        "Quedan 12 horas." to "Quedan doce horas.",
        "Faltan 21 horas." to "Faltan veintiuna horas.",
        "Tienes 1 hora libre." to "Tienes una hora libre.",
        "Compraste 1 juego." to "Compraste un juego.",
        "Tienes 21 juegos." to "Tienes veintiún juegos.",
        "Jugaste 200 partidas." to "Jugaste doscientas partidas.",
        "Hay 500 personas." to "Hay quinientas personas.",
        "Llevas 101 partidas." to "Llevas ciento una partidas.",
        "El número 1." to "El número uno.",
        "Tengo 1.000 monedas." to "Tengo mil monedas.",
        "Tengo 1,000 monedas." to "Tengo mil monedas.",
        "Hay 1.000.000 de jugadores." to "Hay un millón de jugadores.",
        "Pesa 3,5 GB." to "Pesa tres coma cinco gigas.",
        "Pesa 3.5 GB." to "Pesa tres coma cinco gigas.",
        "Son 12.345 puntos." to "Son doce mil trescientos cuarenta y cinco puntos.",
        "Cuesta 0,5 euros." to "Cuesta cero coma cinco euros.",
        "El nivel 7." to "El nivel siete.",
        "Hace -5 grados." to "Hace menos cinco grados.",
        "Llegó al 23 %." to "Llegó al veintitrés por ciento.",
        "Llegó al 23%." to "Llegó al veintitrés por ciento.",
        "Llegó al 100 %." to "Llegó al cien por ciento.",
        "La versión 2.1.4 ya está." to "La versión dos punto uno punto cuatro ya está.",
        "Instala la versión 2.0." to "Instala la versión dos punto cero.",
        "Actualiza a v1.2.3 hoy." to "Actualiza a versión uno punto dos punto tres hoy.",
        "Cuesta 1 €." to "Cuesta un euro.",
        "Cuesta 5,99 €." to "Cuesta cinco euros con noventa y nueve.",
        "Cuesta 21 €." to "Cuesta veintiún euros.",
        "Cuesta 0,99 €." to "Cuesta noventa y nueve céntimos.",
        "Cuesta 2.000.000 €." to "Cuesta dos millones de euros.",
        "Cuesta \$10." to "Cuesta diez dólares.",
        "Cuesta US\$ 5." to "Cuesta cinco dólares.",
        "Cuesta R\$ 10,50." to "Cuesta diez reales con cincuenta.",
        "Cuesta ¥500." to "Cuesta quinientos yenes.",
        "Cuesta £1." to "Cuesta una libra.",
        "A las 21:45." to "A las veintiuna cuarenta y cinco.",
        "A las 9:05." to "A las nueve y cinco.",
        "A las 9:30." to "A las nueve y media.",
        "A las 10:00." to "A las diez en punto.",
        "A las 0:15." to "A las cero quince.",
        "A las 10 a. m." to "A las diez de la mañana.",
        "A las 7:30 p. m." to "A las siete y media de la tarde.",
        "A las 3 pm." to "A las tres de la tarde.",
        "Dura 01:23:45." to "Dura una hora, veintitrés minutos y cuarenta y cinco segundos.",
        "El 27/09/2026." to "El veintisiete de septiembre de dos mil veintiséis.",
        "El 2026-09-27." to "El veintisiete de septiembre de dos mil veintiséis.",
        "El 27.09.2026 empieza." to "El veintisiete de septiembre de dos mil veintiséis empieza.",
        "El 15/08 abre." to "El quince de agosto abre.",
        "Nació en 1998." to "Nació en mil novecientos noventa y ocho.",
        "Del 1 de enero al 31 de diciembre." to "Del uno de enero al treinta y uno de diciembre.",
        "Entre 10-20 fps." to "Entre diez y veinte efe pe ese.",
        "Tardará 5–10 minutos." to "Tardará cinco a diez minutos.",
        "Juega a 60 fps." to "Juega a sesenta efe pe ese.",
        "Tiene 16 GB de RAM." to "Tiene dieciséis gigas de RAM.",
        "Un disco de 1 TB." to "Un disco de un tera.",
        "A 3,2 GHz." to "A tres coma dos gigahercios.",
        "Pantalla de 144 Hz." to "Pantalla de ciento cuarenta y cuatro hercios.",
        "Batería de 5000 mAh." to "Batería de cinco mil miliamperios hora.",
        "Carga de 65 W." to "Carga de sesenta y cinco vatios.",
        "Ping de 30 ms." to "Ping de treinta milisegundos.",
        "Hace 25 °C." to "Hace veinticinco grados.",
        "Van a 120 km/h." to "Van a ciento veinte kilómetros por hora.",
        "Mide 5 m." to "Mide cinco metros.",
        "Pesa 2 kg." to "Pesa dos kilos.",
        "Resolución 1920x1080." to "Resolución mil novecientos veinte por mil ochenta.",
        "Juega en 4K." to "Juega en cuatro ka.",
        "En 1080p." to "En mil ochenta pe.",
        "Es 4x más rápido." to "Es cuatro veces más rápido.",
        "Gana x2 de experiencia." to "Gana por dos de experiencia.",
        "Tienes 10k seguidores." to "Tienes diez mil seguidores.",
        "Hay 5M de descargas." to "Hay cinco millones de descargas.",
        "Con 5G." to "Con cinco ge.",
        "Quedaste 1.º." to "Quedaste primero.",
        "Quedó 2ª." to "Quedó segunda.",
        "El 3er puesto." to "El tercer puesto.",
        "El 1.er nivel." to "El primer nivel.",
        "Mi 2do intento." to "Mi segundo intento.",
        "Final Fantasy VII es genial." to "Final Fantasy siete es genial.",
        "Grand Theft Auto V vendió mucho." to "Grand Theft Auto cinco vendió mucho.",
        "GTA V vendió mucho." to "ge te a cinco vendió mucho.",
        "Juega Dark Souls III." to "Juega Dark Souls tres.",
        "La Xbox Series X." to "La equis box Series equis.",
        "Mega Man X." to "Mega Man equis.",
        "Estamos en el siglo XXI." to "Estamos en el siglo veintiuno.",
        "Parte I y Parte II." to "Parte uno y Parte dos.",
        "Yo I." to "Yo I.",
        "Compra el DLC." to "Compra el de ele ce.",
        "Un RPG de acción." to "Un erre pe ge de acción.",
        "Juega en PS5." to "Juega en pe ese cinco.",
        "Juega en PC." to "Juega en pe ce.",
        "Habla con el NPC." to "Habla con el ene pe ce.",
        "Te queda poco HP." to "Te queda poco hache pe.",
        "Ganas 500 XP." to "Ganas quinientos equis pe.",
        "Modo co-op." to "Modo cooperativo.",
        "Partidas PvP." to "Partidas pe uve pe.",
        "Fue el GOTY." to "Fue el goti.",
        "Un juego AAA." to "Un juego triple a.",
        "Un MMO clásico." to "Un eme eme o clásico.",
        "Conecta el USB." to "Conecta el u ese be.",
        "Una buena CPU y GPU." to "Una buena ce pe u y ge pe u.",
        "Te lo dije, etc." to "Te lo dije, etcétera.",
        "Juegos de rol, p. ej. Zelda." to "Juegos de rol, por ejemplo Zelda.",
        "El Dr. García." to "El doctor García.",
        "La Sra. López." to "La señora López.",
        "Mario vs. Bowser." to "Mario versus Bowser.",
        "Tarda aprox. 5 minutos." to "Tarda aproximadamente cinco minutos.",
        "El n.º 3." to "El número tres.",
        "El núm. 7." to "El número siete.",
        "Tom & Jerry." to "Tom y Jerry.",
        "2 + 2 = 4." to "dos más dos igual a cuatro.",
        "Escribe a masha@elyndra.app ya." to "Escribe a masha arroba elyndra punto app ya.",
        "Visita elyndra.app/juegos hoy." to "Visita elyndra punto app barra juegos hoy.",
        "Faltan ~5 minutos." to "Faltan unos cinco minutos.",
        "Quedan 10+ horas." to "Quedan más de diez horas.",
        "Es el #1 en ventas." to "Es el número uno en ventas.",
        "Tienes 1/2 barra." to "Tienes medio barra.",
        "Te quedan 3/4 de vida." to "Te quedan tres cuartos de vida.",
        "Disponible 24/7." to "Disponible veinticuatro siete.",
        "Una pantalla 16:9." to "Una pantalla dieciséis a nueve.",
        "Gana 3:1." to "Gana tres a uno.",
        "Tienes 1 vez más." to "Tienes una vez más.",
        "Lo intentó 21 veces." to "Lo intentó veintiuna veces.",
        "Queda 1 semana." to "Queda una semana.",
        "Hay 1 persona." to "Hay una persona.",
        "Dijo 1 y 2." to "Dijo uno y dos.",
        "Salió 1 de 3." to "Salió uno de tres.",
        "¿Tienes 5 minutos?" to "¿Tienes cinco minutos?",
        "¡Ganaste 1.500 monedas!" to "¡Ganaste mil quinientas monedas!",
        "Juega PS5/Xbox." to "Juega pe ese cinco o equis box.",
        "Nivel lvl 5." to "Nivel nivel cinco.",
    )

    private val EN = listOf(
        "I have 3 lives." to "I have three lives.",
        "You have 1 hour left." to "You have one hour left.",
        "It took 21 hours." to "It took twenty-one hours.",
        "There are 1,000 coins." to "There are one thousand coins.",
        "There are 1,000,000 players." to "There are one million players.",
        "It weighs 3.5 GB." to "It weighs three point five gigabytes.",
        "Score: 12,345 points." to "Score: twelve thousand three hundred forty-five points.",
        "It is -5 degrees." to "It is minus five degrees.",
        "Battery at 23%." to "Battery at twenty-three percent.",
        "Battery at 100 %." to "Battery at one hundred percent.",
        "Version 2.1.4 is out." to "Version two point one point four is out.",
        "Update to v1.2.3 today." to "Update to version one point two point three today.",
        "It costs \$5.99." to "It costs five dollars and ninety-nine cents.",
        "It costs \$1." to "It costs one dollar.",
        "It costs €1." to "It costs one euro.",
        "It costs €2.50." to "It costs two euros and fifty cents.",
        "It costs ¥500." to "It costs five hundred yen.",
        "It costs £3.50." to "It costs three pounds and fifty pence.",
        "It costs \$0.99." to "It costs ninety-nine cents.",
        "A \$1.5M budget." to "A one point five million dollars budget.",
        "At 9:05." to "At nine oh five.",
        "At 10:00." to "At ten o'clock.",
        "At 21:45." to "At twenty-one forty-five.",
        "At 10 am." to "At ten A M.",
        "At 7:30 p.m." to "At seven thirty P M.",
        "It lasts 01:23:45." to "It lasts one hour, twenty-three minutes and forty-five seconds.",
        "On 09/27/2026." to "On September twenty-seventh, twenty twenty-six.",
        "On 27/09/2026." to "On September twenty-seventh, twenty twenty-six.",
        "On 2026-09-27." to "On September twenty-seventh, twenty twenty-six.",
        "Born in 1998." to "Born in nineteen ninety-eight.",
        "The remake came out in 2023." to "The remake came out in twenty twenty-three.",
        "The 1990s were great." to "The nineteen nineties were great.",
        "Back in the 90s." to "Back in the nineties.",
        "Between 10-20 fps." to "Between ten and twenty F P S.",
        "Runs at 60 fps." to "Runs at sixty F P S.",
        "It has 16 GB of RAM." to "It has sixteen gigabytes of RAM.",
        "A 144 Hz screen." to "A one hundred forty-four hertz screen.",
        "A 5000 mAh battery." to "A five thousand milliamp hours battery.",
        "Ping of 30 ms." to "Ping of thirty milliseconds.",
        "It's 25 °C outside." to "It's twenty-five degrees Celsius outside.",
        "Resolution 1920x1080." to "Resolution nineteen twenty by ten eighty.",
        "Play in 4K." to "Play in four K.",
        "Stream in 1080p." to "Stream in ten eighty p.",
        "It is 4x faster." to "It is four times faster.",
        "Get x2 XP." to "Get times two X P.",
        "You finished 1st." to "You finished first.",
        "You came 2nd and 3rd." to "You came second and third.",
        "The 21st level." to "The twenty-first level.",
        "Final Fantasy VII is great." to "Final Fantasy Seven is great.",
        "Grand Theft Auto V sold well." to "Grand Theft Auto Five sold well.",
        "The Xbox Series X." to "The Xbox Series X.",
        "Chapter IV begins." to "Chapter Four begins.",
        "I think so." to "I think so.",
        "Buy the DLC." to "Buy the D L C.",
        "An RPG with PvP." to "An R P G with P V P.",
        "Play on PS5." to "Play on P S five.",
        "Talk to the NPC." to "Talk to the N P C.",
        "It won GOTY." to "It won Game of the Year.",
        "An AAA game." to "An triple A game.",
        "Put on the VR headset." to "Put on the V R headset.",
        "e.g. Zelda." to "for example Zelda.",
        "Dr. Smith vs. Mr. Jones." to "Doctor Smith versus Mister Jones.",
        "It takes approx. 5 minutes." to "It takes approximately five minutes.",
        "It is No. 7." to "It is number seven.",
        "Games, movies, etc." to "Games, movies, et cetera.",
        "Tom & Jerry." to "Tom and Jerry.",
        "2 + 2 = 4." to "two plus two equals four.",
        "About ~5 minutes." to "About about five minutes.",
        "It is the #1 game." to "It is the number one game.",
        "50% off." to "fifty percent off.",
        "Half is 1/2." to "Half is one half.",
        "Available 24/7." to "Available twenty-four seven.",
        "10+ hours of play." to "more than ten hours of play.",
        "September 27, 2026." to "September twenty-seventh, twenty twenty-six.",
        "You've got 1 new message!" to "You've got one new message!",
    )

    private val PT = listOf(
        "Tenho 3 vidas." to "Tenho três vidas.",
        "Falta 1 hora." to "Falta uma hora.",
        "São 2 horas." to "São duas horas.",
        "Há 200 pessoas." to "Há duzentas pessoas.",
        "Custa R\$ 10,50." to "Custa dez reais e cinquenta centavos.",
        "Custa 1 real." to "Custa um real.",
        "Custa 5 €." to "Custa cinco euros.",
        "Bateria em 23 %." to "Bateria em vinte e três por cento.",
        "Pesa 3,5 GB." to "Pesa três vírgula cinco gigas.",
        "A versão 2.1.4." to "A versão dois ponto um ponto quatro.",
        "Às 21:45." to "Às vinte e uma e quarenta e cinco.",
        "Às 9:00." to "Às nove horas.",
        "No dia 27/09/2026." to "No dia vinte e sete de setembro de dois mil e vinte e seis.",
        "Em 1998." to "Em mil novecentos e noventa e oito.",
        "Ficou em 1º lugar." to "Ficou em primeiro lugar.",
        "Ela ficou em 2ª." to "Ela ficou em segunda.",
        "São 1.000 moedas." to "São mil moedas.",
        "Há 1.000.000 de jogadores." to "Há um milhão de jogadores.",
        "Roda a 60 fps." to "Roda a sessenta efe pê esse.",
        "Entre 10-20 minutos." to "Entre dez e vinte minutos.",
        "Temperatura de -5 °C." to "Temperatura de menos cinco graus.",
        "Faltam 16, 17 e 19." to "Faltam dezesseis, dezessete e dezenove.",
        "Em 2021." to "Em dois mil e vinte e um.",
    )

    private val FR = listOf(
        "J'ai 3 vies." to "J'ai trois vies.",
        "Il reste 1 heure." to "Il reste une heure.",
        "Il est 21 heures." to "Il est vingt et une heures.",
        "Il y a 71 joueurs." to "Il y a soixante et onze joueurs.",
        "Il y a 80 joueurs." to "Il y a quatre-vingts joueurs.",
        "Il y a 81 joueurs." to "Il y a quatre-vingt-un joueurs.",
        "Il y a 91 joueurs." to "Il y a quatre-vingt-onze joueurs.",
        "Ça coûte 5,99 €." to "Ça coûte cinq euros quatre-vingt-dix-neuf.",
        "Ça coûte 1 €." to "Ça coûte un euro.",
        "Batterie à 23 %." to "Batterie à vingt-trois pour cent.",
        "Ça pèse 3,5 Go." to "Ça pèse trois virgule cinq gigaoctets.",
        "La version 2.1.4." to "La version deux point un point quatre.",
        "À 21h45." to "À vingt et une heures quarante-cinq.",
        "À 21:45." to "À vingt et une heures quarante-cinq.",
        "Le 27/09/2026." to "Le vingt-sept septembre deux mille vingt-six.",
        "Le 1 janvier." to "Le premier janvier.",
        "En 1998." to "En mille neuf cent quatre-vingt-dix-huit.",
        "Tu es 1er." to "Tu es premier.",
        "Elle est 1re." to "Elle est première.",
        "Le 2e niveau." to "Le deuxième niveau.",
        "Le XXIe siècle." to "Le vingt et unième siècle.",
        "Il y a 3 000 pièces." to "Il y a trois mille pièces.",
        "Il y a 200 joueurs." to "Il y a deux cents joueurs.",
        "Il y a 280 000 fans." to "Il y a deux cent quatre-vingt mille fans.",
        "Il y a 1 000 000 de joueurs." to "Il y a un million de joueurs.",
        "Entre 10-20 minutes." to "Entre dix et vingt minutes.",
    )

    private val DE = listOf(
        "Ich habe 3 Leben." to "Ich habe drei Leben.",
        "Noch 1 Stunde." to "Noch eine Stunde.",
        "Ich habe 1 Spiel." to "Ich habe ein Spiel.",
        "Es sind 21 Spieler." to "Es sind einundzwanzig Spieler.",
        "Es kostet 5,99 €." to "Es kostet fünf Euro neunundneunzig.",
        "Akku bei 23 %." to "Akku bei dreiundzwanzig Prozent.",
        "Es hat 3,5 GB." to "Es hat drei Komma fünf Gigabyte.",
        "Version 2.1.4." to "Version zwei Punkt eins Punkt vier.",
        "Um 21:45." to "Um einundzwanzig Uhr fünfundvierzig.",
        "Um 9:00." to "Um neun Uhr.",
        "Am 27.09.2026." to "Am siebenundzwanzigsten September zweitausendsechsundzwanzig.",
        "Am 3. Oktober." to "Am dritten Oktober.",
        "Der 3. Platz." to "Der dritte Platz.",
        "Seit 1998." to "Seit neunzehnhundertachtundneunzig.",
        "Im Jahr 2026." to "Im Jahr zweitausendsechsundzwanzig.",
        "Es sind 1.000 Münzen." to "Es sind eintausend Münzen.",
        "Es sind 1.000.000 Spieler." to "Es sind eine Million Spieler.",
        "Zwischen 10-20 Minuten." to "Zwischen zehn und zwanzig Minuten.",
        "Es sind 2.345 Punkte." to "Es sind zweitausenddreihundertfünfundvierzig Punkte.",
    )

    private val JA = listOf(
        "残り3機です。" to "残り三機です。",
        "12時間45分" to "十二時間四十五分",
        "バッテリーは23%です。" to "バッテリーは二十三パーセントです。",
        "容量は3.5GBです。" to "容量は三点五ギガバイトです。",
        "バージョン2.1.4です。" to "バージョン二点一点四です。",
        "21:45に始まります。" to "二十一時四十五分に始まります。",
        "2026/09/27です。" to "二千二十六年九月二十七日です。",
        "¥500です。" to "五百円です。",
        "500円です。" to "五百円です。",
        "10000人" to "一万人",
        "1億人" to "一億人",
        "\$5.99です。" to "五ドル九十九セントです。",
        "60fpsで動きます。" to "六十エフピーエスで動きます。",
        "1920x1080の解像度。" to "千九百二十かける千八十の解像度。",
        "10〜20分" to "十から二十分",
        "１２時間４５分" to "十二時間四十五分",
        "-5度" to "マイナス五度",
        "1234567" to "百二十三万四千五百六十七",
    )
}
