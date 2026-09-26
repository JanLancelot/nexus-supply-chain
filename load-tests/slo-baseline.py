#!/usr/bin/env python3
"""Measure a small, constant-arrival workload against a verified disposable stack."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import heapq
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
OPERATIONS = {
    "inventory_read": ("/api/v1/inventory/products?size=50", 200),
    "order_create": ("/api/v1/orders", 201),
}


def verify_target(base_url, project):
    """Reject regular development/production stacks before sending credentials or writes."""
    parsed = urllib.parse.urlsplit(base_url)
    if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port
            or parsed.username or parsed.password or parsed.path not in ("", "/")
            or parsed.query or parsed.fragment):
        raise ValueError("Target must be an explicit http://127.0.0.1:<port> base URL")
    if not re.fullmatch(r"nexus-monitoring-test-[a-f0-9]{10}", project):
        raise ValueError("Target must belong to a generated disposable monitoring verification project")
    result = subprocess.run(["docker", "inspect", f"{project}-backend"],
                            capture_output=True, text=True, check=True, timeout=15)
    state = json.loads(result.stdout)[0]
    labels = state.get("Config", {}).get("Labels", {})
    bindings = state.get("NetworkSettings", {}).get("Ports", {}).get("8080/tcp") or []
    environment = state.get("Config", {}).get("Env", [])
    if (labels.get("com.docker.compose.project") != project
            or labels.get("com.docker.compose.service") != "backend"
            or not state.get("State", {}).get("Running")
            or {"HostIp": "127.0.0.1", "HostPort": str(parsed.port)} not in bindings
            or "APP_MONITORING_ENVIRONMENT=monitoring-test" not in environment
            or "APP_SEED_DEMO_DATA=true" not in environment):
        raise ValueError("Target does not match the running disposable monitoring backend")
    return base_url.rstrip("/")


def request(base_url, path, token=None, payload=None):
    body = json.dumps(payload).encode() if payload is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base_url + path, data=body, headers=headers)
    start = time.perf_counter()
    try:
        with HTTP.open(req, timeout=5) as response:
            status, raw = response.status, response.read(2_000_001)
    except urllib.error.HTTPError as error:
        status, raw = error.code, b""
        error.close()
    except (OSError, urllib.error.URLError):
        return {"status": 0, "elapsed_ms": (time.perf_counter() - start) * 1000}, None
    elapsed = (time.perf_counter() - start) * 1000
    try:
        data = json.loads(raw) if len(raw) <= 2_000_000 else None
    except (ValueError, UnicodeDecodeError):
        data = None
    return {"status": status, "elapsed_ms": elapsed}, data


def fixture(base_url, email, password):
    result, data = request(base_url, "/api/v1/auth/login", payload={"email": email, "password": password})
    if result["status"] != 200 or not isinstance(data, dict) or not data.get("token"):
        raise ValueError("Baseline authentication failed; response content is intentionally omitted")
    token = data["token"]

    def read(path):
        result, data = request(base_url, path, token)
        if result["status"] != 200:
            raise ValueError("Baseline fixture discovery failed")
        return data

    suppliers = read("/api/v1/suppliers")
    warehouses = read("/api/v1/warehouses")
    products = read("/api/v1/inventory/products?active=true&size=50")
    if not isinstance(suppliers, list) or not isinstance(warehouses, list) or not isinstance(products, dict):
        raise ValueError("Baseline fixture response does not match the API contract")
    warehouse_ids = {item["id"] for item in warehouses if item.get("id")}
    supplier = next((item for item in suppliers if item.get("id") and item.get("active") is True), None)
    product = next((item for item in products.get("content", [])
                    if item.get("id") and item.get("isActive") is True
                    and item.get("warehouseId") in warehouse_ids), None)
    if not supplier or not product:
        raise ValueError("Baseline requires an active supplier and an active product in an existing warehouse")
    return token, {"supplierId": supplier["id"], "warehouseId": product["warehouseId"],
                   "items": [{"productId": product["id"], "quantity": 1}]}


def perform(base_url, token, order, operation):
    path, expected = OPERATIONS[operation]
    sample, data = request(base_url, path, token, order if operation == "order_create" else None)
    valid_body = (isinstance(data, dict) and (
        isinstance(data.get("content"), list) if operation == "inventory_read" else bool(data.get("id"))))
    sample["contract_success"] = sample["status"] == expected and valid_body
    return sample


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * percent / 100) - 1)]


def summarize(samples, scheduled, dropped):
    counts = Counter(sample["status"] for sample in samples)
    # Match the server SLI response classes; unexpected exclusions remain visible.
    eligible = [sample for sample in samples if 200 <= sample["status"] < 300
                or sample["status"] == 429 or sample["status"] >= 500]
    successful = [sample for sample in eligible if 200 <= sample["status"] < 300]
    successful_fast = [sample for sample in successful if sample["elapsed_ms"] <= 500]
    timings = [sample["elapsed_ms"] for sample in samples]
    return {
        "scheduled": scheduled, "completed": len(samples), "dropped_arrivals": dropped,
        "http_status_counts": {str(status): count for status, count in sorted(counts.items())},
        "transport_failures": counts[0], "excluded_http_responses": sum(
            count for status, count in counts.items() if 300 <= status < 500 and status != 429),
        "contract_failures": sum(not sample["contract_success"] for sample in samples),
        "eligible_server_responses": len(eligible),
        "server_response_availability_fraction": len(successful) / len(eligible) if eligible else None,
        "client_success_within_500ms_fraction": len(successful_fast) / len(eligible) if eligible else None,
        "end_to_end_contract_success_fraction": sum(sample["contract_success"] for sample in samples) / scheduled if scheduled else None,
        "client_duration_ms": {"p50": percentile(timings, 50), "p95": percentile(timings, 95),
                               "p99": percentile(timings, 99), "max": max(timings) if timings else None},
    }


def run_workload(base_url, token, order, duration, rates, workers=16):
    pending, samples, scheduled, dropped = {}, {name: [] for name in rates}, Counter(), Counter()
    start = time.monotonic()
    arrivals = [(start, name, 0) for name in rates]
    heapq.heapify(arrivals)
    with ThreadPoolExecutor(max_workers=workers) as pool:
        while arrivals:
            due, name, index = heapq.heappop(arrivals)
            time.sleep(max(0, due - time.monotonic()))
            for future in list(pending):
                if future.done():
                    samples[pending.pop(future)].append(future.result())
            scheduled[name] += 1
            # Bound in-flight requests instead of hiding overload in an unbounded queue.
            if len(pending) >= workers or time.monotonic() - due > 1 / rates[name]:
                dropped[name] += 1
            else:
                pending[pool.submit(perform, base_url, token, order, name)] = name
            next_due = (index + 1) / rates[name]
            if next_due < duration:
                heapq.heappush(arrivals, (start + next_due, name, index + 1))
        for future, name in pending.items():
            samples[name].append(future.result())
    return {name: summarize(samples[name], scheduled[name], dropped[name]) for name in rates}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--disposable-project", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--duration", type=int, default=60)
    parser.add_argument("--inventory-rps", type=int, default=10)
    parser.add_argument("--order-rps", type=int, default=1)
    args = parser.parse_args(argv)
    if not 1 <= args.duration <= 300 or not 1 <= args.inventory_rps <= 20 or not 1 <= args.order_rps <= 5:
        parser.error("Duration must be 1..300 seconds, inventory rate 1..20/s, and order rate 1..5/s")
    email, password = os.environ.get("LOAD_TEST_ADMIN_EMAIL"), os.environ.get("LOAD_TEST_ADMIN_PASSWORD")
    if not email or not password:
        parser.error("Set LOAD_TEST_ADMIN_EMAIL and LOAD_TEST_ADMIN_PASSWORD in the environment")
    try:
        if args.output.exists() or args.output.is_symlink() or args.output.parent.is_symlink():
            raise ValueError("Report output must be a new file in a real directory")
        base_url = verify_target(args.base_url, args.disposable_project)
        token, order = fixture(base_url, email, password)
        for name in OPERATIONS:
            for _ in range(5):
                if not perform(base_url, token, order, name)["contract_success"]:
                    raise ValueError("Warm-up failed; refusing to measure an invalid workload")
        start = datetime.now(timezone.utc).isoformat()
        rates = {"inventory_read": args.inventory_rps, "order_create": args.order_rps}
        measurements = run_workload(base_url, token, order, args.duration, rates)
        passed = all(item["end_to_end_contract_success_fraction"] == 1 for item in measurements.values())
        inventory_latency = measurements["inventory_read"]["client_success_within_500ms_fraction"]
        sample_targets = all((item["server_response_availability_fraction"] or 0) >= 0.999
                             for item in measurements.values()) and (inventory_latency or 0) >= 0.99
        report = {
            "schema_version": 1, "scope": "local disposable baseline; not a 30-day SLO report",
            "started_at": start, "finished_at": datetime.now(timezone.utc).isoformat(),
            "disposable_project": args.disposable_project, "duration_seconds": args.duration,
            "arrival_rates_per_second": rates, "max_concurrent_requests": 16,
            "warmup_requests_per_operation": 5, "request_timeout_seconds": 5,
            "measurement_notes": [
                "Demo fixtures; fixed request mix; not a capacity or production reliability claim.",
                "Latency is measured by the client including connection and body transfer time; server histogram latency differs.",
                "HTTP 2xx/429/5xx define response availability; excluded responses and transport failures remain explicit.",
                "Dropped arrivals and non-contract responses fail workload validation even when the response SLI looks healthy.",
                "A small successful sample cannot establish a 99.9% long-window reliability guarantee.",
            ],
            "measurements": measurements, "workload_valid": passed,
            "meets_proposed_sample_targets": sample_targets,
        }
        args.output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        # Never replace an earlier measurement or follow a report-file symlink.
        flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
        with os.fdopen(os.open(args.output, flags, 0o600), "w", encoding="utf-8") as output:
            output.write(json.dumps(report, indent=2) + "\n")
        print(f"Local baseline saved to {args.output}")
        return 0 if passed and sample_targets else 1
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        print("Baseline could not complete safely; verify the disposable project, credentials, and fixture prerequisites.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
