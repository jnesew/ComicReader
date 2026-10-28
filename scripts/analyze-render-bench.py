#!/usr/bin/env python3
"""Compare one exported device result ZIP. Standard library only; no network access."""
import argparse
import collections
import json
import statistics
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('results_zip')
args = parser.parse_args()
with zipfile.ZipFile(args.results_zip) as archive:
    for name in ('run.json', 'cases.jsonl', 'state.json'):
        if archive.getinfo(name).file_size > 16 * 1024 * 1024:
            raise SystemExit('Report exceeds bounded input limit')
    metadata = json.loads(archive.read('run.json'))
    state = json.loads(archive.read('state.json'))
    cases = [json.loads(line) for line in archive.read('cases.jsonl').splitlines() if line]
print(f'Device: {metadata["model"]}; API {metadata["api"]}; source {metadata["source"]}; run {state["status"]}')
print('Paired comparisons within this run. Negative change means less waiting. Small sample counts are descriptive, not statistical proof.\n')
baseline = {(c['fixture'], c['mode'], c['repeat']): c for c in cases
            if c['policy'] == 'baseline_high' and c['status'] == 'completed'}
groups = collections.defaultdict(list)
excluded = collections.Counter()
for case in cases:
    if case['policy'] == 'baseline_high':
        continue
    key = case['fixture'], case['mode'], case['repeat']
    if case['status'] != 'completed' or key not in baseline:
        excluded[case['policy']] += 1
        continue
    ref = baseline[key]
    ready = case['ready_p95_ms'] - ref['ready_p95_ms']
    gap = case['metrics']['exact_missing_area_ms'] - ref['metrics']['exact_missing_area_ms']
    groups[(case['policy'], case['fixture'], case['mode'])].append((ready, gap))
print('| Policy | Fixture / mode | Pairs | Median p95 readiness change (ms) | Median exact-gap area change (ms) |')
print('| --- | --- | ---: | ---: | ---: |')
for (policy, fixture, mode), values in sorted(groups.items()):
    print(f'| {policy} | {fixture} / {mode} | {len(values)} | {statistics.median(v[0] for v in values):+.2f} | {statistics.median(v[1] for v in values):+.2f} |')
if excluded:
    print('\nExcluded incomplete/failed/unpaired cases:', dict(excluded))
print('\nCheck frame misses, actual cap/use, native/Java memory and thermal conditions in cases.jsonl before choosing a policy. Exact gaps may contain softer fallback pixels.')
