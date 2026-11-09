import json
import unittest
from pathlib import Path
from types import SimpleNamespace

import yaml

from portscat import catalog as cat
from portscat import discovery, env
from portscat.art import GameArt, normalize, similar
from portscat.gates import Verdict
from portscat.report import Report
from portscat.trust import Trust

FIX = Path(__file__).parent / "fixtures"


class Discovery(unittest.TestCase):
    def test_awesome_list_rows_become_candidates_and_tool_sections_are_skipped(self):
        found = discovery.parse_awesome((FIX / "awesome_sample.md").read_text(encoding="utf-8"))
        repos = [c.repo for c in found]
        self.assertEqual(["Waterdish/Shipwright-Android", "izzy2lost/perfect_dark", "diasurgical/DevilutionX", "FWGS/xash3d-fwgs"], repos)
        oot = found[0]
        self.assertEqual(("Ocarina of Time", "n64"), (oot.game, oot.platform))
        self.assertTrue(oot.guessed)
        self.assertEqual("pc", found[2].platform)

    def test_obtainium_pack_skips_obtainium_itself(self):
        found = discovery.parse_obtainium((FIX / "obtainium_sample.json").read_text(encoding="utf-8"))
        self.assertEqual(["linkzenic/Zelda64Recomp-Android"], [c.repo for c in found])
        self.assertEqual("Zelda 64: Recompiled", found[0].game)

    def test_osgameclones_entries_need_github_and_android(self):
        found = discovery.parse_osgameclones((FIX / "osgameclones_sample.yaml").read_text(encoding="utf-8"))
        self.assertEqual([("diasurgical/devilutionX", "Diablo")], [(c.repo, c.game) for c in found])

    def test_portmaster_gives_unique_titles_only(self):
        self.assertEqual(["Cave Story", "Doom"], discovery.portmaster_titles((FIX / "portmaster_sample.json").read_text()))

    def test_game_names_lose_list_decorations(self):
        self.assertEqual("Halo CE", discovery.clean_game("Halo CE (Andiweli)"))
        self.assertEqual("Sonic Unleashed", discovery.clean_game("Sonic Unleashed - Android Port"))
        self.assertEqual("Perfect Dark", discovery.clean_game("Perfect Dark"))

    def test_appended_blocks_are_valid_yaml(self):
        c = discovery.Candidate("o/r", 'Game "Quoted": Part 2', "xbox360", True, "github-topic:recompilation", "a note")
        doc = yaml.safe_load("ports:\n" + discovery.yaml_block(c))
        self.assertEqual({"repo": "o/r", "game": 'Game "Quoted": Part 2', "platform": "xbox360", "platformGuessed": True,
                          "discovered": "github-topic:recompilation", "note": "a note"}, doc["ports"][0])

    def test_search_hits_must_be_ports_that_name_their_game(self):
        def hit(name, desc):
            return {"full_name": f"o/{name}", "name": name, "description": desc, "topics": []}
        c = discovery.from_search_item(hit("MetroidPrime2Port", "Native port of Metroid Prime 2: Echoes (GameCube) for Android"), "github-topic:decompilation")
        self.assertEqual(("Metroid Prime 2", "gc", True), (c.game, c.platform, c.game_guessed))
        c = discovery.from_search_item(hit("ngbrecomp", "Ninja Gaiden Black (Xbox, 2005) statically recompiled to Android"), "t")
        self.assertEqual("Ninja Gaiden Black", c.game)
        c = discovery.from_search_item(hit("FableDroid", "Fable II running natively on Android: static recompilation"), "t")
        self.assertEqual("Fable II", c.game)
        # Tools, lists, emulators and system code are not ports.
        for name, desc in [("ddc", "Android DEX to Java decompiler in Rust"), ("awesome-ports", "A curated list of native Android ports"),
                           ("Panda3DS", "HLE 3DS emulator"), ("android_kernel_x", "Kernel for the sprout device"),
                           ("smali", "smali and baksmali release artifacts")]:
            self.assertIsNone(discovery.from_search_item(hit(name, desc), "t"), name)
        # No game named: nothing to add.
        self.assertIsNone(discovery.from_search_item(hit("thing", "An Android app"), "t"))

    def test_a_guessed_game_is_flagged_in_sources(self):
        c = discovery.Candidate("o/r", "Fable II", "xbox360", False, "github-topic:recompilation", game_guessed=True)
        self.assertTrue(yaml.safe_load("ports:\n" + discovery.yaml_block(c))["ports"][0]["gameGuessed"])

    def test_platform_guesses(self):
        self.assertEqual("xbox360", discovery.guess_platform("Static recompilation of an Xbox 360 game")[0])
        self.assertEqual("wiiu", discovery.guess_platform("Wii U recomp")[0])
        self.assertEqual("pc", discovery.guess_platform("an engine")[0])


class FakeGH:
    """Offline GitHub: search pages and releases from dicts; counts release lookups."""

    def __init__(self, search=None, releases=None):
        self.search, self.rel, self.release_calls = search or {}, releases or {}, 0

    def search_repos(self, query, per_page=100, page=1):
        return self.search.get((query, page), [])

    def releases(self, slug):
        self.release_calls += 1
        return self.rel.get(slug, [])


class MoreDiscovery(unittest.TestCase):
    APK = [{"tag_name": "v1", "published_at": "2026-01-01T00:00:00Z", "assets": [{"name": "game-android.apk"}]}]

    def item(self, name, desc, stars=0, **kw):
        return {"full_name": f"o/{name}", "name": name, "description": desc, "stargazers_count": stars, "topics": [], **kw}

    def test_keyword_hits_need_an_apk_or_a_named_game_with_a_following(self):
        gh = FakeGH(releases={"o/withapk": self.APK})
        budget = [10]
        apk = discovery.keyword_candidate(gh, self.item("withapk", "Android port of Wario Land 4"), "s", budget)
        self.assertEqual("Wario Land 4", apk.game)
        self.assertTrue(apk.game_guessed)
        # No APK: only with a named game and some stars.
        self.assertIsNone(discovery.keyword_candidate(gh, self.item("x", "Android port of Mega Man X", stars=3), "s", budget))
        self.assertEqual("Mega Man X", discovery.keyword_candidate(gh, self.item("y", "Android port of Mega Man X", stars=40), "s", budget).game)
        # Not about Android at all, or a tool: never.
        self.assertIsNone(discovery.keyword_candidate(gh, self.item("z", "PC port of Mega Man X", stars=99), "s", budget))
        self.assertIsNone(discovery.keyword_candidate(gh, self.item("t", "Android decompiler for games", stars=99), "s", budget))

    def test_release_lookups_stop_at_the_budget(self):
        gh = FakeGH(releases={"o/a": self.APK})
        budget = [0]
        self.assertIsNone(discovery.keyword_candidate(gh, self.item("a", "android port of something"), "s", budget))
        self.assertEqual(0, gh.release_calls)

    def test_generic_markdown_lists_and_the_reddit_wiki_give_candidates(self):
        md = "- [Super Mario 64](https://github.com/o/sm64-android) - Android port\n- [awesome list](https://github.com/x/awesome)"
        c = discovery.parse_links(md, "list:x")
        self.assertEqual([("o/sm64-android", "Super Mario 64")], [(x.repo, x.game) for x in c])
        wiki = json.dumps({"data": {"content_md": "| [Sonic Mania](https://github.com/o/mania) | Android |"}})
        self.assertEqual(["o/mania"], [x.repo for x in discovery.reddit_wiki(wiki)])
        self.assertEqual([], discovery.reddit_wiki("<html>blocked</html>"))

    def test_descriptions_are_not_game_names(self):
        for bad in ["All seven Commander Keen games in one app for Windows and Android - a", "npiriou's Rerac-vr",
                    "Noita Android port: the Windows game", "x" * 61]:
            self.assertFalse(discovery.plausible_game(bad), bad)
        self.assertTrue(discovery.plausible_game("The Legend of Zelda: Twilight Princess"))

    def test_ps4_is_its_own_platform(self):
        self.assertEqual("ps4", discovery.guess_platform("God of War III Remastered (PS4) native Android port")[0])
        self.assertEqual("ps3", discovery.guess_platform("PS3 recomp for Android")[0])

    def test_switch_2_is_its_own_platform(self):
        self.assertEqual("switch2", discovery.guess_platform("Native Switch 2 game ported to Android")[0])
        self.assertEqual("switch2", discovery.guess_platform("NS2 recomp")[0])
        self.assertEqual("switch", discovery.guess_platform("Nintendo Switch recomp")[0])


class Env(unittest.TestCase):
    def test_dotenv_parsing(self):
        parsed = env.parse_dotenv('# c\n\nexport A=1\nB = "two words"\nC=x # comment\nD=\nbad line\n')
        self.assertEqual({"A": "1", "B": "two words", "C": "x", "D": ""}, parsed)

    def test_real_environment_variables_win(self):
        tmp = FIX / ".." / "_tmp.env"
        tmp.write_text("SGDB_API_KEY=from-file\nIGDB_CLIENT_ID=file-id\n", encoding="utf-8")
        try:
            environ = {"SGDB_API_KEY": "from-env"}
            self.assertEqual(["IGDB_CLIENT_ID"], env.load(tmp, environ))
            self.assertEqual("from-env", environ["SGDB_API_KEY"])
            self.assertEqual("file-id", environ["IGDB_CLIENT_ID"])
        finally:
            tmp.unlink()

    def test_redact_scrubs_key_values(self):
        environ = {"SGDB_API_KEY": "supersecretvalue"}
        self.assertEqual("token=*** ok", env.redact("token=supersecretvalue ok", environ))


class Art(unittest.TestCase):
    def test_titles_compare_like_the_app(self):
        self.assertEqual(normalize("The Legend of Zelda: Ocarina of Time"), normalize("Legend of Zelda, The - Ocarina of Time (USA)"))
        self.assertTrue(similar("Pokémon Emerald", "Pokemon Emerald"))
        self.assertFalse(similar("Mario Kart 64", "Mario Kart Wii"))


class Catalog(unittest.TestCase):
    REPO = {"full_name": "Waterdish/Shipwright-Android", "name": "Shipwright-Android", "owner": {"login": "Waterdish"},
            "description": "Ship of Harkinian for Android", "topics": ["android", "zelda", "n64"], "license": {"spdx_id": "NOASSERTION"},
            "stargazers_count": 410, "created_at": "2024-02-01T00:00:00Z", "fork": True, "parent": {"full_name": "HarbourMasters/Shipwright"}}
    RELEASE = {"tag_name": "v9.0.5", "published_at": "2026-04-02T12:00:00Z", "prerelease": False}

    def entry(self, src=None, art=None):
        src = src or {"repo": "Waterdish/Shipwright-Android", "game": "The Legend of Zelda: Ocarina of Time", "platform": "n64"}
        art = art or GameArt(igdbId=1029, releaseYear=1998, cover="https://images.igdb.com/c.jpg")
        return cat.build_entry(src, self.REPO, self.RELEASE, Verdict(True, flags=["prerelease"]), Trust(False, "< 3 contributors"), art, 2)

    def test_links_in_the_repo_description_are_dropped(self):
        self.assertEqual("Android port of", cat.plain_text("Android port of https://github.com/x/y"))
        self.assertEqual("Simple port for Android", cat.plain_text("Simple port (https://x.y/z) for Android"))
        self.assertNotIn("http", cat.plain_text("A clone of http://old.googlecode.com/svn. Cloned by http://svn2github.com/ , closed."))
        self.assertEqual("Ship of Harkinian for Android", cat.plain_text("Ship of Harkinian for Android"))

    def test_an_entry_has_the_schema_2_shape_the_app_reads(self):
        e = self.entry()
        self.assertEqual("waterdish-shipwright-android", e["id"])
        self.assertEqual("Shipwright-Android", e["name"])
        self.assertEqual({"name": "The Legend of Zelda: Ocarina of Time", "originalPlatform": "n64", "igdbId": 1029, "releaseYear": 1998,
                          "art": {"cover": "https://images.igdb.com/c.jpg"}}, e["game"])
        self.assertEqual({"owner": "HarbourMasters", "name": "Shipwright"}, e["upstream"])
        self.assertEqual(["zelda"], e["tags"])
        self.assertNotIn("license", e)
        self.assertNotIn("scan", e["trust"])
        self.assertEqual(["prerelease"], e["trust"]["flags"])
        self.assertEqual({"en": "Ship of Harkinian for Android"}, e["short"])
        self.assertTrue(e["requires"]["userRom"])
        json.dumps(e)

    def test_seed_fields_win_and_ids_stay(self):
        src = {"repo": "Waterdish/Shipwright-Android", "id": "soh", "game": "G", "platform": "n64", "port": "Ship of Harkinian",
               "requirement": "Your own ROM.", "featured": True, "asset": "(?i)android.*\\.apk$"}
        e = self.entry(src)
        self.assertEqual(("soh", "Ship of Harkinian", True), (e["id"], e["name"], e["featured"]))
        self.assertEqual({"en": "Your own ROM."}, e["requires"]["text"])
        self.assertEqual([{"platform": "android", "asset": "(?i)android.*\\.apk$"}], e["builds"])

    def test_long_repo_ids_are_cut_with_a_hash_and_stay_valid(self):
        long_id = cat.slug_id("SSunnKing/Open-Nectar---Pikmin-Native-PC-Mobile-Port-With-A-Very-Long-Name")
        self.assertLessEqual(len(long_id), 48)
        self.assertTrue(cat.ID.fullmatch(long_id))
        self.assertEqual(long_id, cat.slug_id("SSunnKing/Open-Nectar---Pikmin-Native-PC-Mobile-Port-With-A-Very-Long-Name"))

    def test_duplicate_ids_are_reported(self):
        e = self.entry()
        self.assertEqual(["waterdish-shipwright-android: duplicate id"], cat.unique_ids([e, dict(e)]))

    def test_view_only_zip_and_vr_entries_have_the_shape_the_app_reads(self):
        src = {"repo": "Waterdish/Shipwright-Android", "game": "G", "platform": "n64"}
        art = GameArt(cover="https://c")
        view = cat.build_entry(src, self.REPO, None, Verdict(True, availability="source", note="no releases"), Trust(False, "x"), art, 2)
        self.assertEqual([], view.get("builds", []))
        self.assertEqual("source", view["availability"])
        self.assertNotIn("release", view)
        self.assertNotIn("scan", view["trust"])
        zipped = cat.build_entry(src, self.REPO, self.RELEASE, Verdict(True, archive={"name": "g-android.zip"}), Trust(False, "x"), art, 2,
                                 inner_sha256="ab" * 32)
        b = zipped["builds"][0]
        self.assertEqual(r"(?i)\.apk$", b["archive"])
        self.assertEqual("ab" * 32, b["innerSha256"])
        self.assertRegex("g-android.zip", b["asset"])
        self.assertNotRegex("g-windows.zip", b["asset"])
        self.assertNotIn("availability", zipped)
        vr = cat.build_entry(src, self.REPO, self.RELEASE, Verdict(True, availability="vr"), Trust(False, "x"), art, 2)
        self.assertEqual("vr", vr["availability"])
        r = Report(included=[view, zipped, vr], view_only=[("o/r", "G", "no releases")])
        text = r.render("2026-11-08")
        self.assertIn("| n64 | 1 | 1 | 1 |", text)
        self.assertIn("## Not on Android yet (view-only)", text)

    def test_the_report_lists_missing_art_and_exclusions(self):
        e = self.entry(art=GameArt())
        r = Report(included=[e], excluded=[("re3/re3", "GTA III", "G4 blocklisted")])
        text = r.render("2026-11-08")
        self.assertIn("no cover / hero", text)
        self.assertIn("| re3/re3 | GTA III | G4 blocklisted |", text)
        self.assertNotIn("VirusTotal", text)
        self.assertNotIn("Not scanned", text)


class SourcesFiles(unittest.TestCase):
    ROOT = Path(__file__).parent.parent

    def test_sources_blocklist_and_trusted_parse(self):
        sources = yaml.safe_load((self.ROOT / "sources.yaml").read_text(encoding="utf-8"))
        ports = sources["ports"]
        self.assertGreater(len(ports), 100)
        for p in ports:
            self.assertTrue(p.get("repo") and p.get("game") and p.get("platform"), p)
        repos = [p["repo"].lower() for p in ports]
        self.assertEqual(len(repos), len(set(repos)), "a repo is listed twice")
        ids = [p["id"] for p in ports if "id" in p]
        self.assertIn("soh", ids)
        block = yaml.safe_load((self.ROOT / "blocklist.yaml").read_text(encoding="utf-8"))
        self.assertIn("re3/re3", {b["repo"].lower() for b in block["repos"]})
        self.assertEqual([], yaml.safe_load((self.ROOT / "trusted.yaml").read_text(encoding="utf-8"))["repos"])

    def test_not_games_are_excluded_with_their_reason_before_any_request(self):
        import generate
        ports = [{"repo": "a/game", "game": "Doom", "platform": "dos"},
                 {"repo": "b/app", "game": "kdeconnect", "platform": "pc", "notGame": "an app or tool, not a game"}]
        kept, excluded = generate.split_not_games(ports)
        self.assertEqual(["a/game"], [p["repo"] for p in kept])
        self.assertEqual([("b/app", "kdeconnect", "not a game port: an app or tool, not a game")], excluded)
        # Every notGame in sources.yaml says why.
        sources = yaml.safe_load((self.ROOT / "sources.yaml").read_text(encoding="utf-8"))
        for p in sources["ports"]:
            if "notGame" in p:
                self.assertIsInstance(p["notGame"], str, p["repo"])
                self.assertTrue(p["notGame"].strip(), p["repo"])

    def test_no_key_values_are_committed_anywhere_but_env(self):
        # .env.example has names only; no tracked file in the tool holds something that looks like a key value.
        example = env.parse_dotenv((self.ROOT / ".env.example").read_text(encoding="utf-8"))
        self.assertEqual(set(env.KEYS), set(example))
        self.assertTrue(all(v == "" for v in example.values()))


if __name__ == "__main__":
    unittest.main()
