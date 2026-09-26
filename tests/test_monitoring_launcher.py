"""Guard isolation at the Compose/environment boundary of the monitoring smoke test."""

import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "monitoring_launcher", Path(__file__).resolve().parents[1] / "bin/verify-monitoring.py")
LAUNCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LAUNCHER)


class MonitoringLauncherTests(unittest.TestCase):
    def test_ambient_live_configuration_cannot_select_resources_or_accounts(self):
        ambient_password = secrets.token_urlsafe(24)
        ambient_grafana_password = secrets.token_urlsafe(24)
        ambient_jwt_secret = secrets.token_urlsafe(32)
        environment = LAUNCHER.isolated_environment({
            "PATH": "/test/bin", "DOCKER_HOST": "unix:///test/docker.sock",
            "COMPOSE_FILE": "/live/compose.yml", "COMPOSE_PROJECT_NAME": "production",
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://live/database",
            "APP_BOOTSTRAP_ADMIN_PASSWORD": ambient_password,
            "APP_MONITORING_GRAFANA_URL": "https://live.example.test",
            "GF_SECURITY_ADMIN_PASSWORD": ambient_grafana_password, "JWT_SECRET": ambient_jwt_secret,
            "MANAGEMENT_SERVER_PORT": "8080", "PROMETHEUS_URL": "https://live.example.test",
            "ALERTMANAGER_CONFIG_DIR": "/live/receivers",
        })
        for key in ("COMPOSE_FILE", "COMPOSE_PROJECT_NAME", "SPRING_DATASOURCE_URL",
                    "GF_SECURITY_ADMIN_PASSWORD", "MANAGEMENT_SERVER_PORT", "PROMETHEUS_URL", "ALERTMANAGER_CONFIG_DIR"):
            self.assertNotIn(key, environment)
        self.assertEqual(environment["PATH"], "/test/bin")
        self.assertEqual(environment["DOCKER_HOST"], "unix:///test/docker.sock")
        self.assertNotEqual(environment["APP_BOOTSTRAP_ADMIN_PASSWORD"], ambient_password)
        self.assertNotEqual(environment["GRAFANA_ADMIN_PASSWORD"], ambient_grafana_password)
        self.assertNotEqual(environment["JWT_SECRET"], ambient_jwt_secret)
        self.assertEqual(environment["APP_MONITORING_GRAFANA_URL"], "http://localhost:3000")
        self.assertGreaterEqual(len(environment["JWT_SECRET"]), 32)
        self.assertNotEqual(environment["JWT_SECRET"], LAUNCHER.isolated_environment({})["JWT_SECRET"])

    def test_baseline_cannot_be_requested_in_config_only_mode(self):
        result = subprocess.run([sys.executable, str(LAUNCHER.ROOT / "bin/verify-monitoring.py"),
                                 "--config-only", "--baseline-report", "/unused/report.json"],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertIn("requires the live stack", result.stderr)

    def test_existing_baseline_report_is_refused_before_starting_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            report.write_text("existing evidence")
            result = subprocess.run([sys.executable, str(LAUNCHER.ROOT / "bin/verify-monitoring.py"),
                                     "--baseline-report", str(report)],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 2)
            self.assertIn("new file", result.stderr)
            self.assertEqual(report.read_text(), "existing evidence")

    def test_resolved_compose_gets_exclusive_resources_and_random_loopback_ports(self):
        source = {
            "name": "nexus-supply-chain",
            "services": {
                "backend": {"build": {"context": "/repo"}, "image": "shared-backend",
                            "ports": [{"target": 8080, "published": "80"}], "restart": "unless-stopped"},
                "db": {"container_name": "shared-db", "image": "postgres:15", "ports": ["5432:5432"]},
                "grafana": {"image": "grafana/grafana:13.2.2", "ports": ["3000:3000"]},
                "prometheus": {"image": "prom/prometheus:v3.13.3", "ports": ["9090:9090"]},
            },
            "volumes": {"pg_data": {"name": "production_pg_data"}},
            "networks": {"default": {"name": "production_default", "internal": True}},
        }
        result = LAUNCHER.isolate_config(source, "test-unique")
        self.assertNotIn("name", result)
        self.assertNotIn("image", result["services"]["backend"])
        self.assertEqual(result["services"]["db"]["image"], "postgres:15")
        self.assertEqual(result["services"]["db"]["ports"], [])
        for name, service in result["services"].items():
            self.assertEqual(service["container_name"], f"test-unique-{name}")
            self.assertEqual(service["restart"], "no")
            for port in service["ports"]:
                self.assertEqual(port["host_ip"], "127.0.0.1")
                self.assertEqual(port["published"], "0")
        self.assertEqual(result["volumes"]["pg_data"]["name"], "test-unique_pg_data")
        self.assertEqual(result["networks"]["default"]["name"], "test-unique_default")
        self.assertTrue(result["networks"]["default"]["internal"])

    def test_external_resources_are_refused_before_startup(self):
        for category in ("volumes", "networks"):
            with self.subTest(category=category), self.assertRaises(ValueError):
                LAUNCHER.isolate_config({"services": {}, category: {"shared": {"external": True}}}, "test")

    def test_configured_notification_receivers_are_not_used_or_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "configured"
            source.mkdir()
            receiver = '[{"targets": ["production-alerts.internal:9093"]}]'
            (source / "alertmanagers.json").write_text(receiver)
            (source / "prometheus.yml").write_text("scrape_configs: []")
            mount = {"source": str(source), "target": "/etc/prometheus", "read_only": True}
            config = {"services": {"prometheus": {"volumes": [mount]}}}
            destination = root / "isolated"
            LAUNCHER.isolate_prometheus(config, source, destination)
            self.assertEqual(json.loads((destination / "alertmanagers.json").read_text()), [{"targets": ["alertmanager:9093"]}])
            self.assertEqual((source / "alertmanagers.json").read_text(), receiver)
            self.assertEqual(mount["source"], str(destination))
            self.assertTrue(mount["read_only"])
            self.assertTrue((destination / "prometheus.yml").is_file())


if __name__ == "__main__":
    unittest.main()
