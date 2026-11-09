import json
import unittest
from pathlib import Path

from portscat import gates
from portscat.github import count_from, latest_release
from portscat.http import Response

FIX = Path(__file__).parent / "fixtures"


def fixture(name):
    text = (FIX / name).read_text(encoding="utf-8")
    return json.loads(text) if name.endswith(".json") else text


REPO = {"full_name": "o/r", "created_at": "2024-01-01T00:00:00Z"}
LANGS = {"C++": 120000, "CMake": 4000}


def evaluate(**kw):
    args = dict(repo_status=200, repo=REPO, languages=LANGS, commits=250, release=fixture("release_ok.json"),
                readme=fixture("readme_byo.md"), blocklist=set())
    args.update(kw)
    return gates.evaluate("o/r", **args)


class SourceGate(unittest.TestCase):
    def test_no_source_code_is_flagged_not_excluded(self):
        v = evaluate(languages={})
        self.assertTrue(v.included)
        self.assertIn(gates.NO_SOURCE, v.flags)
        self.assertIn(gates.NO_SOURCE, evaluate(commits=1).flags)
        self.assertNotIn(gates.NO_SOURCE, evaluate().flags)

    def test_no_source_and_a_huge_build_is_a_repackaged_game(self):
        release = fixture("release_ok.json")
        release["assets"][0]["size"] = 900 * 1_048_576
        v = evaluate(languages={}, release=release)
        self.assertFalse(v.included)
        self.assertTrue(v.reason.startswith("G5"))

    def test_unknown_commit_count_does_not_exclude(self):
        self.assertTrue(evaluate(commits=None).included)


class ApkGate(unittest.TestCase):
    def test_an_apk_passes_and_zips_beside_it_do_not_count(self):
        v = evaluate()
        self.assertTrue(v.included)
        self.assertEqual(["soh-android-arm64-v8a.apk", "soh-android-armeabi-v7a.apk"], [a["name"] for a in v.apks])

    def test_a_debug_build_installs_flagged_and_an_unsigned_one_does_not(self):
        v = evaluate(release=fixture("release_debug_only.json"))
        self.assertTrue(v.included)
        self.assertEqual(gates.ANDROID, v.availability)
        self.assertIn(gates.DEBUG_BUILD, v.flags)
        self.assertEqual(["app-debug.apk"], [a["name"] for a in v.apks])
        unsigned = {"tag_name": "v1", "published_at": "2026-01-01T00:00:00Z", "assets": [{"name": "game-unsigned.apk"}]}
        u = evaluate(release=unsigned)
        self.assertTrue(u.included)
        self.assertEqual(gates.SOURCE, u.availability)
        self.assertIn("unsigned", u.note)

    def test_an_apk_inside_an_android_zip_installs_from_the_zip(self):
        v = evaluate(release=fixture("release_zip.json"))
        self.assertTrue(v.included)
        self.assertEqual(gates.ANDROID, v.availability)
        self.assertEqual("fheroes2_android.zip", v.archive["name"])
        self.assertNotIn(gates.DEBUG_BUILD, v.flags)

    def test_a_zip_whose_apk_couldnt_be_hashed_is_view_only_never_installable(self):
        v = evaluate(release=fixture("release_zip.json"))
        self.assertIs(v, gates.zip_checked(v, "ab" * 32))
        u = gates.zip_checked(v, None)
        self.assertTrue(u.included)
        self.assertEqual(gates.SOURCE, u.availability)
        self.assertIsNone(u.archive)
        self.assertEqual([], u.apks)
        self.assertIn("fheroes2_android.zip", u.note)
        # A plain APK release has no zip to check.
        plain = evaluate()
        self.assertIs(plain, gates.zip_checked(plain, None))

    def test_no_release_or_no_android_build_is_view_only(self):
        v = evaluate(release=None)
        self.assertTrue(v.included)
        self.assertEqual(gates.SOURCE, v.availability)
        self.assertEqual("no releases", v.note)
        pc = {"tag_name": "v2", "published_at": "2026-01-01T00:00:00Z", "assets": [{"name": "game-windows-x64.zip"}]}
        w = evaluate(release=pc)
        self.assertEqual(gates.SOURCE, w.availability)
        # sources.yaml can force it (a build kit whose release isn't a game build).
        self.assertEqual(gates.SOURCE, evaluate(view_only=True).availability)
        self.assertEqual([], evaluate(view_only=True).apks)

    def test_the_release_picked_is_the_newest_with_something_android_installs(self):
        releases = [
            {"tag_name": "v3", "published_at": "2026-03-01T00:00:00Z", "assets": [{"name": "win.zip"}]},
            {"tag_name": "v2", "published_at": "2026-02-01T00:00:00Z", "assets": [{"name": "game.apk"}]},
            {"tag_name": "v1", "published_at": "2026-01-01T00:00:00Z", "assets": [{"name": "old.apk"}]},
        ]
        self.assertEqual("v2", gates.pick_release(releases)["tag_name"])
        self.assertEqual("v3", gates.pick_release(releases[:1])["tag_name"])
        self.assertIsNone(gates.pick_release([]))

    def test_vr_builds_are_marked_for_headsets(self):
        quest = {"tag_name": "v1", "published_at": "2026-01-01T00:00:00Z", "assets": [{"name": "Strikers-MetaQuest3-alpha1.18.apk"}]}
        dq = {"tag_name": "v1", "published_at": "2026-01-01T00:00:00Z", "assets": [{"name": "dragon-quest-android.apk"}]}
        self.assertEqual(gates.ANDROID, evaluate(release=dq).availability)
        self.assertEqual(gates.VR_ONLY, evaluate(readme="Runs standalone on Meta Quest 3.").availability)
        self.assertEqual(gates.VR_ONLY, evaluate(release=quest).availability)
        self.assertEqual(gates.VR_ONLY, evaluate(vr=True).availability)
        # sources.yaml can say it isn't (a name with "quest" that isn't a headset build).
        self.assertEqual(gates.ANDROID, evaluate(release=quest, vr=False).availability)
        self.assertEqual(gates.ANDROID, evaluate().availability)

    def test_the_latest_release_may_be_a_prerelease_and_drafts_never_count(self):
        self.assertEqual("v2.1-rc1", latest_release(fixture("releases_list.json"))["tag_name"])
        self.assertIsNone(latest_release([{"tag_name": "d", "draft": True, "published_at": None}]))

    def test_a_prerelease_is_flagged(self):
        release = dict(fixture("release_ok.json"), prerelease=True)
        self.assertIn(gates.PRERELEASE, evaluate(release=release).flags)


class BlocklistGate(unittest.TestCase):
    def test_blocklisted_repos_are_excluded_case_insensitively(self):
        v = gates.evaluate("RE3/re3", repo_status=451, repo=None, languages={}, commits=None, release=None, readme="",
                           blocklist={"re3/re3"})
        self.assertEqual("G4 blocklisted", v.reason)

    def test_a_missing_repo_says_why(self):
        self.assertEqual("repo unavailable (HTTP 404)", evaluate(repo=None, repo_status=404).reason)


class BundledDataGate(unittest.TestCase):
    def test_a_readme_that_says_the_data_ships_is_excluded(self):
        self.assertTrue(gates.says_bundled(fixture("readme_bundled.md")))
        self.assertTrue(evaluate(readme=fixture("readme_bundled.md")).reason.startswith("G5"))

    def test_negated_claims_are_not_bundled(self):
        self.assertFalse(gates.says_bundled(fixture("readme_byo.md")))
        self.assertFalse(gates.says_bundled(fixture("readme_negated.md")))
        self.assertTrue(evaluate(readme=fixture("readme_negated.md")).included)

    def test_a_readme_that_never_asks_for_your_copy_needs_a_check(self):
        v = evaluate(readme=fixture("readme_vague.md"))
        self.assertTrue(v.included)
        self.assertIn(gates.CHECK_REQUIREMENTS, v.flags)
        self.assertNotIn(gates.CHECK_REQUIREMENTS, evaluate().flags)

    def test_a_huge_apk_needs_a_check(self):
        release = fixture("release_ok.json")
        release["assets"][0]["size"] = 900 * 1_048_576
        self.assertIn(gates.CHECK_REQUIREMENTS, evaluate(release=release).flags)


class OnlyThreeExclusions(unittest.TestCase):
    def test_nothing_but_blocklist_404_and_bundled_data_excludes(self):
        # No VirusTotal gate any more: flags never exclude.
        self.assertFalse(hasattr(gates, "scan_gate"))
        for v in [evaluate(), evaluate(release=None), evaluate(languages={}), evaluate(release=fixture("release_debug_only.json"))]:
            self.assertTrue(v.included)
        self.assertNotIn("not_scanned", evaluate().flags)


class Counting(unittest.TestCase):
    def test_counts_come_from_the_last_page_link(self):
        link = '<https://api.github.com/repositories/1/commits?per_page=1&page=2>; rel="next", <https://api.github.com/repositories/1/commits?per_page=1&page=1234>; rel="last"'
        self.assertEqual(1234, count_from(Response(200, "u", {"link": link}, b"[{}]")))
        self.assertEqual(1, count_from(Response(200, "u", {}, b"[{}]")))
        self.assertEqual(0, count_from(Response(409, "u", {}, b"")))


if __name__ == "__main__":
    unittest.main()
