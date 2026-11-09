"""report.md: included (with trust and flags), excluded with the reason, view-only and missing art."""
from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Report:
    included: list[dict] = field(default_factory=list)          # catalog entries
    excluded: list[tuple[str, str, str]] = field(default_factory=list)  # (repo, game, reason)
    guessed: list[str] = field(default_factory=list)            # repos whose platform is a guess
    discovered: list[str] = field(default_factory=list)         # repos added to sources.yaml this run
    notes: list[str] = field(default_factory=list)
    view_only: list[tuple[str, str, str]] = field(default_factory=list)  # (repo, game, why there's no APK)

    def missing_art(self) -> list[tuple[dict, list[str]]]:
        out = []
        for e in self.included:
            art = e["game"].get("art", {})
            missing = [k for k in ("cover", "hero") if not art.get(k)]
            if missing:
                out.append((e, missing))
        return out

    @staticmethod
    def availability(e: dict) -> str:
        return e.get("availability") or ("android" if e.get("builds") else "source")

    def per_platform(self) -> list[tuple[str, int, int, int]]:
        """(platform, installable, VR only, not on Android yet), by platform."""
        rows: dict[str, list[int]] = {}
        for e in self.included:
            r = rows.setdefault(e["game"]["originalPlatform"], [0, 0, 0])
            r[{"android": 0, "vr": 1}.get(self.availability(e), 2)] += 1
        return [(p, *v) for p, v in sorted(rows.items(), key=lambda x: (-sum(x[1]), x[0]))]

    def render(self, updated: str) -> str:
        inc = sorted(self.included, key=lambda e: (e["game"]["originalPlatform"], e["game"]["name"].lower()))
        verified = sum(1 for e in inc if e["trust"]["verified"])
        installable = sum(1 for e in inc if self.availability(e) == "android")
        lines = [
            f"# Ports catalog report ({updated})",
            "",
            f"- Included: **{len(inc)}** ({verified} verified, {len(inc) - verified} unverified)",
            f"- Installable: **{installable}**; VR headset only: **{sum(1 for e in inc if self.availability(e) == 'vr')}**; "
            f"not on Android yet: **{sum(1 for e in inc if self.availability(e) == 'source')}**",
            f"- Excluded: **{len(self.excluded)}**",
            f"- Missing art (cover or hero): **{len(self.missing_art())}**",
            f"- Discovered this run: **{len(self.discovered)}**",
            "",
        ]
        lines += [f"> {n}" for n in self.notes]
        lines += ["", "## Per platform", "", "| Platform | Installable | VR only | Not on Android yet |", "|---|---|---|---|"]
        lines += [f"| {p} | {a} | {v} | {s} |" for p, a, v, s in self.per_platform()]
        lines += ["", "## Included", "", "| Game | Platform | Port (repo) | Availability | Trust | Flags | Release |", "|---|---|---|---|---|---|---|"]
        for e in inc:
            t = e["trust"]
            trust = "verified" if t["verified"] else f"unverified ({t.get('reason', '')})"
            flags = ", ".join(t.get("flags", []))
            if any(b.get("archive") for b in e.get("builds", [])):
                flags = ", ".join(filter(None, [flags, "zip"]))
            repo = f"{e['repo']['owner']}/{e['repo']['name']}"
            rel = e.get("release") or {}
            when = f"{rel['tag']} · {rel['publishedAt'][:10]}" if rel else "no releases"
            lines.append(f"| {e['game']['name']} | {e['game']['originalPlatform']} | {e['name']} ({repo}) | {self.availability(e)} "
                         f"| {trust} | {flags or '—'} | {when} |")
        if self.view_only:
            lines += ["", "## Not on Android yet (view-only)", "", "| Repo | Game | Why |", "|---|---|---|"]
            lines += [f"| {r} | {g} | {why} |" for r, g, why in sorted(self.view_only, key=lambda x: x[0].lower())]
        lines += ["", "## Excluded", "", "| Repo | Game | Reason |", "|---|---|---|"]
        for repo, game, reason in sorted(self.excluded, key=lambda x: (x[2], x[0].lower())):
            lines.append(f"| {repo} | {game} | {reason} |")
        lines += ["", "## Missing art", ""]
        lines += [f"- {e['game']['name']} ({e['game']['originalPlatform']}, {e['id']}): no {' / '.join(m)}" for e, m in self.missing_art()] or ["None."]
        if self.guessed:
            lines += ["", "## Platform is a guess (fix it in sources.yaml)", ""]
            lines += [f"- {r}" for r in sorted(self.guessed, key=str.lower)]
        if self.discovered:
            lines += ["", "## Added to sources.yaml this run", ""]
            lines += [f"- {r}" for r in self.discovered]
        return "\n".join(lines) + "\n"
