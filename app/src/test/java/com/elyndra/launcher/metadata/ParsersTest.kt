package com.elyndra.launcher.metadata

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    private fun json(s: String) = Json.parseToJsonElement(s)

    private val ssGame = """
        {"header":{"success":"true"},"response":{
          "ssuser":{"id":"tester","niveau":"1","maxthreads":"1","requeststoday":"12","maxrequestsperday":"20000",
                    "requestskotoday":"2","maxrequestskoperday":"2000","maxrequestspermin":"40"},
          "jeu":{"id":"3","notgame":"false",
            "noms":[{"region":"ss","text":"Sonic The Hedgehog 2"},{"region":"jp","text":"ソニック・ザ・ヘッジホッグ2"}],
            "synopsis":[{"langue":"en","text":"Sonic &amp; Tails"},{"langue":"es","text":"Sonic y Tails"}],
            "dates":[{"region":"us","text":"1992-11-24"},{"region":"jp","text":"1992-11-21"}],
            "genres":[{"id":"7","noms":[{"langue":"en","text":"Platform"},{"langue":"es","text":"Plataformas"}]}],
            "developpeur":{"id":"1","text":"Sonic Team"},"editeur":{"id":"2","text":"SEGA"},
            "joueurs":{"text":"1-2"},"note":{"text":"17"},
            "medias":[
              {"type":"box-2D","parent":"jeu","url":"https://x/mediaJeu.php?media=box-2D(us)","region":"us","format":"png"},
              {"type":"box-2D","parent":"jeu","url":"https://x/mediaJeu.php?media=box-2D(eu)","region":"eu","format":"png"},
              {"type":"fanart","parent":"jeu","url":"https://x/fanart","format":"jpg"},
              {"type":"wheel-hd","parent":"jeu","url":"https://x/wheel","region":"wor","format":"png"}
            ]}}}
    """.trimIndent()

    @Test
    fun screenScraperUserAndGame() {
        val root = json(ssGame)
        val user = ScreenScraperParser.user(root)!!
        assertEquals("tester", user.id)
        assertEquals(12, user.requestsToday)
        assertEquals(20000, user.maxRequestsPerDay)

        val game = ScreenScraperParser.gameInfo(root)!!
        assertEquals("3", game.id)
        assertEquals("Sonic The Hedgehog 2", game.name(listOf("us")))
        assertEquals("ソニック・ザ・ヘッジホッグ2", game.name(listOf("jp")))
        assertEquals("Sonic y Tails", game.description(listOf("es")))
        assertEquals("Sonic & Tails", game.description(listOf("fr")))
        assertEquals("1992-11-24", game.releaseDate(listOf("eu")))
        assertEquals("1992-11-21", game.releaseDate(listOf("jp")))
        assertEquals("Plataformas", game.genre(listOf("es")))
        assertEquals("Sonic Team", game.developer)
        assertEquals("SEGA", game.publisher)
        assertEquals(17.0, game.rating!!, 0.0)
        assertEquals("https://x/mediaJeu.php?media=box-2D(eu)", game.media(listOf("box-2D"), listOf("eu"))!!.url)
        assertEquals("https://x/fanart", game.media(listOf("fanart", "ss"), listOf("es"))!!.url)
        assertNull(game.media(listOf("box-3D"), listOf("us")))
        assertFalse(game.notGame)
    }

    @Test
    fun screenScraperEmptySearchAndSizedUrls() {
        assertTrue(ScreenScraperParser.search(json("""{"response":{"jeux":[{}]}}""")).isEmpty())
        val media = SsMedia("box-2D", "https://x/mediaJeu.php?media=box-2D(us)", "us", "png")
        assertEquals("https://x/mediaJeu.php?media=box-2D(us)&maxwidth=640&outputformat=jpg", ScreenScraperClient.sizedMediaUrl(media, 640, jpg = true))
    }

    @Test
    fun screenScraperNotGameIsFlagged() {
        val g = ScreenScraperParser.game(json("""{"id":"9","noms":[{"region":"ss","text":"ZZZ(notgame):Setup"}]}""").let { it as kotlinx.serialization.json.JsonObject })!!
        assertTrue(g.notGame)
    }

    @Test
    fun igdbGamesTokenAndQuery() {
        val games = IgdbParser.games(
            json(
                """
                [{"id":1070,"name":"Super Mario World","summary":"Mario…","first_release_date":659059200,
                  "genres":[{"id":8,"name":"Platform"}],
                  "involved_companies":[{"id":1,"company":{"id":70,"name":"Nintendo EAD"},"developer":true,"publisher":false},
                                        {"id":2,"company":{"id":71,"name":"Nintendo"},"developer":false,"publisher":true}],
                  "cover":{"id":5,"image_id":"co2kn9"},"artworks":[{"id":6,"image_id":"ar1"}],
                  "screenshots":[{"id":7,"image_id":"sc1"}],"total_rating":88.5,"game_modes":[{"id":1,"name":"Single player"}]}]
                """.trimIndent(),
            ),
        )
        val g = games.single()
        assertEquals(1070L, g.id)
        assertEquals(listOf("Nintendo EAD"), g.developers)
        assertEquals(listOf("Nintendo"), g.publishers)
        assertEquals("co2kn9", g.coverId)
        assertEquals(listOf("ar1"), g.artworkIds)
        assertEquals(88.5, g.rating!!, 0.0)
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big/co2kn9.jpg", IgdbClient.imageUrl("co2kn9", "cover_big"))

        val token = IgdbParser.token(json("""{"access_token":"abc","expires_in":5000000,"token_type":"bearer"}"""), now = 1000L)!!
        assertEquals("abc", token.accessToken)
        assertEquals(1000L + (5_000_000L - 86_400L) * 1000L, token.expiresAt)

        val q = IgdbParser.searchQuery("Say \"Hi\"", listOf(19, 58))
        assertTrue(q.contains("search \"Say \\\"Hi\\\"\";"))
        assertTrue(q.contains("where platforms = (19,58);"))
        assertFalse(IgdbParser.searchQuery("Zelda", null).contains("where"))
    }

    @Test
    fun steamGridDbSearchImagesAndErrors() {
        val games = SgdbParser.games(json("""{"success":true,"data":[{"id":1,"name":"Celeste","types":["steam"],"verified":true}]}"""))
        assertEquals(1L, games.single().id)
        assertTrue(games.single().verified)
        val images = SgdbParser.images(
            json("""{"success":true,"data":[{"id":10,"score":3,"style":"alternate","width":600,"height":900,"url":"https://cdn2/g.png","thumb":"t"}]}"""),
        )
        assertEquals("https://cdn2/g.png", images.single().url)
        assertEquals(600, images.single().width)
        assertEquals("Invalid key", SgdbParser.error(json("""{"success":false,"errors":["Invalid key"]}""")))
    }

    @Test
    fun retroAchievementsProfileListAndProgress() {
        val profile = RaParser.profile(json("""{"User":"MaxMilyin","TotalPoints":399597,"TotalSoftcorePoints":0,"TotalTruePoints":1599212,"UserPic":"/UserPic/MaxMilyin.png"}"""))!!
        assertEquals("MaxMilyin", profile.user)
        assertEquals(399597, profile.points)

        val list = RaParser.gameList(
            json(
                """[{"Title":"Legend of Zelda, The: A Link to the Past","ID":2,"ConsoleID":3,"NumAchievements":50,"Points":500,"Hashes":["ABCDEF0123456789ABCDEF0123456789"]},
                    {"Title":"~Hack~ Legend of Zelda, The: A Link to the Past","ID":99,"ConsoleID":3,"NumAchievements":10,"Points":100,"Hashes":[]},
                    {"Title":"Pokemon - Red Version | Pokemon - Blue Version","ID":5,"ConsoleID":4,"NumAchievements":20,"Points":300,"Hashes":[]}]""",
            ),
        )
        assertEquals(3, list.size)
        assertEquals(2, RaParser.matchHash(list, "abcdef0123456789abcdef0123456789")!!.id)
        assertNull(RaParser.matchHash(list, "00000000000000000000000000000000"))
        assertEquals(2, RaParser.matchTitle(list, "The Legend of Zelda: A Link to the Past")!!.id)
        assertEquals(5, RaParser.matchTitle(list, "Pokemon - Blue Version")!!.id)
        assertNull(RaParser.matchTitle(list, "Super Metroid"))

        val progress = RaParser.progress(
            json(
                """{"ID":1,"Title":"Sonic the Hedgehog","ImageBoxArt":"/Images/051872.png","Released":"1992-06-02 00:00:00",
                   "NumAwardedToUser":1,"NumAwardedToUserHardcore":1,
                   "Achievements":{"9":{"ID":9,"Title":"That Was Easy","Description":"Act 1","Points":3,"BadgeName":"250336","DisplayOrder":1,
                                        "DateEarned":"2016-03-12 17:47:29","DateEarnedHardcore":"2016-03-12 17:47:29"},
                                   "10":{"ID":10,"Title":"Speedy","Description":"Fast","Points":10,"BadgeName":"250337","DisplayOrder":2}}}""",
            ),
        )
        assertNotNull(progress)
        assertEquals(2, progress!!.total)
        assertEquals(1, progress.earned)
        assertEquals(13, progress.points)
        assertEquals(3, progress.earnedPoints)
        assertEquals("1992-06-02", progress.released)
        assertEquals("https://media.retroachievements.org/Badge/250337_lock.png", RetroAchievementsClient.badgeUrl("250337", locked = true))
    }

    @Test
    fun retroAchievementsEmptyAchievementsArray() {
        val p = RaParser.progress(json("""{"ID":7,"Title":"X","Achievements":[]}"""))!!
        assertEquals(0, p.total)
    }
}
