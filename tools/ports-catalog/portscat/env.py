"""API keys from the environment, with an optional `.env` file next to generate.py.

Real environment variables always win over the file (the GitHub Action uses
repository secrets). Values are never printed: `redact()` scrubs them from any
text before it reaches a log, the report or a file.
"""
from __future__ import annotations

import os
from pathlib import Path

KEYS = ("SGDB_API_KEY", "IGDB_CLIENT_ID", "IGDB_CLIENT_SECRET", "GITHUB_TOKEN")


def parse_dotenv(text: str) -> dict[str, str]:
    """KEY=VALUE lines; `#` comments, blank lines, `export ` and surrounding quotes are allowed."""
    out: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        if line.startswith("export "):
            line = line[len("export "):].lstrip()
        key, _, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
            value = value[1:-1]
        elif " #" in value:
            value = value.split(" #", 1)[0].rstrip()
        if key:
            out[key] = value
    return out


def load(path: Path, environ: dict | None = None) -> list[str]:
    """Loads `path` into `environ` without overriding what is already set. Returns the names loaded (never values)."""
    environ = os.environ if environ is None else environ
    if not path.is_file():
        return []
    loaded = []
    for key, value in parse_dotenv(path.read_text(encoding="utf-8")).items():
        if value and not environ.get(key):
            environ[key] = value
            loaded.append(key)
    return loaded


def secret(name: str, environ: dict | None = None) -> str | None:
    environ = os.environ if environ is None else environ
    value = (environ.get(name) or "").strip()
    return value or None


def redact(text: str, environ: dict | None = None) -> str:
    """`text` with every configured key value replaced by `***`."""
    for name in KEYS:
        value = secret(name, environ)
        if value and len(value) >= 6:
            text = text.replace(value, "***")
    return text
