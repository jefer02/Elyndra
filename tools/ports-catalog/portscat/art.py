"""Art and ids of the ORIGINAL game, resolved at build time into absolute https URLs.

Order: what sources.yaml pins by hand, SteamGridDB (cover/hero/logo/icon) and
IGDB (ids, year, cover, artwork, screenshots) when their keys are set, the
Steam CDN for PC/DOS games, and libretro thumbnails (keyless). A source only
counts when its title matches the game closely (see `similar`).
"""
from __future__ import annotations

import difflib
import html
import re
import threading
import time
import unicodedata
import urllib.parse
from dataclasses import dataclass, field

from .http import Http

IGDB_PLATFORMS = {
    "nes": [18, 99], "fds": [51], "snes": [19, 58], "n64": [4], "gc": [21], "wii": [5], "wiiu": [41], "switch": [130], "switch2": [508],
    "gb": [33], "gbc": [22], "gba": [24], "nds": [20, 159], "n3ds": [37, 137], "virtualboy": [87],
    "psx": [7], "ps2": [8], "ps3": [9], "ps4": [48], "psp": [38], "psvita": [46],
    "sg1000": [84], "mastersystem": [64], "gamegear": [35], "megadrive": [29], "segacd": [78], "sega32x": [30],
    "saturn": [32], "dreamcast": [23], "xbox": [11], "xbox360": [12], "pc": [6], "dos": [13],
}
LIBRETRO = {
    "ps2": "Sony - PlayStation 2", "ps3": "Sony - PlayStation 3", "psp": "Sony - PlayStation Portable",
    "psx": "Sony - PlayStation", "psvita": "Sony - PlayStation Vita", "xbox360": "Microsoft - Xbox 360",
    "xbox": "Microsoft - Xbox", "n64": "Nintendo - Nintendo 64", "nds": "Nintendo - Nintendo DS",
    "n3ds": "Nintendo - Nintendo 3DS", "gc": "Nintendo - GameCube", "wii": "Nintendo - Wii", "wiiu": "Nintendo - Wii U",
    "gba": "Nintendo - Game Boy Advance", "gbc": "Nintendo - Game Boy Color", "gb": "Nintendo - Game Boy",
    "nes": "Nintendo - Nintendo Entertainment System", "snes": "Nintendo - Super Nintendo Entertainment System",
    "megadrive": "Sega - Mega Drive - Genesis", "mastersystem": "Sega - Master System - Mark III",
    "gamegear": "Sega - Game Gear", "segacd": "Sega - Mega-CD - Sega CD", "sega32x": "Sega - 32X",
    "saturn": "Sega - Saturn", "dreamcast": "Sega - Dreamcast", "sg1000": "Sega - SG-1000", "dos": "DOS",
}
STEAM_PLATFORMS = {"pc", "dos"}
STEAM_CDN = "https://cdn.akamai.steamstatic.com/steam/apps"
IGDB_IMG = "https://images.igdb.com/igdb/image/upload"
LIBRETRO_HOST = "https://thumbnails.libretro.com"

_BRACKETS = re.compile(r"[(\[][^)\]]*[)\]]")
_ARTICLES = re.compile(r"\b(the|a|an)\b")
_NON_ALNUM = re.compile(r"[^a-z0-9]+")


def normalize(title: str) -> str:
    """Same reduction as the app's PortLookup.normalizeTitle."""
    s = _BRACKETS.sub(" ", title)
    s = "".join(c for c in unicodedata.normalize("NFD", s) if not unicodedata.combining(c)).lower()
    return _NON_ALNUM.sub("", _ARTICLES.sub(" ", s))


def _core(title: str) -> str:
    """normalize() without a trailing "version" (IGDB says "Pokémon Emerald Version")."""
    n = normalize(title)
    return n[:-len("version")] if n.endswith("version") and len(n) > len("version") else n


def similar(a: str, b: str, threshold: float = 0.88) -> bool:
    na, nb = _core(a), _core(b)
    if not na or not nb:
        return False
    return na == nb or difflib.SequenceMatcher(None, na, nb).ratio() >= threshold


@dataclass
class GameArt:
    igdbId: int | None = None
    sgdbId: int | None = None
    steamAppId: int | None = None
    releaseYear: int | None = None
    cover: str | None = None
    hero: str | None = None
    logo: str | None = None
    icon: str | None = None
    screenshots: list[str] = field(default_factory=list)
    sources: list[str] = field(default_factory=list)
    # The title a source matched (IGDB's first): the canonical name for a game guessed from free text.
    matchedName: str | None = None

    def fill(self, source: str, **values):
        used = False
        for k, v in values.items():
            if v in (None, [], ""):
                continue
            if k == "screenshots":
                if not self.screenshots:
                    self.screenshots, used = list(v)[:6], True
            elif getattr(self, k) is None:
                setattr(self, k, v)
                used = True
        if used and source not in self.sources:
            self.sources.append(source)

    @property
    def identified(self) -> bool:
        """A source recognised the game (an id from IGDB, SteamGridDB or Steam)."""
        return any(x is not None for x in (self.igdbId, self.sgdbId, self.steamAppId))

    @property
    def missing(self) -> list[str]:
        return [k for k in ("cover", "hero") if getattr(self, k) is None]


class ArtResolver:
    def __init__(self, http: Http, sgdb_key: str | None, igdb_id: str | None, igdb_secret: str | None,
                 log=print):
        self.http, self.sgdb_key, self.igdb_id, self.igdb_secret, self.log = http, sgdb_key, igdb_id, igdb_secret, log
        self._igdb_token: str | None = None
        self._lock = threading.Lock()
        self._libretro_names: dict[str, list[str]] = {}

    # ── SteamGridDB ───────────────────────────────────────────
    def _sgdb(self, path: str):
        resp = self.http.get("https://www.steamgriddb.com/api/v2" + path, headers={"Authorization": f"Bearer {self.sgdb_key}"})
        return (resp.json() or {}).get("data") if resp.ok else None

    def sgdb(self, name: str, game_id: int | None, art: GameArt):
        if not self.sgdb_key:
            return
        if game_id is None:
            hits = self._sgdb("/search/autocomplete/" + urllib.parse.quote(name, safe="")) or []
            hit = next((h for h in hits if similar(h.get("name", ""), name)), None)
            if not hit:
                return
            game_id = hit["id"]
            art.matchedName = art.matchedName or hit.get("name")

        def first(kind: str, query: str = "") -> str | None:
            items = self._sgdb(f"/{kind}/game/{game_id}{query}") or []
            return items[0]["url"] if items else None

        art.fill(
            "steamgriddb",
            sgdbId=game_id,
            cover=first("grids", "?dimensions=600x900&types=static"),
            hero=first("heroes", "?types=static"),
            logo=first("logos", "?types=static"),
            icon=first("icons", "?types=static"),
        )

    # ── IGDB ──────────────────────────────────────────────────
    def _igdb_auth(self) -> str | None:
        with self._lock:
            if self._igdb_token is None and self.igdb_id and self.igdb_secret:
                q = urllib.parse.urlencode({"client_id": self.igdb_id, "client_secret": self.igdb_secret, "grant_type": "client_credentials"})
                resp = self.http.request("https://id.twitch.tv/oauth2/token?" + q, method="POST", data=b"")
                self._igdb_token = (resp.json() or {}).get("access_token", "") if resp.ok else ""
                if not self._igdb_token:
                    self.log(f"  IGDB: authentication failed (HTTP {resp.status})")
            return self._igdb_token or None

    def _igdb(self, body: str) -> list:
        token = self._igdb_auth()
        if not token:
            return []
        for attempt in range(3):
            resp = self.http.request("https://api.igdb.com/v4/games", method="POST", data=body.encode(),
                                     headers={"Client-ID": self.igdb_id, "Authorization": f"Bearer {token}", "Accept": "application/json"})
            if resp.status == 429:
                time.sleep(1 + attempt)
                continue
            return resp.json() if resp.ok else []
        return []

    def igdb(self, name: str, platform: str, game_id: int | None, art: GameArt):
        if not (self.igdb_id and self.igdb_secret):
            return
        fields = "fields name,category,first_release_date,cover.image_id,artworks.image_id,screenshots.image_id,platforms;"
        if game_id is not None:
            games = self._igdb(f"{fields} where id = {int(game_id)};")
        else:
            safe = name.replace('"', " ")
            ids = IGDB_PLATFORMS.get(platform)
            where = f" where platforms = ({','.join(map(str, ids))});" if ids else ""
            games = self._igdb(f'search "{safe}"; {fields}{where} limit 10;')
            games = [g for g in games if similar(g.get("name", ""), name)]
            # The exact title first, then main games (category 0) over remakes/ports/bundles, then the oldest.
            games.sort(key=lambda g: (_core(g.get("name", "")) != _core(name), g.get("category", 0) != 0,
                                      g.get("first_release_date") or 1 << 40))
        if not games:
            return
        g = games[0]
        if g.get("name"):
            art.matchedName = g["name"]
        year = time.gmtime(g["first_release_date"]).tm_year if g.get("first_release_date") else None
        artworks = [a["image_id"] for a in g.get("artworks", []) if a.get("image_id")]
        shots = [s["image_id"] for s in g.get("screenshots", []) if s.get("image_id")]
        art.fill(
            "igdb",
            igdbId=g["id"],
            releaseYear=year,
            cover=f"{IGDB_IMG}/t_cover_big_2x/{g['cover']['image_id']}.jpg" if g.get("cover", {}).get("image_id") else None,
            hero=f"{IGDB_IMG}/t_1080p/{(artworks + shots)[0]}.jpg" if artworks + shots else None,
            screenshots=[f"{IGDB_IMG}/t_1080p/{s}.jpg" for s in shots],
        )

    # ── Steam (keyless) ───────────────────────────────────────
    def steam(self, name: str, app_id: int | None, art: GameArt):
        if app_id is None:
            q = urllib.parse.urlencode({"term": name, "l": "english", "cc": "US"})
            resp = self.http.get("https://store.steampowered.com/api/storesearch/?" + q, cache=True)
            items = (resp.json() or {}).get("items", []) if resp.ok else []
            hit = next((i for i in items if similar(i.get("name", ""), name)), None)
            if not hit:
                return
            app_id = hit["id"]
            art.matchedName = art.matchedName or hit.get("name")
        cover = f"{STEAM_CDN}/{app_id}/library_600x900_2x.jpg"
        hero = f"{STEAM_CDN}/{app_id}/library_hero.jpg"
        logo = f"{STEAM_CDN}/{app_id}/logo.png"
        art.fill(
            "steam",
            steamAppId=app_id,
            cover=cover if self.http.head_ok(cover) else None,
            hero=hero if self.http.head_ok(hero) else f"{STEAM_CDN}/{app_id}/header.jpg",
            logo=logo if self.http.head_ok(logo) else None,
        )

    # ── libretro thumbnails (keyless) ─────────────────────────
    def _names(self, folder: str) -> list[str]:
        with self._lock:
            if folder not in self._libretro_names:
                resp = self.http.get(f"{LIBRETRO_HOST}/{urllib.parse.quote(folder)}/Named_Boxarts/", cache=True)
                found = re.findall(r'href="([^"]+?)\.png"', resp.text()) if resp.ok else []
                self._libretro_names[folder] = [html.unescape(urllib.parse.unquote(n)) for n in found]
            return self._libretro_names[folder]

    def libretro(self, name: str, platform: str, art: GameArt):
        folder = LIBRETRO.get(platform)
        if not folder:
            return
        want = normalize(name)
        names = [n for n in self._names(folder) if normalize(n) == want]
        if not names:
            return
        # (USA) first, then (World), then whatever comes first.
        best = sorted(names, key=lambda n: (0 if "(USA" in n else 1 if "(World" in n else 2, len(n)))[0]
        base = f"{LIBRETRO_HOST}/{urllib.parse.quote(folder)}"
        file = urllib.parse.quote(best) + ".png"
        art.fill("libretro", cover=f"{base}/Named_Boxarts/{file}", hero=f"{base}/Named_Snaps/{file}")

    # ── all of it ─────────────────────────────────────────────
    def resolve(self, name: str, platform: str, pinned: dict) -> GameArt:
        art = GameArt()
        pinned_art = pinned.get("art") or {}
        art.fill("sources.yaml", igdbId=pinned.get("igdbId"), sgdbId=pinned.get("sgdbId"), steamAppId=pinned.get("steamAppId"),
                 releaseYear=pinned.get("releaseYear"), **{k: pinned_art.get(k) for k in ("cover", "hero", "logo", "icon", "screenshots")})
        steps = [
            lambda: self.sgdb(name, art.sgdbId, art),
            lambda: self.igdb(name, platform, art.igdbId, art),
        ]
        if platform in STEAM_PLATFORMS:
            steps.append(lambda: self.steam(name, art.steamAppId, art))
        steps.append(lambda: self.libretro(name, platform, art))
        for step in steps:
            try:
                step()
            except Exception as e:  # one failing source never stops the others
                self.log(f"  art lookup failed for {name}: {type(e).__name__}")
        return art


def to_json(art: GameArt) -> dict:
    out = {k: getattr(art, k) for k in ("cover", "hero", "logo", "icon") if getattr(art, k)}
    if art.screenshots:
        out["screenshots"] = list(art.screenshots)
    return out
