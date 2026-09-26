"""Verify workload classification and safety without loading an application."""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch
import urllib.request

spec = importlib.util.spec_from_file_location("slo_baseline", Path(__file__).with_name("slo-baseline.py"))
baseline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(baseline)
PROJECT = "nexus-monitoring-test-0123456789"
URL = "http://127.0.0.1:43210"


def container():
    return {"Config": {"Labels": {"com.docker.compose.project": PROJECT,
                                    "com.docker.compose.service": "backend"},
                       "Env": ["APP_MONITORING_ENVIRONMENT=monitoring-test", "APP_SEED_DEMO_DATA=true"]},
            "State": {"Running": True},
            "NetworkSettings": {"Ports": {"8080/tcp": [{"HostIp": "127.0.0.1", "HostPort": "43210"}]}}}


def sample(status, elapsed=10, contract=True):
    return {"status": status, "elapsed_ms": elapsed, "contract_success": contract}


class BaselineSafetyTests(unittest.TestCase):
    def test_only_verified_disposable_loopback_target_is_accepted(self):
        result = subprocess.CompletedProcess([], 0, json.dumps([container()]))
        with patch.object(baseline.subprocess, "run", return_value=result) as inspect:
            self.assertEqual(baseline.verify_target(URL, PROJECT), URL)
            inspect.assert_called_once()
        for url, project in [("https://production.example.test", PROJECT), (URL, "nexus-local"),
                             (URL + "/api", PROJECT), (URL + "?token=value", PROJECT),
                             ("http://user@127.0.0.1:43210", PROJECT)]:
            with self.subTest(url=url, project=project), patch.object(baseline.subprocess, "run") as inspect:
                with self.assertRaises(ValueError):
                    baseline.verify_target(url, project)
                inspect.assert_not_called()

    def test_port_project_and_demo_marker_must_match_before_requests(self):
        for mutation in (lambda item: item["Config"]["Labels"].update({"com.docker.compose.project": "nexus-local"}),
                         lambda item: item["State"].update({"Running": False}),
                         lambda item: item["Config"].update({"Env": []}),
                         lambda item: item["NetworkSettings"]["Ports"].update({"8080/tcp": []})):
            state = container()
            mutation(state)
            result = subprocess.CompletedProcess([], 0, json.dumps([state]))
            with patch.object(baseline.subprocess, "run", return_value=result), self.assertRaises(ValueError):
                baseline.verify_target(URL, PROJECT)

    def test_redirects_never_forward_credentials(self):
        request = urllib.request.Request(URL, headers={"Authorization": "Bearer " + secrets.token_urlsafe(24)})
        redirect = baseline.NoRedirect().redirect_request(request, None, 302, "", {}, "https://example.test")
        self.assertIsNone(redirect)

    def test_status_classes_do_not_hide_client_transport_or_rate_limit_failures(self):
        data = baseline.summarize([sample(200, 500), sample(201, 501), sample(503, contract=False),
                                   sample(429, contract=False), sample(401, contract=False),
                                   sample(302, contract=False), sample(0, contract=False)], 8, 1)
        self.assertEqual(data["eligible_server_responses"], 4)
        self.assertEqual(data["server_response_availability_fraction"], 0.5)
        self.assertEqual(data["client_success_within_500ms_fraction"], 0.25)
        self.assertEqual(data["end_to_end_contract_success_fraction"], 0.25)
        self.assertEqual(data["excluded_http_responses"], 2)
        self.assertEqual(data["transport_failures"], 1)
        self.assertEqual(data["dropped_arrivals"], 1)

    def test_no_eligible_responses_are_unknown_not_perfect(self):
        data = baseline.summarize([sample(401, contract=False)], 1, 0)
        self.assertIsNone(data["server_response_availability_fraction"])
        self.assertIsNone(data["client_success_within_500ms_fraction"])
        self.assertIsNone(baseline.summarize([], 0, 0)["client_duration_ms"]["p99"])

    def test_order_uses_active_product_warehouse_and_active_supplier(self):
        responses = iter([
            (sample(200), {"token": secrets.token_urlsafe(24)}),
            (sample(200), [{"id": "inactive-supplier", "active": False}, {"id": "supplier", "active": True}]),
            (sample(200), [{"id": "warehouse"}]),
            (sample(200), {"content": [
                {"id": "bad", "isActive": True, "warehouseId": "missing"},
                {"id": "good", "isActive": True, "warehouseId": "warehouse"}]}),
        ])
        with patch.object(baseline, "request", side_effect=lambda *args, **kwargs: next(responses)):
            _, order = baseline.fixture(URL, "test@example.test", secrets.token_urlsafe(24))
        self.assertEqual(order, {"supplierId": "supplier", "warehouseId": "warehouse",
                                 "items": [{"productId": "good", "quantity": 1}]})

    def test_unexpected_success_body_fails_workload_contract(self):
        with patch.object(baseline, "request", return_value=(sample(200), {"error": "unavailable"})):
            self.assertFalse(baseline.perform(URL, "token", {}, "inventory_read")["contract_success"])

    def test_slow_requests_drop_arrivals_instead_of_queueing_unbounded_work(self):
        def slow(*args):
            time.sleep(0.5)
            return sample(200)

        with patch.object(baseline, "perform", side_effect=slow):
            data = baseline.run_workload(URL, "token", {}, 0.2, {"inventory_read": 10}, workers=1)
        self.assertEqual(data["inventory_read"]["scheduled"], 2)
        self.assertEqual(data["inventory_read"]["dropped_arrivals"], 1)
        self.assertEqual(data["inventory_read"]["completed"], 1)

    def test_report_is_private_sanitized_and_never_overwritten(self):
        secret = secrets.token_urlsafe(24)
        measurements = {name: baseline.summarize([sample(200)], 1, 0) for name in baseline.OPERATIONS}
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "report.json"
            arguments = ["--base-url", URL, "--disposable-project", PROJECT, "--output", str(output)]
            with patch.dict(os.environ, {"LOAD_TEST_ADMIN_EMAIL": "private@example.test", "LOAD_TEST_ADMIN_PASSWORD": secret}), \
                    patch.object(baseline, "verify_target", return_value=URL), \
                    patch.object(baseline, "fixture", return_value=(secret, {})) as fixture, \
                    patch.object(baseline, "perform", return_value=sample(200)), \
                    patch.object(baseline, "run_workload", return_value=measurements):
                self.assertEqual(baseline.main(arguments), 0)
                contents = output.read_text()
                self.assertEqual(output.stat().st_mode & 0o777, 0o600)
                self.assertNotIn(secret, contents)
                self.assertNotIn("private@example.test", contents)
                self.assertEqual(baseline.main(arguments), 2)
                self.assertEqual(output.read_text(), contents)
                self.assertEqual(fixture.call_count, 1)
                link = Path(directory) / "link.json"
                link.symlink_to(output)
                self.assertEqual(baseline.main(arguments[:-1] + [str(link)]), 2)
                self.assertEqual(fixture.call_count, 1)


if __name__ == "__main__":
    unittest.main()
