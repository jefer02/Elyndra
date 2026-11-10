"""The automatic gates. Pure functions over facts gathered elsewhere (tests/test_gates.py).

Only three things exclude an entry; everything else is included, flagged:

- In blocklist.yaml (DMCA, legal, not a port).
- The repository is gone (404).
- Bundled copyrighted data: the README says the game data ships with the
  app, or there is no source code at all and the build is huge (a
  repackaged game, not a port). Unclear ("check_requirements") when the
  README never says to bring your own copy or the APK is huge.

How an included entry installs (never excludes):

- an `.apk` in the newest release that has one: installable;
- only inside a zip with "android" in its name: installable, the app
  extracts the APK (`archive`, checked against `innerSha256`);
- only debug builds: installable, flagged "debug_build" (updates may need a
  reinstall);
- nothing installable (no releases, PC only, only unsigned APKs, or
  `viewOnly: true` in sources.yaml): view-only ("availability": "source"),
  "Not available on Android yet";
- a build for standalone VR headsets (Meta Quest): "availability": "vr".

No source code (no languages or a single commit) flags "no_source", and the
entry is never verified.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field, replace

DEBUG = re.compile(r"(?i)(^|[^a-z])(debug|unsigned)([^a-z]|$)")
BYO = re.compile(
    r"(?i)(your own|bring your own|own (copy|legal|legally)|legally (obtained|owned|purchased)|must (own|provide|supply|have)|"
    r"provide (your|the|a)|supply (your|the)|(not|never|no)\s+(include|included|contain|bundled|ship)|"
    r"does not (include|contain|ship|bundle)|doesn't (include|contain|ship)|no (game|copyrighted) (data|assets|files)|"
    r"original (game )?(data|files|assets|rom|disc|iso)|requires? (the|a|an|your)? ?(original|copy|rom|iso|disc|game files|game data)|"
    r"\brom\b|disc image|dump (of )?your|purchased copy|(steam|gog) (version|copy|install))",
)
BUNDLED = re.compile(
    r"(?i)(game (data|assets|files) (are|is) (included|bundled)|includes? (all )?(the )?(game|original) (data|assets|files)|"
    r"ready to play out of the box with (all|the original) (assets|data))",
)
NEGATION = re.compile(r"(?i)\b(no|not|never|without|n't|nor)\b[^.\n]{0,40}$")
HUGE_APK_MB = 500

UNSIGNED = re.compile(r"(?i)(^|[^a-z])unsigned([^a-z]|$)")
# "Quest" alone is not enough (Dragon Quest, Quest for Glory): only Meta/Oculus Quest, a Quest model, OpenXR or "VR".
VR = re.compile(r"(?i)(meta[\s_-]?quest|oculus|\bquest[\s_-]?(2|3|3s|pro)\b|standalone vr|openxr|\bvr\b)")
# A repo with no code whose APK is this big is a repackaged game, not a port.
REPACK_MB = 300

PRERELEASE = "prerelease"
CHECK_REQUIREMENTS = "check_requirements"
DEBUG_BUILD = "debug_build"
NO_SOURCE = "no_source"

ANDROID = "android"
SOURCE = "source"
VR_ONLY = "vr"


@dataclass
class Verdict:
    included: bool
    reason: str = ""
    flags: list[str] = field(default_factory=list)
    apks: list[dict] = field(default_factory=list)
    availability: str = ANDROID
    # The zip asset the APK comes in (None: the APK is the asset).
    archive: dict | None = None
    # Why a view-only entry has no installable APK (shown in the report).
    note: str = ""


def usable_apks(assets: list[dict]) -> list[dict]:
    """APK assets that count for gate 2 (an `.apk` that isn't a debug or unsigned build)."""
    return [a for a in assets if a.get("name", "").lower().endswith(".apk") and not DEBUG.search(a["name"])]


def debug_apks(assets: list[dict]) -> list[dict]:
    """Debug builds: signed with a debug key, so they install (unsigned ones don't)."""
    return [a for a in assets if a.get("name", "").lower().endswith(".apk") and DEBUG.search(a["name"])
            and not UNSIGNED.search(a["name"])]


def android_zips(assets: list[dict]) -> list[dict]:
    """Zips that say they're the Android build (the APK is inside)."""
    return [a for a in assets if a.get("name", "").lower().endswith(".zip") and re.search(r"(?i)android|\bapk\b", a["name"])]


def installable_assets(release: dict | None) -> bool:
    assets = (release or {}).get("assets") or []
    return bool(usable_apks(assets) or android_zips(assets) or debug_apks(assets))


def pick_release(releases: list[dict]) -> dict | None:
    """The newest published, non-draft release with something Android installs; else the newest one."""
    published = sorted((r for r in releases if not r.get("draft") and r.get("published_at")),
                       key=lambda r: r["published_at"], reverse=True)
    return next((r for r in published if installable_assets(r)), published[0] if published else None)


def is_vr(name: str, readme: str, assets: list[dict]) -> bool:
    """A build for standalone VR headsets: the repo or the APK says Quest/VR."""
    names = " ".join(a.get("name", "") for a in assets)
    return bool(VR.search(name) or VR.search(names) or re.search(r"(?i)(meta quest|oculus quest|quest (2|3|pro))", readme[:4000]))


def says_bundled(readme: str) -> bool:
    """True if the README claims the game data ships with the app (negated phrases don't count)."""
    for m in BUNDLED.finditer(readme):
        if not NEGATION.search(readme[max(0, m.start() - 50):m.start()]):
            return True
    return False


def says_bring_your_own(readme: str) -> bool:
    return bool(BYO.search(readme))


def source_gate(languages: dict, commits: int | None) -> str | None:
    """Gate 1. The reason it fails, or None."""
    if sum(v for v in languages.values() if isinstance(v, int)) <= 0:
        return "G1 no source code (GitHub reports no languages)"
    if commits is not None and commits <= 1:
        return f"G1 only {commits} commit"
    return None


def apk_gate(release: dict | None) -> tuple[str | None, list[dict]]:
    """Gate 2, the old strict form: (reason or None, the usable APKs). See `install_kind` for what decides now."""
    if release is None:
        return "G2 no releases", []
    assets = release.get("assets") or []
    apks = usable_apks(assets)
    if apks:
        return None, apks
    tag = release.get("tag_name", "?")
    if any(a.get("name", "").lower().endswith(".apk") for a in assets):
        return f"G2 only debug/unsigned APKs ({tag})", []
    if android_zips(assets):
        return f"G2 APK only inside a zip ({tag})", []
    return f"G2 latest release has no APK asset ({tag})", []


def install_kind(release: dict | None) -> tuple[str, list[dict], dict | None, bool, str]:
    """(availability, APKs, zip asset, debug build, note) for the release the app would install from."""
    if release is None:
        return SOURCE, [], None, False, "no releases"
    assets = release.get("assets") or []
    tag = release.get("tag_name", "?")
    if (apks := usable_apks(assets)):
        return ANDROID, apks, None, False, ""
    if (zips := android_zips(assets)):
        return ANDROID, [], zips[0], False, ""
    if (debug := debug_apks(assets)):
        return ANDROID, debug, None, True, ""
    if any(a.get("name", "").lower().endswith(".apk") for a in assets):
        return SOURCE, [], None, False, f"only unsigned APKs ({tag})"
    return SOURCE, [], None, False, f"no Android build in releases ({tag})"


def blocklisted(slug: str, blocklist: set[str]) -> bool:
    return slug.lower() in blocklist


def zip_checked(v: Verdict, inner_sha256: str | None) -> Verdict:
    """A zip whose APK couldn't be pulled out and hashed (no single safe APK inside, or the download failed) is
    never offered for install: the app would have nothing to check it against. The entry stays, view-only."""
    if v.archive is None or inner_sha256:
        return v
    name = v.archive.get("name", "the zip")
    return replace(v, availability=SOURCE, apks=[], archive=None,
                   flags=[f for f in v.flags if f not in (PRERELEASE, DEBUG_BUILD)],
                   note=f"{name}: no single APK could be checked inside")


def evaluate(slug: str, *, repo_status: int, repo: dict | None, languages: dict, commits: int | None,
             release: dict | None, readme: str, blocklist: set[str], vr: bool | None = None,
             view_only: bool = False) -> Verdict:
    """Every gate, cheapest and most final first.

    `vr`: sources.yaml says whether the build is for VR headsets (None: guess from the names).
    `view_only`: sources.yaml says the release isn't a game build (a build kit, a tool): never installable.
    """
    if blocklisted(slug, blocklist):
        return Verdict(False, "G4 blocklisted")
    if repo is None:
        return Verdict(False, f"repo unavailable (HTTP {repo_status})")
    if says_bundled(readme):
        return Verdict(False, "G5 README says the game data is bundled")
    availability, apks, archive, debug, note = install_kind(release)
    if view_only:
        availability, apks, archive, debug, note = SOURCE, [], None, False, "source only (sources.yaml)"
    no_source = source_gate(languages, commits) is not None
    biggest_mb = max([a.get("size", 0) for a in apks] + [(archive or {}).get("size", 0)], default=0) / 1_048_576
    if no_source and biggest_mb > REPACK_MB:
        return Verdict(False, f"G5 no source code and a {biggest_mb:.0f} MB build: likely the game repackaged with its data")
    flags = []
    if release and release.get("prerelease") and availability != SOURCE:
        flags.append(PRERELEASE)
    if not says_bring_your_own(readme) or biggest_mb > HUGE_APK_MB:
        flags.append(CHECK_REQUIREMENTS)
    if debug:
        flags.append(DEBUG_BUILD)
    if no_source:
        flags.append(NO_SOURCE)
    if availability == ANDROID and (vr if vr is not None else is_vr(slug, readme, apks + ([archive] if archive else []))):
        availability = VR_ONLY
    return Verdict(True, flags=flags, apks=apks, availability=availability, archive=archive, note=note)
