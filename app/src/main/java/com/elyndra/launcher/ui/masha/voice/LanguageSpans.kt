package com.elyndra.launcher.ui.masha.voice

/** Trozo de texto y el idioma en que la voz debe leerlo. */
data class Span(val text: String, val lang: String)

/**
 * Parte una frase (es/pt/fr/de) en tramos del idioma base y tramos en inglés
 * (títulos de juegos: "Hollow Knight", "The Legend of Zelda: Tears of the
 * Kingdom"), para que la voz lea el inglés en inglés sin arrastrar el acento a
 * las palabras de alrededor.
 *
 * Heurística conservadora:
 * - Una palabra es inglesa si [split]`.isEnglishWord` la conoce (CMUdict) y NO es
 *   una palabra del idioma base (lista propia de palabras frecuentes, sufijos
 *   típicos, tildes/ñ/ç…).
 * - Hace falta una semilla "fuerte": ortografía imposible en el idioma base (w, k,
 *   th, sh, gh, ck, oo, y final tras consonante, dos consonantes al final, s+consonante
 *   al principio…). Una sola palabra suelta solo cuenta si es fuerte y tiene ≥ 4
 *   letras; las cortas ambiguas (no, me, red, hot, fin…) se quedan en el idioma base.
 * - Si la semilla va en mayúscula, el tramo crece por las palabras con mayúscula de
 *   al lado (título: "Final Fantasy", "Elden Ring") y por conectores ingleses
 *   (of, the, and) y ":" / "-" / "&" internos.
 * - Dos o más palabras inglesas con mayúscula también forman título ("Dead Space").
 * - La puntuación de alrededor se queda en el tramo base; los tramos se tocan sin
 *   perder nada: concatenarlos devuelve el texto exacto.
 *
 * Listas de palabras: hechas a mano para este proyecto (palabras funcionales y
 * frecuentes, verbos conjugados, vocabulario de juegos); no copiadas de ninguna lista
 * con licencia. En inglés o japonés devuelve un solo tramo.
 */
object LanguageSpans {

    fun split(text: String, lang: String, isEnglishWord: (String) -> Boolean): List<Span> {
        val base = lang.trim().lowercase().take(2)
        if (text.isEmpty() || base !in WORDS.keys) return listOf(Span(text, lang))
        return try {
            Splitter(text, base, lang, isEnglishWord).run()
        } catch (_: Throwable) {
            listOf(Span(text, lang))
        }
    }

    private class Tok(
        val start: Int, val end: Int, val raw: String, val low: String,
        val base: Boolean, val strong: Boolean, val weak: Boolean, val english: Boolean,
        val cap: Boolean, val sentenceStart: Boolean, val connector: Boolean,
        /** Desconocida para el diccionario pero con ortografía inglesa ("Stardew", "Kart"). */
        val shape: Boolean,
    )

    private class Splitter(
        private val text: String,
        private val base: String,
        private val outLang: String,
        private val isEnglish: (String) -> Boolean,
    ) {
        private val words = WORDS.getValue(base)
        private val toks = tokenize()

        fun run(): List<Span> {
            val runs = ArrayList<IntRange>()
            for (k in toks.indices) {
                val t = toks[k]
                val seed = t.strong || (t.weak && t.cap && !t.sentenceStart)
                if (!seed || runs.any { k in it }) continue
                grow(k)?.let { r -> if (runs.none { it.first <= r.last && r.first <= it.last }) runs += r }
            }
            if (runs.isEmpty()) return listOf(Span(text, outLang))
            runs.sortBy { it.first }
            val spans = ArrayList<Span>()
            var pos = 0
            for (r in runs) {
                val a = toks[r.first].start
                val b = toks[r.last].end
                if (a > pos) spans += Span(text.substring(pos, a), outLang)
                spans += Span(text.substring(a, b), "en")
                pos = b
            }
            if (pos < text.length) spans += Span(text.substring(pos), outLang)
            return merge(spans)
        }

        /** Tramo alrededor de la semilla [k], o null si no convence. */
        private fun grow(k: Int): IntRange? {
            val title = toks[k].cap
            var a = k
            var b = k
            while (b + 1 < toks.size && gapOk(b, b + 1) && accept(toks[b + 1], title)) b++
            while (a - 1 >= 0 && gapOk(a - 1, a) && accept(toks[a - 1], title)) a--
            // Sin conectores sueltos en los bordes ("The" con mayúscula sí abre título).
            while (a <= b && toks[a].connector && !toks[a].cap) a++
            while (b >= a && toks[b].connector) b--
            if (a > b) return null
            val body = (a..b).map { toks[it] }.filter { !it.connector }
            val strong = body.count { it.strong || (it.shape && it.cap) }
            val weak = body.count { it.weak }
            if (body.size <= 1 && b == a) {
                val t = toks[a]
                return if (t.strong && t.low.length >= 4) a..b else null
            }
            val ok = (strong >= 1 && (strong + weak) * 2 >= body.size) ||
                (title && weak >= 2 && (strong + weak) * 2 >= body.size)
            return if (ok) a..b else null
        }

        private fun accept(t: Tok, title: Boolean): Boolean {
            if (t.raw[0].isDigit()) return false
            if (t.strong || t.weak) return true
            if (!title || !t.cap) return false
            // Palabra con mayúscula dentro de un título: inglesa aunque también sea base
            // ("Final", "Red"), o desconocida ("Zelda", "Elden"); nunca la primera de la frase
            // salvo que sea inglesa y no una palabra funcional ("En", "La").
            if (t.english) return !t.sentenceStart || t.low !in words.function
            // En alemán todos los sustantivos van en mayúscula: la mayúscula no dice nada.
            return base != "de" && !t.base && !t.sentenceStart && t.raw.all { it.isLetter() && it.code < 128 }
        }

        /** Entre dos palabras de un título solo caben espacios y ":", "-", "–", "&". */
        private fun gapOk(i: Int, j: Int): Boolean {
            val g = text.substring(toks[i].end, toks[j].start)
            var marks = 0
            for (c in g) {
                when (c) {
                    ' ', '\u00A0', '\t' -> {}
                    ':', '-', '–', '&' -> marks++
                    else -> return false
                }
            }
            return marks <= 1 && g.isNotEmpty()
        }

        private fun merge(spans: List<Span>): List<Span> {
            val out = ArrayList<Span>()
            var k = 0
            while (k < spans.size) {
                var s = spans[k]
                // Tramo base de solo espacios entre dos ingleses: todo inglés.
                if (s.lang == "en" && k + 2 < spans.size && spans[k + 2].lang == "en" && spans[k + 1].text.isBlank()) {
                    s = Span(s.text + spans[k + 1].text + spans[k + 2].text, "en")
                    k += 2
                }
                if (s.text.isNotEmpty()) {
                    val last = out.lastOrNull()
                    if (last != null && last.lang == s.lang) out[out.size - 1] = Span(last.text + s.text, s.lang) else out += s
                }
                k++
            }
            // Una segunda pasada por si la fusión dejó dos ingleses seguidos.
            return if (out.size < spans.size && out.zipWithNext().any { (x, y) -> x.lang == "en" && y.lang == "en" }) merge(out) else out
        }

        private fun tokenize(): List<Tok> {
            val out = ArrayList<Tok>()
            for (m in TOKEN.findAll(text)) {
                val raw = m.value
                val low = raw.lowercase().replace('’', '\'')
                val ascii = raw.all { (it in 'a'..'z') || (it in 'A'..'Z') || it == '\'' || it == '’' || it == '-' }
                val digits = raw.any { it.isDigit() }
                val isBase = !ascii || words.isBase(low)
                val english = ascii && !digits && englishWord(low)
                val strong = english && !isBase && low.length >= 3 && strongEnglish(low, base)
                val weak = english && !isBase && !strong
                out += Tok(
                    m.range.first, m.range.last + 1, raw, low, isBase, strong, weak, english,
                    raw[0].isUpperCase(), sentenceStart(m.range.first), low in CONNECTORS,
                    shape = ascii && !digits && !isBase && !english && strongEnglish(low, base),
                )
            }
            return out
        }

        private fun englishWord(low: String): Boolean = low.split('-').all { p ->
            p.isNotEmpty() && (isEnglish(p) || (p.endsWith("'s") && p.length > 2 && isEnglish(p.dropLast(2))))
        }

        private fun sentenceStart(i: Int): Boolean {
            var j = i - 1
            while (j >= 0 && text[j].isWhitespace() && text[j] != '\n') j--
            if (j < 0) return true
            return text[j] in ".!?¡¿…\n\"«“(["
        }
    }

    /* ── ortografía ── */

    private const val VOWELS = "aeiouy"

    /** Rasgos que casi nunca da la ortografía del idioma base. */
    internal fun strongEnglish(w0: String, base: String): Boolean {
        val w = w0.removeSuffix("'s").replace("'", "").replace("-", "")
        if (w.length < 3) return false
        val last = w.last()
        val prev = w[w.length - 2]
        val finalYAfterConsonant = last == 'y' && prev !in VOWELS
        val common = "th" in w || "gh" in w || "wh" in w || "ght" in w || finalYAfterConsonant || w.endsWith("ing")
        if (common) return true
        return when (base) {
            "es", "pt" -> {
                val doubleVowel = ("oo" in w || "ee" in w) && !w.startsWith("coo") && !w.startsWith("ree") &&
                    !w.startsWith("pree") && !w.endsWith("eer") && !w.endsWith("eem")
                val finals = if (base == "es") "aeiousnlrdzyjx" else "aeiousrlmzx"
                val clusterEnd = last !in VOWELS && prev !in VOWELS && !(base == "pt" && last == 's' && prev == 'n')
                'w' in w || 'k' in w || "sh" in w || "ph" in w || "ck" in w || doubleVowel ||
                    (base == "es" && "ou" in w) || w.endsWith("tion") || w.endsWith("sion") ||
                    last !in finals || clusterEnd || (w[0] == 's' && w[1] !in VOWELS)
            }
            "fr" -> 'w' in w || 'k' in w || "sh" in w || "ck" in w || "oo" in w || "ee" in w || "ow" in w
            "de" -> ("sh" in w && "sch" !in w) || "ea" in w || "ow" in w || "aw" in w || "ew" in w || "oo" in w
            else -> false
        }
    }

    private class Words(list: String, function: String, private val suffixes: List<String>) {
        val set: Set<String> = list.split(' ', '\n').filter { it.isNotBlank() }.toHashSet()
        val function: Set<String> = function.split(' ').filter { it.isNotBlank() }.toHashSet()
        fun isBase(w: String): Boolean =
            w in set || w in function || (w.length >= 5 && suffixes.any { w.endsWith(it) })
    }

    private val TOKEN = Regex("[\\p{L}\\p{M}\\p{N}]+(?:['’\\-][\\p{L}\\p{M}\\p{N}]+)*")

    /** Conectores ingleses que solo cuentan dentro de un título. */
    private val CONNECTORS = setOf(
        "of", "the", "and", "to", "in", "on", "for", "a", "an", "at", "with", "from", "by", "or", "vs", "n", "into",
        "over", "under", "up", "out", "off", "is", "it", "my", "your",
    )

    /* ── Español (≈2000 palabras) ── */

    private const val ES_FUNCTION =
        "a al ante bajo con contra de del desde durante en entre hacia hasta mediante para por según sin so sobre tras " +
            "y e ni o u pero mas sino aunque porque pues que si como cuando donde mientras el la lo los las un una unos unas " +
            "yo tú tu vos usted ustedes él ella ello ellos ellas nosotros vosotros me te se nos os le les mi mis tus su sus " +
            "este esta esto estos estas ese esa eso esos esas aquel aquella no sí ya muy más menos tan hoy aquí allí así"

    private const val ES_WORDS = """
aún todavía también tampoco incluso además apenas luego entonces bien mal mejor peor ahí allá acá ayer mañana ahora antes
después siempre nunca jamás pronto tarde temprano quizá quizás acaso casi solo sólo tanto mucho mucha muchos muchas poco
poca pocos pocas demasiado demasiada bastante ambos ambas cada cualquier cualquiera varios varias algo alguien alguno alguna
algunos algunas algún nada nadie ninguno ninguna ningún todo toda todos todas otro otra otros otras mismo misma mismos mismas
nuestro nuestra nuestros nuestras vuestro vuestra suyo suya suyos suyas mío mía míos mías tuyo tuya tuyos tuyas aquello
aquellos aquellas qué quien quién quienes cual cuál cuales cuáles cuyo cuya cuanto cuánto cuanta cuánta cuántos cuántas cómo
dónde cuándo adónde conmigo contigo consigo mediante versus vía salvo excepto
ser soy eres es somos sois son era eras éramos eran fui fuiste fue fuimos fueron sea seas seamos sean sería serías seríamos
serían será serás seremos serán sido siendo sé
estar estoy estás está estamos están estaba estabas estábamos estaban estuve estuviste estuvo estuvimos estuvieron esté
estés estemos estén estado estados estando estará estarás estaría
haber he has ha hay hemos han había habías habíamos habían habrá habrás habría hubo haya hayas hayamos hayan habido
tener tengo tienes tiene tenemos tienen tenía tenías teníamos tenían tuve tuviste tuvo tuvimos tuvieron tenga tengas tengamos
tengan tendré tendrás tendrá tendremos tendrán tendría tenido teniendo ten
hacer hago haces hace hacemos hacen hacía hacías hacían hice hiciste hizo hicimos hicieron haga hagas hagamos hagan haré
harás hará haremos harán haría hecho hecha hechos haciendo haz
ir voy vas va vamos van iba ibas íbamos iban fuiste vaya vayas vayamos vayan iré irás irá iremos irán iría ido yendo ve id
poder puedo puedes puede podemos pueden podía podías podían pude pudiste pudo pudimos pudieron pueda puedas podamos puedan
podré podrás podrá podremos podrán podría podrías podríamos podrían podido pudiendo
decir digo dices dice decimos dicen decía dije dijiste dijo dijimos dijeron diga digas digamos digan diré dirás dirá diría
dicho dicha diciendo di dime dile
ver veo ves ve vemos ven veía veías veían vi viste vio vimos vieron vea veas veamos vean veré verás verá vería visto vista
viendo mira mirar miro miras miramos miran miraba mire mires
dar doy das da damos dan daba dabas di diste dio dimos dieron dé des demos den daré darás dará daría dado dada dando dame
saber sabes sabe sabemos saben sabía sabías supe supiste supo sepa sepas sabré sabrás sabrá sabría sabido
querer quiero quieres quiere queremos quieren quería querías quise quisiste quiso quiera quieras querré querrás querrá
querría querido quieras
llegar llego llegas llega llegamos llegan llegaba llegué llegaste llegó llegue llegará llegado llegando
pasar paso pasas pasa pasamos pasan pasaba pasé pasaste pasó pase pases pasará pasado pasada pasando
deber debo debes debe debemos deben debía debería deberías deberíamos
poner pongo pones pone ponemos ponen ponía puse pusiste puso ponga pongas pondré pondrá puesto puesta poniendo pon ponte
parecer parezco pareces parece parecen parecía pareció parezca
quedar quedo quedas queda quedamos quedan quedaba quedé quedaste quedó quede quedará quedado quedan quédate
creer creo crees cree creemos creen creía creí creíste creyó crea creas
hablar hablo hablas habla hablamos hablan hablaba hablé hablaste habló hable hables hablará hablado hablando
llevar llevo llevas lleva llevamos llevan llevaba llevé llevaste llevó lleve lleves llevará llevado llevando
dejar dejo dejas deja dejamos dejan dejaba dejé dejaste dejó deje dejes dejará dejado dejando
seguir sigo sigues sigue seguimos siguen seguía seguí seguiste siguió siga sigas seguirá seguido siguiendo
encontrar encuentro encuentras encuentra encontramos encuentran encontré encontraste encontró encuentre encontrado
llamar llamo llamas llama llamamos llaman llamé llamó llame llamado llamada llamadas
venir vengo vienes viene venimos vienen venía vine viniste vino venga vengas vendré vendrá venido viniendo ven
pensar pienso piensas piensa pensamos piensan pensaba pensé pensaste pensó piense pensado pensando
salir salgo sales sale salimos salen salía salí saliste salió salga salgas saldré saldrá salido saliendo sal
volver vuelvo vuelves vuelve volvemos vuelven volvía volví volviste volvió vuelva vuelvas volverá vuelto volviendo
tomar tomo tomas toma tomamos toman tomé tomaste tomó tome tomado tomando
conocer conozco conoces conoce conocemos conocen conocía conocí conociste conoció conozca conocido conocida
vivir vivo vives vive vivimos viven vivía viví viviste vivió viva vivido viviendo
sentir siento sientes siente sentimos sienten sentía sentí sentiste sintió sienta sentido sintiendo
tratar trato tratas trata tratamos tratan traté trató trate tratado tratando
contar cuento cuentas cuenta contamos cuentan conté contaste contó cuente contado contando
empezar empiezo empiezas empieza empezamos empiezan empecé empezaste empezó empiece empezado empezando
esperar espero esperas espera esperamos esperan esperaba esperé esperó espere esperado esperando
buscar busco buscas busca buscamos buscan busqué buscaste buscó busque buscado buscando
existir existe existen existía entrar entro entras entra entramos entran entré entraste entró entre entrado entrando
trabajar trabajo trabajas trabaja trabajamos trabajan trabajé trabajó trabajando
escribir escribo escribes escribe escribimos escriben escribí escribió escrito escribiendo escríbeme
perder pierdo pierdes pierde perdemos pierden perdí perdiste perdió pierda perdido perdida perdiendo
entender entiendo entiendes entiende entendemos entienden entendí entendió entendido
pedir pido pides pide pedimos piden pedí pediste pidió pida pedido
recibir recibo recibes recibe recibimos reciben recibí recibiste recibió recibido
recordar recuerdo recuerdas recuerda recordamos recuerdan recordé recordó recuerde recordado recuérdame
terminar termino terminas termina terminamos terminan terminé terminaste terminó termine terminado terminando
permitir permite permiten permitió aparecer aparece aparecen apareció aparezca
conseguir consigo consigues consigue conseguimos consiguen conseguí conseguiste consiguió conseguido
comenzar comienzo comienzas comienza comenzamos comienzan comencé comenzó comenzado
servir sirve sirven sirvió sacar saco sacas saca sacamos sacan saqué sacaste sacó
necesitar necesito necesitas necesita necesitamos necesitan necesité necesitó necesitas
mantener mantengo mantienes mantiene mantienen resultar resulta resultan resultó
leer leo lees lee leemos leen leí leíste leyó lea leído leyendo caer cae caen cayó caído
cambiar cambio cambias cambia cambiamos cambian cambié cambiaste cambió cambie cambiado cambiando
presentar presenta presentan crear creas crea creamos crean creé creó creado creando
abrir abro abres abre abrimos abren abrí abriste abrió abra abierto abriendo ábrelo
ganar gano ganas gana ganamos ganan gané ganaste ganó gane ganado ganando ganador ganadora
traer traigo traes trae traemos traen traje trajo traiga traído
morir muere mueren murió muerto muerta muriendo
aceptar acepto aceptas acepta aceptar aceptado lograr logro logras logra logramos logran logré lograste logró
explicar explico explicas explica explicó preguntar pregunto preguntas pregunta preguntó
tocar toco tocas toca tocó correr corro corres corre corrió corriendo usar uso usas usa usamos usan usé usó usado usando
pagar pago pagas paga pagué pagó pagado ayudar ayudo ayudas ayuda ayudamos ayudan ayudé ayudó ayúdame
gustar gusta gustan gustó gustaría gustaba encantar encanta encantan encantó encantaría
jugar juego juegas juega jugamos juegan jugaba jugabas jugué jugaste jugó juegue juegues juguemos jugará jugarás jugaría
jugado jugando juguemos juega
escuchar escucho escuchas escucha escuchó ofrecer ofrece ofrecen descubrir descubre descubrí descubriste descubrió
intentar intento intentas intenta intentamos intentan intenté intentaste intentó
olvidar olvido olvidas olvida olvidé olvidaste olvidó valer vale valen valió
comer como comes come comemos comen comí comiste comió mostrar muestra muestran mostró
continuar continúa continúan continuó aprender aprendo aprendes aprende aprendí aprendiste
comprar compro compras compra compramos compran compré compraste compró subir subo subes sube subió
cerrar cierro cierras cierra cerró responder responde respondió importar importa importan importó
obtener obtengo obtienes obtiene obtuvo obtenido imaginar imagino imaginas imagina
elegir elijo eliges elige eligió elegido preparar preparo preparas prepara preparó
significar significa funcionar funciona funcionan funcionó guardar guardo guardas guarda guardé guardó guardado
instalar instalo instalas instala instalado descargar descargo descargas descarga descargué descargado descargando
actualizar actualizo actualizas actualiza actualizado actualizando iniciar inicia inició iniciado
probar pruebo pruebas prueba probé probaste probó probado recomendar recomiendo recomiendas recomienda recomendado
preferir prefiero prefieres prefiere preferí disfrutar disfruto disfrutas disfruta disfrutó disfrútalo
sonar suena suenan sonó relajar relájate descansar descanso descansas descansa descansa
apagar apago apagas apaga apagado encender enciendo enciendes enciende encendido conectar conecta conectado
cargar carga cargando cargado completar completa completado completada desbloquear desbloquea desbloqueado desbloqueaste
superar supera superaste superado lanzar lanza lanzó lanzamiento salvar salva salvó matar mata mató
pelear pelea peleas luchar lucha luchas explorar explora explorando construir construye
avisar aviso avisas avisa avísame recordar dormir duermo duermes duerme durmió
""" + """
año años día días vez veces tiempo tiempos hora horas minuto minutos segundo segundos semana semanas mes meses momento
momentos vida vidas mundo mundos casa casas cosa cosas hombre hombres mujer mujeres niño niños niña niñas gente persona
personas parte partes lugar lugares forma formas caso casos grupo grupos problema problemas país países ciudad ciudades
trabajo mano manos ojo ojos agua noche noches tarde tardes punto puntos nombre nombres verdad palabra palabras historia
historias idea ideas final finales fin principio medio lado hijo hija padre madre familia amigo amiga amigos amigas
nivel niveles juego juegos partida partidas jugador jugadores jugadora jugadoras misión misiones mapa mapas personaje
personajes enemigo enemigos jefe jefes arma armas batalla batallas pantalla pantallas consola consolas mando mandos
control controles botón botones tienda tiendas oferta ofertas precio precios logro logros trofeo trofeos modo modos
campaña campañas multijugador equipo equipos servidor servidores conexión red redes batería baterías carga memoria espacio
almacenamiento archivo archivos versión versiones actualización actualizaciones parche parches descarga descargas
instalación ajuste ajustes configuración opción opciones menú menús sonido sonidos música volumen voz voces idioma
idiomas texto textos mensaje mensajes notificación notificaciones aplicación aplicaciones biblioteca colección género
géneros aventura aventuras acción estrategia terror rol carreras deporte deportes plataformas puzle puzles simulación
lucha disparos abierto sigilo supervivencia fantasía ciencia ficción estudio estudios desarrollador desarrolladores
compañía saga sagas entrega entregas secuela precuela remasterización edición ediciones expansión expansiones contenido
gráficos rendimiento calidad resolución velocidad frecuencia imagen imágenes vídeo vídeos video videos foto fotos captura
capturas grabación sesión sesiones recompensa recompensas experiencia salud energía poder poderes habilidad habilidades
objeto objetos inventario moneda monedas oro dinero puntuación récord temporada temporadas evento eventos torneo torneos
liga ligas partido partidos victoria victorias derrota derrotas empate muerte muertes estrella estrellas corazón
corazones llave llaves puerta puertas camino caminos zona zonas región regiones castillo castillos mazmorra mazmorras
bosque bosques cueva cuevas montaña montañas río ríos mar mares isla islas cielo tierra fuego hielo sombra sombras luz
luces oscuridad dragón dragones caballero caballeros rey reyes reina reinas príncipe princesa guerrero guerrera mago maga
bruja monstruo monstruos criatura criaturas bestia bestias héroe héroes heroína villano villanos leyenda leyendas alma
almas espíritu espíritus destino reino reinos imperio guerra guerras paz fuerza fuerzas magia hechizo hechizos espada
espadas escudo escudos arco flecha flechas armadura casco anillo anillos gema gemas cristal cristales tesoro tesoros
cofre cofres caja cajas carta cartas mazo tablero dado dados pieza piezas ficha fichas ronda rondas turno turnos fase
fases etapa etapas capítulo capítulos episodio episodios acto actos escena escenas secreto secretos pista pistas truco
trucos consejo consejos guía guías ayuda error errores fallo fallos solución soluciones pregunta preguntas respuesta
respuestas razón razones cuenta cuentas perfil perfiles usuario usuarios contraseña correo teléfono móvil móviles
ordenador computadora portátil tableta televisión tele auriculares altavoz micrófono cámara teclado ratón volante
cosa momento ejemplo mitad resto total parte principio lado sitio sitio modo manera tipo clase número números cifra
señal señales mensaje ruido silencio calor frío hambre sed sueño sueños miedo risa suerte ganas amor fiesta cumpleaños
regalo regalos sorpresa noticia noticias novedad novedades función funciones característica características ventaja
ventajas desafío desafíos reto retos meta metas objetivo objetivos tarea tareas pendiente pendientes lista listas
colega colegas compañero compañeros rival rivales aliado aliados clan clanes gremio gremios comunidad comunidades
público estreno lanzamientos demo prueba pruebas beta alfa jugabilidad historia trama argumento guion diálogo diálogos
banda sonora canción canciones artista artistas diseño arte estilo mundo escenario escenarios ciudad pueblo aldea
granja casa hogar habitación cuarto cocina calle calles coche coches carro carros moto motos avión barco tren nave naves
planeta planetas galaxia estrellas espacio tiempo clima lluvia nieve viento sol luna mañana tarde noche madrugada
lunes martes miércoles jueves viernes sábado domingo enero febrero marzo abril mayo junio julio agosto septiembre
setiembre octubre noviembre diciembre primavera verano otoño invierno fin semana
cero uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece catorce quince dieciséis diecisiete dieciocho
diecinueve veinte veintiuno veintiún veintiuna veintidós veintitrés veinticuatro veinticinco veintiséis veintisiete
veintiocho veintinueve treinta cuarenta cincuenta sesenta setenta ochenta noventa cien ciento doscientos doscientas
trescientos trescientas cuatrocientos cuatrocientas quinientos quinientas seiscientos seiscientas setecientos setecientas
ochocientos ochocientas novecientos novecientas mil millón millones billón coma por ciento media cuarto tercio doble
triple primero primera primer tercero tercera tercer cuarto cuarta quinto quinta sexto séptimo octavo noveno décimo
""" + """
bueno buena buenos buenas buen malo mala malos malas gran grande grandes pequeño pequeña pequeños pequeñas nuevo nueva
nuevos nuevas viejo vieja viejos viejas mejores peores último última últimos últimas largo larga largos cortos corto
corta alto alta altos altas baja bajos bajas fácil fáciles difícil difíciles rápido rápida rápidos lento lenta fuerte
fuertes débil débiles claro clara oscuro oscura bonito bonita feo fea hermoso hermosa increíble increíbles genial
geniales perfecto perfecta posible imposible importante importantes necesario necesaria cierto cierta real reales total
totales general generales normal normales especial especiales principal principales simple simples libre libres feliz
felices triste tristes sola solos solas cansado cansada listo lista seguro segura seguros rojo roja azul azules verde
verdes amarillo amarilla negro negra blanco blanca gris morado morada rosa naranja dorado dorada divertido divertida
aburrido aburrida emocionante épico épica épicos legendario legendaria raro rara común comunes único única gratis
gratuito gratuita disponible disponibles oficial nacional internacional social local global digital físico física
visual original originales clásico clásica clásicos moderno moderna antiguo antigua futuro futura pasado pasada actual
actuales anterior siguiente próximo próxima entero entera completo completa mayor menor mínimo máximo junto juntos
juntas lleno llena vacío vacía cerrado cerrada activo activa oculto oculta secreta tranquilo tranquila precioso preciosa
enorme enormes brutal brutales loco loca raro extraño extraña rico rica pobre caro cara barato barata listo sabio
valiente cool top pro súper super mega hiper ultra mini maxi extra
hola adiós gracias perdón vale claro vaya oye venga buenas saludos bienvenido bienvenida felicidades enhorabuena ánimo
ojalá bravo guau vamos anda uf ay oh ah eh bueno sí no ok okay okey jaja jeje
masha elyndra
wifi web webs chat chats club clubs fútbol bug bugs lag app apps link links email online offline skin skins boss bosses
noob gamer gamers streamer streamers stream streams gameplay spoiler spoilers trailer trailers tráiler remake remakes
remaster bot bots crack fan fans hobby software hardware login blog blogs youtuber youtubers kilo kilos karate kiwi koala
kayak karaoke kiosco show shows rock pop jazz internet test tests ticket tickets clic click robot robots sándwich parking
camping marketing ranking rankings casting podcast podcasts selfie selfies influencer likes like post posts tuit meme
memes emoji emojis router módem smartphone tablet laptop joystick gamepad pack packs combo combos tutorial tutoriales
checkpoint crafteo farmear spawn loot looteo speedrun speedruns stats nerf buff hype crossover spinoff indie indies
mod mods streaming spam wiki
dije dices dijo nada nadie siempre claro cierto verdad mentira ninguna gente mundo pan sal york nueva nuevo
"""

    private val ES_SUFFIXES = listOf(
        "ción", "ciones", "sión", "siones", "mente", "idad", "idades", "ando", "iendo", "ado", "ados", "ada", "adas", "ido",
        "idos", "ida", "idas", "aba", "aban", "ábamos", "ía", "ían", "emos", "amos", "imos", "aste", "iste", "aron", "ieron",
        "ará", "erá", "irá", "ante", "antes", "ente", "entes", "ista", "istas", "ismo", "oso", "osa", "osos", "osas", "ito",
        "ita", "itos", "itas", "ero", "eros", "era", "eras", "ería", "ario", "aria", "ores", "ales", "iles", "anza", "encia",
        "ancia", "eza", "ura", "uras", "aje", "ajes", "azo", "illo", "illa", "ísimo", "ísima",
    )

    /* ── Portugués (≈500) ── */

    private const val PT_FUNCTION =
        "a o as os um uma uns umas de do da dos das em no na nos nas por pelo pela pelos pelas para com sem sob sobre " +
            "entre até desde e ou mas que se como quando onde eu tu você vocês ele ela eles elas nós me te lhe nos vos " +
            "meu minha meus minhas teu tua seu sua seus suas nosso nossa este esta isto esse essa isso aquele aquela " +
            "não sim já muito mais menos tão hoje aqui ali assim"

    private const val PT_WORDS = """
ser sou és é somos são era eram foi foram seja sejam será serão sido sendo estar estou está estamos estão estava estavam
esteve esteja estado estando ter tenho tens tem temos têm tinha tinham teve tive tenha terá tido fazer faço faz fazemos
fazem fez fiz faça fará feito fazendo ir vou vai vamos vão ia iam fui vá irá ido indo poder posso pode podemos podem podia
pôde possa poderá poderia dizer digo diz dizemos dizem disse diga dirá dito ver vejo vê vemos veem viu vi veja verá visto
dar dou dá damos dão deu dei dê dado saber sei sabe sabemos sabem sabia soube saiba querer quero quer queremos querem queria
quis queira ficar fico fica ficamos ficam ficou fique ficado passar passa passou passado jogar jogo joga jogamos jogam jogou
joguei jogando jogado jogue jogos jogador jogadores jogadora partida partidas precisar preciso precisa precisam gostar
gosto gosta gostam gostou gostaria achar acho acha achou chegar chega chegou começar começa começou comece
voltar volta voltou tentar tenta tentou tente usar usa usou usando ganhar ganha ganhou ganhe perder perde perdeu perca
abrir abre abriu aberto fechar fecha fechou baixar baixa baixou atualizar atualiza atualizado instalar instala instalado
salvar salva salvou continuar continua continuou esperar espera esperou olhar olha olhou pensar penso pensa pensou
falar falo fala falou conhecer conhece conheceu descansar descansa lembrar lembra lembrou recomendar recomendo recomenda
experimentar experimente divirta aproveite aproveitar
ano anos dia dias vez vezes tempo hora horas minuto minutos segundo segundos semana semanas mês meses momento vida mundo
casa coisa coisas homem mulher gente pessoa pessoas parte lugar forma caso grupo problema país cidade trabalho mão olho
água noite tarde manhã ponto pontos nome verdade palavra história ideia fim final começo lado filho pai mãe família amigo
amiga amigos nível níveis jogo missão missões mapa personagem personagens inimigo inimigos chefe arma armas batalha
batalhas tela telas console consoles controle botão loja oferta preço conquista conquistas troféu modo modos campanha
equipe servidor conexão rede bateria memória espaço arquivo versão atualização configuração opção menu som música volume
voz idioma texto mensagem notificação aplicativo biblioteca coleção gênero aventura ação estratégia terror corrida esporte
estúdio saga edição conteúdo gráficos desempenho qualidade resolução velocidade imagem vídeo foto sessão recompensa
experiência saúde energia poder habilidade objeto moeda moedas ouro dinheiro temporada evento torneio vitória derrota
morte estrela coração chave porta caminho zona região castelo floresta caverna montanha rio mar ilha céu terra fogo
gelo sombra luz dragão cavaleiro rei rainha herói lenda alma destino reino guerra paz força magia espada escudo tesouro
baú carta rodada turno fase etapa capítulo episódio segredo dica ajuda erro solução pergunta resposta conta perfil
usuário senha celular computador fone
segunda terça quarta quinta sexta sábado domingo janeiro fevereiro março abril maio junho julho agosto setembro
outubro novembro dezembro
zero dois duas três quatro cinco seis sete oito nove dez onze doze treze catorze quatorze quinze dezesseis dezasseis
dezessete dezassete dezoito dezenove dezanove vinte trinta quarenta cinquenta sessenta setenta oitenta noventa cem
cento duzentos duzentas trezentos quinhentos mil milhão milhões bilhão vírgula cento primeiro primeira
bom boa bons boas mau má grande grandes pequeno pequena novo nova novos novas velho velha melhor pior último última
longo curto alto baixo fácil difícil rápido lento forte fraco claro escuro bonito lindo feio incrível ótimo ótima
perfeito possível importante certo real total geral normal especial principal simples livre feliz triste sozinho
cansado pronto seguro vermelho azul verde amarelo preto branco divertido chato legal épico lendário raro comum único
grátis disponível oficial original clássico moderno antigo próximo anterior completo cheio vazio ativo secreto
olá oi tchau obrigado obrigada desculpa beleza nossa puxa vamos claro bem mal sempre nunca ainda também depois antes
agora logo cedo talvez quase só somente então porque pois embora enquanto nada ninguém algo alguém tudo todo toda
todos todas outro outra outros outras mesmo mesma cada qualquer algum alguma nenhum nenhuma vários várias pouco pouca
muitos muitas bastante demais
masha elyndra wifi web chat app apps online offline skin skins bug bugs lag gamer gameplay trailer remake streamer
futebol clube internet show rock pop game games
"""

    private val PT_SUFFIXES = listOf(
        "ção", "ções", "são", "sões", "mente", "dade", "dades", "ando", "endo", "indo", "ado", "ados", "ada", "adas", "ido",
        "idos", "ida", "idas", "inho", "inha", "oso", "osa", "eiro", "eira", "ava", "avam", "iam", "amos", "emos", "imos",
    )

    /* ── Francés (≈500) ── */

    private const val FR_FUNCTION =
        "le la les l un une des du de d à au aux en dans par pour avec sans sous sur entre vers chez et ou mais donc or " +
            "ni car que qui quoi dont où si comme quand je tu il elle on nous vous ils elles me te se lui leur leurs mon " +
            "ma mes ton ta tes son sa ses notre nos votre vos ce cet cette ces ne pas plus moins très bien ici là oui non"

    private const val FR_WORDS = """
être suis es est sommes êtes sont était étaient été sera seront soit avoir ai as a avons avez ont avait avaient eu aura
auront ait faire fais fait faisons faites font faisait fera aller vais vas va allons allez vont allait ira allé pouvoir
peux peut pouvons pouvez peuvent pouvait pourra pourrait pu vouloir veux veut voulons voulez veulent voulait voudrait
voulu dire dis dit disons disent disait dira voir vois voit voyons voyez voient voyait verra vu savoir sais sait savons
savez savent savait su prendre prends prend prenons prennent pris venir viens vient venons viennent venu jouer joue joues
jouons jouez jouent jouait joué jouant jeu jeux joueur joueurs joueuse partie parties devoir dois doit devons devez
doivent devrait falloir faut mettre mets met mis passer passe passé trouver trouve trouvé donner donne donné parler
parle parlé aimer aime aimes aimé penser pense pensé regarder regarde regardé attendre attends attend attendu gagner
gagne gagné perdre perds perd perdu essayer essaie essayé ouvrir ouvre ouvert fermer ferme fermé télécharger télécharge
installer installe sauvegarder recommander recommande continuer continue commencer commence commencé finir finis fini
terminer termine terminé reposer repose rappeler rappelle
an ans année années jour jours fois temps heure heures minute minutes seconde secondes semaine semaines mois moment vie
monde maison chose choses homme femme gens personne personnes lieu forme cas groupe problème pays ville travail main
œil yeux eau nuit soir matin point points nom vérité mot mots histoire idée fin début côté fils père mère famille ami
amie amis niveau niveaux mission missions carte cartes personnage personnages ennemi ennemis chef arme armes bataille
écran écrans console consoles manette bouton boutique offre prix succès trophée mode modes campagne équipe serveur
connexion réseau batterie mémoire espace fichier version mise jour configuration option menu son musique volume voix
langue texte message notification application bibliothèque collection genre aventure action stratégie horreur course
sport studio saga édition contenu graphismes performance qualité résolution vitesse image vidéo photo session
récompense expérience santé énergie pouvoir compétence objet pièce pièces or argent saison événement tournoi victoire
défaite mort étoile cœur clé porte chemin zone région château forêt grotte montagne rivière mer île ciel terre feu
glace ombre lumière dragon chevalier roi reine héros légende âme destin royaume guerre paix force magie épée bouclier
trésor coffre manche tour phase étape chapitre épisode secret astuce aide erreur solution question réponse compte
profil utilisateur mot passe téléphone ordinateur casque
lundi mardi mercredi jeudi vendredi samedi dimanche janvier février mars avril mai juin juillet août septembre octobre
novembre décembre
zéro un deux trois quatre cinq six sept huit neuf dix onze douze treize quatorze quinze seize vingt trente quarante
cinquante soixante cent cents mille million millions milliard virgule premier première deuxième second seconde
bon bonne bons bonnes mauvais mauvaise grand grande grands petit petite petits nouveau nouvelle nouveaux vieux vieille
meilleur meilleure pire dernier dernière long longue court haut bas facile difficile rapide lent fort faible clair
sombre beau belle joli laid incroyable génial géniale parfait parfaite possible important certain réel total général
normal spécial principal simple libre heureux triste seul fatigué prêt sûr rouge bleu vert jaune noir blanc amusant
ennuyeux épique légendaire rare commun unique gratuit disponible officiel original classique moderne ancien prochain
précédent complet plein vide actif caché
bonjour salut bonsoir merci pardon désolé bravo allez voilà alors aussi encore déjà toujours jamais souvent parfois
maintenant après avant bientôt tard tôt peut-être presque seulement ensuite puis parce pourquoi comment combien rien
quelque chose quelqu'un tout toute tous toutes autre autres même chaque plusieurs peu beaucoup assez trop
masha elyndra wifi web chat appli online skin bug lag gamer gameplay remake streamer foot club internet rock pop jeu
"""

    private val FR_SUFFIXES = listOf(
        "ment", "ments", "eux", "euse", "euses", "ique", "iques", "isme", "iste", "aient", "ait", "ais", "ée", "ées", "eur",
        "eurs", "ette", "ettes", "ière", "ières", "eau", "eaux", "ité", "ités", "ance", "ence", "age", "ages", "ions", "iez",
    )

    /* ── Alemán (≈500) ── */

    private const val DE_FUNCTION =
        "der die das den dem des ein eine einen einem einer eines und oder aber doch denn sondern dass ob wenn als wie " +
            "in im an am auf aus bei beim mit nach von vom zu zum zur für über unter vor hinter neben zwischen durch gegen " +
            "ohne um bis seit ich du er sie es wir ihr mich dich sich uns euch mir dir ihm ihnen mein meine dein deine " +
            "sein seine unser nicht kein keine ja nein sehr mehr weniger hier da so"

    private const val DE_WORDS = """
sein bin bist ist sind seid war warst waren gewesen wird werden wurde wurden worden haben habe hast hat habt hatte
hatten gehabt können kann kannst könnt konnte könnte müssen muss musst musste müsste wollen will willst wollte sollen
soll sollst sollte dürfen darf durfte mögen mag möchte machen mache machst macht machte gemacht gehen gehe gehst geht
ging gegangen kommen komme kommst kommt kam gekommen sehen sehe siehst sieht sah gesehen sagen sage sagst sagt sagte
gesagt geben gibt gab gegeben spielen spiele spielst spielt spielte gespielt spielend spiel spiele spieler spielerin
wissen weiß weißt wusste finden finde findest findet fand gefunden bleiben bleibt blieb nehmen nimmt nahm genommen
brauchen brauchst braucht lassen lässt ließ stehen steht stand liegen liegt lag denken denkst denkt dachte glauben
glaubst glaubt heißen heißt hieß halten hält hielt zeigen zeigt zeigte führen führt sprechen spricht bringen bringt
leben lebt fragen fragt fragte gewinnen gewinnt gewann gewonnen verlieren verliert verlor verloren versuchen versucht
öffnen öffnet schließen schließt laden lädt geladen installieren installiert speichern speichert gespeichert
empfehlen empfehle empfiehlt starten startet beginnen beginnt begann enden endet warten wartet ausruhen erinnern
jahr jahre jahren tag tage tagen mal zeit stunde stunden minute minuten sekunde sekunden woche wochen monat monate
moment leben welt haus ding dinge mann frau leute mensch menschen person personen teil ort form fall gruppe problem
land stadt arbeit hand auge augen wasser nacht abend morgen punkt punkte name wahrheit wort wörter geschichte idee ende
anfang seite sohn vater mutter familie freund freundin freunde level stufe mission missionen karte karten figur
figuren feind feinde boss waffe waffen kampf kämpfe bildschirm konsole konsolen controller knopf laden angebot preis
erfolg erfolge trophäe modus kampagne team server verbindung netz netzwerk akku speicher platz datei version
aktualisierung einstellung einstellungen option menü ton musik lautstärke stimme sprache text nachricht nachrichten
benachrichtigung app bibliothek sammlung genre abenteuer aktion strategie horror rennen sport studio reihe ausgabe
inhalt grafik leistung qualität auflösung geschwindigkeit bild bilder video foto sitzung belohnung erfahrung gesundheit
energie kraft fähigkeit gegenstand münze münzen gold geld saison ereignis turnier sieg niederlage tod stern herz
schlüssel tür weg zone region burg wald höhle berg fluss meer insel himmel erde feuer eis schatten licht drache ritter
könig königin held legende seele schicksal reich krieg frieden magie schwert schild schatz truhe runde zug phase
kapitel folge geheimnis tipp hilfe fehler lösung frage antwort konto profil benutzer passwort handy computer kopfhörer
montag dienstag mittwoch donnerstag freitag samstag sonntag januar februar märz april mai juni juli august september
oktober november dezember
null eins zwei drei vier fünf sechs sieben acht neun zehn elf zwölf zwanzig dreißig vierzig fünfzig hundert tausend
million millionen milliarde komma prozent uhr erste zweite dritte
gut gute guter gutes schlecht groß große großer klein kleine neu neue neuer alt alte besser beste schlechter letzte
lang kurz hoch niedrig leicht einfach schwer schwierig schnell langsam stark schwach hell dunkel schön hässlich
unglaublich toll super perfekt möglich wichtig sicher echt ganz allgemein normal besonders frei glücklich traurig
allein müde fertig rot blau grün gelb schwarz weiß lustig langweilig episch legendär selten gemeinsam einzig kostenlos
verfügbar offiziell original klassisch modern nächste vorige voll leer aktiv geheim
hallo tschüss danke bitte entschuldigung klar genau okay also auch noch schon immer nie oft manchmal jetzt dann nachher
vorher bald spät früh vielleicht fast nur nun weil warum wie wieviel nichts niemand etwas jemand alles alle jeder jede
jedes andere anderen selbst viel viele wenig wenige genug zu
masha elyndra wlan web chat online skin bug lag gamer gameplay trailer remake streamer fußball klub internet rock pop
handy spiel spiele update updates download
"""

    private val DE_SUFFIXES = listOf(
        "ung", "ungen", "heit", "keit", "lich", "isch", "chen", "schaft", "ieren", "iert", "sch", "ßen", "tz", "ige", "iger",
        "igen", "ens", "ern", "eln", "bar", "los", "haft",
    )

    private val WORDS: Map<String, Words> by lazy {
        mapOf(
            "es" to Words(ES_WORDS, ES_FUNCTION, ES_SUFFIXES),
            "pt" to Words(PT_WORDS, PT_FUNCTION, PT_SUFFIXES),
            "fr" to Words(FR_WORDS, FR_FUNCTION, FR_SUFFIXES),
            "de" to Words(DE_WORDS.lowercase(), DE_FUNCTION, DE_SUFFIXES),
        )
    }
}
