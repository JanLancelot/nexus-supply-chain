"""Verify monitoring on an isolated Compose project, never a configured live target."""

import argparse
import base64
import importlib.util
import json
import math
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCAL_HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def isolated_environment(source):
    prefixes = ("APP_", "SPRING_", "JWT_", "MANAGEMENT_", "GRAFANA_", "GF_",
                "PROMETHEUS_", "COMPOSE_", "ALERTMANAGER_", "CADDY_")
    environment = {key: value for key, value in source.items() if not key.startswith(prefixes)}
    environment.update({
        "SPRING_DATASOURCE_PASSWORD": secrets.token_hex(24),
        "JWT_SECRET": secrets.token_hex(32),
        "GRAFANA_ADMIN_PASSWORD": secrets.token_hex(24),
        "GRAFANA_SECRET_KEY": secrets.token_hex(32),
        "GRAFANA_DB_PASSWORD": secrets.token_hex(24),
        "GRAFANA_DB_ADMIN_PASSWORD": secrets.token_hex(24),
        "APP_BOOTSTRAP_ADMIN_PASSWORD": secrets.token_hex(24),
        "APP_BOOTSTRAP_STAFF_PASSWORD": secrets.token_hex(24),
        "APP_BOOTSTRAP_ADMIN_EMAIL": "admin@example.test",
        "APP_SEED_DEMO_DATA": "true",
        "APP_MONITORING_ENVIRONMENT": "monitoring-test",
        "APP_MONITORING_GRAFANA_URL": "http://localhost:3000",
    })
    return environment


def isolate_config(config, project):
    """Replace every shared resource and host port before creating anything."""
    config.pop("name", None)
    for name, service in config["services"].items():
        service["container_name"] = f"{project}-{name}"
        service["restart"] = "no"
        if "build" in service:
            service.pop("image", None)
        service["ports"] = []
        if name in {"backend", "prometheus", "grafana"}:
            port = {"backend": 8080, "prometheus": 9090, "grafana": 3000}[name]
            service["ports"] = [{"target": port, "published": "0", "host_ip": "127.0.0.1"}]
    for category in ("volumes", "networks"):
        for name, settings in config.get(category, {}).items():
            if settings and settings.get("external"):
                raise ValueError(f"Verification refuses external {category}: {name}")
            config[category][name] = {**(settings or {}), "name": f"{project}_{name}"}
    return config


def isolate_prometheus(config, source, destination):
    # Operators may have configured a real receiver. Verification must never send to it.
    shutil.copytree(source, destination)
    (destination / "alertmanagers.json").write_text('[{"targets":["alertmanager:9093"]}]\n', encoding="utf-8")
    for volume in config["services"]["prometheus"]["volumes"]:
        if volume.get("target") == "/etc/prometheus":
            volume["source"] = str(destination)


def request(url, *, headers=None, data=None, expected=200):
    payload = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(url, data=payload, headers={
        **({"Content-Type": "application/json"} if payload else {}), **(headers or {}),
    })
    try:
        with LOCAL_HTTP.open(req, timeout=10) as response:
            status, body = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, body = error.code, error.read()
    if status != expected:
        raise AssertionError(f"{urllib.parse.urlsplit(url).path}: expected HTTP {expected}, got {status}")
    return json.loads(body) if body and body.lstrip().startswith((b"{", b"[")) else body


def eventually(description, action, timeout=120):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            if action():
                print(f"PASS: {description}", flush=True)
                return
        except (OSError, AssertionError, ValueError) as error:
            last_error = error
        time.sleep(2)
    raise AssertionError(f"Timed out: {description}" + (f" ({last_error})" if last_error else ""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config-only", action="store_true")
    parser.add_argument("--baseline-report", type=Path,
                        help="Run the disposable 60-second workload and write a new private JSON report")
    options = parser.parse_args()
    if options.config_only and options.baseline_report:
        parser.error("--baseline-report requires the live stack")
    if options.baseline_report and (options.baseline_report.exists() or options.baseline_report.is_symlink()):
        parser.error("Baseline output must be a new file")
    environment = isolated_environment(os.environ)
    project = f"nexus-monitoring-test-{secrets.token_hex(5)}"
    status = 0
    with tempfile.TemporaryDirectory(prefix="nexus-monitoring-") as directory:
        temporary = Path(directory)
        alert_config = temporary / "alertmanager"
        alert_config.mkdir(mode=0o755)
        template = (ROOT / "docker/alertmanager/alertmanager.example.yml").read_text()
        (alert_config / "alertmanager.yml").write_text(template.replace("group_wait: 30s", "group_wait: 1s")
                                                      .replace("group_interval: 5m", "group_interval: 5s"))
        for name, endpoint in (("operator", "operator"), ("watchdog", "watchdog")):
            (alert_config / f"{name}-webhook-url").write_text(f"http://receiver:8080/{endpoint}\n")
        environment["ALERTMANAGER_CONFIG_DIR"] = str(alert_config)
        empty_env = temporary / "empty.env"
        empty_env.touch(mode=0o600)
        compose = ["docker", "compose", "--project-name", project, "--env-file", str(empty_env)]

        def run(arguments, *, capture=False, check=True, timeout=900):
            return subprocess.run(arguments, env=environment, check=check, text=True,
                                  capture_output=capture, timeout=timeout)

        run(["docker", "info"], capture=True, timeout=20)
        # Validate the production merge without opening ports or requesting certificates.
        environment.update({"APP_DOMAIN": "app.example.test", "GRAFANA_DOMAIN": "metrics.example.test",
                            "CADDY_ACME_EMAIL": ""})
        selfhosted = json.loads(run([*compose, "-f", str(ROOT / "docker-compose.yml"),
                                    "-f", str(ROOT / "docker/selfhosted.compose.yml"),
                                    "config", "--format", "json"], capture=True).stdout)
        for name in ("backend", "db", "redis", "kafka", "prometheus", "grafana", "grafana-db", "alertmanager"):
            assert not selfhosted["services"][name].get("ports"), f"Self-hosted {name} exposes a host port"
        assert selfhosted["services"]["backend"]["environment"]["APP_MONITORING_GRAFANA_URL"] == "https://metrics.example.test"
        assert selfhosted["services"]["backend"]["environment"]["APP_CORS_ALLOWED_ORIGINS"] == "https://app.example.test"
        assert selfhosted["services"]["grafana"]["environment"]["GF_SECURITY_COOKIE_SECURE"] == "true"
        for network in ("application_data", "grafana_database", "metrics", "application_ingress", "grafana_ingress"):
            assert selfhosted["networks"][network]["internal"]
        expected_networks = {
            "caddy": {"edge", "application_ingress", "grafana_ingress"},
            "backend": {"application_ingress", "application_data", "metrics"},
            "grafana": {"grafana_ingress", "grafana_database", "metrics"},
            "grafana-db": {"grafana_database"}, "db": {"application_data"},
            "prometheus": {"metrics"}, "alertmanager": {"metrics", "notification_egress"},
        }
        for name, networks in expected_networks.items():
            assert set(selfhosted["services"][name]["networks"]) == networks
        run(["docker", "run", "--rm", "--network", "none",
             "-e", "APP_DOMAIN=app.example.test", "-e", "GRAFANA_DOMAIN=metrics.example.test", "-e", "CADDY_ACME_EMAIL=",
             "--mount", f"type=bind,source={ROOT / 'docker/caddy/Caddyfile'},target=/etc/caddy/Caddyfile,readonly",
             selfhosted["services"]["caddy"]["image"], "caddy", "validate", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"])
        print("PASS: self-hosted HTTPS configuration and private service ports", flush=True)
        resolved = run([*compose, "-f", str(ROOT / "docker-compose.yml"), "config", "--format", "json"], capture=True)
        config = isolate_config(json.loads(resolved.stdout), project)
        prometheus_config = temporary / "prometheus"
        isolate_prometheus(config, ROOT / "docker/prometheus", prometheus_config)
        for volume in config["services"]["alertmanager"]["volumes"]:
            if volume.get("target") == "/etc/alertmanager":
                volume["source"] = str(alert_config)
        # No external notification route exists in verification, even if a secret leaks into the environment.
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
        config_path = temporary / "compose.json"
        config_path.touch(mode=0o600)
        config_path.write_text(json.dumps(config), encoding="utf-8")
        compose.extend(["-f", str(config_path)])
        prom_image = config["services"]["prometheus"]["image"]
        promtool = ["docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/promtool",
                    "--mount", f"type=bind,source={prometheus_config},target=/etc/prometheus,readonly",
                    prom_image]
        # Run both syntax validation and executable alert/recording-rule regression cases.
        run([*promtool, "check", "config", "/etc/prometheus/prometheus.yml"])
        for fixture in sorted((prometheus_config / "tests").glob("*.test.yml")):
            run([*promtool, "test", "rules", "/etc/prometheus/tests/" + fixture.name])
        run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/amtool",
             "--mount", f"type=bind,source={alert_config},target=/etc/alertmanager,readonly",
             config["services"]["alertmanager"]["image"], "check-config", "/etc/alertmanager/alertmanager.yml"])
        setup_spec = importlib.util.spec_from_file_location("alerting_setup", ROOT / "bin/configure-alerting.py")
        setup = importlib.util.module_from_spec(setup_spec)
        setup_spec.loader.exec_module(setup)
        email_config = setup.configure_email(temporary / "email-secrets", "smtp.example.test:587",
            "sender@example.test", "operator@example.test", "sender@example.test", "test-only-password",
            "https://uptime.betterstack.com/api/v1/heartbeat/test-only-token")
        run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/amtool",
             "--mount", f"type=bind,source={email_config},target=/etc/alertmanager,readonly",
             config["services"]["alertmanager"]["image"], "check-config", "/etc/alertmanager/alertmanager.yml"])
        print("PASS: direct SMTP configuration with STARTTLS and a private password file", flush=True)
        old_sqlite = temporary / "grafana.db"
        old_sqlite.touch()
        guarded = run(["docker", "run", "--rm", "--network", "none", "--entrypoint", "/bin/sh",
                       "--mount", f"type=bind,source={ROOT / 'docker/grafana/start.sh'},target=/start.sh,readonly",
                       "--mount", f"type=bind,source={old_sqlite},target=/var/lib/grafana/grafana.db,readonly",
                       config["services"]["grafana"]["image"], "/start.sh"], check=False, capture=True)
        assert guarded.returncode == 1 and "Existing Grafana SQLite data found" in guarded.stderr
        print("PASS: existing SQLite storage cannot be silently replaced", flush=True)
        if options.config_only:
            return 0
        # Production-cadence fixtures above validate accounting semantics. In the
        # disposable smoke only, evaluate reporting groups each minute so every
        # rule executes without waiting an hour. These overlapping smoke rollups
        # must never be used as monthly reliability evidence. Alert windows stay unchanged.
        for filename in ("slo.yml", "slo-coverage.yml", "slo-history.yml"):
            rule_path = prometheus_config / "rules" / filename
            rule_path.write_text(rule_path.read_text().replace("interval: 1h", "interval: 1m")
                                 .replace("interval: 15m", "interval: 1m"))
        try:
            print(f"Starting disposable monitoring project {project}", flush=True)
            run([*compose, "up", "--build", "-d", "--wait", "--wait-timeout", "240"])

            def url(service, port):
                address = run([*compose, "port", service, str(port)], capture=True).stdout.strip()
                if not address.startswith("127.0.0.1:"):
                    raise AssertionError(f"Unexpected verification binding for {service}")
                return f"http://{address}"

            backend, prometheus, grafana = url("backend", 8080), url("prometheus", 9090), url("grafana", 3000)
            receiver = url("receiver", 8080)
            for name in ("prometheus", "grafana", "grafana-db", "alertmanager"):
                state = json.loads(run(["docker", "inspect", f"{project}-{name}"], capture=True).stdout)[0]
                assert state["HostConfig"]["Memory"] > 0 and state["HostConfig"]["NanoCpus"] > 0
                assert not state["State"]["OOMKilled"]
            role = run([*compose, "exec", "-T", "grafana-db", "psql", "-U", "postgres", "-Atc",
                        "SELECT rolsuper,rolcreatedb,rolcreaterole,rolreplication FROM pg_roles WHERE rolname='grafana'"], capture=True)
            assert role.stdout.strip() == "f|f|f|f", "Grafana role has excessive privileges"
            print("PASS: enforced container budgets and restricted Grafana database role", flush=True)
            database_state = json.loads(run(["docker", "inspect", f"{project}-grafana-db"], capture=True).stdout)[0]
            database_ip = database_state["NetworkSettings"]["Networks"][f"{project}_grafana_database"]["IPAddress"]
            probe = ("import socket,sys; s=socket.socket(); s.settimeout(3); "
                     "connected=s.connect_ex((sys.argv[1],5432))==0; "
                     "assert connected == (sys.argv[2]=='allow'), 'unexpected database reachability'")
            for network, expected in (("grafana_database", "allow"), ("application_ingress", "deny"),
                                      ("grafana_ingress", "deny"), ("application_data", "deny")):
                run(["docker", "run", "--rm", "--network", f"{project}_{network}",
                     "python:3.13-alpine", "python", "-c", probe, database_ip, expected], timeout=20)
            print("PASS: Grafana database reachable only on its data network", flush=True)
            eventually("real application health", lambda: request(backend + "/api/health")["status"] == "UP")
            request(backend + "/api/v1/monitoring", expected=401)
            request(backend + "/actuator/prometheus", expected=401)
            token = request(backend + "/api/v1/auth/login", data={
                "email": "admin@example.test", "password": environment["APP_BOOTSTRAP_ADMIN_PASSWORD"],
            })["token"]
            auth = {"Authorization": "Bearer " + token}
            assert request(backend + "/api/v1/monitoring", headers=auth) == {
                "enabled": True, "grafanaUrl": "http://localhost:3000",
            }
            request(backend + "/actuator/prometheus", headers=auth, expected=403)
            staff = request(backend + "/api/v1/auth/login", data={
                "email": "staff@example.test", "password": environment["APP_BOOTSTRAP_STAFF_PASSWORD"],
            })["token"]
            request(backend + "/api/v1/monitoring", headers={"Authorization": "Bearer " + staff}, expected=403)
            for _ in range(8):
                request(backend + "/api/v1/inventory/products", headers=auth)

            def query(expression):
                result = request(prometheus + "/api/v1/query?" + urllib.parse.urlencode({"query": expression}))
                assert result["status"] == "success", "Prometheus query failed"
                return result["data"]["result"]

            eventually("Prometheus scrapes real application", lambda: any(
                float(item["value"][1]) == 1 for item in query('up{job="spring-backend"}')))
            for metric in ("http_server_requests_seconds_bucket", "jvm_memory_used_bytes",
                           "hikaricp_connections_max", "nexus_inventory_products", "nexus_orders"):
                eventually(f"live {metric}", lambda metric=metric: bool(query(metric)))
            for component in ("business", "dependencies"):
                eventually(f"fresh successful {component} snapshot", lambda component=component: bool(query(
                    'nexus_observability_refresh_success{component="' + component + '"} == 1 '
                    'and on(job,instance,component) (time() - '
                    'nexus_observability_last_success_timestamp_seconds{component="' + component + '"} < 60)')))
            for metric in ("nexus_inventory_products", "nexus_inventory_low_stock_products",
                           "nexus_inventory_units", "nexus_inventory_value", "nexus_orders"):
                values = query(metric)
                assert values and all(math.isfinite(float(item["value"][1])) for item in values), \
                    f"Missing or non-finite business values: {metric}"
            assert {item["metric"]["status"] for item in query("nexus_orders")} == {
                "DRAFT", "PENDING_APPROVAL", "APPROVED", "SHIPPED", "DELIVERED", "CANCELLED",
            }, "Missing order lifecycle series"
            dependencies = query("nexus_dependency_up")
            assert {item["metric"]["dependency"] for item in dependencies} == {"cache", "events"}
            assert all(float(item["value"][1]) == 1 for item in dependencies), "Dependencies are degraded"
            # Authentication stays separate; a website bearer token must not grant Grafana access.
            request(grafana + "/api/search", expected=401)
            request(grafana + "/api/search", headers=auth, expected=401)
            grafana_auth = {"Authorization": "Basic " + base64.b64encode(
                ("admin:" + environment["GRAFANA_ADMIN_PASSWORD"]).encode()).decode()}
            eventually("Grafana datasource reaches Prometheus", lambda:
                       request(grafana + "/api/datasources/uid/nexus-prometheus/health", headers=grafana_auth)["status"] == "OK")
            for uid in ("nexus-overview", "nexus-supply-chain", "nexus-service-objectives"):
                dashboard = request(grafana + "/api/dashboards/uid/" + uid, headers=grafana_auth)
                assert dashboard["dashboard"]["panels"], f"Empty dashboard: {uid}"
                assert dashboard["meta"]["provisioned"], f"Dashboard is not provisioned: {uid}"
                for panel in dashboard["dashboard"]["panels"]:
                    for target in panel.get("targets", []):
                        expression = target.get("expr", "")
                        if expression:
                            expression = expression.replace("$instance", ".*").replace(
                                "$environment", "monitoring-test").replace("$__rate_interval", "1m").replace("$slo", "inventory-availability")
                            assert "$" not in expression, "Unresolved dashboard query variable"
                            query(expression)  # Empty traffic/alert results are valid; invalid PromQL is not.
            def rules_ready():
                groups = request(prometheus + "/api/v1/rules")["data"]["groups"]
                for group in groups:
                    for rule in group["rules"]:
                        if rule["health"] == "err":
                            raise ValueError("Rule evaluation failed: " + rule["name"])
                return bool(groups) and all(rule["health"] == "ok"
                    for group in groups for rule in group["rules"])
            eventually("all live rules evaluate successfully", rules_ready)
            print("PASS: admin authorization, private metrics, Grafana login, provisioned dashboards and live rules", flush=True)
            assert query('http_server_requests_seconds_bucket{uri="/api/v1/inventory/products",le="0.5"}'), \
                "Missing exact inventory latency bucket"
            # A new installation must never display a complete month as healthy.
            assert not query('nexus:slo_attainment:ratio30d'), "New stack reports a complete SLO month"
            if options.baseline_report:
                baseline_environment = {**environment, "LOAD_TEST_ADMIN_EMAIL": "admin@example.test",
                                        "LOAD_TEST_ADMIN_PASSWORD": environment["APP_BOOTSTRAP_ADMIN_PASSWORD"]}
                subprocess.run([sys.executable, str(ROOT / "load-tests/slo-baseline.py"),
                                "--base-url", backend, "--disposable-project", project,
                                "--output", str(options.baseline_report.resolve())],
                               env=baseline_environment, check=True, timeout=120)
                print("PASS: reproducible inventory/order baseline saved privately", flush=True)
            # Round-trip a logical backup into a new database on this disposable PostgreSQL instance.
            restored_password = secrets.token_hex(24)
            request(grafana + "/api/admin/users", headers=grafana_auth, data={
                "name": "Restore verification", "login": "restore-check", "email": "restore@example.test",
                "password": restored_password,
            })
            with (temporary / "grafana.dump").open("wb") as backup:
                subprocess.run([*compose, "exec", "-T", "grafana-db", "pg_dump", "-U", "postgres",
                                "-Fc", "--no-owner", "grafana"], env=environment, stdout=backup, check=True)
            run([*compose, "exec", "-T", "grafana-db", "createdb", "-U", "postgres", "-O", "grafana", "restore_check"])
            with (temporary / "grafana.dump").open("rb") as backup:
                subprocess.run([*compose, "exec", "-T", "grafana-db", "pg_restore", "-U", "postgres",
                                "--exit-on-error", "--no-owner", "--role=grafana", "-d", "restore_check"],
                               env=environment, stdin=backup, check=True)
            config["services"]["grafana"]["environment"]["GF_DATABASE_NAME"] = "restore_check"
            config_path.write_text(json.dumps(config), encoding="utf-8")
            run([*compose, "up", "-d", "--no-deps", "--force-recreate", "--wait", "grafana"])
            grafana = url("grafana", 3000)
            restored_auth = {"Authorization": "Basic " + base64.b64encode(
                ("restore-check:" + restored_password).encode()).decode()}
            assert request(grafana + "/api/user", headers=restored_auth)["login"] == "restore-check"
            for uid in ("nexus-overview", "nexus-supply-chain", "nexus-service-objectives"):
                assert request(grafana + "/api/dashboards/uid/" + uid, headers=grafana_auth)["dashboard"]["panels"]
            eventually("restored Grafana datasource is healthy", lambda:
                       request(grafana + "/api/datasources/uid/nexus-prometheus/health", headers=grafana_auth)["status"] == "OK")
            print("PASS: restored PostgreSQL serves Grafana login, dashboards and datasource", flush=True)

            def delivered(alert, status, endpoint):
                return any(event["path"] == endpoint and any(
                    item["labels"].get("alertname") == alert and item["status"] == status
                    for item in event["payload"].get("alerts", [])) for event in request(receiver + "/events"))

            eventually("watchdog traverses Prometheus and Alertmanager", lambda:
                       delivered("NexusWatchdog", "firing", "/watchdog"))
            run([*compose, "stop", "backend"])
            eventually("backend outage appears in Prometheus", lambda: any(
                float(item["value"][1]) == 0 for item in query('up{job="spring-backend"}')), timeout=60)
            eventually("backend outage notification delivered", lambda:
                       delivered("NexusBackendDown", "firing", "/operator"), timeout=210)
            run([*compose, "start", "backend"])
            eventually("scraping recovers after backend restart", lambda: any(
                float(item["value"][1]) == 1 for item in query('up{job="spring-backend"}')))
            eventually("backend recovery notification delivered", lambda:
                       delivered("NexusBackendDown", "resolved", "/operator"))
            assert not delivered("NexusWatchdog", "firing", "/operator"), "Watchdog leaked into operator alerts"
            print("Monitoring verification passed.", flush=True)
        except (subprocess.SubprocessError, OSError, AssertionError, KeyError, ValueError) as error:
            status = 1
            print(f"Monitoring verification failed: {error}", file=sys.stderr)
            logs = run([*compose, "logs", "--no-color", "--tail", "60"], capture=True, check=False)
            redacted = logs.stdout + logs.stderr
            for key, value in environment.items():
                if value and any(word in key for word in ("PASSWORD", "SECRET")):
                    redacted = redacted.replace(value, "[redacted]")
            print(redacted, file=sys.stderr)
        finally:
            # This project owns every container, network, volume and built image it removes.
            cleanup = run([*compose, "down", "--volumes", "--rmi", "local", "--remove-orphans"], check=False)
            if cleanup.returncode:
                print(f"Cleanup failed for {project}; inspect this disposable project.", file=sys.stderr)
                status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
