#!/usr/bin/env python3
"""Verify shipped synthetic files using only the Python standard library."""
import hashlib
import json
from pathlib import Path
import zipfile
root = Path(__file__).resolve().parent.parent / 'app/src/renderBench/assets/bench'
manifest = json.loads((root / 'manifest.json').read_text())
assert manifest['version'] == 1
total = 0
for item in manifest['fixtures']:
    path = root / item['name']
    assert path.parent == root
    data = path.read_bytes()
    assert len(data) == item['bytes'], path
    assert hashlib.sha256(data).hexdigest() == item['sha256'], path
    total += len(data)
    if path.suffix in ('.cbz', '.epub'):
        with zipfile.ZipFile(path) as z:
            assert z.testzip() is None, path
assert total <= 32 * 1024 * 1024
print(f'Validated {len(manifest["fixtures"])} synthetic fixtures, {total:,} encoded bytes.')
