"""Exercise lifecycle sequencing using only disposable Terraform/Azure stubs."""

import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


class LifecycleScriptTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="nexus lifecycle ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.scripts = self.root / "bin"
        self.tools = self.root / "fake-tools"
        self.scripts.mkdir()
        self.tools.mkdir()
        (self.root / "terraform").mkdir()
        for name in ("resume.sh", "suspend.sh"):
            shutil.copy2(REPOSITORY_ROOT / "bin" / name, self.scripts / name)
        self.record = self.root / "calls.jsonl"
        self.bash = shutil.which("bash")
        # PATH has no real az/terraform or credentials, making cloud access
        # impossible even on a developer machine logged into Azure.
        (self.tools / "dirname").symlink_to(shutil.which("dirname"))
        recorder = self.root / "record.py"
        recorder.write_text(textwrap.dedent("""\
            import json
            import os
            from pathlib import Path
            import sys

            tool = sys.argv[1]
            arguments = sys.argv[2:]
            with Path(os.environ["LIFECYCLE_RECORD"]).open("a", encoding="utf-8") as record:
                record.write(json.dumps({"tool": tool, "arguments": arguments}) + "\\n")
            if tool == "terraform" and arguments[1:3] == ["output", "-raw"]:
                output = arguments[3]
                if output == os.environ.get("FAIL_OUTPUT"):
                    sys.exit(23)
                if output == os.environ.get("EMPTY_OUTPUT"):
                    print(os.environ.get("EMPTY_VALUE", ""))
                    sys.exit(0)
                print({
                    "resource_group_name": "selected-resource-group",
                    "postgres_server_name": "selected-production-db",
                    "staging_postgres_server_name": "selected-staging-db",
                }[output])
            elif tool == "terraform":
                if os.environ.get("FAIL_APPLY") == "yes":
                    sys.exit(29)
            elif tool == "az":
                name = arguments[arguments.index("--name") + 1]
                if name == os.environ.get("FAIL_DATABASE"):
                    sys.exit(31)
            else:
                sys.exit(99)
            """), encoding="utf-8")
        for tool in ("terraform", "az"):
            wrapper = self.tools / tool
            wrapper.write_text(
                f"#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(str(recorder))} {tool} \"$@\"\n",
                encoding="utf-8",
            )
            wrapper.chmod(0o755)
        self.environment = {"PATH": str(self.tools), "LIFECYCLE_RECORD": str(self.record)}

    def run_script(self, name, **configuration):
        self.record.unlink(missing_ok=True)
        result = subprocess.run(
            [self.bash, str(self.scripts / name)],
            cwd=self.root,
            env={**self.environment, **configuration},
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        calls = []
        if self.record.exists():
            calls = [json.loads(line) for line in self.record.read_text(encoding="utf-8").splitlines()]
        return result, calls

    def output_calls(self):
        return [self.terraform("output", "-raw", name) for name in (
            "resource_group_name", "postgres_server_name", "staging_postgres_server_name"
        )]

    def terraform(self, *arguments):
        return {"tool": "terraform", "arguments": [f"-chdir={self.scripts}/../terraform", *arguments]}

    def azure(self, operation, database):
        return {"tool": "az", "arguments": [
            "postgres", "flexible-server", operation,
            "--resource-group", "selected-resource-group", "--name", database,
        ]}

    def test_resume_resolves_both_servers_and_starts_them_before_enabling_compute(self):
        result, calls = self.run_script("resume.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, self.output_calls() + [
            self.azure("start", "selected-production-db"),
            self.azure("start", "selected-staging-db"),
            self.terraform("apply", "-var=enable_compute=true"),
        ])
        self.assertIn("resume completed", result.stdout)

    def test_suspend_resolves_both_servers_before_disabling_compute_then_stops_them(self):
        result, calls = self.run_script("suspend.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, self.output_calls() + [
            self.terraform("apply", "-var=enable_compute=false"),
            self.azure("stop", "selected-production-db"),
            self.azure("stop", "selected-staging-db"),
        ])
        self.assertIn("suspend completed", result.stdout)

    def test_missing_state_output_fails_before_any_mutation(self):
        outputs = ("resource_group_name", "postgres_server_name", "staging_postgres_server_name")
        for script in ("resume.sh", "suspend.sh"):
            for index, output in enumerate(outputs):
                with self.subTest(script=script, output=output):
                    result, calls = self.run_script(script, FAIL_OUTPUT=output)
                    self.assertEqual(result.returncode, 23)
                    self.assertEqual(calls, self.output_calls()[:index + 1])
                    self.assertNotIn("completed", result.stdout)

    def test_empty_state_values_fail_before_mutations_even_when_terraform_succeeds(self):
        for script in ("resume.sh", "suspend.sh"):
            for output in ("resource_group_name", "postgres_server_name", "staging_postgres_server_name"):
                for value in ("", " \t "):
                    with self.subTest(script=script, output=output, value=value):
                        result, calls = self.run_script(script, EMPTY_OUTPUT=output, EMPTY_VALUE=value)
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn("empty resource group or database name", result.stderr)
                        self.assertEqual(calls, self.output_calls())
                        self.assertNotIn("completed", result.stdout)

    def test_resume_does_not_enable_compute_after_either_database_fails_to_start(self):
        for index, database in enumerate(("selected-production-db", "selected-staging-db")):
            with self.subTest(database=database):
                result, calls = self.run_script("resume.sh", FAIL_DATABASE=database)
                self.assertEqual(result.returncode, 31)
                self.assertEqual(calls, self.output_calls() + [
                    self.azure("start", name) for name in ("selected-production-db", "selected-staging-db")[:index + 1]
                ])
                self.assertNotIn("completed", result.stdout)

    def test_suspend_stops_on_apply_failure_without_stopping_any_database(self):
        result, calls = self.run_script("suspend.sh", FAIL_APPLY="yes")
        self.assertEqual(result.returncode, 29)
        self.assertEqual(calls, self.output_calls() + [self.terraform("apply", "-var=enable_compute=false")])
        self.assertNotIn("completed", result.stdout)

    def test_suspend_propagates_each_database_stop_failure_without_continuing(self):
        for index, database in enumerate(("selected-production-db", "selected-staging-db")):
            with self.subTest(database=database):
                result, calls = self.run_script("suspend.sh", FAIL_DATABASE=database)
                self.assertEqual(result.returncode, 31)
                self.assertEqual(calls, self.output_calls() + [self.terraform("apply", "-var=enable_compute=false")] + [
                    self.azure("stop", name) for name in ("selected-production-db", "selected-staging-db")[:index + 1]
                ])
                self.assertNotIn("completed", result.stdout)

    def test_resume_reports_failed_compute_apply_without_claiming_completion(self):
        result, calls = self.run_script("resume.sh", FAIL_APPLY="yes")
        self.assertEqual(result.returncode, 29)
        self.assertEqual(calls[-1], self.terraform("apply", "-var=enable_compute=true"))
        self.assertNotIn("completed", result.stdout)

    def test_missing_commands_fail_before_state_reads_or_cloud_mutations(self):
        for tool in ("terraform", "az"):
            with self.subTest(tool=tool):
                executable = self.tools / tool
                unavailable = self.tools / (tool + ".unavailable")
                executable.rename(unavailable)
                try:
                    for script in ("resume.sh", "suspend.sh"):
                        result, calls = self.run_script(script)
                        self.assertNotEqual(result.returncode, 0)
                        self.assertIn(f"Required command missing: {tool}", result.stderr)
                        self.assertEqual(calls, [])
                finally:
                    unavailable.rename(executable)


if __name__ == "__main__":
    unittest.main()
