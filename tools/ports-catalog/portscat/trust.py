"""Computed trust (tests/test_trust.py).

`verified` only when the repository is at least a year old, has 100+ stars and
3+ contributors, or is a GitHub fork (the real fork relationship, never a
README claim) of a repository that meets all three, or is listed by hand in
trusted.yaml.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone

MIN_AGE_DAYS = 365
MIN_STARS = 100
MIN_CONTRIBUTORS = 3


@dataclass(frozen=True)
class RepoStats:
    slug: str
    created_at: str  # ISO-8601
    stars: int
    contributors: int


@dataclass(frozen=True)
class Trust:
    verified: bool
    reason: str


def parse_time(iso: str) -> datetime:
    return datetime.fromisoformat(iso.replace("Z", "+00:00"))


def shortfalls(stats: RepoStats, now: datetime) -> list[str]:
    out = []
    if (now - parse_time(stats.created_at)).days < MIN_AGE_DAYS:
        out.append("repo < 1 year")
    if stats.stars < MIN_STARS:
        out.append(f"< {MIN_STARS} stars")
    if stats.contributors < MIN_CONTRIBUTORS:
        out.append(f"< {MIN_CONTRIBUTORS} contributors")
    return out


def compute(stats: RepoStats, parent: RepoStats | None, trusted: set[str], now: datetime | None = None) -> Trust:
    now = now or datetime.now(timezone.utc)
    if stats.slug.lower() in trusted:
        return Trust(True, "listed in trusted.yaml")
    own = shortfalls(stats, now)
    if not own:
        return Trust(True, "meets age, stars and contributors")
    if parent is not None and not shortfalls(parent, now):
        return Trust(True, f"GitHub fork of {parent.slug}, which meets age, stars and contributors")
    return Trust(False, ", ".join(own))
