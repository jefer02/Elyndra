"""Finds candidate repositories and appends the new ones to sources.yaml.

Sources:
- lists: the awesome-android-native-ports list, other "awesome" lists of
  Android ports found on GitHub, the lists in `discovery.lists`, Obtainium
  config repos (the recomp pack and others), the r/EmulationOnAndroid wiki
  when Reddit answers (it often doesn't without an account: logged, skipped);
- osgameclones (entries that mention Android);
- GitHub searches: topics (decompilation / recompilation / source-port /
  game-engine-recreation + android) and keywords ("android port", "recomp
  android", "switch port android"…), several pages each. A keyword hit counts
  only if a release has something Android installs or it names its game and
  has some following (keyword search is noisy);
- forks of every listed repo and upstream that mention Android;
- the PortMaster list (game names searched on GitHub, all of them each run).

New entries are appended as text (comments in sources.yaml survive) with
`discovered:` and, when the platform is a guess, `platformGuessed: true`.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass

import yaml

AWESOME = "https://raw.githubusercontent.com/abduznik/awesome-android-native-ports/HEAD/README.md"
OBTAINIUM = "https://raw.githubusercontent.com/heyalmoo/Obtainium-Android-Recomps/HEAD/obtainium-android-recomps.json"
OSGAMECLONES = "opengaming/osgameclones"
PORTMASTER = "https://raw.githubusercontent.com/PortsMaster/PortMaster-Info/main/ports.json"
TOPICS = ("decompilation", "recompilation", "source-port", "game-engine-recreation")
KEYWORDS = (
    '"android port"', '"recomp android"', '"decomp android"', '"native port" android', '"pc port" android',
    '"switch port" android', '"switch 2" port', "recompiled android apk", "decompilation android apk", '"android" "static recompilation"',
    '"native android port"', '"ps4 port" android', '"ps4 recomp"', '"ps3 recomp"', '"ps3 port" android', '"ps2 recomp" android',
    '"ps2 port" android', '"xbox 360 recomp" android', '"recompilation" android ps2',
)
KEYWORD_PAGES = 3
# Release lookups for keyword hits, at most this many per run (each is one GitHub request).
RELEASE_CHECKS = 250
KEYWORD_MIN_STARS = 15
REDDIT_WIKI = ("https://www.reddit.com/r/EmulationOnAndroid/wiki/index.json",
               "https://www.reddit.com/r/EmulationOnAndroid/wiki/ports.json")
LINK = re.compile(r"\[([^\]]{2,80})\]\((https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/?\)")
SKIP_SECTIONS = {None, "Contents", "Tools & Frameworks", "Related Lists", "Contributing", "Disclaimer", "Credits", "License"}
GH = re.compile(r"https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)")

PLATFORMS = [
    ("xbox360", r"xbox 360|\b360\b"), ("xbox", r"original xbox|\bxbox\b"),
    ("switch2", r"switch ?2\b|\bns2\b|switch2"), ("switch", r"\bswitch\b"), ("wiiu", r"wii u"), ("wii", r"\bwii\b"), ("gc", r"gamecube|\bgcn?\b"),
    ("n64", r"\bn64\b|nintendo 64"), ("n3ds", r"\b3ds\b"), ("nds", r"\bnds\b|nintendo ds"),
    ("gba", r"\bgba\b|game boy advance"), ("gbc", r"game boy color|\bgbc\b"), ("gb", r"game ?boy\b|\bgb\b"),
    ("snes", r"\bsnes\b|super nintendo|super famicom"), ("nes", r"\bnes\b|famicom"),
    ("ps4", r"\bps4\b|playstation 4"), ("ps3", r"\bps3\b|playstation 3"), ("ps2", r"\bps2\b|playstation 2"), ("psp", r"\bpsp\b"),
    ("psx", r"\bps1\b|\bpsx\b|playstation\b"), ("dreamcast", r"dreamcast"), ("saturn", r"saturn"),
    ("segacd", r"sega cd|mega[- ]cd"), ("sega32x", r"\b32x\b"), ("megadrive", r"genesis|mega drive|megadrive"),
    ("dos", r"\bms-dos\b|\bdos\b"), ("pc", r"\bpc\b|windows|steam|gog"),
]


@dataclass
class Candidate:
    repo: str
    game: str
    platform: str
    guessed: bool
    source: str
    note: str = ""
    # The game name was pulled out of free text: generate.py keeps the entry only if IGDB/SGDB/Steam confirm it.
    game_guessed: bool = False


# Repositories that are not game ports: tools, lists, emulators, system code.
NOT_A_PORT = re.compile(
    r"(?i)\b(decompiler|deobfusc\w*|disassembler|dex\b|smali|apktool|jadx|reverse[- ]engineering (lab|tool)|"
    r"awesome|curated|catalog|catalogue|import pack|obtainium|hub for|guide|gu[ií]a|tutorial|"
    r"emulator|kernel|bootloader|device tree|vulnerab\w*|payload|forensic|bot\b|cheat|gameguardian|launcher for)\b"
)
# "port of X", "recompilation of X", "X running natively", "X (Xbox, 2005) recompiled"…
_GAME_PATTERNS = [
    re.compile(r"(?i)(?:port|recompilation|decompilation|recomp|reimplementation|remake|rebuild) of (?:the )?(.+?)(?:\s*[(\[]|\s+(?:for|to|on|from|in|running|that|with)\b|[,.:;!—–]\s|$)"),
    re.compile(r"(?i)^(.+?)\s+(?:\(|running natively|natively|static(?:ally)? recomp|recompiled|decompiled|rebuilt|native (?:pc |android )?port)"),
]


def plausible_game(game: str) -> bool:
    """A name, not a sentence: descriptions passed off as names ("All seven … games in one app - a",
    "npiriou's Rerac-vr", "Noita Android port: the Windows game") don't count."""
    g = game.strip()
    return (2 <= len(g) <= 60 and not NOT_A_PORT.search(g) and not re.match(r"(?i)^(android|native|static|a|an|the)$", g)
            and not re.search(r"(?i)port:|'s |\s-\s*an?$|\b(in one app|for (windows|android))\b", g))


def extract_game(text: str) -> str | None:
    """The original game a repo description talks about, or None."""
    text = re.sub(r"^[^A-Za-z0-9]+", "", (text or "").strip())
    for pattern in _GAME_PATTERNS:
        m = pattern.search(text)
        if m:
            game = re.sub(r"(?i)^(an? |the )?(experimental |unofficial |native |full |heavy )*", "", m.group(1)).strip(" -–—:")
            if plausible_game(game):
                return game
    return None


def looks_like_port(item: dict) -> bool:
    text = " ".join([item.get("name", ""), item.get("description") or ""])
    return not NOT_A_PORT.search(text) and not item.get("archived") and not item.get("fork_of_list")


def guess_platform(text: str) -> tuple[str, bool]:
    """(platform id, guessed). Nothing recognisable: PC, flagged as a guess."""
    t = text.lower()
    for pid, pattern in PLATFORMS:
        if re.search(pattern, t):
            return pid, True
    return "pc", True


def clean_game(name: str) -> str:
    """'Halo CE (Andiweli)' → 'Halo CE'; list decorations and trailing port words go."""
    s = re.sub(r"\s*[(\[][^)\]]*[)\]]\s*$", "", name.strip())
    s = re.sub(r"(?i)\s+[-–:]\s*(android|mobile|native|pc)( port)?$", "", s)
    s = re.sub(r"(?i)\s+(android port|android)$", "", s)
    return s.strip().rstrip(":-–").strip() or name.strip()


def repo_of(url: str) -> str | None:
    m = GH.search(url)
    if not m:
        return None
    slug = m.group(1).rstrip("/").removesuffix(".git")
    return slug if slug.count("/") == 1 else None


def parse_awesome(md: str) -> list[Candidate]:
    out, section = [], None
    for line in md.splitlines():
        if line.startswith("## "):
            section = line[3:].strip()
            continue
        if section in SKIP_SECTIONS:
            continue
        m = re.match(r"\|\s*\[([^\]]+)\]\((https://github\.com/[^)\s]+)\)\s*\|\s*([^|]*)\|", line)
        if not m:
            continue
        repo = repo_of(m.group(2))
        if not repo:
            continue
        game, desc = m.group(1).strip(), m.group(3).strip()
        text = desc if "Source Ports" in section else f"{section} {game} {desc}"
        platform, guessed = guess_platform(text)
        out.append(Candidate(repo, clean_game(game), platform, guessed, f"awesome-android-native-ports:{section}", desc))
    return out


def parse_obtainium(text: str) -> list[Candidate]:
    data = json.loads(text)
    out = []
    for app in data.get("apps", []):
        repo = repo_of(app.get("url", ""))
        if repo and "obtainium" not in repo.lower():
            name = app.get("name", repo.split("/")[1])
            platform, guessed = guess_platform(name)
            out.append(Candidate(repo, clean_game(name), platform, guessed, "obtainium-android-recomps"))
    return out


def parse_osgameclones(text: str) -> list[Candidate]:
    """Entries of one osgameclones games/*.yaml whose repo is on GitHub and that mention Android."""
    out = []
    for entry in yaml.safe_load(text) or []:
        if not isinstance(entry, dict):
            continue
        repo = repo_of(str(entry.get("repo", "")))
        if not repo or "android" not in json.dumps(entry, default=str).lower():
            continue
        originals = entry.get("remakes") or entry.get("clones") or []
        if not originals:
            continue
        game = str(originals[0])
        platform, guessed = guess_platform(" ".join(map(str, entry.get("info", []) if isinstance(entry.get("info"), list) else [entry.get("info", "")])))
        out.append(Candidate(repo, clean_game(game), platform, guessed, "osgameclones", str(entry.get("name", ""))))
    return out


def parse_links(md: str, source: str) -> list[Candidate]:
    """Any Markdown list of GitHub links ("[Game](https://github.com/o/r)"): the link text is the game, to confirm."""
    out = []
    for line in md.splitlines():
        for m in LINK.finditer(line):
            repo = repo_of(m.group(2))
            name = clean_game(m.group(1))
            if not repo or not plausible_game(name) or "github.com" in name.lower():
                continue
            platform, guessed = guess_platform(line)
            out.append(Candidate(repo, name, platform, guessed, source, re.sub(r"\s+", " ", line.strip())[:160], game_guessed=True))
    return out


def reddit_wiki(text: str) -> list[Candidate]:
    data = json.loads(text) if text.strip().startswith("{") else {}
    md = ((data.get("data") or {}).get("content_md") or "")
    return parse_links(md, "reddit:r/EmulationOnAndroid")


def portmaster_titles(text: str) -> list[str]:
    """Game titles from PortMaster's ports.json (only names are used)."""
    found: list[str] = []

    def walk(node):
        if isinstance(node, dict):
            title = node.get("title")
            if isinstance(title, str) and title.strip():
                found.append(title.strip())
            for v in node.values():
                walk(v)
        elif isinstance(node, list):
            for v in node:
                walk(v)

    walk(json.loads(text))
    return sorted(set(found), key=str.lower)


def from_search_item(item: dict, source: str) -> Candidate | None:
    """A search hit as a candidate, only if it looks like a port and names its original game."""
    if not looks_like_port(item):
        return None
    game = extract_game(item.get("description") or "")
    if not game:
        return None
    text = " ".join([item.get("name", ""), item.get("description") or "", " ".join(item.get("topics", []))])
    platform, guessed = guess_platform(text)
    return Candidate(item["full_name"], game, platform, guessed, source, (item.get("description") or "")[:160], game_guessed=True)


def yaml_block(c: Candidate) -> str:
    """One sources.yaml entry, as text to append."""
    lines = [
        f"  - repo: {c.repo}",
        f"    game: {json.dumps(c.game, ensure_ascii=False)}",
        f"    platform: {c.platform}",
    ]
    if c.guessed:
        lines.append("    platformGuessed: true")
    if c.game_guessed:
        lines.append("    gameGuessed: true")
    lines.append(f"    discovered: {json.dumps(c.source, ensure_ascii=False)}")
    if c.note:
        lines.append(f"    note: {json.dumps(c.note[:160], ensure_ascii=False)}")
    return "\n".join(lines) + "\n"


def keyword_candidate(gh, item: dict, source: str, budget: list[int]) -> Candidate | None:
    """A keyword hit: kept if a release has something Android installs, or it names its game and has a following."""
    from .gates import installable_assets
    if not looks_like_port(item):
        return None
    text = " ".join([item.get("name", ""), item.get("description") or "", " ".join(item.get("topics", []))])
    if "android" not in text.lower() and "apk" not in text.lower():
        return None
    game = extract_game(item.get("description") or "")
    has_apk = False
    if budget[0] > 0:
        budget[0] -= 1
        has_apk = any(installable_assets(r) for r in gh.releases(item["full_name"]) if not r.get("draft"))
    if not has_apk and not (game and item.get("stargazers_count", 0) >= KEYWORD_MIN_STARS):
        return None
    platform, guessed = guess_platform(text)
    return Candidate(item["full_name"], game or clean_game(item.get("name", "")), platform, guessed, source,
                     (item.get("description") or "")[:160], game_guessed=True)


def discover(gh, known: set[str], state: dict, log=print, portmaster_per_run: int = 10_000,
             on_source=None, done: set[str] | None = None) -> list[Candidate]:
    """Every new candidate (repos not in `known`, lowercased). `state` keeps the PortMaster cursor between runs.

    Checkpoint: after each source, `on_source(name, its new candidates)` saves them (generate.py appends them to
    sources.yaml and records the source in discovery-state.json); sources in `done` are skipped, so a stopped
    run resumes with the next source instead of starting over.
    """
    found: list[Candidate] = []
    done = done if done is not None else set()

    def add(cands: list[Candidate]) -> list[Candidate]:
        new = []
        for c in cands:
            key = c.repo.lower()
            if key not in known:
                known.add(key)
                found.append(c)
                new.append(c)
        return new

    def safely(name, fn):
        if name in done:
            return
        try:
            new = add(fn())
        except Exception as e:  # a broken list never stops the run
            log(f"  discovery: {name} failed ({type(e).__name__})")
            new = []
        done.add(name)
        if on_source:
            on_source(name, new)

    safely("awesome-android-native-ports", lambda: parse_awesome(gh.raw(AWESOME)))
    safely("obtainium", lambda: parse_obtainium(gh.raw(OBTAINIUM)))
    for url in state.get("lists", []):
        safely(f"list:{url}", lambda u=url: parse_links(gh.raw(u), f"list:{u}"))

    def awesome_lists():
        out = []
        for item in gh.search_repos("awesome android ports in:name,description", per_page=20):
            if item["full_name"].lower() != "abduznik/awesome-android-native-ports":
                out += parse_links(gh.readme(item["full_name"]), f"awesome:{item['full_name']}")
        return out

    safely("awesome lists", awesome_lists)

    def obtainium_configs():
        out = []
        for item in gh.search_repos("obtainium in:name,description android games", per_page=15):
            resp = gh.get(f"/repos/{item['full_name']}/contents")
            for f in (resp.json() if resp.ok else []) or []:
                if isinstance(f, dict) and f.get("name", "").endswith(".json") and f.get("download_url"):
                    try:
                        out += parse_obtainium(gh.raw(f["download_url"]))
                    except (ValueError, AttributeError):
                        pass
        return out

    safely("obtainium configs", obtainium_configs)

    def reddit():
        out = []
        for url in REDDIT_WIKI:
            text = gh.raw(url)
            if not text:
                log(f"  discovery: {url} unavailable (Reddit needs an account for its API); skipped")
                continue
            out += reddit_wiki(text)
        return out

    safely("reddit", reddit)

    def osgc():
        resp = gh.get(f"/repos/{OSGAMECLONES}/contents/games")
        files = resp.json() if resp.ok else []
        out = []
        for f in files:
            if f.get("name", "").endswith(".yaml"):
                out += parse_osgameclones(gh.raw(f["download_url"]))
        return out

    safely("osgameclones", osgc)
    for topic in TOPICS:
        safely(f"topic:{topic}", lambda t=topic: [c for i in gh.search_repos(f"topic:{t} topic:android") if (c := from_search_item(i, f"github-topic:{t}"))])

    budget = [RELEASE_CHECKS]
    for kw in KEYWORDS:
        def search(k=kw):
            out = []
            for page in range(1, KEYWORD_PAGES + 1):
                items = gh.search_repos(f"{k} in:name,description,readme fork:true pushed:>2023-01-01", page=page)
                for i in items:
                    if i["full_name"].lower() in known:
                        continue
                    if (c := keyword_candidate(gh, i, f"github-search:{k}", budget)):
                        out.append(c)
                if len(items) < 100:
                    break
            return out
        safely(f"search:{kw}", search)

    def forks_of_upstreams():
        from .gates import installable_assets
        out = []
        games, platforms = state.get("upstreamGames", {}), state.get("upstreamPlatforms", {})
        # Every listed repo and upstream, not only the seeds: forks are where Android builds appear.
        for upstream in dict.fromkeys(state.get("upstreams", []) + state.get("forkSources", [])):
            for f in gh.forks(upstream, pages=1):
                text = f"{f.get('name', '')} {f.get('description') or ''}".lower()
                if "android" not in text or not looks_like_port(f):
                    continue
                # A plain copy of a listed port isn't another port: only forks with their own Android build count.
                if not any(installable_assets(r) for r in gh.releases(f["full_name"]) if not r.get("draft")):
                    continue
                # The upstream says which game and platform it is; nothing to guess.
                platform = platforms.get(upstream)
                guessed_platform = guess_platform(text)
                out.append(Candidate(f["full_name"], games.get(upstream) or f["name"], platform or guessed_platform[0], platform is None,
                                     f"fork:{upstream}", (f.get("description") or "")[:160], game_guessed=upstream not in games))
        return out

    safely("forks:with-builds", forks_of_upstreams)

    def portmaster():
        titles = portmaster_titles(gh.raw(PORTMASTER))
        if not titles:
            return []
        start = state.get("portmasterCursor", 0) % len(titles)
        batch = (titles + titles)[start:start + portmaster_per_run]
        state["portmasterCursor"] = (start + len(batch)) % len(titles)
        out = []
        for title in batch:
            want = re.sub(r"[^a-z0-9]", "", title.lower())
            if len(want) < 4:
                continue
            for item in gh.search_repos(f'"{title}" android port in:name,description', per_page=5):
                name = re.sub(r"[^a-z0-9]", "", item.get("name", "").lower())
                text = f"{item.get('name', '')} {item.get('description') or ''}".lower()
                # The title in the repo name, and the repo says it's a port for Android.
                if want in name and "android" in text and "port" in text and looks_like_port(item):
                    platform, guessed = guess_platform(text)
                    out.append(Candidate(item["full_name"], title, platform, guessed, "portmaster-name-search",
                                         (item.get("description") or "")[:160], game_guessed=True))
        return out

    safely("portmaster", portmaster)
    return found
