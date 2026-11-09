"""Builds schema-2 catalog entries (the format the app's PortCatalogParser reads)."""
from __future__ import annotations

import hashlib
import re
from datetime import datetime, timezone

from . import GENERATOR, SCHEMA

ID = re.compile(r"[a-z0-9][a-z0-9-]{0,47}")
GENERIC_TOPICS = {
    "android", "game", "games", "port", "ports", "decompilation", "recompilation", "recomp", "decomp", "source-port",
    "game-engine-recreation", "reverse-engineering", "apk", "mobile", "cpp", "c", "sdl2", "sdl", "opengl", "vulkan",
    "cmake", "emulator", "n64", "nintendo-64", "xbox-360", "gamecube", "wii", "switch", "pc", "windows", "linux",
    "game-port", "android-port", "native-port", "pc-port", "static-recompilation", "decompiled", "android-game",
    "android-app", "androidgame", "port-android", "recompiled", "switch2", "nintendo-switch", "macos", "ios",
}
# Links in a repo description aren't shown in the store (and could point anywhere, ROM sites included).
URL = re.compile(r"\s*\(?\bhttps?://\S+")


def plain_text(desc: str) -> str:
    return re.sub(r"\s{2,}", " ", URL.sub("", desc)).strip(" -–:,;")


PLATFORM_ORDER = ["nes", "fds", "snes", "n64", "gc", "wii", "wiiu", "switch", "switch2", "gb", "gbc", "gba", "nds", "n3ds",
                  "psx", "ps2", "ps3", "ps4", "psp", "psvita", "sg1000", "mastersystem", "gamegear", "megadrive", "segacd",
                  "sega32x", "saturn", "dreamcast", "xbox", "xbox360", "pc", "dos"]


def slug_id(repo: str) -> str:
    """A stable catalog id from the repository (installed ports are keyed by it, so it never changes)."""
    s = re.sub(r"[^a-z0-9]+", "-", repo.lower()).strip("-")
    if len(s) > 48:
        s = s[:41].rstrip("-") + "-" + hashlib.sha1(repo.lower().encode()).hexdigest()[:6]
    return s


def infer_kind(repo: str, platform: str) -> str:
    r = repo.lower()
    if "recomp" in r:
        return "recomp"
    if "decomp" in r:
        return "decomp"
    return "reimpl" if platform in ("pc", "dos") else "decomp"


def tags_from(topics: list[str], limit: int = 4) -> list[str]:
    return [t for t in topics if t not in GENERIC_TOPICS][:limit]


def gh(slug: str | None) -> dict | None:
    if not slug or "/" not in slug:
        return None
    owner, name = slug.split("/", 1)
    return {"owner": owner, "name": name}


def build_entry(src: dict, repo: dict, release: dict | None, verdict, trust, art, contributors: int,
                inner_sha256: str | None = None) -> dict:
    slug = repo["full_name"]
    platform = src["platform"]
    desc = plain_text(repo.get("description") or "")
    short = src.get("short") or ({"en": desc} if desc else {})
    requires = dict(src.get("requires") or {})
    requires.setdefault("userRom", True)
    if src.get("requirement") and not requires.get("text"):
        requires["text"] = {"en": src["requirement"]}
    build = {"platform": "android"}
    for k in ("asset", "abis", "package"):
        if src.get(k):
            build[k] = src[k]
    availability = getattr(verdict, "availability", "android")
    archive = getattr(verdict, "archive", None)
    if archive is not None:
        # The release asset is the zip; the app installs the APK inside.
        build.setdefault("asset", r"(?i)(android|apk).*\.zip$")
        build["archive"] = r"(?i)\.apk$"
        if inner_sha256:
            build["innerSha256"] = inner_sha256
    upstream = src.get("upstream") or (repo.get("parent") or {}).get("full_name")
    license_id = (repo.get("license") or {}).get("spdx_id")
    game = {
        "name": src["game"],
        "originalPlatform": platform,
        "igdbId": art.igdbId,
        "sgdbId": art.sgdbId,
        "steamAppId": art.steamAppId,
        "releaseYear": art.releaseYear,
        "art": _art(art),
    }
    entry = {
        "id": src.get("id") or slug_id(src["repo"]),
        "name": src.get("port") or repo["name"],
        "kind": src.get("kind") or infer_kind(slug, platform),
        "developer": src.get("developer") or repo["owner"]["login"],
        "repo": gh(slug),
        "upstream": gh(upstream) if upstream and upstream.lower() != slug.lower() else None,
        "game": {k: v for k, v in game.items() if v not in (None, {})},
        # View-only: no Android build (the app shows "View project on GitHub").
        "builds": [] if availability == "source" else [build],
        "availability": availability if availability != "android" else None,
        "requires": requires,
        "tags": src.get("tags") or tags_from(repo.get("topics") or []),
        "short": short,
        "long": src.get("long") or {},
        "license": license_id if license_id and license_id != "NOASSERTION" else None,
        "release": ({"tag": release["tag_name"], "publishedAt": release["published_at"], "prerelease": bool(release.get("prerelease"))}
                    if release else None),
        "trust": {
            "verified": trust.verified,
            "reason": trust.reason,
            "stars": repo.get("stargazers_count", 0),
            "contributors": contributors,
            "createdAt": (repo.get("created_at") or "")[:10],
            "flags": verdict.flags,
        },
        "featured": bool(src.get("featured")),
    }
    entry["trust"] = {k: v for k, v in entry["trust"].items() if v is not None}
    return {k: v for k, v in entry.items() if v not in (None, [], {}) or k in ("tags", "featured")}


def _art(art) -> dict:
    out = {k: getattr(art, k) for k in ("cover", "hero", "logo", "icon") if getattr(art, k)}
    if art.screenshots:
        out["screenshots"] = list(art.screenshots)
    return out




def sort_key(entry: dict):
    p = entry["game"]["originalPlatform"]
    return (PLATFORM_ORDER.index(p) if p in PLATFORM_ORDER else len(PLATFORM_ORDER), entry["game"]["name"].lower(),
            not entry["trust"]["verified"], -entry["trust"].get("stars", 0), entry["id"])


def catalog(entries: list[dict], today: str | None = None) -> dict:
    return {
        "schema": SCHEMA,
        "updated": today or datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "generator": GENERATOR,
        "ports": sorted(entries, key=sort_key),
    }


def unique_ids(entries: list[dict]) -> list[str]:
    """Problems with ids: invalid or repeated (the app drops those entries)."""
    seen, problems = set(), []
    for e in entries:
        if not ID.fullmatch(e["id"]):
            problems.append(f"{e['id']}: invalid id")
        elif e["id"] in seen:
            problems.append(f"{e['id']}: duplicate id")
        seen.add(e["id"])
    return problems
