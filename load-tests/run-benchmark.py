#!/usr/bin/env python3
"""Run k6 and report measurements without changing the database fixtures."""
import argparse
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
RESULTS = ROOT / 'load-tests/results'
PROMETHEUS_URL = 'http://localhost:9090'


def query_prometheus(query, start, end):
    params = urllib.parse.urlencode({'query': query, 'start': start, 'end': end, 'step': '15s'})
    try:
        with urllib.request.urlopen(f'{PROMETHEUS_URL}/api/v1/query_range?{params}', timeout=5) as response:
            data = json.load(response)
        if data.get('status') != 'success':
            raise ValueError('Prometheus returned an unsuccessful response')
        return data.get('data', {}).get('result', [])
    except (urllib.error.URLError, ValueError, OSError) as error:
        print(f'Prometheus measurement unavailable: {error}', file=sys.stderr)
        return []


def stats(result):
    values = []
    for series in result:
        for _, raw in series.get('values', []):
            try:
                value = float(raw)
                if math.isfinite(value):
                    values.append(value)
            except (ValueError, TypeError):
                continue
    return (sum(values) / len(values), max(values)) if values else (None, None)


def formatted(value, scale=1):
    return 'N/A' if value is None else f'{value / scale:,.2f}'


def formatted_fraction(value):
    # Preserve small failures and near-perfect checks instead of rounding to 0 or 1.
    return 'N/A' if value is None else str(value)


def seed_disposable_database():
    # This is only called after the operator supplies the explicit destructive flag.
    with (ROOT / 'docker/scale_data.sql').open('rb') as source:
        subprocess.run([
            'docker', 'exec', '-i', 'pg_enterprise_supply', 'psql',
            '-U', 'enterprise_admin', '-d', 'supply_db',
            '-v', 'ON_ERROR_STOP=1', '-v', 'ALLOW_DESTRUCTIVE_SEED=true',
        ], stdin=source, check=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profile', nargs='?', default='smoke', choices=['smoke', 'stress', 'extreme', 'diagnostic'])
    parser.add_argument('--reset-disposable-db', action='store_true',
                        help='DESTRUCTIVE: erase orders, audit logs and notifications; load benchmark fixtures')
    args = parser.parse_args(argv)
    if args.reset_disposable_db:
        seed_disposable_database()

    RESULTS.mkdir(parents=True, exist_ok=True)
    summary = RESULTS / 'full-summary.json'
    summary.unlink(missing_ok=True)
    start = datetime.now(timezone.utc).isoformat()
    env = dict(os.environ, K6_FULL_SUMMARY='1')
    completed = subprocess.run([str(ROOT / 'load-tests/run.sh'), args.profile], cwd=ROOT, env=env)
    end = datetime.now(timezone.utc).isoformat()
    if not summary.exists():
        print('k6 did not produce a summary.', file=sys.stderr)
        return completed.returncode or 1
    data = json.loads(summary.read_text())
    metrics = data.get('metrics', {})

    def measurement(name, field):
        return metrics.get(name, {}).get('values', {}).get(field)

    rows = [
        ('HTTP requests', measurement('http_reqs', 'count'), formatted),
        ('Requests/second', measurement('http_reqs', 'rate'), formatted),
        ('HTTP failure fraction', measurement('http_req_failed', 'rate'), formatted_fraction),
        ('Check success fraction', measurement('checks', 'rate'), formatted_fraction),
        ('Mean latency (ms)', measurement('http_req_duration', 'avg'), formatted),
        ('Median latency (ms)', measurement('http_req_duration', 'med'), formatted),
        ('p95 latency (ms)', measurement('http_req_duration', 'p(95)'), formatted),
    ]
    lines = ['# Benchmark report', '', f'Profile: `{args.profile}`', f'Start: {start}', f'End: {end}',
             f'k6 exit status: {completed.returncode} (0 means thresholds passed)', '',
             '| Measurement | Value |', '|---|---|']
    lines += [f'| {label} | {formatter(value)} |' for label, value, formatter in rows]
    lines += ['', '## Prometheus observations', '',
              'N/A means no usable measurement was returned. Node-exporter observations describe its container environment unless host mounts are configured.', '',
              '| Measurement | Mean | Peak |', '|---|---|---|']
    queries = [
        ('CPU use (%)', "100 - (avg(rate(node_cpu_seconds_total{mode='idle'}[1m])) * 100)", 1),
        ('Memory use (%)', '100 * (1 - node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)', 1),
        ('JVM heap (MiB)', 'sum(jvm_memory_used_bytes{area="heap"})', 1024 ** 2),
        ('JVM threads', 'jvm_threads_live_threads', 1),
        ('Active database connections', 'hikaricp_connections_active', 1),
        ('Pending database connections', 'hikaricp_connections_pending', 1),
    ]
    for label, query, scale in queries:
        mean, peak = stats(query_prometheus(query, start, end))
        lines.append(f'| {label} | {formatted(mean, scale)} | {formatted(peak, scale)} |')
    report = '\n'.join(lines) + '\n'
    filename = RESULTS / f'benchmark_report_{datetime.now().strftime("%Y%m%d_%H%M%S")}.md'
    filename.write_text(report)
    (RESULTS / 'latest-benchmark-report.md').write_text(report)
    print(f'Report saved: {filename}')
    # A report must not turn failed k6 thresholds (exit 99) into a successful run.
    return completed.returncode


if __name__ == '__main__':
    sys.exit(main())
