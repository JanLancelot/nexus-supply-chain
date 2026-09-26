#!/usr/bin/env python3
"""Collect bounded, read-only diagnostics from an explicitly selected local stack."""

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import secrets
import selectors
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ("backend", "db", "redis", "kafka", "prometheus", "grafana", "grafana-db", "alertmanager")
SECTIONS = ("services", "targets", "alerts", "metrics", "logs")
JOBS = ("spring-backend", "prometheus", "alertmanager")
ALERTS = frozenset(("NexusWatchdog", "NexusAlertmanagerDown", "NexusNotificationFailures",
    "NexusRuleEvaluationFailures", "NexusBackendDown", "NexusHighErrorRate", "NexusHighLatency",
    "NexusDatabasePoolSaturated", "NexusDependencyDegraded", "NexusSnapshotFailed",
    "NexusSnapshotStale", "NexusTelemetryMissing", "NexusSloFastBurn", "NexusSloSustainedBurn"))
# Aggregation deliberately removes instance, environment, pool and arbitrary labels.
QUERIES = {
    "database_connections_active": 'sum(hikaricp_connections_active{job="spring-backend"})',
    "database_connections_max": 'sum(hikaricp_connections_max{job="spring-backend"})',
    "database_connections_pending": 'sum(hikaricp_connections_pending{job="spring-backend"})',
    "dependency_events_up": 'min(nexus_dependency_up{job="spring-backend",dependency="events"})',
    "dependency_cache_up": 'min(nexus_dependency_up{job="spring-backend",dependency="cache"})',
    "business_refresh_success": 'min(nexus_observability_refresh_success{job="spring-backend",component="business"})',
    "dependency_refresh_success": 'min(nexus_observability_refresh_success{job="spring-backend",component="dependencies"})',
    "oldest_snapshot_age_seconds": 'time() - min(nexus_observability_last_success_timestamp_seconds{job="spring-backend"})',
}
MAX_BYTES = 1024 * 1024
COMMAND_TIMEOUT = 10
HTTP_TIMEOUT = 5


class CollectionError(Exception):
    """A fixed error code safe to put into a report; never holds source text."""


def valid_project(project):
    return bool(re.fullmatch(r"nexus-(?:local|supply-chain|monitoring-test-[a-f0-9]{10}|drill-[a-f0-9]{12})", project))


def command(args, *, timeout=COMMAND_TIMEOUT, limit=MAX_BYTES):
    """Bound both pipes in memory and wall time, including a single huge log line."""
    try:
        environment = os.environ.copy()
        if args[:2] == ["docker", "--host"]:
            environment = {key: value for key, value in environment.items() if not key.startswith("DOCKER_")}
        process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment)
    except OSError:
        raise CollectionError("command_unavailable") from None
    output = {"stdout": bytearray(), "stderr": bytearray()}
    deadline = time.monotonic() + timeout
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ, "stdout")
            selector.register(process.stderr, selectors.EVENT_READ, "stderr")
            while selector.get_map():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise CollectionError("command_timeout")
                for key, _ in selector.select(min(remaining, 0.1)):
                    chunk = os.read(key.fileobj.fileno(), 8192)
                    if not chunk:
                        selector.unregister(key.fileobj)
                        continue
                    output[key.data].extend(chunk)
                    if sum(map(len, output.values())) > limit:
                        raise CollectionError("output_limit")
            try:
                process.wait(timeout=max(0.001, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                raise CollectionError("command_timeout") from None
        if process.returncode:
            raise CollectionError("command_failed")
        return tuple(output[name].decode("utf-8", errors="replace") for name in ("stdout", "stderr"))
    finally:
        if process.poll() is None:
            process.kill()
            process.wait()
        process.stdout.close()
        process.stderr.close()


def json_command(args):
    try:
        return json.loads(command(args)[0])
    except (ValueError, TypeError):
        raise CollectionError("invalid_json") from None


def local_docker():
    # Resolve once then pin --host for every later invocation. DOCKER_CONTEXT takes
    # precedence over DOCKER_HOST in Docker itself, so refuse ambiguous overrides.
    if os.environ.get("DOCKER_CONTEXT") and os.environ.get("DOCKER_HOST"):
        raise CollectionError("ambiguous_docker_context")
    if os.environ.get("DOCKER_HOST") and not os.environ.get("DOCKER_CONTEXT"):
        endpoint = os.environ["DOCKER_HOST"]
    else:
        endpoint = command(["docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"])[0].strip()
    if not endpoint.startswith("unix:///") or "\n" in endpoint or "\x00" in endpoint:
        raise CollectionError("local_unix_docker_required")
    return ["docker", "--host", endpoint]


INSPECT_FORMAT = ('{"disposable":{{json (index .Config.Labels "io.nexus.disposable-drill")}},"project":{{json (index .Config.Labels "com.docker.compose.project")}},'
    '"service":{{json (index .Config.Labels "com.docker.compose.service")}},'
    '"state":{{json .State.Status}},"oom_killed":{{json .State.OOMKilled}},'
    '"exit_code":{{json .State.ExitCode}},"restarts":{{json .RestartCount}},'
    '"health":{{with (index .State "Health")}}{{json .Status}}{{else}}"not_configured"{{end}},'
    '"ports":{{json (index .NetworkSettings.Ports "9090/tcp")}}}')


def discover(docker, project):
    identifiers = command([*docker, "ps", "-aq", "--filter", f"label=com.docker.compose.project={project}"])[0].split()
    if not identifiers or len(identifiers) > 32 or any(not re.fullmatch(r"[a-f0-9]{12,64}", value) for value in identifiers):
        raise CollectionError("project_not_found_or_invalid")
    containers = []
    for identifier in identifiers:
        state = json_command([*docker, "inspect", "--format", INSPECT_FORMAT, identifier])
        if state.get("project") != project:
            raise CollectionError("project_label_mismatch")
        if project.startswith("nexus-drill-") and state.get("disposable") != "true":
            raise CollectionError("disposable_drill_label_required")
        if state.get("service") in SERVICES:
            containers.append((identifier, state))
    if not containers:
        raise CollectionError("no_supported_services")
    return containers


def prometheus_url(containers):
    candidates = [state for _, state in containers if state["service"] == "prometheus"]
    if len(candidates) != 1 or candidates[0].get("state") != "running":
        raise CollectionError("prometheus_not_running_or_ambiguous")
    bindings = candidates[0].get("ports") or []
    # Never accept wildcard/remote publication, URLs from env, or service-discovery data.
    if not bindings or any(binding.get("HostIp") not in ("127.0.0.1", "::1") for binding in bindings):
        raise CollectionError("prometheus_loopback_binding_required")
    binding = bindings[0]
    port = str(binding.get("HostPort", ""))
    if not port.isdigit() or not 1 <= int(port) <= 65535:
        raise CollectionError("invalid_prometheus_port")
    host = "[::1]" if binding["HostIp"] == "::1" else "127.0.0.1"
    return f"http://{host}:{port}"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CollectionError("http_redirect_refused")


def request(base, path, params=None):
    url = base + path + ("?" + urllib.parse.urlencode(params) if params else "")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(url, timeout=HTTP_TIMEOUT) as response:
            body = response.read(MAX_BYTES + 1)
        if len(body) > MAX_BYTES:
            raise CollectionError("output_limit")
        parsed = json.loads(body)
        if parsed.get("status") != "success":
            raise CollectionError("prometheus_query_failed")
        return parsed["data"]
    except (OSError, urllib.error.URLError):
        raise CollectionError("http_unavailable") from None
    except (ValueError, KeyError, TypeError, AttributeError):
        raise CollectionError("invalid_prometheus_response") from None


def enum(value, choices):
    return value if isinstance(value, str) and value in choices else "unknown"


def count(value):
    return value if isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 2**31 else None


def services(containers, required=SERVICES):
    rows = [{"service": state["service"],
        "state": enum(state.get("state"), ("created", "running", "paused", "restarting", "removing", "exited", "dead")),
        "health": enum(state.get("health"), ("healthy", "unhealthy", "starting", "not_configured")),
        "oom_killed": state.get("oom_killed") is True,
        "exit_code": count(state.get("exit_code")), "restarts": count(state.get("restarts"))}
        for _, state in containers]
    return {"instances": rows, "missing_services": sorted(set(required) - {row["service"] for row in rows})}


def targets(base):
    data = request(base, "/api/v1/targets", {"state": "active"})
    rows = []
    ignored = 0
    for target in data["activeTargets"]:
        job = target.get("labels", {}).get("job")
        if job not in JOBS:
            ignored += 1
            continue
        rows.append({"job": job, "health": enum(target.get("health"), ("up", "down", "unknown")),
                     "has_scrape_error": bool(target.get("lastError"))})
    return {"instances": rows, "missing_jobs": sorted(set(JOBS) - {row["job"] for row in rows}),
            "other_targets_omitted": ignored}


def alerts(base):
    data = request(base, "/api/v1/alerts")
    rows, omitted = [], 0
    for alert in data["alerts"]:
        labels = alert.get("labels", {})
        if labels.get("alertname") not in ALERTS:
            omitted += 1
            continue
        rows.append({"name": labels["alertname"],
            "state": enum(alert.get("state"), ("pending", "firing", "inactive")),
            "severity": enum(labels.get("severity"), ("none", "warning", "critical"))})
        if labels.get("alertname") in ("NexusSloFastBurn", "NexusSloSustainedBurn"):
            rows[-1]["slo"] = enum(labels.get("slo"), ("inventory-availability", "order-availability", "inventory-latency"))
    return {"instances": rows, "other_alerts_omitted": omitted}


def metrics(base):
    rows = {}
    for name, query in QUERIES.items():
        try:
            data = request(base, "/api/v1/query", {"query": query, "timeout": "3s"})
            if data.get("resultType") != "vector" or len(data["result"]) != 1:
                raise CollectionError("metric_missing_or_ambiguous")
            value = float(data["result"][0]["value"][1])
            if not math.isfinite(value):
                raise CollectionError("metric_not_finite")
            rows[name] = {"value": value}
        except CollectionError as error:
            rows[name] = {"error": str(error)}
        except (KeyError, IndexError, ValueError, TypeError):
            rows[name] = {"error": "invalid_metric"}
    return rows


def summarize_logs(text):
    levels = Counter()
    lines = text.splitlines()
    for line in lines:
        match = re.search(r"\b(TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\b", line, flags=re.I)
        level = match.group(1).upper() if match else "unclassified"
        levels["WARN" if level == "WARNING" else level] += 1
    return {"lines": len(lines), "levels": dict(sorted(levels.items()))}


def logs(docker, containers):
    rows = []
    for identifier, state in containers:
        row = {"service": state["service"]}
        try:
            stdout, stderr = command([*docker, "logs", "--tail", "200", "--since", "10m", identifier], limit=256*1024)
            row.update(summarize_logs("\n".join(stdout.splitlines() + stderr.splitlines())))
            row["possibly_truncated"] = row["lines"] >= 200
        except CollectionError as error:
            row["error"] = str(error)
        rows.append(row)
    return {"tail_limit_per_service": 200, "lookback_minutes": 10, "instances": rows,
            "messages": "omitted_by_design"}


def collect(project, selected=SECTIONS):
    if not valid_project(project):
        raise CollectionError("unsupported_project")
    started = time.monotonic()
    docker = local_docker()
    containers = discover(docker, project)
    report = {"schema_version": 1, "project": project,
        "collected_at": datetime.now(timezone.utc).isoformat(), "sections": {}}
    for name in selected:
        try:
            if name == "services":
                required = tuple(name for name in SERVICES if name not in ("grafana", "grafana-db")) if project.startswith("nexus-drill-") else SERVICES
                data = services(containers, required)
            elif name == "logs":
                data = logs(docker, containers)
            else:
                base = prometheus_url(containers)
                data = {"targets": targets, "alerts": alerts, "metrics": metrics}[name](base)
            partial = (name == "metrics" and any("error" in row for row in data.values())) or (
                name == "logs" and any("error" in row for row in data["instances"])) or (
                name == "services" and bool(data["missing_services"])) or (
                name == "targets" and bool(data["missing_jobs"]))
            report["sections"][name] = {"status": "partial" if partial else "complete", "data": data}
        except CollectionError as error:
            report["sections"][name] = {"status": "unavailable", "error": str(error)}
        except (KeyError, TypeError, ValueError, AttributeError):
            report["sections"][name] = {"status": "unavailable", "error": "invalid_source_data"}
    report["collection_status"] = "complete" if all(row["status"] == "complete" for row in report["sections"].values()) else "partial"
    report["elapsed_seconds"] = round(time.monotonic() - started, 3)
    return report


def markdown(report):
    lines = ["# Local diagnostic report", "", f"Project: `{report['project']}`",
        f"Collected: {report['collected_at']}", f"Collection: **{report['collection_status']}**",
        f"Elapsed: {report['elapsed_seconds']} seconds", "",
        "Collection completeness is not a service-health verdict. Missing evidence is never treated as healthy.", "",
        "Log messages, arbitrary labels, URLs, environment variables, and healthcheck output are omitted.", ""]
    for name, section in report["sections"].items():
        lines.extend([f"## {name} ({section['status']})", "", "```json", json.dumps(section.get("data", {"error": section.get("error")}), indent=2), "```", ""])
    return "\n".join(lines)


def write_report(report, output_dir):
    root = Path(output_dir).absolute()
    if any(parent.is_symlink() for parent in (root, *root.parents)):
        raise CollectionError("symlink_output_refused")
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    destination = root / (datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(4))
    destination.mkdir(mode=0o700)
    for name, contents in (("report.json", json.dumps(report, indent=2) + "\n"), ("report.md", markdown(report))):
        descriptor = os.open(destination / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
            handle.write(contents)
    return destination


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True, help="nexus-local, nexus-supply-chain, or a supported disposable project")
    parser.add_argument("--output-dir", type=Path, default=ROOT / ".artifacts/diagnostics")
    parser.add_argument("--section", choices=SECTIONS, help="collect one section for a command-by-command baseline")
    args = parser.parse_args(argv)
    try:
        report = collect(args.project, (args.section,) if args.section else SECTIONS)
        destination = write_report(report, args.output_dir)
        print(f"Collection {report['collection_status']}; report: {destination / 'report.md'}")
        return 0 if report["collection_status"] == "complete" else 1
    except (CollectionError, OSError) as error:
        code = str(error) if isinstance(error, CollectionError) else "report_write_failed"
        print(f"Diagnostics refused: {code}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
