"""A small urllib client: retries, JSON, conditional GETs with an on-disk ETag cache.

The cache stores URL, ETag and body only, never request headers, so tokens
and keys can't end up on disk.
"""
from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

UA = "elyndra-ports-catalog/1 (+https://github.com/jefer02/elyndra-ports)"


@dataclass
class Response:
    status: int
    url: str
    headers: dict = field(default_factory=dict)
    body: bytes = b""

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300

    def text(self) -> str:
        return self.body.decode("utf-8", "replace")

    def json(self):
        return json.loads(self.body.decode("utf-8")) if self.body else None


class HttpError(Exception):
    def __init__(self, status: int, url: str, body: str = ""):
        super().__init__(f"HTTP {status} for {url}")
        self.status, self.url, self.body = status, url, body


class Http:
    def __init__(self, cache_dir: Path | None = None, timeout: int = 60, tries: int = 3):
        self.cache_dir = cache_dir
        self.timeout = timeout
        self.tries = tries
        if cache_dir:
            cache_dir.mkdir(parents=True, exist_ok=True)

    def _cache_file(self, url: str) -> Path | None:
        return self.cache_dir / (hashlib.sha1(url.encode()).hexdigest() + ".json") if self.cache_dir else None

    def request(self, url: str, method: str = "GET", headers: dict | None = None, data=None,
                cache: bool = False, timeout: int | None = None) -> Response:
        """`data`: bytes, or a callable that returns a fresh file-like body for each attempt (streamed, never
        read into memory whole; set Content-Length in `headers`)."""
        headers = {"User-Agent": UA, **(headers or {})}
        cached = None
        cf = self._cache_file(url) if cache and method == "GET" else None
        if cf and cf.is_file():
            try:
                cached = json.loads(cf.read_text(encoding="utf-8"))
                if cached.get("etag"):
                    headers["If-None-Match"] = cached["etag"]
            except ValueError:
                cached = None
        last: Exception | None = None
        for attempt in range(self.tries):
            body = data() if callable(data) else data
            req = urllib.request.Request(url, data=body, method=method, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                    resp = Response(r.status, r.geturl(), {k.lower(): v for k, v in r.headers.items()}, r.read())
                if cf and resp.headers.get("etag"):
                    cf.write_text(json.dumps({"url": url, "etag": resp.headers["etag"], "headers": _keep(resp.headers),
                                              "body": resp.text()}), encoding="utf-8")
                return resp
            except urllib.error.HTTPError as e:
                hdrs = {k.lower(): v for k, v in (e.headers or {}).items()}
                if e.code == 304 and cached is not None:
                    return Response(200, url, cached.get("headers", {}), cached["body"].encode("utf-8"))
                body = e.read() if e.fp else b""
                if e.code in (500, 502, 503, 504) and attempt < self.tries - 1:
                    time.sleep(3 * (attempt + 1))
                    continue
                return Response(e.code, url, hdrs, body)
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
                last = e
                if hasattr(body, "close"):
                    body.close()
                if attempt < self.tries - 1:
                    time.sleep(3 * (attempt + 1))
                    continue
        raise HttpError(0, url, str(last))

    def get(self, url: str, headers: dict | None = None, cache: bool = False) -> Response:
        return self.request(url, headers=headers, cache=cache)

    def head_ok(self, url: str) -> bool:
        try:
            return self.request(url, method="HEAD").ok
        except HttpError:
            return False

    def download(self, url: str, dest: Path, headers: dict | None = None) -> str:
        """Streams `url` to `dest` in 1 MB chunks (never whole in memory); returns its SHA-256.
        A failed download leaves no partial file behind."""
        req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
        h = hashlib.sha256()
        dest.parent.mkdir(parents=True, exist_ok=True)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout * 5) as r, dest.open("wb") as out:
                while chunk := r.read(1 << 20):
                    h.update(chunk)
                    out.write(chunk)
        except BaseException:
            dest.unlink(missing_ok=True)
            raise
        return h.hexdigest()


def _keep(headers: dict) -> dict:
    return {k: v for k, v in headers.items() if k in ("link", "content-type")}
