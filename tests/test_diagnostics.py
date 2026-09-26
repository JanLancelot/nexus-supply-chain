"""Exercise diagnostic reports at local process and HTTP boundaries."""

from contextlib import contextmanager
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("diagnose", Path(__file__).resolve().parents[1] / "bin/diagnose.py")
DIAGNOSE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DIAGNOSE)
BENCHMARK_SPEC = importlib.util.spec_from_file_location("benchmark_diagnostics", Path(DIAGNOSE.__file__).with_name("benchmark-diagnostics.py"))
BENCHMARK = importlib.util.module_from_spec(BENCHMARK_SPEC)
BENCHMARK_SPEC.loader.exec_module(BENCHMARK)


@contextmanager
def prometheus(mode="normal", secret=""):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if mode == "redirect":
                self.send_response(302)
                self.send_header("Location", "http://example.invalid/private")
                self.end_headers()
                return
            if mode == "oversized":
                body = b"x" * (DIAGNOSE.MAX_BYTES + 2)
            elif mode == "invalid":
                body = b'{"status":"success","data":null}'
            else:
                if self.path.startswith("/api/v1/targets"):
                    data = {"activeTargets": [{"labels": {"job": job, "private": secret},
                        "health": "down" if job == "spring-backend" else "up",
                        "lastError": secret if job == "spring-backend" else "", "scrapeUrl": secret}
                        for job in DIAGNOSE.JOBS]}
                elif self.path.startswith("/api/v1/alerts"):
                    data = {"alerts": [{"labels": {"alertname": "NexusBackendDown", "severity": "critical", "customer": secret},
                        "state": "firing", "annotations": {"description": secret}},
                        {"labels": {"alertname": secret}, "state": "firing"}]}
                else:
                    data = {"resultType": "vector", "result": [] if mode == "missing" else [
                        {"metric": {"private": secret}, "value": [123, "NaN" if mode == "nonfinite" else "3"]}]}
                body = json.dumps({"status": "success", "data": data}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server.server_port
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


def fake_docker(directory, port, secret, *, endpoint="unix:///test.sock", host_ip="127.0.0.1", label="nexus-local", log_failure=False, disposable=None, service_names=None):
    executable = directory / "docker"
    payload = {"port": port, "secret": secret, "endpoint": endpoint, "host_ip": host_ip, "project": label,
               "services": list(service_names or DIAGNOSE.SERVICES), "log_failure": log_failure, "disposable": disposable}
    executable.write_text(f'''#!{sys.executable}
import json, sys
config = {payload!r}
args = sys.argv[1:]
if args[:2] == ["context", "inspect"]:
    print(config["endpoint"])
elif args[:1] != ["--host"]:
    sys.exit(9)
elif args[2] == "ps":
    print("\\n".join(format(i+1, "012x") for i in range(len(config["services"]))))
elif args[2] == "inspect":
    service = config["services"][int(args[-1], 16) - 1]
    print(json.dumps({{"disposable": config["disposable"], "project": config["project"], "service": service, "state": "running", "health": "healthy",
        "oom_killed": False, "exit_code": 0, "restarts": 0,
        "ports": [{{"HostIp": config["host_ip"], "HostPort": str(config["port"])}}],
        "extra_private": config["secret"]}}))
elif args[2] == "logs":
    print("ERROR customer=" + config["secret"])
    print("INFO Authorization: Bearer " + config["secret"], file=sys.stderr)
    if config["log_failure"]:
        sys.exit(1)
else:
    sys.exit(9)
''')
    executable.chmod(0o700)


class DiagnosticTests(unittest.TestCase):
    def cli(self, directory, *extra):
        environment = {key: value for key, value in os.environ.items() if not key.startswith("DOCKER_")}
        environment["PATH"] = str(directory) + os.pathsep + environment.get("PATH", "")
        return subprocess.run([sys.executable, str(Path(DIAGNOSE.__file__)), "--project", "nexus-local",
            "--output-dir", str(directory / "reports"), *extra], env=environment, capture_output=True, text=True, timeout=20)

    def test_complete_cli_keeps_health_evidence_and_never_exports_raw_source_text(self):
        private = secrets.token_urlsafe(32) + " customer@example.test /orders/123"
        with tempfile.TemporaryDirectory() as temporary, prometheus(secret=private) as port:
            directory = Path(temporary).resolve()
            fake_docker(directory, port, private)
            result = self.cli(directory)
            self.assertEqual(result.returncode, 0, result.stderr)
            report_path = next((directory / "reports").glob("*/report.json"))
            report = json.loads(report_path.read_text())
            self.assertEqual(report["collection_status"], "complete")
            self.assertEqual(report["sections"]["targets"]["data"]["instances"][0]["health"], "down")
            self.assertEqual(report["sections"]["alerts"]["data"]["instances"][0]["state"], "firing")
            self.assertEqual(report["sections"]["alerts"]["data"]["other_alerts_omitted"], 1)
            self.assertEqual(report["sections"]["logs"]["data"]["instances"][0]["levels"], {"ERROR": 1, "INFO": 1})
            for path in report_path.parent.iterdir():
                self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
                self.assertNotIn(private, path.read_text())
                self.assertNotIn("customer@example.test", path.read_text())
            self.assertEqual(stat.S_IMODE(report_path.parent.stat().st_mode), 0o700)
            self.assertNotIn(private, result.stdout + result.stderr)

    def test_partial_cli_keeps_other_evidence_when_metrics_and_logs_fail(self):
        with tempfile.TemporaryDirectory() as temporary, prometheus(mode="missing") as port:
            directory = Path(temporary).resolve()
            fake_docker(directory, port, "", log_failure=True)
            result = self.cli(directory)
            self.assertEqual(result.returncode, 1, result.stderr)
            report = json.loads(next((directory / "reports").glob("*/report.json")).read_text())
            self.assertEqual(report["sections"]["services"]["status"], "complete")
            self.assertEqual(report["sections"]["metrics"]["status"], "partial")
            self.assertEqual(report["sections"]["logs"]["status"], "partial")
            self.assertEqual(report["sections"]["metrics"]["data"]["database_connections_active"],
                             {"error": "metric_missing_or_ambiguous"})

    def test_unavailable_prometheus_does_not_hide_docker_health(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            fake_docker(directory, 1, "", host_ip="0.0.0.0")
            result = self.cli(directory)
            self.assertEqual(result.returncode, 1)
            report = json.loads(next((directory / "reports").glob("*/report.json")).read_text())
            self.assertEqual(report["sections"]["targets"]["error"], "prometheus_loopback_binding_required")
            self.assertEqual(report["sections"]["services"]["status"], "complete")

    def test_remote_daemon_and_wrong_project_labels_refused_before_collection(self):
        for options, code in (({"endpoint": "ssh://production"}, "local_unix_docker_required"),
                              ({"label": "different-project"}, "project_label_mismatch")):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary).resolve()
                fake_docker(directory, 1, "", **options)
                result = self.cli(directory)
                self.assertEqual(result.returncode, 2)
                self.assertIn(code, result.stderr)
                self.assertFalse((directory / "reports").exists())

    def test_drill_profile_requires_disposable_labels_and_its_six_expected_services(self):
        project = "nexus-drill-0123456789ab"
        for marker in (None, "true"):
            with self.subTest(marker=marker), tempfile.TemporaryDirectory() as temporary, prometheus() as port:
                directory = Path(temporary).resolve()
                fake_docker(directory, port, "", label=project, disposable=marker,
                            service_names=[name for name in DIAGNOSE.SERVICES if name not in ("grafana", "grafana-db")])
                result = self.cli(directory, "--project", project)
                self.assertEqual(result.returncode, 0 if marker else 2, result.stderr)
                if marker:
                    report = json.loads(next((directory / "reports").glob("*/report.json")).read_text())
                    self.assertEqual(report["sections"]["services"]["data"]["missing_services"], [])
                else:
                    self.assertIn("disposable_drill_label_required", result.stderr)

    def test_project_is_required_and_not_arbitrary(self):
        for project in ("production", "nexus-local; touch /tmp/no", "-nexus-local", "nexus-local\n"):
            with self.subTest(project=project), self.assertRaisesRegex(DIAGNOSE.CollectionError, "unsupported_project"):
                DIAGNOSE.collect(project)
        for project in ("nexus-local", "nexus-supply-chain", "nexus-drill-0123456789ab", "nexus-monitoring-test-abcdef0123"):
            self.assertTrue(DIAGNOSE.valid_project(project))

    def test_nonfinite_metrics_and_oversized_http_responses_are_explicit_errors(self):
        with prometheus(mode="nonfinite") as port:
            result = DIAGNOSE.metrics(f"http://127.0.0.1:{port}")
            self.assertTrue(all(row == {"error": "metric_not_finite"} for row in result.values()))
        with prometheus(mode="oversized") as port, self.assertRaisesRegex(DIAGNOSE.CollectionError, "output_limit"):
            DIAGNOSE.request(f"http://127.0.0.1:{port}", "/api/v1/alerts")

    def test_redirects_refused_even_when_destination_is_loopback(self):
        with prometheus(mode="redirect") as port, self.assertRaisesRegex(DIAGNOSE.CollectionError, "http_redirect_refused"):
            DIAGNOSE.request(f"http://127.0.0.1:{port}", "/api/v1/alerts")

    def test_malformed_response_does_not_crash_cli(self):
        with tempfile.TemporaryDirectory() as temporary, prometheus(mode="invalid") as port:
            directory = Path(temporary).resolve()
            fake_docker(directory, port, "")
            result = self.cli(directory)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertNotIn("Traceback", result.stderr)

    def test_command_deadline_and_output_limits_include_stderr(self):
        started = time.monotonic()
        with self.assertRaisesRegex(DIAGNOSE.CollectionError, "command_timeout"):
            DIAGNOSE.command([sys.executable, "-c", "import time; time.sleep(5)"], timeout=0.05)
        self.assertLess(time.monotonic() - started, 2)
        for stream in ("stdout", "stderr"):
            with self.subTest(stream=stream), self.assertRaisesRegex(DIAGNOSE.CollectionError, "output_limit"):
                DIAGNOSE.command([sys.executable, "-c", f"import sys; sys.{stream}.write('x'*100000)"], limit=1000)

    def test_no_environment_override_or_proxy_is_used_for_http(self):
        with prometheus() as port, patch.dict(os.environ, {"HTTP_PROXY": "http://127.0.0.1:1", "NO_PROXY": ""}):
            result = DIAGNOSE.request(f"http://127.0.0.1:{port}", "/api/v1/alerts")
            self.assertTrue(result["alerts"])
        with patch.dict(os.environ, {"DOCKER_CONTEXT": "remote", "DOCKER_HOST": "unix:///local.sock"}):
            with self.assertRaisesRegex(DIAGNOSE.CollectionError, "ambiguous_docker_context"):
                DIAGNOSE.local_docker()

    def test_symlink_report_destination_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary).resolve()
            (directory / "alias").symlink_to(directory, target_is_directory=True)
            with self.assertRaisesRegex(DIAGNOSE.CollectionError, "symlink_output_refused"):
                DIAGNOSE.write_report({}, directory / "alias" / "reports")

    def test_comparison_runs_equivalent_sections_and_retains_trials_without_human_claims(self):
        with tempfile.TemporaryDirectory() as temporary, prometheus() as port:
            directory = Path(temporary).resolve()
            fake_docker(directory, port, "")
            environment = {key: value for key, value in os.environ.items() if not key.startswith("DOCKER_")}
            environment["PATH"] = str(directory) + os.pathsep + environment.get("PATH", "")
            result = subprocess.run([sys.executable, str(Path(BENCHMARK.__file__)), "--project", "nexus-local",
                "--iterations", "3", "--output-dir", str(directory / "comparisons")],
                env=environment, capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            report_path = next((directory / "comparisons").glob("*/benchmark.json"))
            report = json.loads(report_path.read_text())
            self.assertEqual(report["measurement"], "machine_elapsed_collection_only")
            self.assertEqual(len(report["trials"]), 3)
            self.assertEqual(report["trials"][0]["order"], ["separate", "combined"])
            self.assertEqual(report["trials"][1]["order"], ["combined", "separate"])
            self.assertEqual(len(list(report_path.parent.rglob("report.json"))), 18)
            self.assertEqual(report["trials"][0]["separate"]["invocations"], 5)
            self.assertEqual(report["trials"][0]["combined"]["invocations"], 1)
            self.assertEqual(stat.S_IMODE(report_path.stat().st_mode), 0o600)
            for trial in (report_path.parent / "trial-1",):
                separate = [json.loads(path.read_text())["sections"] for path in (trial / "separate").glob("*/report.json")]
                combined = json.loads(next((trial / "combined").glob("*/report.json")).read_text())["sections"]
                self.assertEqual({key: value for section in separate for key, value in section.items()}, combined)

    def test_benchmark_propagates_partial_child_results(self):
        with patch.object(BENCHMARK.subprocess, "run", return_value=subprocess.CompletedProcess([], 1)):
            result = BENCHMARK.measure("nexus-local", Path("unused"), separate=True)
            self.assertEqual(result["collection_status"], "partial")
            self.assertEqual(result["exit_codes"], [1] * 5)

    def test_log_and_state_projection_handles_unrecognized_values_without_echo(self):
        private = secrets.token_urlsafe(32)
        self.assertEqual(DIAGNOSE.summarize_logs(f"{private}\nwarn token={private}\n"),
                         {"lines": 2, "levels": {"WARN": 1, "unclassified": 1}})
        projected = DIAGNOSE.services([("irrelevant", {"service": "backend", "health": private,
            "state": private, "exit_code": private, "restarts": private})])
        self.assertNotIn(private, json.dumps(projected))


if __name__ == "__main__":
    unittest.main()
