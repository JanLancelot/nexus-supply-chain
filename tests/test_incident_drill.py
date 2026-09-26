"""The CLI must fail closed at remote-engine, notification and cleanup boundaries."""

import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("incident_drill", ROOT / "bin/run-incident-drill.py")
DRILL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DRILL)


class IncidentDrillTests(unittest.TestCase):
    def test_remote_engines_are_refused_before_any_mutation(self):
        for endpoint in ("tcp://live:2376", "ssh://operator@live", "npipe:////./pipe/docker"):
            with self.subTest(endpoint=endpoint), patch.object(DRILL.subprocess, "run") as run:
                with self.assertRaisesRegex(ValueError, "local Docker"):
                    DRILL.local_docker({"DOCKER_HOST": endpoint})
                run.assert_not_called()

    def test_context_is_checked_and_local_endpoint_is_pinned(self):
        environment = {"DOCKER_CONTEXT": "desktop-linux", "DOCKER_HOST": "tcp://unused:2376"}
        result = subprocess.CompletedProcess([], 0, json.dumps([
            {"Endpoints": {"docker": {"Host": "unix:///private/test/docker.sock"}}}]))
        with patch.object(DRILL.subprocess, "run", return_value=result):
            self.assertEqual(DRILL.local_docker(environment), ["docker", "--host", "unix:///private/test/docker.sock"])
        self.assertNotIn("DOCKER_CONTEXT", environment)
        self.assertNotIn("DOCKER_HOST", environment)

    def test_remote_selected_context_is_refused_even_with_local_host_variable(self):
        result = subprocess.CompletedProcess([], 0, json.dumps([
            {"Endpoints": {"docker": {"Host": "ssh://operator@production"}}}]))
        with patch.object(DRILL.subprocess, "run", return_value=result), self.assertRaises(ValueError):
            DRILL.local_docker({"DOCKER_CONTEXT": "production", "DOCKER_HOST": "unix:///test.sock"})

    def test_cli_does_not_accept_existing_projects_or_targets(self):
        for args in (["--project", "production"], ["--target", "https://live.example.test"],
                     ["--scenario", "arbitrary-service"]):
            with self.subTest(args=args), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as raised:
                    DRILL.main(args)
                self.assertEqual(raised.exception.code, 2)

    def test_disposable_config_preserves_timings_and_replaces_notification_destinations(self):
        source = {"services": {
            "backend": {"ports": ["8080:8080"]}, "db": {}, "grafana": {}, "grafana-db": {},
            "prometheus": {"volumes": [{"target": "/etc/prometheus", "source": "/configured"}]},
            "alertmanager": {"volumes": [{"target": "/etc/alertmanager", "source": "/live-receiver"}]},
        }, "networks": {"notification_egress": {}, "development_access": {}},
            "volumes": {"pg_data": {"name": "production-data"}}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = DRILL.prepare_config(copy.deepcopy(source), "nexus-drill-test", root)
            self.assertNotIn("grafana", config["services"])
            self.assertNotIn("grafana-db", config["services"])
            self.assertEqual(config["volumes"]["pg_data"]["name"], "nexus-drill-test_pg_data")
            self.assertTrue(config["networks"]["notification_egress"]["internal"])
            self.assertEqual((root / "alertmanager/alertmanager.yml").read_bytes(),
                             (ROOT / "docker/alertmanager/alertmanager.example.yml").read_bytes())
            self.assertEqual((root / "prometheus/rules/nexus.yml").read_bytes(),
                             (ROOT / "docker/prometheus/rules/nexus.yml").read_bytes())
            for endpoint in ("operator", "watchdog"):
                self.assertEqual((root / f"alertmanager/{endpoint}-webhook-url").read_text(),
                                 f"http://receiver:8080/{endpoint}\n")
            self.assertEqual(json.loads((root / "prometheus/alertmanagers.json").read_text()),
                             [{"targets": ["alertmanager:9093"]}])
            for service in config["services"].values():
                self.assertEqual(service["labels"]["io.nexus.disposable-drill"], "true")
                for port in service.get("ports", []):
                    self.assertEqual(port["host_ip"], "127.0.0.1")
                    self.assertEqual(port["published"], "0")

    def test_delivery_requires_correct_route_alert_status_and_component(self):
        event = {"path": "/operator", "received_at": "2026-09-26T00:00:00Z", "payload": {"alerts": [
            {"labels": {"alertname": "NexusSnapshotFailed", "component": "business"},
             "status": "firing", "annotations": {"secret": "must not be copied"}}]}}
        expected = {"received_at": event["received_at"], "status": "firing"}
        self.assertEqual(DRILL.matching_delivery([event], DRILL.SCENARIOS["database"], "firing"), expected)
        self.assertIsNone(DRILL.matching_delivery([event], DRILL.SCENARIOS["backend"], "firing"))
        self.assertIsNone(DRILL.matching_delivery([event], DRILL.SCENARIOS["database"], "resolved"))
        event["payload"]["alerts"][0]["labels"]["component"] = "dependencies"
        self.assertIsNone(DRILL.matching_delivery([event], DRILL.SCENARIOS["database"], "firing"))
        event["path"] = "/watchdog"
        self.assertIsNone(DRILL.matching_delivery([event], DRILL.SCENARIOS["database"], "firing"))

    def test_inventory_recovery_checks_application_response_contract(self):
        self.assertTrue(DRILL.inventory_present({"content": [{"id": "seeded"}], "totalElements": 1}))
        for body in ({"content": [], "totalElements": 0}, {"status": "UP"}, [], b"ok"):
            self.assertFalse(DRILL.inventory_present(body))

    def test_report_intervals_measure_individual_drills_and_private_files(self):
        timeline = DRILL.Timeline("backend")
        timeline.result["events"] = [{"event": name, "at": "2026-09-26T00:00:00Z", "elapsed_seconds": elapsed}
                                     for name, elapsed in (("fault_active", 2), ("signal_detected", 15),
                                                           ("notification_observed", 166), ("recovery_started", 167),
                                                           ("user_operation_restored", 201))]
        scenario = timeline.finish()
        self.assertEqual(scenario["intervals_seconds"]["detection_from_fault"], 13)
        self.assertEqual(scenario["intervals_seconds"]["user_recovery_from_action"], 34)
        self.assertNotIn("resolution_notice_from_user_recovery", scenario["intervals_seconds"])
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report"
            DRILL.write_report(path, {"status": "failed", "started_at": "now", "commit": "abc",
                                      "cleanup": "passed", "scenarios": [scenario]})
            self.assertEqual((path / "report.json").stat().st_mode & 0o777, 0o600)
            self.assertEqual(path.stat().st_mode & 0o777, 0o700)
            self.assertIn("not production MTTR", (path / "report.md").read_text())

    def test_cleanup_is_attempted_on_startup_failure_and_interrupt_and_report_excludes_command_output(self):
        for failure in (subprocess.CalledProcessError(1, ["secret-command"], stderr="private-output"), KeyboardInterrupt()):
            calls, reports = [], []
            def run(command, **_kwargs):
                calls.append(command)
                if "up" in command:
                    raise failure
                output = "{}" if "config" in command else ("" if "ls" in command else "abc123\n")
                return subprocess.CompletedProcess(command, 0, output, "")
            with self.subTest(failure=type(failure).__name__), patch.object(DRILL.subprocess, "run", side_effect=run), \
                    patch.object(DRILL, "local_docker", return_value=["docker", "--host", "unix:///test.sock"]), \
                    patch.object(DRILL, "prepare_config", return_value={}), \
                    patch.object(DRILL, "write_report", side_effect=lambda _path, report: reports.append(report)), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as stderr:
                code = DRILL.main(["--scenario", "backend"])
            self.assertIn(code, (1, 130))
            cleanup = [call for call in calls if "down" in call]
            self.assertEqual(len(cleanup), 1)
            self.assertIn("--volumes", cleanup[0])
            self.assertIn("--project-name", cleanup[0])
            self.assertTrue(cleanup[0][cleanup[0].index("--project-name") + 1].startswith("nexus-drill-"))
            self.assertEqual(reports[0]["cleanup"], "passed")
            self.assertNotIn("private-output", json.dumps(reports) + stderr.getvalue())
            self.assertNotIn("secret-command", json.dumps(reports) + stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
