"""Test bootstrap recovery without network access or running application tests."""

import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


class MavenBootstrapTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="maven bootstrap ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "bin").mkdir()
        (self.root / "backend").mkdir()
        self.fake_tools = self.root / "fake-tools"
        self.fake_tools.mkdir()
        (self.fake_tools / "dirname").symlink_to(shutil.which("dirname"))
        self.launcher = self.root / "bin" / "prepare-maven.sh"
        shutil.copy2(REPOSITORY_ROOT / "bin" / self.launcher.name, self.launcher)
        self.record = self.root / "calls.jsonl"
        recorder = self.root / "record.py"
        recorder.write_text('''import json
import os
from pathlib import Path
import sys

record = Path(os.environ["BOOTSTRAP_RECORD"])
calls = [json.loads(line) for line in record.read_text().splitlines()] if record.exists() else []
tool = sys.argv[1]
with record.open("a") as output:
    output.write(json.dumps({"tool": tool, "args": sys.argv[2:],
                            "verbose": os.environ.get("MVNW_VERBOSE")}) + "\\n")
if tool == "maven":
    failures = json.loads(os.environ["BOOTSTRAP_FAILURES"])
    attempt = sum(call["tool"] == "maven" for call in calls)
    sys.exit(failures[attempt] if attempt < len(failures) else 0)
''')
        for path, tool in ((self.root / "backend" / "mvnw", "maven"),
                           (self.fake_tools / "sleep", "sleep")):
            path.write_text(f"#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(str(recorder))} {tool} \"$@\"\n")
            path.chmod(0o755)

    def run_bootstrap(self, failures):
        result = subprocess.run(
            [shutil.which("bash"), str(self.launcher)], cwd=self.root,
            env={"PATH": str(self.fake_tools), "BOOTSTRAP_RECORD": str(self.record),
                 "BOOTSTRAP_FAILURES": json.dumps(failures)},
            capture_output=True, text=True, timeout=5, check=False,
        )
        calls = [json.loads(line) for line in self.record.read_text().splitlines()]
        for call in calls:
            if call["tool"] == "maven":
                self.assertEqual(call["args"], ["-B", "-ntp", "--version"])
                self.assertEqual(call["verbose"], "true")
        return result, calls

    def test_ready_distribution_starts_once_without_waiting(self):
        result, calls = self.run_bootstrap([])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["tool"] for call in calls], ["maven"])

    def test_transient_download_failures_recover_before_tests_start(self):
        result, calls = self.run_bootstrap([8, 8])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call["tool"] for call in calls], ["maven", "sleep", "maven", "sleep", "maven"])
        self.assertEqual([call["args"] for call in calls if call["tool"] == "sleep"], [["5"], ["10"]])
        self.assertIn("retrying distribution setup", result.stderr)

    def test_persistent_failure_is_bounded_and_preserves_failure_status(self):
        result, calls = self.run_bootstrap([8, 8, 23, 0])
        self.assertEqual(result.returncode, 23)
        self.assertEqual(sum(call["tool"] == "maven" for call in calls), 3)
        self.assertEqual(calls[-1]["tool"], "maven")
        self.assertIn("tests have not started", result.stderr)


if __name__ == "__main__":
    unittest.main()
