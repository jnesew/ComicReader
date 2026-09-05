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
    "app/src/main/java/io/github/jnesew/comicviewer/util/InputLimits.java"
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

with tempfile.TemporaryDirectory(prefix="comicviewer-missing-policy-") as directory:
    database_path = Path(directory) / "library.db"
    connection = sqlite3.connect(database_path)
    connection.executescript(
        """
        CREATE TABLE series (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE progress (
            uri TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            available INTEGER NOT NULL,
            manual_source INTEGER NOT NULL,
            series_id INTEGER NOT NULL DEFAULT 0
        );
        CREATE TABLE scanned_files (
            source_identity TEXT PRIMARY KEY,
            tree_uri TEXT NOT NULL,
            canonical_uri TEXT NOT NULL,
            missing_confirmed INTEGER NOT NULL DEFAULT 0
        );
        """
    )
    connection.executemany(
        "INSERT INTO progress(uri, title, available, manual_source) VALUES (?, ?, ?, ?)",
        [
            ("confirmed", "Confirmed", 0, 0),
            ("lost-access", "Lost access", 0, 0),
            ("old-folder", "Old folder", 0, 0),
            ("uncertain-copy", "Uncertain copy", 0, 0),
            ("available", "Available", 1, 0),
            ("manual", "Manual", 0, 1),
        ],
    )
    connection.executemany(
        """
        INSERT INTO scanned_files(
            source_identity, tree_uri, canonical_uri, missing_confirmed
        ) VALUES (?, ?, ?, ?)
        """,
        [
            ("confirmed-current", "tree:active", "confirmed", 1),
            ("lost-current", "tree:active", "lost-access", 0),
            ("old-confirmed", "tree:old", "old-folder", 1),
            ("copy-current", "tree:active", "uncertain-copy", 1),
            ("copy-old", "tree:old", "uncertain-copy", 0),
            ("available-current", "tree:active", "available", 1),
            ("manual-current", "tree:active", "manual", 1),
        ],
    )
    selected = connection.execute(
        """
        SELECT p.uri
        FROM progress p
        WHERE p.available=0 AND p.manual_source=0
          AND EXISTS (
              SELECT 1 FROM scanned_files known
              WHERE known.canonical_uri=p.uri AND known.tree_uri=?
          )
          AND NOT EXISTS (
              SELECT 1 FROM scanned_files uncertain
              WHERE uncertain.canonical_uri=p.uri AND uncertain.missing_confirmed=0
          )
        ORDER BY p.uri
        """,
        ("tree:active",),
    ).fetchall()
    connection.close()

assert selected == [("confirmed",)]
print("Confirmed-missing cleanup policy passed")
PY
