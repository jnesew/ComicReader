#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

python3 - <<'PY'
import re
import sqlite3
import tempfile
import unicodedata
from pathlib import Path

limits_source = Path(
    "app/src/main/java/com/localtools/comicviewer/util/InputLimits.java"
).read_text(encoding="utf-8")


def java_limit(name: str) -> int:
    match = re.search(rf"\b{name}\s*=\s*([0-9_]+)\s*;", limits_source)
    if match is None:
        raise AssertionError(f"Missing Java input limit: {name}")
    return int(match.group(1).replace("_", ""))


title_limit = java_limit("MAX_TITLE_CODE_POINTS")
series_limit = java_limit("MAX_SERIES_CODE_POINTS")
issue_limit = java_limit("MAX_ISSUE_CODE_POINTS")
stable_key_limit = java_limit("MAX_STABLE_KEY_CODE_POINTS")

title = unicodedata.normalize("NFC", "Café — 小さなネモ 🚀")
series_prefix = "Sarja 🚀 "
series = series_prefix + "𐐷" * (series_limit - len(series_prefix))
issue = "第 １２ 話"
stable_key = "provider:" + "α" * min(64, stable_key_limit - len("provider:"))

assert len(title) <= title_limit
assert len(series) == series_limit
assert len(issue) <= issue_limit
assert len(stable_key) <= stable_key_limit
assert title == unicodedata.normalize("NFC", title)

with tempfile.TemporaryDirectory(prefix="comicviewer-db-roundtrip-") as directory:
    database_path = Path(directory) / "library.db"
    connection = sqlite3.connect(database_path)
    connection.execute(
        """
        CREATE TABLE progress (
            stable_key TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL DEFAULT '',
            series_name TEXT NOT NULL DEFAULT '',
            series_number TEXT NOT NULL DEFAULT '',
            detected_series_key TEXT NOT NULL DEFAULT ''
        )
        """
    )
    connection.execute(
        """
        INSERT INTO progress (
            stable_key, title, series_name, series_number, detected_series_key
        ) VALUES (?, ?, ?, ?, ?)
        """,
        (stable_key, title, series, issue, stable_key),
    )
    connection.commit()
    connection.close()

    connection = sqlite3.connect(database_path)
    row = connection.execute(
        """
        SELECT stable_key, title, series_name, series_number, detected_series_key
        FROM progress WHERE stable_key = ?
        """,
        (stable_key,),
    ).fetchone()
    integrity = connection.execute("PRAGMA integrity_check").fetchone()
    connection.close()

assert row == (stable_key, title, series, issue, stable_key)
assert integrity == ("ok",)
print("Unicode metadata SQLite round-trip passed")
PY
