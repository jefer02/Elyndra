#!/usr/bin/env python3
"""Regenerates the Elyndra ports catalog.

    python generate.py                       # discovery + gates + art -> catalog.json here
    python generate.py --out ../../app/src/main/assets/ports/catalog.json
    python generate.py --no-discover         # only what sources.yaml already lists

Keys come from the environment (or a .env next to this file; real variables
win): GITHUB_TOKEN, SGDB_API_KEY, IGDB_CLIENT_ID, IGDB_CLIENT_SECRET. All
are optional; without them the catalog is built with keyless art (and a much
lower GitHub rate limit). See README.md.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import yaml

from portscat import catalog as cat
from portscat import discovery, env, gates, trust
from portscat.art import ArtResolver
from portscat.github import GitHub
from portscat.http import Http
from portscat.report import Report
from portscat.inner import InnerSha

HERE = Path(__file__).resolve().parent
# Candidates per batch when gathering facts (progress is logged after each one).
BATCH = 25


def log(msg: str):
    print(env.redact(msg), flush=True)


def load_yaml(path: Path, default):
    return (yaml.safe_load(path.read_text(encoding="utf-8")) or default) if path.is_file() else default


def repo_set(path: Path) -> set[str]:
    data = load_yaml(path, {})
    return {str(x["repo"] if isinstance(x, dict) else x).lower() for x in (data.get("repos") or [])}


def split_not_games(ports: list[dict]) -> tuple[list[dict], list[tuple[str, str, str]]]:
    """Apps, tools, emulators and engines that discovery picked up (`notGame: reason` in sources.yaml).
    They stay listed, so discovery doesn't add them again, but cost no GitHub requests: (ports, excluded)."""
    excluded = [(p["repo"], p.get("game", ""), f"not a game port: {p['notGame']}") for p in ports if p.get("notGame")]
    return [p for p in ports if not p.get("notGame")], excluded


def collect(gh: GitHub, src: dict) -> dict:
    """Everything the gates and trust need for one candidate."""
    slug = src["repo"]
    status, repo = gh.repo(slug)
    facts = {"src": src, "status": status, "repo": repo}
    if repo is None:
        return facts
    full = repo["full_name"]
    # The newest release with something Android installs (an APK, a zip with one, a debug build); else the newest.
    release = gates.pick_release(gh.releases(full))
    if release and src.get("asset"):
        # A pinned asset pattern (sources.yaml) is what the app will look for: only those assets count.
        pattern = re.compile(src["asset"])
        release = {**release, "assets": [a for a in release.get("assets") or [] if pattern.search(a.get("name", ""))]}
    facts["release"] = release
    facts["languages"] = gh.languages(full)
    facts["commits"] = gh.commit_count(full)
    facts["readme"] = gh.readme(full)
    facts["contributors"] = gh.contributor_count(full)
    parent = repo.get("parent")
    if repo.get("fork") and parent:
        facts["parent"] = trust.RepoStats(parent["full_name"], parent.get("created_at") or "1970-01-01T00:00:00Z",
                                          parent.get("stargazers_count", 0), gh.contributor_count(parent["full_name"]))
    return facts


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sources", type=Path, default=HERE / "sources.yaml")
    ap.add_argument("--blocklist", type=Path, default=HERE / "blocklist.yaml")
    ap.add_argument("--trusted", type=Path, default=HERE / "trusted.yaml")
    ap.add_argument("--state", type=Path, default=HERE / "discovery-state.json")
    ap.add_argument("--out", type=Path, default=HERE / "catalog.json")
    ap.add_argument("--report", type=Path, default=HERE / "report.md")
    ap.add_argument("--no-discover", action="store_true", help="skip discovery; only the repos already in sources.yaml")
    ap.add_argument("--workers", type=int, default=6)
    args = ap.parse_args(argv)

    loaded = env.load(HERE / ".env")
    if loaded:
        log(f".env: loaded {', '.join(loaded)}")
    have = {k: bool(env.secret(k)) for k in env.KEYS}
    log("keys: " + ", ".join(f"{k}={'set' if v else 'missing'}" for k, v in have.items()))

    http = Http(cache_dir=HERE / ".cache" / "http")
    gh = GitHub(http, env.secret("GITHUB_TOKEN"))
    report = Report()

    sources = load_yaml(args.sources, {})
    ports = sources.get("ports") or []
    blocklist, trusted = repo_set(args.blocklist), repo_set(args.trusted)

    # 1. Discovery: new candidates are appended to sources.yaml as text.
    state = json.loads(args.state.read_text(encoding="utf-8")) if args.state.is_file() else {}
    aliases: dict[str, str] = state.get("aliases", {})
    if not args.no_discover:
        log("discovery…")
        disc = sources.get("discovery") or {}
        upstream_platforms = {p["repo"]: p["platform"] for p in ports}
        upstream_platforms.update({p["upstream"]: p["platform"] for p in ports if p.get("upstream")})
        upstream_games = dict(disc.get("upstreamGames", {}))
        upstream_games.update({p["repo"]: p["game"] for p in ports if not p.get("gameGuessed")})
        upstream_games.update({p["upstream"]: p["game"] for p in ports if p.get("upstream") and not p.get("gameGuessed")})
        fork_sources = sorted({p["repo"] for p in ports} | {p["upstream"] for p in ports if p.get("upstream")}, key=str.lower)
        run_state = {**state, "upstreams": disc.get("upstreams", []), "upstreamGames": upstream_games,
                     "upstreamPlatforms": upstream_platforms, "forkSources": fork_sources, "lists": disc.get("lists", [])}
        # Every name a listed repo is known by: its own, older ones (aka), and renames seen in earlier runs.
        known = {p["repo"].lower() for p in ports} | {a.lower() for p in ports for a in p.get("aka", [])}
        known |= set(aliases) | set(aliases.values())
        # Checkpoint: a stopped run skips the sources it already finished (their finds are in sources.yaml).
        checkpoint = state.get("discoveryCheckpoint") or {}
        done = set(checkpoint.get("done", []))
        if done:
            log(f"  resuming discovery: {len(done)} sources already done")
        stamped = [bool(checkpoint.get("stamped"))]

        def on_source(name, new):
            if new:
                with args.sources.open("a", encoding="utf-8") as f:
                    if not stamped[0]:
                        f.write(f"\n  # discovered {datetime.now(timezone.utc):%Y-%m-%d}\n")
                        stamped[0] = True
                    for c in new:
                        f.write(discovery.yaml_block(c))
                log(f"  {name}: {len(new)} new")
            state["discoveryCheckpoint"] = {"done": sorted(done), "stamped": stamped[0]}
            state["portmasterCursor"] = run_state.get("portmasterCursor", 0)
            args.state.write_text(json.dumps(state, indent=1, sort_keys=True) + "\n", encoding="utf-8")

        found = discovery.discover(gh, known, run_state, log=log, on_source=on_source, done=done)
        state["portmasterCursor"] = run_state.get("portmasterCursor", 0)
        state.pop("discoveryCheckpoint", None)
        args.state.write_text(json.dumps(state, indent=1, sort_keys=True) + "\n", encoding="utf-8")
        report.discovered = [c.repo for c in found]
        ports = (load_yaml(args.sources, {}).get("ports") or [])
        log(f"  {len(found)} new candidates")

    ports, not_games = split_not_games(ports)
    report.excluded += not_games

    # 2. Facts from GitHub, in parallel.
    log(f"checking {len(ports)} candidates on GitHub…")
    # In batches: progress shows, and GitHub's answers are kept in the on-disk ETag cache as they come.
    facts = []
    with cf.ThreadPoolExecutor(args.workers) as ex:
        for i in range(0, len(ports), BATCH):
            facts += list(ex.map(lambda s: collect(gh, s), ports[i:i + BATCH]))
            log(f"  {min(i + BATCH, len(ports))}/{len(ports)}")
    log(f"  {gh.calls} GitHub requests")
    for f in facts:
        if f["repo"] and f["repo"]["full_name"].lower() != f["src"]["repo"].lower():
            aliases[f["src"]["repo"].lower()] = f["repo"]["full_name"].lower()
    state["aliases"] = dict(sorted(aliases.items()))
    args.state.write_text(json.dumps(state, indent=1, sort_keys=True) + "\n", encoding="utf-8")

    # 3. Gates, deduplicated by the repository GitHub resolves to (renames).
    inner = InnerSha(http, HERE / ".cache", log=log)
    seen: set[str] = set()
    final = []
    for f in facts:
        src = f["src"]
        full = (f["repo"] or {}).get("full_name", src["repo"])
        if full.lower() in seen:
            continue
        seen.add(full.lower())
        # Listed or renamed: either name counts for the blocklist.
        slug = full if full.lower() in blocklist else src["repo"]
        v = gates.evaluate(slug, repo_status=f["status"], repo=f["repo"], languages=f.get("languages", {}),
                           commits=f.get("commits"), release=f.get("release"), readme=f.get("readme", ""),
                           blocklist=blocklist, vr=src.get("vr"), view_only=bool(src.get("viewOnly")))
        if not v.included:
            report.excluded.append((full, src.get("game", ""), v.reason))
            continue
        # A zip release: the APK inside is hashed (streamed to disk, deleted after) so the app can check it;
        # if it can't be, the entry is view-only.
        f["inner_sha"] = inner.get(v.archive) if v.archive is not None else None
        f["verdict"] = gates.zip_checked(v, f["inner_sha"])
        final.append(f)

    # 5. Trust and art.
    art = ArtResolver(http, env.secret("SGDB_API_KEY"), env.secret("IGDB_CLIENT_ID"), env.secret("IGDB_CLIENT_SECRET"), log=log)
    log(f"art for {len(final)} entries…")
    with cf.ThreadPoolExecutor(4) as ex:
        arts = list(ex.map(lambda f: art.resolve(f["src"]["game"], f["src"]["platform"], f["src"]), final))

    entries = []
    for f, a in zip(final, arts):
        repo = f["repo"]
        if f["src"].get("gameGuessed"):
            # Identity: a game name pulled out of free text counts only if a source recognised it.
            if not a.identified:
                report.excluded.append((repo["full_name"], f["src"]["game"], "original game not identified (set `game:` in sources.yaml)"))
                continue
            f["src"] = {**f["src"], "game": a.matchedName or f["src"]["game"]}
        stats = trust.RepoStats(repo["full_name"], repo["created_at"], repo.get("stargazers_count", 0), f["contributors"])
        # trusted.yaml may name the repository before a rename.
        t = trust.compute(stats, f.get("parent"), trusted | ({repo["full_name"].lower()} if f["src"]["repo"].lower() in trusted else set()))
        entries.append(cat.build_entry(f["src"], repo, f["release"], f["verdict"], t, a, f["contributors"], inner_sha256=f.get("inner_sha")))
        if f["verdict"].availability == gates.SOURCE:
            report.view_only.append((repo["full_name"], f["src"]["game"], f["verdict"].note))
        if f["src"].get("platformGuessed"):
            report.guessed.append(repo["full_name"])

    problems = cat.unique_ids(entries)
    for p in problems:
        log(f"  id problem: {p}")
    out = cat.catalog(entries)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(out, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    report.included = out["ports"]
    args.report.write_text(env.redact(report.render(out["updated"])), encoding="utf-8")
    log(f"wrote {args.out} ({len(entries)} ports) and {args.report}")
    log(f"  excluded {len(report.excluded)}, missing art {len(report.missing_art())}, view-only {len(report.view_only)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
