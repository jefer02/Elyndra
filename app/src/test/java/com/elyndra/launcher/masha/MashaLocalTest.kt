package com.elyndra.launcher.masha

import com.elyndra.launcher.data.PlayStats
import com.elyndra.launcher.domain.Fixtures
import com.elyndra.launcher.domain.Fixtures.NOW
import com.elyndra.launcher.domain.Fixtures.folder
import com.elyndra.launcher.domain.Fixtures.rom
import com.elyndra.launcher.domain.Fixtures.session
import com.elyndra.launcher.domain.Fixtures.withArt
import com.elyndra.launcher.domain.curation.LibraryCurator
import com.elyndra.launcher.domain.device.DeviceState
import com.elyndra.launcher.domain.games
import com.elyndra.launcher.domain.lists.DynamicList
import com.elyndra.launcher.domain.profile.GameProfiles
import com.elyndra.launcher.domain.session.SessionMood
import com.elyndra.launcher.masha.offline.OfflineIntent
import com.elyndra.launcher.masha.offline.OfflineIntents
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import com.elyndra.launcher.domain.relationship.AffinityLevel
import org.junit.Test
import java.time.ZoneId

/** Masha sin IA (intenciones por palabras clave) y el contexto que se manda a la IA. */
class MashaLocalTest {

    /* ── intenciones sin conexión ─────────────────────────────── */

    @Test
    fun herNameIsIgnoredWhenItIsLucy() {
        assertEquals(OfflineIntent.Launch("Zelda"), OfflineIntents.detect("Lucy, juega Zelda"))
        assertEquals(OfflineIntent.Launch("Zelda"), OfflineIntents.detect("hey Lucy play Zelda"))
        assertEquals(OfflineIntent.Launch("Zelda"), OfflineIntents.detect("play Zelda, Lucy"))
        assertEquals(OfflineIntent.Plan(25, 35, SessionMood.Any), OfflineIntents.detect("Oye Lucy, tengo media hora"))
        assertEquals(OfflineIntent.Plan(30, 40, SessionMood.Any), OfflineIntents.detect("Salut Lucy : j'ai 30-40 minutes"))
        assertEquals(OfflineIntent.Plan(25, 35, SessionMood.Any), OfflineIntents.detect("Hallo Lucy, halbe Stunde"))
        assertEquals(OfflineIntent.Recommend, OfflineIntents.detect("Olá Lucy, o que jogar?"))
        assertEquals("ゼルダを起動して", OfflineIntents.withoutName("ねえルーシー、ゼルダを起動して"))
        assertEquals("ゼルダを起動して", OfflineIntents.withoutName("ルーシー、ゼルダを起動して"))
    }

    @Test
    fun commonMisspellingsOfLucyCount() {
        listOf("Lucie", "lucey", "Luci", "Lusy", "LUCYY").forEach {
            assertEquals(it, OfflineIntent.Launch("Zelda"), OfflineIntents.detect("$it, play Zelda"))
        }
        assertEquals("ゼルダを起動して", OfflineIntents.withoutName("ルシー、ゼルダを起動して"))
        // Dentro de otra palabra no es su nombre.
        assertEquals("Lucifer, play", OfflineIntents.withoutName("Lucifer, play"))
    }

    @Test
    fun mashaIsNoLongerAFormOfAddress() {
        assertEquals("Masha, play Zelda", OfflineIntents.withoutName("Masha, play Zelda"))
        assertEquals("マーシャ、ゼルダを起動して", OfflineIntents.withoutName("マーシャ、ゼルダを起動して"))
        assertEquals(OfflineIntent.Unknown, OfflineIntents.detect("Masha?"))
        assertEquals(OfflineIntent.Unknown, OfflineIntents.detect("Hi, Masha!"))
    }

    @Test
    fun aGreetingOrJustHerNameIsAHello() {
        listOf("Lucy", "Lucy?", "hola Lucy", "Hi, Lucy!", "Bonjour Lucy.", "ねえ、ルーシー", "hey").forEach {
            assertEquals(it, OfflineIntent.Hello, OfflineIntents.detect(it))
        }
        // Solo tras una coma cuenta como nombre al final: "play Lucy" podría ser un juego.
        assertEquals(OfflineIntent.Launch("Lucy"), OfflineIntents.detect("play Lucy"))
    }

    @Test
    fun timeBudgetsAreUnderstood() {
        assertEquals(OfflineIntent.Plan(30, 40, SessionMood.Any), OfflineIntents.detect("tengo 30-40 minutos"))
        assertEquals(OfflineIntent.Plan(30, 40, SessionMood.Any), OfflineIntents.detect("I have 30 to 40 min"))
        assertEquals(OfflineIntent.Plan(25, 35, SessionMood.Any), OfflineIntents.detect("tengo media hora"))
        assertEquals(OfflineIntent.Plan(80, 100, SessionMood.Any), OfflineIntents.detect("an hour and a half"))
        assertEquals(OfflineIntent.Plan(60, 120, SessionMood.Any), OfflineIntents.detect("1-2 horas"))
    }

    @Test
    fun moodsAreUnderstood() {
        assertEquals(SessionMood.Light, (OfflineIntents.detect("algo ligero") as OfflineIntent.Plan).mood)
        assertEquals(SessionMood.Continue, (OfflineIntents.detect("quiero seguir con algo") as OfflineIntent.Plan).mood)
        assertEquals(SessionMood.Fresh, (OfflineIntents.detect("something new, 45 min") as OfflineIntent.Plan).mood)
    }

    @Test
    fun launchesAreUnderstoodButOnlyWithATitle() {
        assertEquals(OfflineIntent.Launch("Okami"), OfflineIntents.detect("juega a Okami"))
        assertEquals(OfflineIntent.Launch("Crash Bandicoot"), OfflineIntents.detect("play Crash Bandicoot!"))
        assertTrue(OfflineIntents.detect("play something light") is OfflineIntent.Plan)
        assertEquals(OfflineIntent.ShowList(DynamicList.Abandoned), OfflineIntents.detect("abre los abandonados"))
    }

    @Test
    fun listsAreUnderstood() {
        assertEquals(OfflineIntent.ShowList(DynamicList.NeverOpened), OfflineIntents.detect("¿Qué juegos nunca he abierto?"))
        assertEquals(OfflineIntent.ShowList(DynamicList.ArtworkMissing), OfflineIntents.detect("¿Qué juegos están sin carátula?"))
        assertEquals(OfflineIntent.ShowList(DynamicList.BestOnDevice), OfflineIntents.detect("which games run best on this phone"))
        assertEquals(OfflineIntent.ShowList(DynamicList.Duplicates), OfflineIntents.detect("tengo duplicados?"))
    }

    @Test
    fun shortWordsInsideOtherWordsDoNotFool() {
        // "hora" está en "ahora", "stat" en "PlayStation", "arc" en "arcade".
        assertEquals(OfflineIntent.Recommend, OfflineIntents.detect("¿Qué juego de PlayStation me pongo ahora?"))
        assertEquals(OfflineIntent.Unknown, OfflineIntents.detect("me gustan los arcade"))
        assertEquals(OfflineIntent.Stats, OfflineIntents.detect("¿cuántas horas he jugado esta semana?"))
        assertEquals(OfflineIntent.Arcs, OfflineIntents.detect("¿cómo va mi arco?"))
    }

    @Test
    fun nativePortsAreUnderstoodInEveryLanguage() {
        listOf(
            "¿hay algún port nativo?",
            "is there a native port of Zelda?",
            "tem algum port nativo?",
            "y a-t-il un portage natif ?",
            "gibt es eine native Portierung?",
            "busca recompilaciones de Xbox 360",
            "show me decompilations",
        ).forEach { assertEquals(it, OfflineIntent.FindPort, OfflineIntents.detect(it)) }
    }

    @Test
    fun portInsideOtherWordsDoesNotFool() {
        assertTrue(OfflineIntents.detect("pon el modo portrait") !is OfflineIntent.FindPort)
        assertTrue(OfflineIntents.detect("no importa, qué juego me recomiendas") !is OfflineIntent.FindPort)
        assertTrue(OfflineIntents.detect("¿qué soporte tiene mi mando?") !is OfflineIntent.FindPort)
        assertTrue(OfflineIntents.detect("ouvre les portes") !is OfflineIntent.FindPort)
    }

    @Test
    fun maintenanceIsUnderstood() {
        assertEquals(OfflineIntent.UpdateMetadata, OfflineIntents.detect("actualiza las carátulas"))
        assertEquals(OfflineIntent.UpdateMetadata, OfflineIntents.detect("download the missing covers"))
        assertEquals(OfflineIntent.Cleanup, OfflineIntents.detect("revisa mi biblioteca"))
    }

    /* ── el contexto que ve la IA ─────────────────────────────── */

    private val ps2 = folder("ps2", "ps2", "nethersx2")
    private val okami = rom("okami", ps2, meta = withArt(name = "Okami", genre = "Action"), stats = PlayStats(90, NOW - 2 * Fixtures.DAY, 2))
    private val sotc = rom("sotc", ps2)
    private val library = Fixtures.library(
        listOf(ps2),
        listOf(okami, sotc),
        sessions = listOf(session("r:okami", 3.0, 50, "nethersx2"), session("r:okami", 2.0, 40, "nethersx2")),
    )

    private fun build(
        device: DeviceState?,
        now: Long = NOW,
        memories: List<Memory> = emptyList(),
        userFolders: List<String> = emptyList(),
        relationship: AffinityLevel? = null,
        lastConversation: Pair<String, Long>? = null,
    ): BuiltContext {
        val games = library.games()
        val snapshot = KnowledgeSnapshot(library, games, GameProfiles.buildAll(games, library.sessions, now = now), LibraryCurator.analyze(games), emptyList(), now)
        return MashaContextBuilder.build(
            ContextInput(
                snapshot = snapshot,
                arcs = emptyList(),
                memories = memories,
                device = device,
                zone = ZoneId.of("UTC"),
                language = "es",
                screen = "library",
                focus = "Okami",
                services = listOf("ScreenScraper"),
                metadataRunning = false,
                installedEmulators = listOf("NetherSX2"),
                folderEmulators = mapOf("ps2" to ("NetherSX2" to true)),
                savedLists = listOf("RPG pendientes"),
                userFolders = userFolders,
                relationship = relationship,
                lastConversation = lastConversation,
            ),
        )
    }

    @Test
    fun theRelationshipLevelGoesInTheContextAndChangesTheFingerprint() {
        val curious = build(DeviceState(), relationship = AffinityLevel.Curious)
        val close = build(DeviceState(), relationship = AffinityLevel.Close)
        assertEquals("close", DeepSeekProtocolJson.parse(close.json)["RELATIONSHIP_LEVEL"]!!.jsonPrimitive.content)
        assertNotEquals(curious.fingerprint, close.fingerprint)
        assertFalse("RELATIONSHIP_LEVEL" in DeepSeekProtocolJson.parse(build(DeviceState()).json))
    }

    @Test
    fun userFoldersGoByNameOnlyAndOnlyWhenThereAreAny() {
        val with = DeepSeekProtocolJson.parse(build(DeviceState(), userFolders = listOf("Favoritos")).json)
        assertEquals("Favoritos", with["library"]!!.jsonObject["user_folders"]!!.jsonArray.single().jsonPrimitive.content)
        val without = DeepSeekProtocolJson.parse(build(DeviceState()).json)
        assertFalse("user_folders" in without["library"]!!.jsonObject)
    }

    @Test
    fun theContextNeverCarriesPathsOrUris() {
        val summary = "We talked about content://com.android.externalstorage.documents/tree/primary:ROMs/okami.iso and /storage/emulated/0/media/x"
        listOf(build(DeviceState(batteryPct = 50)), build(DeviceState(batteryPct = 50), lastConversation = summary to NOW - 3_600_000)).forEach {
            val json = it.json
            assertFalse("content://" in json)
            assertFalse("primary:" in json)
            assertFalse(".iso" in json)
            assertFalse("media/" in json)
        }
    }

    @Test
    fun theLastConversationGoesInTheContextWithHowLongAgo() {
        val root = DeepSeekProtocolJson.parse(build(DeviceState(), lastConversation = "We talked about Okami." to NOW - 3 * 3_600_000L).json)
        val last = root["LAST_CONVERSATION"]!!.jsonObject
        assertEquals("We talked about Okami.", last["summary"]!!.jsonPrimitive.content)
        assertEquals("3h ago", last["ago"]!!.jsonPrimitive.content)
        assertFalse("LAST_CONVERSATION" in DeepSeekProtocolJson.parse(build(DeviceState()).json))
    }

    @Test
    fun theContextSaysWhatMashaNeeds() {
        val root = DeepSeekProtocolJson.parse(build(DeviceState(batteryPct = 50)).json)
        assertEquals(2, root["library"]!!.jsonObject["games"]!!.jsonPrimitive.content.toInt())
        assertEquals("Okami", root["in_progress"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(1, root["never_opened"]!!.jsonObject["count"]!!.jsonPrimitive.content.toInt())
        assertEquals("50", root["device"]!!.jsonObject["battery_pct"]!!.jsonPrimitive.content)
        assertEquals("Okami", root["screen"]!!.jsonObject["focused"]!!.jsonPrimitive.content)
        assertTrue(root["catalog"]!!.jsonArray.size <= MashaContextBuilder.MAX_CATALOG)
    }

    @Test
    fun theFingerprintIgnoresBatteryAndTheHour() {
        val a = build(DeviceState(batteryPct = 80), now = NOW)
        val b = build(DeviceState(batteryPct = 20, charging = true), now = NOW + 60 * 60 * 1000)
        assertEquals(a.fingerprint, b.fingerprint)
        assertNotEquals(a.json, b.json)
        // Lo que sí cambia la respuesta —lo que Masha recuerda— cambia la huella.
        val c = build(DeviceState(batteryPct = 80), memories = listOf(Memory(1, "preference", null, "Odia farmear", 0.7f, NOW)))
        assertNotEquals(a.fingerprint, c.fingerprint)
    }

    @Test
    fun relativeTimesReadNaturally() {
        assertEquals("5min ago", MashaContextBuilder.ago(5 * 60 * 1000L))
        assertEquals("3h ago", MashaContextBuilder.ago(3 * 60 * 60 * 1000L))
        assertEquals("yesterday", MashaContextBuilder.ago(Fixtures.DAY + 1000))
        assertEquals("3w ago", MashaContextBuilder.ago(21 * Fixtures.DAY))
    }

    private object DeepSeekProtocolJson {
        fun parse(json: String) = com.elyndra.launcher.masha.deepseek.DeepSeekProtocol.json.parseToJsonElement(json).jsonObject
    }
}
