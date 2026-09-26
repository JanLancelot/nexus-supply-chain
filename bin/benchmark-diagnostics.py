#!/usr/bin/env python3
"""Compare five separate collection commands with one combined invocation, not human toil."""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import statistics
import subprocess
import sys
import time

SPEC = importlib.util.spec_from_file_location("diagnose", Path(__file__).with_name("diagnose.py"))
DIAGNOSE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DIAGNOSE)


def measure(project, directory, separate):
    sections = DIAGNOSE.SECTIONS if separate else (None,)
    codes = []
    started = time.monotonic()
    for section in sections:
        args = [sys.executable, str(Path(DIAGNOSE.__file__)), "--project", project,
                "--output-dir", str(directory)]
        if section:
            args.extend(["--section", section])
        # The child already suppresses source text; retain private evidence files,
        # and keep the terminal focused on timings rather than eighteen paths.
        result = subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                timeout=780, check=False)
        codes.append(result.returncode)
    return {"elapsed_seconds": round(time.monotonic() - started, 3),
            "invocations": len(sections), "exit_codes": codes,
            "collection_status": "complete" if all(code == 0 for code in codes) else "partial"}


def benchmark(project, output_dir, iterations):
    if not DIAGNOSE.valid_project(project):
        raise DIAGNOSE.CollectionError("unsupported_project")
    root = Path(output_dir).absolute()
    if any(parent.is_symlink() for parent in (root, *root.parents)):
        raise DIAGNOSE.CollectionError("symlink_output_refused")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    destination = root / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4))
    destination.mkdir(mode=0o700)
    trials = []
    for index in range(iterations):
        trial = {"trial": index + 1}
        # Alternate order to make one method less consistently favored by caches.
        order = ("separate", "combined") if index % 2 == 0 else ("combined", "separate")
        trial["order"] = list(order)
        for method in order:
            trial[method] = measure(project, destination / f"trial-{index+1}" / method,
                                    separate=method == "separate")
        trials.append(trial)
    complete = all(trial[method]["collection_status"] == "complete"
                   for trial in trials for method in ("separate", "combined"))
    report = {"schema_version": 1, "project": project, "collected_at": datetime.now(timezone.utc).isoformat(),
        "measurement": "machine_elapsed_collection_only", "collection_status": "complete" if complete else "partial",
        "diagnostic_source_sha256": hashlib.sha256(Path(DIAGNOSE.__file__).read_bytes()).hexdigest(),
        "sections": list(DIAGNOSE.SECTIONS), "queries": DIAGNOSE.QUERIES,
        "trials": trials,
        "median_seconds": {method: round(statistics.median(trial[method]["elapsed_seconds"] for trial in trials), 3)
                           for method in ("separate", "combined")},
        "limitations": ["No human manual investigation was timed; no MTTR or human toil saving is established.",
                        "Same section code, query set, log bounds and target; observations occur at different times.",
                        "Separate mode repeats target discovery five times; combined mode discovers once.",
                        "No injected fault, recovery action or delivery notification is part of this benchmark."]}
    lines = ["# Diagnostic collection measurement", "", f"Project: `{project}`",
             f"Measured: {report['collected_at']}", f"Collection: {report['collection_status']}", "",
             "Machine elapsed collection time only. No human manual investigation was timed.", "",
             "| Trial | Order | Five separate commands (seconds) | One combined command (seconds) |",
             "| --- | --- | ---: | ---: |"]
    for trial in trials:
        lines.append(f"| {trial['trial']} | {' then '.join(trial['order'])} | {trial['separate']['elapsed_seconds']} | {trial['combined']['elapsed_seconds']} |")
    lines.extend(["", f"Medians: separate {report['median_seconds']['separate']} s; combined {report['median_seconds']['combined']} s.", "",
                  *[f"- {item}" for item in report["limitations"]], ""])
    for name, contents in (("benchmark.json", json.dumps(report, indent=2) + "\n"),
                           ("benchmark.md", "\n".join(lines))):
        descriptor = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(contents)
    return destination, complete


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--iterations", type=int, choices=range(3, 11), default=3)
    parser.add_argument("--output-dir", type=Path, default=DIAGNOSE.ROOT / ".artifacts/diagnostics/benchmarks")
    args = parser.parse_args(argv)
    try:
        destination, complete = benchmark(args.project, args.output_dir, args.iterations)
        print(f"Machine collection comparison: {destination / 'benchmark.md'}")
        return 0 if complete else 1
    except (OSError, subprocess.TimeoutExpired, DIAGNOSE.CollectionError):
        print("Benchmark failed; inspect any private partial reports. No performance conclusion is available.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
