#!/usr/bin/env python3
"""Offline analysis of one benchmark ZIP (protocols 1 and 2). Never pools different devices/runs."""
import argparse
import collections
import json
import math
import statistics
import zipfile


def number(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) else None


def median(values):
    values = [v for v in values if number(v) is not None]
    return statistics.median(values) if values else None


def difference(a, b):
    return float(a - b) if number(a) is not None and number(b) is not None else None


def cell(value):
    if value is None:
        return '—'
    if isinstance(value, float):
        return f'{value:+.2f}'
    return str(value).replace('|', '\\|').replace('\n', ' ')


def key(case):
    return case['policy'], case['fixture'], case['mode'], case['repeat']


def completed(case):
    return (case.get('status') == 'completed' and case.get('repeat', -1) >= 0
            and case.get('readiness_timeouts', 0) == 0
            and case.get('metrics', {}).get('render_failures', 0) == 0
            and (not case.get('session_reuse') or case.get('completed_cycles') == case.get('planned_cycles')))


def frame_valid(frame):
    return (isinstance(frame, dict) and frame.get('count', 0) > 0 and frame.get('valid', True)
            and frame.get('dropped_reports', frame.get('dropped_reports_associated_with_callbacks', 0)) == 0
            and frame.get('frame_buffer_overflow', 0) == 0)


def read_report(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        def read(name, optional=False):
            count = names.count(name)
            if count == 0 and optional:
                return b''
            if count != 1:
                raise ValueError(f'Missing or duplicate report entry: {name}')
            if archive.getinfo(name).file_size > 32 * 1024 * 1024:
                raise ValueError(f'Report entry exceeds 32 MiB: {name}')
            return archive.read(name)
        metadata = json.loads(read('run.json'))
        state = json.loads(read('state.json'))
        if metadata.get('schema') not in (1, 2):
            raise ValueError('Unsupported report schema')
        if metadata['schema'] == 2 and metadata.get('protocol') != 'render-bench-2':
            raise ValueError('Unsupported benchmark protocol')
        def lines(name, optional=False):
            result = [json.loads(line) for line in read(name, optional).splitlines() if line]
            if len(result) > 50000 or any(not isinstance(x, dict) for x in result):
                raise ValueError(f'Invalid or excessive records: {name}')
            return result
        cases = lines('cases.jsonl')
        actions = lines('actions.jsonl', True)
        checkpoints = lines('checkpoints.jsonl', True)
    identities = [key(c) for c in cases]
    if len(identities) != len(set(identities)):
        raise ValueError('Duplicate case identity; refusing ambiguous baseline pairing')
    identities = [(a['case_id'], a['cycle'], a['action'], a['occurrence']) for a in actions]
    if len(identities) != len(set(identities)):
        raise ValueError('Duplicate action identity')
    return metadata, state, cases, actions, checkpoints


def analyze(path, include_actions=False):
    metadata, state, cases, actions, checkpoints = read_report(path)
    out = []
    emit = out.append
    def table(headers, rows):
        emit('| ' + ' | '.join(headers) + ' |')
        emit('| ' + ' | '.join('---' for _ in headers) + ' |')
        for row in rows:
            emit('| ' + ' | '.join(cell(v) for v in row) + ' |')
    emit(f'Device: {cell(metadata["model"])}; API {metadata["api"]}; source {cell(metadata["source"])}; run {cell(state["status"])}')
    emit(f'Protocol: {metadata.get("protocol", "legacy-1")}; app heap: {metadata.get("heap_limit_bytes", 0) / 2**20:.1f} MiB')
    emit('Within-run paired comparisons. Negative deltas mean less time/work. Samples are descriptive, not statistical confidence.\n')
    statuses = collections.Counter(c.get('status', 'unknown') for c in cases)
    emit('Case statuses: ' + str(dict(statuses)))
    emit(f'Completed measured cases eligible for pairing: {sum(completed(c) for c in cases)}; action records: {len(actions)}; sustained checkpoints: {len(checkpoints)}.')
    if state.get('status') == 'completed_with_case_errors':
        emit('RUN FINISHED WITH CASE ERRORS: failed/timed-out cases are excluded; inspect statuses.')
    elif state.get('status') != 'completed':
        emit('RUN INCOMPLETE: recorded completed cases may be inspected; this run is not a passing battery.')
    invalid_frames = sum(1 for c in cases if completed(c) and (c.get('frame_error') or not frame_valid(c.get('frames'))))
    emit(f'Completed cases with unavailable/incomplete frame diagnostics: {invalid_frames}.')
    emit('')
    baseline = {(c['fixture'], c['mode'], c['repeat']): c for c in cases if completed(c) and c['policy'] == 'baseline_high'}
    groups = collections.defaultdict(list)
    excluded = collections.Counter()
    for case in cases:
        if case.get('repeat', -1) < 0 or case['policy'] == 'baseline_high':
            continue
        ref = baseline.get((case['fixture'], case['mode'], case['repeat']))
        if not completed(case) or ref is None:
            excluded[case['policy']] += 1
            continue
        groups[case['policy'], case['fixture'], case['mode']].append((case, ref))
    rows = []
    for (policy, fixture, mode), pairs in sorted(groups.items()):
        frames = [(a['frames'], b['frames']) for a, b in pairs if not a.get('frame_error') and not b.get('frame_error')
                  and frame_valid(a.get('frames')) and frame_valid(b.get('frames'))]
        ratios = [100 * (a['metrics']['decode_ms'] / b['metrics']['decode_ms'] - 1)
                  for a, b in pairs if b['metrics'].get('decode_ms', 0) > 0]
        rows.append((policy, f'{fixture} / {mode}', len(pairs),
                     median(difference(a.get('ready_p95_ms'), b.get('ready_p95_ms')) for a, b in pairs),
                     median(difference(a['metrics'].get('exact_missing_area_ms'), b['metrics'].get('exact_missing_area_ms')) for a, b in pairs),
                     median(ratios), len(frames),
                     median(difference(a.get('duration_p95_bin_ms'), b.get('duration_p95_bin_ms')) for a, b in frames),
                     f'{sum(a["misses"] for a, _ in frames)}/{sum(a["count"] for a, _ in frames)} vs '
                     f'{sum(b["misses"] for _, b in frames)}/{sum(b["count"] for _, b in frames)}' if frames else None))
    table(['Policy', 'Workload', 'Pairs', 'Δ case ready p95 ms', 'Δ exact-gap area ms', 'Δ decode %', 'Frame pairs', 'Δ frame p95 bin ms', 'Candidate vs baseline misses/reports'], rows)
    if excluded:
        emit('\nExcluded failed/interrupted/timed-out/unpaired candidate cases: ' + str(dict(excluded)))
    if include_actions and actions:
        eligible = {c.get('case_id'): c for c in cases if completed(c)}
        base_actions = {}
        action_groups = collections.defaultdict(list)
        for action in actions:
            c = eligible.get(action['case_id'])
            if c and c['policy'] == 'baseline_high' and action.get('status') == 'completed':
                base_actions[c['fixture'], c['mode'], c['repeat'], action['cycle'], action['action'], action['occurrence']] = action
        for action in actions:
            c = eligible.get(action['case_id'])
            if not c or c['policy'] == 'baseline_high' or action.get('status') != 'completed':
                continue
            ref = base_actions.get((c['fixture'], c['mode'], c['repeat'], action['cycle'], action['action'], action['occurrence']))
            if ref is not None:
                action_groups[c['policy'], c['fixture'], c['mode'], action['action']].append((action, ref))
        emit('\nAction windows (readiness kind is preserved in raw records; scroll readiness includes settling after lift):\n')
        rows = []
        for (policy, fixture, mode, action), pairs in sorted(action_groups.items()):
            ff = [(a['frames'], b['frames']) for a, b in pairs if frame_valid(a.get('frames')) and frame_valid(b.get('frames'))]
            def phase_delta(phase):
                return median(difference(a.get('phases', {}).get(phase, {}).get('p95_bin_ms'),
                                         b.get('phases', {}).get(phase, {}).get('p95_bin_ms')) for a, b in ff)
            rows.append((policy, f'{fixture} / {mode}', action, len(pairs),
                         median(difference(a.get('ready_ms'), b.get('ready_ms')) for a, b in pairs),
                         median(difference(a['metrics_delta'].get('exact_missing_area_ms'), b['metrics_delta'].get('exact_missing_area_ms')) for a, b in pairs),
                         phase_delta('draw'), phase_delta('sync'), phase_delta('command_issue'), phase_delta('gpu')))
        table(['Policy', 'Workload', 'Action', 'Pairs', 'Δ ready ms', 'Δ exact-gap ms', 'Δ draw p95', 'Δ sync p95', 'Δ commands p95', 'Δ GPU p95'], rows)
    if checkpoints:
        emit('\nSustained-session diagnostics (first/last cycle samples, not leak proof; incomplete cases remain partial):\n')
        groups = collections.defaultdict(list)
        case_index = {c.get('case_id'): c for c in cases}
        for checkpoint in checkpoints:
            groups[checkpoint['case_id']].append(checkpoint)
        rows = []
        for case_id, records in sorted(groups.items()):
            records.sort(key=lambda r: r['cycle'])
            if len({r['cycle'] for r in records}) != len(records):
                raise ValueError('Duplicate sustained cycle checkpoint')
            first, last = records[0], records[-1]
            rows.append((case_id, case_index.get(case_id, {}).get('status', 'partial'), len(records),
                         first['memory']['native_allocated_bytes'] / 2**20,
                         last['memory']['native_allocated_bytes'] / 2**20,
                         first['shared_bytes'] / 2**20, last['shared_bytes'] / 2**20))
        table(['Case', 'Status', 'Cycles', 'First native MiB', 'Last native MiB', 'First shared MiB', 'Last shared MiB'], rows)
    emit('\nExact gaps may display softer fallback. Frame phases can overlap; unsupported phases are unavailable, not zero. Frame counts differ by policy; miss ratios alone do not rank smoothness. Native samples omit full process/GPU memory and may miss peaks.')
    emit('Protocol 2 changes instrumentation, geometry and scroll settling; use its own baseline. Device performance/default decisions require live evidence.')
    return '\n'.join(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results_zip')
    parser.add_argument('--actions', action='store_true', help='Include paired per-action frame-phase and readiness tables')
    args = parser.parse_args()
    try:
        print(analyze(args.results_zip, args.actions))
    except (ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(2, f'Invalid benchmark report: {error}\n')


if __name__ == '__main__':
    main()
