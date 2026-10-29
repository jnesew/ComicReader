#!/usr/bin/env python3
"""Report integrity and observable pairing tests; standard library only."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('analyzer', Path(__file__).with_name('analyze-render-bench.py'))
analyzer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analyzer)


def case(policy, repeat=0, status='completed', dropped=0):
    baseline = policy == 'baseline_high'
    return dict(policy=policy, fixture='raster.cbz', mode='continuous', repeat=repeat, status=status,
                case_id=f'{policy}/raster.cbz/continuous/{repeat}', readiness_timeouts=0,
                ready_p95_ms=100 if baseline else 80,
                metrics=dict(exact_missing_area_ms=60 if baseline else 40, decode_ms=20 if baseline else 10),
                frames=dict(count=10, misses=1, duration_p95_bin_ms=15 if baseline else 10, dropped_reports=dropped))


class Reports(unittest.TestCase):
    def write(self, cases, *, schema=2, state='completed', actions=(), checkpoints=()):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path = Path(folder.name) / 'report.zip'
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('run.json', json.dumps(dict(schema=schema, protocol='render-bench-2', model='Fixture',
                                                  api=26, source='1234567', heap_limit_bytes=256 * 2**20)))
            z.writestr('state.json', json.dumps(dict(status=state)))
            z.writestr('cases.jsonl', '\n'.join(json.dumps(c) for c in cases))
            z.writestr('actions.jsonl', '\n'.join(json.dumps(c) for c in actions))
            z.writestr('checkpoints.jsonl', '\n'.join(json.dumps(c) for c in checkpoints))
        return path

    def test_pairs_by_workload_repeat_and_excludes_failed_or_unpaired_cases(self):
        path = self.write([case('baseline_high'), case('candidate'), case('candidate', 1),
                           case('failed', status='readiness_timeout'), case('baseline_high', -1, 'warmup')])
        output = analyzer.analyze(path)
        self.assertIn('| candidate | raster.cbz / continuous | 1 | -20.00 | -20.00 | -50.00 |', output)
        self.assertIn("'candidate': 1", output)
        self.assertIn("'failed': 1", output)

    def test_dropped_frames_do_not_become_a_zero_cost_improvement(self):
        output = analyzer.analyze(self.write([case('baseline_high'), case('candidate', dropped=1)]))
        self.assertIn('incomplete frame diagnostics: 1', output)
        self.assertIn('| -50.00 | 0 | — | — |', output)

    def test_duplicate_case_identity_is_rejected_instead_of_overwriting_baseline(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate case'):
            analyzer.analyze(self.write([case('baseline_high'), case('baseline_high')]))

    def test_legacy_schema_remains_readable(self):
        output = analyzer.analyze(self.write([case('baseline_high'), case('candidate')], schema=1))
        self.assertIn('| candidate |', output)

    def test_action_pairing_respects_cycle_and_reports_unsupported_gpu_as_unavailable(self):
        actions = []
        for policy in ['baseline_high', 'candidate']:
            c = case(policy)
            actions.append(dict(case_id=c['case_id'], cycle=0, action='next', occurrence=0, status='completed',
                                ready_ms=100 if policy == 'baseline_high' else 80,
                                metrics_delta=dict(exact_missing_area_ms=10),
                                frames=dict(count=1, valid=True, phases={'gpu': {'count': 0, 'p95_bin_ms': None}})))
        output = analyzer.analyze(self.write([case('baseline_high'), case('candidate')], actions=actions), True)
        self.assertIn('| next | 1 | -20.00 | +0.00 | — | — | — | — |', output)

    def test_partial_session_checkpoint_is_diagnostic_without_becoming_a_completed_case(self):
        checkpoint = dict(case_id='candidate/raster.cbz/continuous/0', cycle=0,
                          memory=dict(native_allocated_bytes=32 * 2**20), shared_bytes=16 * 2**20)
        output = analyzer.analyze(self.write([], state='running', checkpoints=[checkpoint]))
        self.assertIn('RUN INCOMPLETE', output)
        self.assertIn('| partial | 1 |', output)


if __name__ == '__main__':
    unittest.main()
