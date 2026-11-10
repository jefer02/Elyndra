import tempfile
import unittest
from pathlib import Path

import hashlib
import zipfile

from portscat import discovery
from portscat.http import Http
from portscat.inner import InnerSha


class FakeDownloads(Http):
    """Downloads that write `files[url]` to disk (or fail midway)."""

    def __init__(self, files):
        super().__init__()
        self.files, self.calls = files, 0

    def download(self, url, dest, headers=None):
        self.calls += 1
        dest.parent.mkdir(parents=True, exist_ok=True)
        data = self.files[url]
        if data is None:
            dest.write_bytes(b"partial")
            dest.unlink()
            raise OSError("connection reset")
        dest.write_bytes(data)
        return hashlib.sha256(data).hexdigest()


class InnerApkHash(unittest.TestCase):
    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        zpath = self.work / "src.zip"
        with zipfile.ZipFile(zpath, "w") as z:
            z.writestr("game-android.apk", b"APKDATA")
        self.zip_bytes = zpath.read_bytes()

    def test_the_apk_inside_is_hashed_cached_and_no_temp_file_is_left(self):
        http = FakeDownloads({"https://x/g-android.zip": self.zip_bytes})
        inner = InnerSha(http, self.work / "cache", log=lambda *_: None)
        asset = {"name": "g-android.zip", "size": len(self.zip_bytes), "browser_download_url": "https://x/g-android.zip"}
        self.assertEqual(hashlib.sha256(b"APKDATA").hexdigest(), inner.get(asset))
        for sub in ("zip", "apk"):
            d = self.work / "cache" / sub
            self.assertEqual([], list(d.iterdir()) if d.is_dir() else [])
        # Cached: a second run doesn't download again.
        again = InnerSha(http, self.work / "cache", log=lambda *_: None)
        self.assertEqual(inner.get(asset), again.get(asset))
        self.assertEqual(1, http.calls)

    def test_a_failed_download_gives_no_hash_and_leftovers_are_purged(self):
        (self.work / "cache" / "zip").mkdir(parents=True)
        (self.work / "cache" / "zip" / "stale.zip").write_bytes(b"x" * 10)
        http = FakeDownloads({"https://x/bad.zip": None})
        inner = InnerSha(http, self.work / "cache", log=lambda *_: None)
        self.assertEqual([], list((self.work / "cache" / "zip").iterdir()))
        self.assertIsNone(inner.get({"name": "bad.zip", "size": 1, "browser_download_url": "https://x/bad.zip"}))


class DiscoveryCheckpoint(unittest.TestCase):
    class GH:
        def __init__(self):
            self.calls = []

        def raw(self, url):
            self.calls.append(url)
            return ""

        def search_repos(self, *a, **k):
            self.calls.append(("search", a))
            return []

        def get(self, *a, **k):
            from portscat.http import Response
            return Response(404, "")

        def readme(self, *a):
            return ""

        def forks(self, *a, **k):
            return []

        def releases(self, *a):
            return []

    def test_each_source_is_saved_and_a_resumed_run_skips_the_finished_ones(self):
        saved = []
        gh = self.GH()
        done: set[str] = set()
        discovery.discover(gh, set(), {}, log=lambda *_: None, on_source=lambda name, new: saved.append(name), done=done)
        self.assertIn("awesome-android-native-ports", saved)
        self.assertIn("portmaster", saved)
        self.assertEqual(set(saved), done)
        # Resumed with everything done: no source runs again.
        gh2 = self.GH()
        again = []
        discovery.discover(gh2, set(), {}, log=lambda *_: None, on_source=lambda n, new: again.append(n), done=set(saved))
        self.assertEqual([], again)
        self.assertEqual([], gh2.calls)


if __name__ == "__main__":
    unittest.main()
