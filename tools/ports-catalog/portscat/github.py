"""GitHub REST API: repository facts, releases, languages, counts and README."""
from __future__ import annotations

import re
import time
import urllib.parse

from .http import Http, Response

API = "https://api.github.com"
LAST_PAGE = re.compile(r'[?&]page=(\d+)>;\s*rel="last"')


class RateLimited(Exception):
    pass


def count_from(resp: Response) -> int:
    """Items of a `per_page=1` listing: the last page number, or the items on the only page."""
    m = LAST_PAGE.search(resp.headers.get("link", ""))
    if m:
        return int(m.group(1))
    data = resp.json() if resp.ok else None
    return len(data) if isinstance(data, list) else 0


def latest_release(releases: list[dict]) -> dict | None:
    """The newest published, non-draft release, stable or prerelease."""
    published = [r for r in releases if not r.get("draft") and r.get("published_at")]
    return max(published, key=lambda r: r["published_at"]) if published else None


class GitHub:
    def __init__(self, http: Http, token: str | None):
        self.http = http
        self.token = token
        self.calls = 0

    def _headers(self, accept: str = "application/vnd.github+json") -> dict:
        h = {"Accept": accept, "X-GitHub-Api-Version": "2022-11-28"}
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        return h

    def get(self, path: str, accept: str = "application/vnd.github+json", cache: bool = True) -> Response:
        url = path if path.startswith("http") else API + path
        for _ in range(3):
            self.calls += 1
            resp = self.http.get(url, headers=self._headers(accept), cache=cache)
            if resp.status in (403, 429) and resp.headers.get("x-ratelimit-remaining") == "0":
                wait = int(resp.headers.get("x-ratelimit-reset", "0")) - int(time.time()) + 2
                if 0 < wait <= 900:
                    time.sleep(wait)
                    continue
                raise RateLimited(f"GitHub rate limit, resets in {wait}s")
            if resp.status == 403 and "secondary rate limit" in resp.text().lower():
                time.sleep(60)
                continue
            return resp
        return resp

    def repo(self, slug: str) -> tuple[int, dict | None]:
        resp = self.get(f"/repos/{slug}")
        return resp.status, (resp.json() if resp.ok else None)

    def releases(self, slug: str) -> list[dict]:
        resp = self.get(f"/repos/{slug}/releases?per_page=15")
        return resp.json() if resp.ok else []

    def languages(self, slug: str) -> dict:
        resp = self.get(f"/repos/{slug}/languages")
        return resp.json() if resp.ok else {}

    def commit_count(self, slug: str) -> int:
        return count_from(self.get(f"/repos/{slug}/commits?per_page=1"))

    def contributor_count(self, slug: str) -> int:
        return count_from(self.get(f"/repos/{slug}/contributors?per_page=1&anon=true"))

    def readme(self, slug: str) -> str:
        resp = self.get(f"/repos/{slug}/readme", accept="application/vnd.github.raw")
        return resp.text() if resp.ok else ""

    def search_repos(self, query: str, per_page: int = 100, page: int = 1) -> list[dict]:
        resp = self.get(f"/search/repositories?q={urllib.parse.quote(query)}&per_page={per_page}&page={page}", cache=False)
        time.sleep(2.1)  # search has its own limit (30/min with a token)
        return (resp.json() or {}).get("items", []) if resp.ok else []

    def forks(self, slug: str, pages: int = 2) -> list[dict]:
        out = []
        for page in range(1, pages + 1):
            resp = self.get(f"/repos/{slug}/forks?sort=newest&per_page=100&page={page}")
            items = resp.json() if resp.ok else []
            out += items or []
            if not items or len(items) < 100:
                break
        return out

    def raw(self, url: str) -> str:
        resp = self.http.get(url, cache=True)
        return resp.text() if resp.ok else ""
