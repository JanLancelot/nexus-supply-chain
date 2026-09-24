"""Public configuration and backup boundaries; no external service calls."""
import importlib.util
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("alerting_setup", ROOT / "bin/configure-alerting.py")
SETUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SETUP)
OPERATOR = "https://uptime.betterstack.com/api/v1/prometheus/webhook/test-token"
WATCHDOG = "https://uptime.betterstack.com/api/v1/heartbeat/test-token"


class AlertingSetupTests(unittest.TestCase):
    def test_email_mode_uses_private_password_file_and_keeps_tls_enabled(self):
        with tempfile.TemporaryDirectory() as root:
            directory = SETUP.configure_email(Path(root) / ".secrets", "smtp.example.test:587",
                "sender@example.test", "operator@example.test", 'user"name', "test-password", WATCHDOG)
            config = (directory / "alertmanager.yml").read_text()
            self.assertIn('auth_username: "user\\"name"', config)
            self.assertIn("require_tls: true", config)
            self.assertIn("send_resolved: true", config)
            self.assertNotIn("test-password", config)
            self.assertNotIn("operator-webhook-url", config)
            self.assertEqual((directory / "smtp-password").read_text().strip(), "test-password")
            self.assertEqual(stat.S_IMODE((directory / "smtp-password").stat().st_mode), 0o444)

    def test_email_mode_rejects_header_injection_and_incompatible_smtp_port_before_writing(self):
        for host, recipient, password in (("smtp.example.test:465", "ops@example.test", "secret"),
                                         ("smtp.example.test:587", "ops@example.test\nBcc: other@example.test", "secret"),
                                         ("smtp.example.test:587", "ops@example.test", "")):
            with tempfile.TemporaryDirectory() as root:
                parent = Path(root) / ".secrets"
                with self.assertRaises(ValueError):
                    SETUP.configure_email(parent, host, "sender@example.test", recipient, "user", password, WATCHDOG)
                self.assertFalse(parent.exists())

    def test_private_configuration_is_readable_in_container_and_never_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            parent = Path(root) / ".secrets"
            directory = SETUP.configure(parent, OPERATOR, WATCHDOG)
            self.assertEqual(stat.S_IMODE(parent.stat().st_mode), 0o700)
            self.assertEqual(stat.S_IMODE(directory.stat().st_mode), 0o755)
            self.assertEqual((directory / "operator-webhook-url").read_text().strip(), OPERATOR)
            self.assertEqual(stat.S_IMODE((directory / "watchdog-webhook-url").stat().st_mode), 0o444)
            with self.assertRaises(ValueError):
                SETUP.configure(parent, OPERATOR, WATCHDOG)

    def test_rejects_wrong_provider_protocol_kind_and_failure_endpoint(self):
        for url in (WATCHDOG.replace("https:", "http:"), WATCHDOG.replace(".com", ".com.evil.test"),
                    WATCHDOG + "/fail", WATCHDOG + "?token=secret", WATCHDOG + "#fragment",
                    WATCHDOG.replace("https://", "https://user:password@"), OPERATOR,
                    WATCHDOG.replace("test-token", "")):
            with self.subTest(url=url), self.assertRaises(ValueError):
                SETUP.validate_url(url, "watchdog")

    def test_invalid_configuration_creates_no_files(self):
        with tempfile.TemporaryDirectory() as root:
            parent = Path(root) / ".secrets"
            with self.assertRaises(ValueError):
                SETUP.configure(parent, OPERATOR, "invalid")
            self.assertFalse(parent.exists())

    def test_backup_is_private_atomic_and_preserves_existing_destination(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            docker = root / "docker"
            docker.write_text('#!/bin/sh\nprintf "PGDMP-test"\nexit "${FAKE_DOCKER_STATUS:-0}"\n')
            docker.chmod(0o700)
            env = {**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"]}
            destination = root / "backup.dump"
            command = ["bash", str(ROOT / "bin/backup-grafana.sh"), str(destination)]
            subprocess.run(command, env=env, check=True, capture_output=True)
            self.assertEqual(stat.S_IMODE(destination.stat().st_mode), 0o600)
            self.assertEqual(destination.read_bytes(), b"PGDMP-test")
            self.assertEqual(subprocess.run(command, env=env, capture_output=True).returncode, 2)
            self.assertEqual(destination.read_bytes(), b"PGDMP-test")
            destination.unlink()
            failed = subprocess.run(command, env={**env, "FAKE_DOCKER_STATUS": "1"}, capture_output=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertFalse(destination.exists())
            self.assertEqual(list(root.glob("*.partial.*")), [])
