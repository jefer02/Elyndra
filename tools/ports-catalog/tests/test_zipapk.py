import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from portscat import zipapk


def make_zip(path: Path, files: dict[str, bytes]):
    with zipfile.ZipFile(path, "w") as z:
        for name, data in files.items():
            z.writestr(name, data)


class ZipApk(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def test_the_single_apk_is_extracted_with_its_hash(self):
        z = self.tmp / "game-android.zip"
        make_zip(z, {"README.txt": b"hi", "build/game.apk": b"APKDATA"})
        apk, name, sha = zipapk.extract(z, self.tmp / "out")
        self.assertEqual("build/game.apk", name)
        self.assertEqual(hashlib.sha256(b"APKDATA").hexdigest(), sha)
        self.assertEqual(b"APKDATA", apk.read_bytes())
        self.assertEqual(self.tmp / "out", apk.parent)

    def test_with_several_the_arm64_or_abi_less_one_wins(self):
        self.assertEqual("g-arm64-v8a.apk", zipapk.pick(["g-armeabi-v7a.apk", "g-arm64-v8a.apk"]))
        self.assertEqual("g.apk", zipapk.pick(["g-x86.apk", "g.apk"]))
        self.assertIsNone(zipapk.pick(["g-x86.apk", "g-armeabi-v7a.apk"]))
        self.assertIsNone(zipapk.pick(["readme.txt"]))

    def test_paths_that_leave_the_archive_are_never_picked(self):
        self.assertIsNone(zipapk.pick(["../evil.apk"]))
        self.assertIsNone(zipapk.pick(["/abs/evil.apk"]))
        self.assertIsNone(zipapk.pick(["C:/evil.apk"]))
        self.assertIsNone(zipapk.pick(["__MACOSX/._game.apk"]))
        z = self.tmp / "evil.zip"
        make_zip(z, {"../evil.apk": b"x"})
        with self.assertRaises(zipapk.BadArchive):
            zipapk.extract(z, self.tmp / "out")

    def test_not_a_zip_is_a_bad_archive(self):
        bad = self.tmp / "bad.zip"
        bad.write_bytes(b"not a zip")
        with self.assertRaises(zipapk.BadArchive):
            zipapk.extract(bad, self.tmp / "out")


if __name__ == "__main__":
    unittest.main()
