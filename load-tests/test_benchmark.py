"""Safety checks for the benchmark runner; no containers or network are used."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('benchmark', Path(__file__).with_name('run-benchmark.py'))
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


class BenchmarkSafetyTests(unittest.TestCase):
    def test_remote_load_target_does_not_implicitly_measure_local_prometheus(self):
        with tempfile.TemporaryDirectory() as temp:
            result_dir = Path(temp)

            def fake_run(*args, **kwargs):
                (result_dir / 'full-summary.json').write_text(json.dumps({'metrics': {}}))
                return subprocess.CompletedProcess(args, 0)

            with patch.dict(benchmark.os.environ, {'BASE_URL': 'https://load-target.example.test'}, clear=True), \
                    patch.object(benchmark, 'RESULTS', result_dir), \
                    patch.object(benchmark.subprocess, 'run', side_effect=fake_run), \
                    patch.object(benchmark, 'query_prometheus', return_value=[]) as metrics:
                self.assertEqual(benchmark.main([]), 0)
                metrics.assert_not_called()
                report = (result_dir / 'latest-benchmark-report.md').read_text()
                self.assertIn('https://load-target.example.test', report)
                self.assertIn('not selected', report)

    def test_explicit_monitoring_source_is_used_and_recorded(self):
        with tempfile.TemporaryDirectory() as temp:
            result_dir = Path(temp)

            def fake_run(*args, **kwargs):
                (result_dir / 'full-summary.json').write_text(json.dumps({'metrics': {}}))
                return subprocess.CompletedProcess(args, 0)

            with patch.object(benchmark, 'RESULTS', result_dir), \
                    patch.object(benchmark.subprocess, 'run', side_effect=fake_run), \
                    patch.object(benchmark, 'query_prometheus', return_value=[]) as metrics:
                self.assertEqual(benchmark.main(['--prometheus-url', 'https://metrics.example.test']), 0)
                self.assertTrue(metrics.called)
                self.assertEqual(metrics.call_args.args[3], 'https://metrics.example.test')
                self.assertIn('https://metrics.example.test', (result_dir / 'latest-benchmark-report.md').read_text())

    def test_missing_measurements_are_not_reported_as_zero(self):
        self.assertEqual(benchmark.stats([]), (None, None))
        self.assertEqual(benchmark.formatted(None), 'N/A')
        self.assertEqual(benchmark.stats([{'values': [[1, 'NaN'], [2, 'Inf']]}]), (None, None))

    def test_measurements_ignore_nonfinite_values(self):
        self.assertEqual(benchmark.stats([{'values': [[1, '2'], [2, '4'], [3, 'NaN']]}]), (3, 4))

    def test_default_run_never_resets_database_and_preserves_k6_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            result_dir = Path(temp)

            def fake_run(*args, **kwargs):
                (result_dir / 'full-summary.json').write_text(json.dumps({'metrics': {}}))
                return subprocess.CompletedProcess(args, 99)

            with patch.object(benchmark, 'RESULTS', result_dir), \
                    patch.object(benchmark, 'seed_disposable_database') as seed, \
                    patch.object(benchmark.subprocess, 'run', side_effect=fake_run), \
                    patch.object(benchmark, 'query_prometheus', return_value=[]):
                self.assertEqual(benchmark.main([]), 99)
                seed.assert_not_called()
                self.assertIn('N/A', (result_dir / 'latest-benchmark-report.md').read_text())

    def test_report_preserves_small_failures_and_near_perfect_checks(self):
        with tempfile.TemporaryDirectory() as temp:
            result_dir = Path(temp)

            def fake_run(*args, **kwargs):
                summary = {'metrics': {
                    'http_req_failed': {'values': {'rate': 0.004}},
                    'checks': {'values': {'rate': 0.9999}},
                }}
                (result_dir / 'full-summary.json').write_text(json.dumps(summary))
                return subprocess.CompletedProcess(args, 0)

            with patch.object(benchmark, 'RESULTS', result_dir), \
                    patch.object(benchmark.subprocess, 'run', side_effect=fake_run), \
                    patch.object(benchmark, 'query_prometheus', return_value=[]):
                self.assertEqual(benchmark.main([]), 0)
                report = (result_dir / 'latest-benchmark-report.md').read_text()
                self.assertIn('| HTTP failure fraction | 0.004 |', report)
                self.assertIn('| Check success fraction | 0.9999 |', report)
        self.assertEqual(benchmark.formatted_fraction(None), 'N/A')
        self.assertGreater(float(benchmark.formatted_fraction(1e-12)), 0)
        self.assertLess(float(benchmark.formatted_fraction(1 - 1e-12)), 1)

    def test_failed_fixture_reset_aborts_before_load(self):
        with patch.object(benchmark, 'seed_disposable_database', side_effect=subprocess.CalledProcessError(1, 'psql')), \
                patch.object(benchmark.subprocess, 'run') as run:
            with self.assertRaises(subprocess.CalledProcessError):
                benchmark.main(['--reset-disposable-db'])
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
