# Ports catalog generator

Builds the catalog the Elyndra ports store reads (`catalog.json`, schema 2):
discovery → gates → trust → art → `catalog.json` + `report.md`.
Python 3.11+, one dependency (PyYAML).

## Run it

```sh
cd tools/ports-catalog
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt      # Windows (.venv/bin/python elsewhere)
.venv/Scripts/python -m unittest discover -s tests -t .      # offline tests

# Regenerate the catalog bundled in the app:
.venv/Scripts/python generate.py --out ../../app/src/main/assets/ports/catalog.json
```

Useful flags: `--no-discover` (only what `sources.yaml` lists), `--out`,
`--report`, `--workers N`.

A stopped run resumes: each discovery source is saved to `sources.yaml` (and
marked done in `discovery-state.json`) as soon as it finishes, GitHub answers
are cached on disk (`.cache/`, ETags), and the SHA-256 of APKs inside zips is
kept in `.cache/inner-sha.json`. Downloads stream to temp files on disk and are
deleted right after hashing.

## Keys

All optional, read from the environment. A `.env` next to `generate.py` is
loaded too (copy `.env.example`); real environment variables win over it.
`.env` is git-ignored: never commit it, and never put key values in any other
file. The generator never prints them (logs and the report are redacted).

| Variable | Used for | Without it |
|---|---|---|
| `GITHUB_TOKEN` | GitHub API (5000 requests/h instead of 60) | slow, may hit the limit |
| `SGDB_API_KEY` | covers, heroes, logos, icons | keyless art only |
| `IGDB_CLIENT_ID`, `IGDB_CLIENT_SECRET` | ids, release year, covers, screenshots | keyless art only |

Keyless art comes from the Steam CDN (PC/DOS games) and libretro thumbnails;
the report lists exactly which entries still lack a cover or hero.


## GitHub Action

`.github/workflows/update-catalog.yml` is meant for `jefer02/elyndra-ports`:
copy the contents of this folder to that repository's root. It runs weekly
(Mondays) and on demand, and commits `catalog.json`, `sources.yaml`,
`discovery-state.json` and `report.md` directly to `main`.

Repository secrets (Settings → Secrets and variables → Actions):
`SGDB_API_KEY`, `IGDB_CLIENT_ID`, `IGDB_CLIENT_SECRET`.
`GITHUB_TOKEN` is the one Actions provides; the workflow needs
`contents: write` (already set).

## Discovery

Each run without `--no-discover` looks for new candidates and appends them to
`sources.yaml`:

- lists: abduznik/awesome-android-native-ports, other "awesome … android
  ports" lists found on GitHub, any raw Markdown URL in `discovery.lists`,
  Obtainium config repos, and the r/EmulationOnAndroid wiki (Reddit usually
  answers 403 without an account: logged and skipped);
- osgameclones entries that mention Android;
- GitHub topic searches (decompilation, recompilation, source-port,
  game-engine-recreation + android) and keyword searches ("android port",
  "recomp android", "decomp android", "native port", "pc port", "switch port",
  "switch 2"…), three pages each. A keyword hit counts only if one of its
  releases has something Android installs, or it names its game and has 15+
  stars;
- forks of every listed repo and upstream that mention Android;
- every PortMaster title, searched on GitHub.

A game name pulled out of free text (`gameGuessed: true`) is kept only if
IGDB/SteamGridDB/Steam recognise it; otherwise the report lists it as "original
game not identified": set `game:` (and `platform:`) by hand and delete the flag.
`vr: true` / `vr: false` overrides the VR-headset guess. Discovery also finds
apps, emulators and libraries with "android port" in their description: mark them
`notGame: "<why>"` instead of deleting them, so they are excluded with that reason,
cost no requests and aren't rediscovered.

## Files

- `sources.yaml`: every candidate (repo, original game, platform, optional
  overrides). Discovery appends new ones at the end with `discovered:`; a
  `platformGuessed: true` entry needs its platform checked.
- `blocklist.yaml`: never included (DMCA'd projects such as re3/reVC and
  their mirrors). Add `- repo: owner/name` with a `reason:`.
- `trusted.yaml`: repos you reviewed by hand; they are always verified.
- `discovery-state.json`: state between runs (no secrets).
- `report.md`: per-platform counts (installable / VR only / not on Android
  yet), included (availability, trust and flags), view-only with why,
  excluded with the reason, missing art.

## Gates

Only three things exclude an entry (each logged with its reason in the report):

- **`blocklist.yaml`**: DMCA or other legal reasons, and repos that aren't
  ports (an original game such as 2048).
- **The repo is gone** (404).
- **Bundled copyrighted data**: the README says the game data ships with the
  app, or there's no source code at all and the build is over 300 MB (a
  repackaged game, not a port).

Everything else is included, and how it installs is decided by the newest
release that has something Android can use:

| Release has | Catalog entry | In the app |
|---|---|---|
| an `.apk` | `builds: [android]` | Install |
| an `.apk` only inside a zip with "android" in its name | `builds[].archive` + `innerSha256` (the APK inside, hashed by the generator) | Install (the app extracts the APK) |
| only a debug build | flag `debug_build` | Install, with a "Debug build" badge and a note that updates may need a reinstall |
| nothing Android installs (no releases, PC only, unsigned only), or `viewOnly: true` | `availability: "source"`, `builds: []` | "Not available on Android yet", "View project on GitHub"; the daily worker checks again |
| a standalone VR build (Meta Quest…) | `availability: "vr"` (or `vr: true` in sources.yaml) | Installs only on a VR headset; "VR headset only" elsewhere |

Other flags: `prerelease`; `check_requirements` (the README never asks for
your own copy, or the APK is over 500 MB); `no_source` (no languages or a
single commit: never verified).

**Trust:** `verified` only if the repository is at least a year old with
100+ stars and 3+ contributors, or is a GitHub fork of one that is (the real
fork link, not a README claim), or is listed in `trusted.yaml`.
