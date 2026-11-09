"""APKs that a release ships inside a zip (gate 2): which one Elyndra would install, extracted safely.

The app does the same (ZipApk.kt): exactly one APK, or the arm64 / ABI-less
one when there are several; nothing outside the archive (no "..", no absolute
paths) and nothing bigger than MAX_APK.
"""
from __future__ import annotations

import hashlib
import re
import zipfile
from pathlib import Path, PurePosixPath

MAX_APK = 4 << 30  # 4 GB: the biggest an APK can be (zip64 aside)
ARM64 = re.compile(r"(?i)(arm64|aarch64|v8a)")
OTHER_ABI = re.compile(r"(?i)(armeabi|armv7|x86|x86_64|x64|i686)")


class BadArchive(Exception):
    pass


def safe_name(name: str) -> bool:
    p = PurePosixPath(name.replace("\\", "/"))
    return not p.is_absolute() and ".." not in p.parts and not re.match(r"^[A-Za-z]:", name)


def pick(names: list[str]) -> str | None:
    """The APK inside the archive Elyndra would install, or None."""
    apks = [n for n in names if n.lower().endswith(".apk") and not n.endswith("/") and safe_name(n)
            and "__macosx" not in n.lower()]
    if len(apks) <= 1:
        return apks[0] if apks else None
    return (next((n for n in apks if ARM64.search(PurePosixPath(n).name)), None)
            or next((n for n in apks if not OTHER_ABI.search(PurePosixPath(n).name)), None))


def extract(zip_path: Path, out_dir: Path) -> tuple[Path, str, str]:
    """(the extracted APK, its name inside the zip, its SHA-256). Raises BadArchive."""
    try:
        zf = zipfile.ZipFile(zip_path)
    except zipfile.BadZipFile as e:
        raise BadArchive(f"not a zip: {e}") from e
    with zf:
        name = pick(zf.namelist())
        if name is None:
            raise BadArchive("no single APK inside")
        info = zf.getinfo(name)
        if info.file_size > MAX_APK:
            raise BadArchive("APK too big")
        out_dir.mkdir(parents=True, exist_ok=True)
        target = out_dir / re.sub(r"[^A-Za-z0-9._-]", "_", PurePosixPath(name).name)
        h = hashlib.sha256()
        written = 0
        with zf.open(info) as src, target.open("wb") as dst:
            while chunk := src.read(1 << 20):
                written += len(chunk)
                if written > MAX_APK:
                    raise BadArchive("APK too big")
                h.update(chunk)
                dst.write(chunk)
        return target, name, h.hexdigest()
