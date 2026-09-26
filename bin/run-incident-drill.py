#!/usr/bin/env python3
"""Exercise recovery only in a newly created, disposable local Docker project."""

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("monitoring_launcher", ROOT / "bin/verify-monitoring.py")
MONITORING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MONITORING)
class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())
SCENARIOS = {
    "backend": {"service": "backend", "alert": "NexusBackendDown",
                "signal": 'up{job="spring-backend"} == 0', "labels": {}},
    "database": {"service": "db", "alert": "NexusSnapshotFailed",
                 "signal": 'nexus_observability_refresh_success{component="business"} == 0',
                 "labels": {"component": "business"}},
}


def utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def local_docker(environment):
    """Pin the validated Unix endpoint; never operate through an ambient remote context."""
    endpoint = environment.get("DOCKER_HOST")
    if environment.get("DOCKER_CONTEXT") or not endpoint:
        result = subprocess.run(["docker", "context", "inspect"], env=environment,
                                check=True, text=True, capture_output=True, timeout=20)
        endpoint = json.loads(result.stdout)[0]["Endpoints"]["docker"]["Host"]
    if not endpoint or not endpoint.startswith("unix:///"):
        raise ValueError("Incident drills require a local Docker Unix socket; remote engines are refused")
    environment.pop("DOCKER_CONTEXT", None)
    environment.pop("DOCKER_HOST", None)
    return ["docker", "--host", endpoint]


def prepare_config(config, project, temporary):
    # Renaming a bind-backed named volume would still write to its original host path.
    for settings in config.get("volumes", {}).values():
        if settings and (settings.get("driver_opts") or settings.get("driver") not in (None, "local")):
            raise ValueError("Incident drills refuse custom or bind-backed volume drivers")
    config = MONITORING.isolate_config(config, project)
    # Grafana and its database do not participate in incident detection or recovery.
    for service in ("grafana", "grafana-db"):
        config["services"].pop(service, None)
    MONITORING.isolate_prometheus(config, ROOT / "docker/prometheus", temporary / "prometheus")
    alert_directory = temporary / "alertmanager"
    alert_directory.mkdir()
    # Keep production grouping, hold and repeat intervals exactly as checked in.
    (alert_directory / "alertmanager.yml").write_bytes(
        (ROOT / "docker/alertmanager/alertmanager.example.yml").read_bytes())
    for endpoint in ("operator", "watchdog"):
        (alert_directory / f"{endpoint}-webhook-url").write_text(f"http://receiver:8080/{endpoint}\n")
    for volume in config["services"]["alertmanager"]["volumes"]:
        if volume.get("target") == "/etc/alertmanager":
            volume["source"] = str(alert_directory)
    config["networks"]["notification_egress"]["internal"] = True
    config["services"]["receiver"] = {
        "image": "python:3.13-alpine", "container_name": f"{project}-receiver",
        "networks": ["notification_egress", "development_access"], "restart": "no",
        "cpus": 0.25, "mem_limit": "64m", "pids_limit": 64,
        "command": ["python", "/receiver.py"],
        "volumes": [{"type": "bind", "source": str(ROOT / "tests/fixtures/monitoring_receiver.py"),
                     "target": "/receiver.py", "read_only": True}],
        "ports": [{"target": 8080, "published": "0", "host_ip": "127.0.0.1"}],
    }
    # Explicit resource labels let the diagnostic command identify an isolated lab.
    for service in config["services"].values():
        service.setdefault("labels", {})["io.nexus.disposable-drill"] = "true"
        if service.get("privileged") or service.get("devices") or service.get("network_mode"):
            raise ValueError("Incident drills refuse privileged devices or network modes")
        for volume in service.get("volumes", []):
            if volume["type"] == "bind":
                source = Path(volume["source"]).resolve()
                if not volume.get("read_only") or not any(
                        source.is_relative_to(root.resolve()) for root in (ROOT, temporary)):
                    raise ValueError("Incident drills only permit read-only repository or temporary bind mounts")
    return config


def http_json(url, *, token=None, data=None, timeout=5):
    target = urllib.parse.urlsplit(url)
    if target.scheme != "http" or target.hostname != "127.0.0.1" or target.username or target.password:
        raise ValueError("Incident HTTP requests require an uncredentialed loopback URL")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(url, headers=headers,
                                     data=None if data is None else json.dumps(data).encode())
    with HTTP.open(request, timeout=timeout) as response:
        if response.status != 200:
            raise ValueError("Incident HTTP operation requires status 200")
        return json.load(response)


def inventory_present(body):
    # This API returns a Slice: totalElements is intentionally -1 to avoid COUNT(*).
    return (isinstance(body, dict) and isinstance(body.get("content"), list)
            and bool(body["content"]))


def wait_for(description, action, *, timeout, interval=2):
    print(f"Waiting for {description}", flush=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = action()
            if value:
                return value
        except (OSError, ValueError):
            pass
        time.sleep(min(interval, max(0, deadline - time.monotonic())))
    raise TimeoutError(description)


def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def matching_delivery(events, scenario, status, *, not_before, fault_at, episode=None):
    for event in events:
        if event.get("path") != "/operator" or timestamp(event["received_at"]) < timestamp(not_before):
            continue
        for alert in event.get("payload", {}).get("alerts", []):
            labels = alert.get("labels", {})
            if (labels.get("alertname") == scenario["alert"] and alert.get("status") == status
                    and all(labels.get(key) == value for key, value in scenario["labels"].items())):
                identity = {"starts_at": alert["startsAt"], "fingerprint": alert["fingerprint"]}
                if (not identity["fingerprint"] or timestamp(identity["starts_at"]) < timestamp(fault_at)
                        or (episode is not None and identity != episode)):
                    continue
                # Never copy receiver payloads, URLs, labels or annotations to reports.
                return {"received_at": event["received_at"], "status": status, "episode": identity}
    return None


class Timeline:
    def __init__(self, scenario):
        self.origin = time.monotonic()
        self.result = {"scenario": scenario, "status": "running", "events": []}

    def mark(self, event, **details):
        item = {"event": event, "at": utc_now(),
                "elapsed_seconds": round(time.monotonic() - self.origin, 3), **details}
        self.result["events"].append(item)
        print(f"{self.result['scenario']}: {event}", flush=True)
        return item

    def finish(self):
        elapsed = {event["event"]: event["elapsed_seconds"] for event in self.result["events"]}
        intervals = {
            "detection_from_fault": ("fault_active", "signal_detected"),
            "notification_from_fault": ("fault_active", "notification_observed"),
            "user_recovery_from_action": ("recovery_started", "user_operation_restored"),
            "fault_to_user_recovery": ("fault_active", "user_operation_restored"),
            "resolution_notice_from_user_recovery": ("user_operation_restored", "resolution_observed"),
        }
        self.result["intervals_seconds"] = {
            name: round(elapsed[end] - elapsed[start], 3) for name, (start, end) in intervals.items()
            if start in elapsed and end in elapsed
        }
        return self.result


class Traffic:
    """One bounded, authenticated read at a time; never retry a write operation."""
    def __init__(self, url, token):
        self.url, self.token = url, token
        self.stop = threading.Event()
        self.samples = []
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        while not self.stop.is_set():
            started = time.monotonic()
            try:
                body = http_json(self.url, token=self.token)
                success = inventory_present(body)
            except (OSError, ValueError):
                success = False
            self.samples.append({"at": utc_now(), "success": success,
                                 "duration_seconds": round(time.monotonic() - started, 3)})
            self.stop.wait(3)

    def close(self):
        self.stop.set()
        self.thread.join(timeout=12)
        if self.thread.is_alive():
            raise RuntimeError("Traffic generator failed to stop")


def observe_fault(query, scenario, traffic, timeline, fault_at, timeout=120):
    """Observe internal signal and user symptom independently; neither delays the other."""
    deadline = time.monotonic() + timeout
    detected, impacted = False, False
    while time.monotonic() < deadline:
        if not impacted:
            failed = next((sample for sample in traffic.samples if not sample["success"]
                           and timestamp(sample["at"]) >= timestamp(fault_at)), None)
            if failed:
                timeline.mark("user_impact_observed", sample_completed_at=failed["at"])
                impacted = True
        if not detected:
            try:
                detected = bool(query(scenario["signal"]))
            except (OSError, ValueError):
                pass
            if detected:
                timeline.mark("signal_detected")
        if detected and impacted:
            return
        time.sleep(min(2, max(0, deadline - time.monotonic())))
    raise TimeoutError("independent outage signal and post-fault authenticated operation failure")


def execute_scenario(name, compose, run, backend, prometheus, receiver, password, result):
    scenario = SCENARIOS[name]
    timeline = Timeline(name)
    result.append(timeline.result)

    def query(expression):
        response = http_json(prometheus + "/api/v1/query?" + urllib.parse.urlencode({"query": expression}))
        if response["status"] != "success":
            raise ValueError("Prometheus query failed")
        return response["data"]["result"]

    def login():
        return http_json(backend + "/api/v1/auth/login", timeout=10, data={
            "email": "admin@example.test", "password": password})["token"]

    def operation(token):
        body = http_json(backend + "/api/v1/inventory/products", token=token)
        return inventory_present(body)

    traffic = None
    try:
        token = wait_for("baseline login", login, timeout=120, interval=5)
        wait_for("baseline inventory read", lambda: operation(token), timeout=60)
        wait_for("healthy backend scrape", lambda: query('up{job="spring-backend"} == 1'), timeout=60)
        wait_for("fresh business snapshot", lambda: query(
            'nexus_observability_refresh_success{component="business"} == 1'), timeout=60)
        baseline_labels = ",".join([f'alertname="{scenario["alert"]}"'] +
                                   [f'{key}="{value}"' for key, value in scenario["labels"].items()])
        wait_for("no pre-existing alert episode", lambda: not query("ALERTS{" + baseline_labels + "}"), timeout=60)
        wait_for("end-to-end watchdog", lambda: any(event.get("path") == "/watchdog"
                 for event in http_json(receiver + "/events")), timeout=90)
        traffic = Traffic(backend + "/api/v1/inventory/products", token)
        traffic.thread.start()
        timeline.mark("baseline_verified")
        fault_at = timeline.mark("fault_requested")["at"]
        run([*compose, "stop", "--timeout", "10", scenario["service"]], timeout=30)
        timeline.mark("fault_active")
        # Inspect the real process state, not only the previously sampled up metric.
        if name == "database":
            state = json.loads(run([*compose, "ps", "--format", "json", "backend"]).stdout)
            if isinstance(state, list):
                state = state[0]
            if state["State"] != "running":
                raise AssertionError("Database drill requires a running backend")
            timeline.mark("backend_process_running")
        observe_fault(query, scenario, traffic, timeline, fault_at)
        labels = ",".join([f'alertname="{scenario["alert"]}"', 'alertstate="firing"'] +
                          [f'{key}="{value}"' for key, value in scenario["labels"].items()])
        wait_for("production alert hold interval", lambda: query("ALERTS{" + labels + "}"), timeout=240)
        timeline.mark("alert_firing_observed")
        event = wait_for("local firing notification", lambda: matching_delivery(
            http_json(receiver + "/events"), scenario, "firing", not_before=fault_at, fault_at=fault_at), timeout=150)
        episode = event["episode"]
        timeline.result["alert_episode"] = episode
        timeline.mark("notification_observed", receiver_received_at=event["received_at"])
        if name == "database":
            if not query('up{job="spring-backend"} == 1'):
                raise AssertionError("Database outage also lost backend telemetry")
            if query('ALERTS{alertname="NexusBackendDown",alertstate="firing"}'):
                raise AssertionError("Database incident incorrectly reported a backend outage")
            timeline.mark("backend_scrape_verified_during_database_outage")
        recovery_at = timeline.mark("recovery_started")["at"]
        recovery_epoch = time.time()
        run([*compose, "start", scenario["service"]], timeout=60)
        token = wait_for("fresh login after recovery", login, timeout=180, interval=5)
        wait_for("authenticated inventory recovery", lambda: operation(token), timeout=90)
        timeline.mark("user_operation_restored")
        wait_for("fresh telemetry after recovery", lambda:
                 query('up{job="spring-backend"} == 1') and query(
                     'nexus_observability_refresh_success{component="business"} == 1') and query(
                     'nexus_observability_last_success_timestamp_seconds{component="business"} > '
                     + str(recovery_epoch)), timeout=120)
        timeline.mark("telemetry_restored")
        event = wait_for("local resolved notification with production grouping", lambda: matching_delivery(
            http_json(receiver + "/events"), scenario, "resolved", not_before=recovery_at,
            fault_at=fault_at, episode=episode), timeout=420)
        timeline.mark("resolution_observed", receiver_received_at=event["received_at"])
        timeline.result["status"] = "passed"
    finally:
        if traffic:
            traffic.close()
            timeline.result["traffic"] = {"operation": "GET /api/v1/inventory/products",
                                          "samples": traffic.samples}
        if timeline.result["status"] != "passed":
            timeline.result["status"] = "failed"
        timeline.finish()


def write_report(directory, report):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    lines = ["# Local incident drill", "", f"Status: {report['status']}",
             f"Started: {report['started_at']}", f"Commit: {report['commit']}", "",
             "These are individual, automated local drills, not production MTTR measurements.",
             "Intervals use the runner's monotonic clock. Notification times include polling delay;",
             "receiver UTC receipt timestamps are also recorded. Human response time is not measured.", ""]
    for scenario in report["scenarios"]:
        lines.extend([f"## {scenario['scenario']}: {scenario['status']}", "",
                      "| Event | UTC | Elapsed seconds |", "| --- | --- | ---: |"])
        for event in scenario["events"]:
            lines.append(f"| {event['event']} | {event['at']} | {event['elapsed_seconds']} |")
        lines.append("")
        for name, value in scenario.get("intervals_seconds", {}).items():
            lines.append(f"- {name}: {value} seconds")
        lines.append("")
    lines.extend([f"Cleanup: {report['cleanup']}", "", "See report.json for traffic samples and configuration hashes."])
    for name, content in (("report.json", json.dumps(report, indent=2) + "\n"),
                          ("report.md", "\n".join(lines) + "\n")):
        path = directory / name
        with open(path, "w", encoding="utf-8", opener=lambda path, flags: os.open(path, flags, 0o600)) as output:
            output.write(content)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=["all", *SCENARIOS], default="all")
    args = parser.parse_args(argv)
    environment = MONITORING.isolated_environment(os.environ)
    project = "nexus-drill-" + secrets.token_hex(6)
    report_directory = ROOT / ".artifacts/incidents" / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + project)
    report = {"schema_version": 1, "project": project, "started_at": utc_now(),
              "status": "failed", "cleanup": "not needed", "scenarios": [],
              "commit": subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True,
                                       capture_output=True, check=True, timeout=10).stdout.strip(),
              "timings": "unchanged checked-in production alert rules and Alertmanager template",
              "config_sha256": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                for path in (ROOT / "docker/prometheus/rules/nexus.yml",
                                             ROOT / "docker/prometheus/prometheus.yml",
                                             ROOT / "docker/alertmanager/alertmanager.example.yml")}}
    exit_code = 1
    def terminate(_signal, _frame):
        raise KeyboardInterrupt
    previous_handler = signal.signal(signal.SIGTERM, terminate)
    try:
        docker = local_docker(environment)
        def run(arguments, *, timeout=60, check=True):
            return subprocess.run(arguments, env=environment, check=check, text=True,
                                  capture_output=True, timeout=timeout)
        run([*docker, "info"], timeout=20)
        with tempfile.TemporaryDirectory(prefix="nexus-drill-") as directory:
            temporary = Path(directory)
            empty_env = temporary / "empty.env"
            empty_env.touch(mode=0o600)
            compose = [*docker, "compose", "--project-name", project, "--env-file", str(empty_env)]
            resolved = run([*compose, "-f", str(ROOT / "docker-compose.yml"), "config", "--format", "json"])
            config = prepare_config(json.loads(resolved.stdout), project, temporary)
            config_path = temporary / "compose.json"
            config_path.touch(mode=0o600)
            config_path.write_text(json.dumps(config))
            compose.extend(["-f", str(config_path)])
            try:
                print(f"Starting disposable project {project}; production alert timings may take 20 minutes.", flush=True)
                run([*compose, "up", "--build", "-d", "--wait", "--wait-timeout", "240"], timeout=1200)
                def url(service, port):
                    address = run([*compose, "port", service, str(port)]).stdout.strip()
                    if not address.startswith("127.0.0.1:") or not address[10:].isdigit():
                        raise ValueError("Drill port must be a random loopback binding")
                    return "http://" + address
                for name in SCENARIOS if args.scenario == "all" else [args.scenario]:
                    execute_scenario(name, compose, run, url("backend", 8080), url("prometheus", 9090),
                                     url("receiver", 8080), environment["APP_BOOTSTRAP_ADMIN_PASSWORD"], report["scenarios"])
                report["status"], exit_code = "passed", 0
            finally:
                # Only this generated project is touched. Volumes contain disposable seeded data.
                report["cleanup"] = "failed"
                cleanup = run([*compose, "down", "--volumes", "--rmi", "local", "--remove-orphans"],
                              check=False, timeout=180)
                if cleanup.returncode == 0:
                    remaining = []
                    for resource in ("container", "volume", "network"):
                        state = run([*docker, resource, "ls", *(["--all"] if resource == "container" else []), "--quiet", "--filter",
                                     f"label=com.docker.compose.project={project}"])
                        remaining.extend(state.stdout.split())
                    if not remaining:
                        report["cleanup"] = "passed"
                    else:
                        report["status"], exit_code = "failed", 1
                else:
                    report["status"], exit_code = "failed", 1
    except (OSError, ValueError, AssertionError, KeyError, subprocess.SubprocessError, KeyboardInterrupt) as error:
        # Do not print CalledProcessError command lines or HTTP bodies that might carry credentials.
        report["error_type"] = type(error).__name__
        if isinstance(error, (TimeoutError, AssertionError)):
            report["failed_check"] = str(error)
        report["status"] = "interrupted" if isinstance(error, KeyboardInterrupt) else "failed"
        exit_code = 130 if isinstance(error, KeyboardInterrupt) else 1
        print(f"Drill {report['status']}: {type(error).__name__}. See the last report event.", file=sys.stderr)
    finally:
        signal.signal(signal.SIGTERM, previous_handler)
        report["finished_at"] = utc_now()
        write_report(report_directory, report)
        print(f"Report: {report_directory / 'report.md'}", flush=True)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
