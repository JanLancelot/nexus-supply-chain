"""Exercise the real E2E launcher against a disposable Maven process boundary."""

import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


class E2ELauncherTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="e2e launcher ")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "bin").mkdir()
        (self.root / "backend").mkdir()
        self.launcher = self.root / "bin" / "start-e2e-backend.sh"
        shutil.copy2(REPOSITORY_ROOT / "bin" / self.launcher.name, self.launcher)
        self.record = self.root / "maven-invocation.json"

        # The only substituted boundary is Maven. The copied launcher still
        # resolves its root, checks credentials, scrubs the environment and execs.
        recorder = self.root / "backend" / "record_invocation.py"
        recorder.write_text(textwrap.dedent("""\
            import json
            import os
            from pathlib import Path
            import sys

            Path(os.environ["LAUNCHER_RECORD_PATH"]).write_text(json.dumps({
                "cwd": os.getcwd(),
                "arguments": sys.argv[1:],
                "environment": dict(os.environ),
            }), encoding="utf-8")
            sys.exit(int(os.environ.get("FAKE_MAVEN_EXIT", "0")))
            """), encoding="utf-8")
        wrapper = self.root / "backend" / "mvnw"
        wrapper.write_text(
            f"#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(str(recorder))} \"$@\"\n",
            encoding="utf-8",
        )
        wrapper.chmod(0o755)
        self.credentials = {
            name: secrets.token_urlsafe(36)
            for name in ("E2E_ADMIN_PASSWORD", "E2E_STAFF_PASSWORD", "E2E_JWT_SECRET")
        }
        # Start from a controlled shell environment; never copy actual developer
        # credentials into the recording or allow them to affect these tests.
        self.environment = {
            "PATH": os.environ.get("PATH", os.defpath),
            "LAUNCHER_RECORD_PATH": str(self.record),
            **self.credentials,
        }

    def launch(self, environment=None):
        return subprocess.run(
            ["bash", str(self.launcher)],
            cwd=self.root,
            env=self.environment if environment is None else environment,
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )

    def invocation(self):
        return json.loads(self.record.read_text(encoding="utf-8"))

    def test_strips_ambient_datastore_broker_profile_and_jvm_configuration(self):
        injected = {
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://database.example.invalid/live",
            "SPRING_DATASOURCE_USERNAME": "ambient-database-user",
            "SPRING_DATA_REDIS_HOST": "redis.example.invalid",
            "SPRING_KAFKA_BOOTSTRAP_SERVERS": "kafka.example.invalid:9092",
            "SPRING_CONFIG_LOCATION": "file:/ambient/application.properties",
            "SPRING_APPLICATION_JSON": '{"spring":{"profiles":{"active":"live"}}}',
            "SPRING_PROFILES_ACTIVE": "live",
            "APP_SEED_DEMO_DATA": "false",
            "APP_BOOTSTRAP_ADMIN_EMAIL": "ambient@example.invalid",
            "JWT_SECRET": "synthetic-ambient-value",
            "SERVER_PORT": "8080",
            "MANAGEMENT_SERVER_PORT": "8081",
            "JAVA_TOOL_OPTIONS": "-Dspring.profiles.active=live",
            "JDK_JAVA_OPTIONS": "-Dspring.config.location=/ambient/override.properties",
            "_JAVA_OPTIONS": "-Dserver.port=8080",
            "MAVEN_OPTS": "-Dspring.datasource.url=jdbc:postgresql://database.example.invalid/live",
            "MAVEN_ARGS": "-Dspring-boot.run.profiles=live",
        }
        result = self.launch({**self.environment, **injected})
        self.assertEqual(result.returncode, 0, result.stderr)
        inherited = self.invocation()["environment"]
        for variable in injected:
            with self.subTest(variable=variable):
                self.assertNotIn(variable, inherited)
        for variable, value in self.credentials.items():
            self.assertEqual(inherited[variable], value)

    def test_launches_only_the_e2e_profile_and_preserves_unrelated_shell_settings(self):
        preserved = {
            "JAVA_HOME": "/selected test jdk",
            "MAVEN_USER_HOME": "/selected maven cache",
            "CUSTOM_SETTING": "a value with spaces, = signs, and ; punctuation",
        }
        result = self.launch({**self.environment, **preserved})
        self.assertEqual(result.returncode, 0, result.stderr)
        invocation = self.invocation()
        self.assertEqual(Path(invocation["cwd"]), (self.root / "backend").resolve())
        self.assertEqual(invocation["arguments"], [
            "-B",
            "-ntp",
            "spring-boot:test-run",
            "-Dspring-boot.run.main-class=com.pg.supplychain.DemoApplication",
            "-Dspring-boot.run.profiles=e2e",
        ])
        for variable, value in {**preserved, **self.environment}.items():
            with self.subTest(variable=variable):
                self.assertEqual(invocation["environment"][variable], value)

    def test_missing_or_empty_credentials_fail_before_maven_starts(self):
        for variable in self.credentials:
            for empty in (False, True):
                with self.subTest(variable=variable, empty=empty):
                    environment = dict(self.environment)
                    if empty:
                        environment[variable] = ""
                    else:
                        del environment[variable]
                    result = self.launch(environment)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(variable, result.stderr)
                    self.assertIn("generate temporary credentials", result.stderr)
                    self.assertFalse(self.record.exists(), "Maven must not start without every credential")

    def test_propagates_maven_failure_to_the_test_runner(self):
        result = self.launch({**self.environment, "FAKE_MAVEN_EXIT": "23"})
        self.assertTrue(self.record.exists())
        self.assertEqual(result.returncode, 23)


if __name__ == "__main__":
    unittest.main()
