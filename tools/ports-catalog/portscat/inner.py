"""The SHA-256 of the APK inside a zip release, for the catalog's `innerSha256` (the app checks it after extracting).

The zip is streamed to a temp file on disk, the APK extracted with the same safety rules as the app
(zipapk.py), hashed and both files deleted. Results are kept in `.cache/inner-sha.json` by the zip's
URL and size, so a release is downloaded once.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from . import zipapk
from .http import Http


class InnerSha:
    def __init__(self, http: Http, workdir: Path, log=print):
        self.http, self.workdir, self.log = http, workdir, log
        self.path = workdir / "inner-sha.json"
        try:
            self.data = json.loads(self.path.read_text(encoding="utf-8")) if self.path.is_file() else {}
        except ValueError:
            self.data = {}
        self.purge_temp()

    def purge_temp(self):
        """Zips and APKs left by a run that was stopped mid-download."""
        for sub in ("zip", "apk"):
            d = self.workdir / sub
            if d.is_dir():
                for f in d.iterdir():
                    if f.is_file():
                        f.unlink(missing_ok=True)

    @staticmethod
    def key(asset: dict) -> str:
        return f"{asset.get('browser_download_url', asset.get('name'))}#{asset.get('size', 0)}"

    def get(self, asset: dict) -> str | None:
        """The inner APK's SHA-256, downloading the zip if it isn't known yet; None if it can't be had."""
        k = self.key(asset)
        if k in self.data:
            return self.data[k]
        url = asset.get("browser_download_url")
        if not url:
            return None
        safe = re.sub(r"[^A-Za-z0-9._-]", "_", asset.get("name", "release.zip"))
        zpath = self.workdir / "zip" / safe
        apk = None
        try:
            self.http.download(url, zpath)
            apk, _, sha = zipapk.extract(zpath, self.workdir / "apk")
        except Exception as e:  # a bad zip or a failed download: the entry ships without innerSha256
            self.log(f"  inner APK of {asset.get('name')}: {type(e).__name__}")
            return None
        finally:
            zpath.unlink(missing_ok=True)
            if apk is not None:
                apk.unlink(missing_ok=True)
        self.data[k] = sha
        self.save()
        return sha

    def save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.data, indent=1, sort_keys=True) + "\n", encoding="utf-8")
