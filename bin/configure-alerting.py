#!/usr/bin/env python3
"""Prepare private webhook or SMTP alerting; never contact a service or print secrets."""
import getpass
import json
import os
import re
from pathlib import Path
import sys
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]


def validate_url(value, kind):
    parsed = urlsplit(value.strip())
    if (parsed.scheme != "https" or parsed.hostname != "uptime.betterstack.com"
            or parsed.username or parsed.password or parsed.port not in (None, 443)
            or parsed.query or parsed.fragment):
        raise ValueError("Use the original HTTPS URL from Better Stack without query parameters or credentials.")
    prefix = "/api/v1/prometheus/webhook/" if kind == "operator" else "/api/v1/heartbeat/"
    if not parsed.path.startswith(prefix) or not re.fullmatch(r"[A-Za-z0-9_-]+/?", parsed.path[len(prefix):]):
        raise ValueError(f"Expected the Better Stack {kind} integration URL.")
    return value.strip()


def configure(parent, operator_url, watchdog_url):
    operator_url = validate_url(operator_url, "operator")
    watchdog_url = validate_url(watchdog_url, "watchdog")
    return write_configuration(parent, (ROOT / "docker/alertmanager/alertmanager.example.yml").read_text(), {
        "operator-webhook-url": operator_url, "watchdog-webhook-url": watchdog_url,
    })


def configure_email(parent, smtp_host, sender, recipient, username, password, watchdog_url):
    watchdog_url = validate_url(watchdog_url, "watchdog")
    if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?:587", smtp_host):
        raise ValueError("Use your SMTP provider's STARTTLS hostname with port 587 (host.example:587).")
    for address in (sender, recipient):
        if not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}", address):
            raise ValueError("Enter a single email address without display names or line breaks.")
    if not username.strip() or not password or any(c in username + password for c in "\r\n\x00"):
        raise ValueError("An SMTP username and application password without line breaks are required.")
    template = (ROOT / "docker/alertmanager/alertmanager.email.example.yml").read_text()
    replacements = {"__RECIPIENT__": recipient, "__SENDER__": sender,
                    "__SMTP_HOST__": smtp_host, "__SMTP_USERNAME__": username}
    template = re.sub(r"__(?:RECIPIENT|SENDER|SMTP_HOST|SMTP_USERNAME)__",
                      lambda match: json.dumps(replacements[match.group()]), template)
    return write_configuration(parent, template, {"smtp-password": password, "watchdog-webhook-url": watchdog_url})


def write_configuration(parent, template, secrets):
    directory = parent / "alertmanager"
    if parent.is_symlink() or directory.exists() or directory.is_symlink():
        raise ValueError("Configuration already exists or the parent is a symlink; inspect it before replacing secrets.")
    parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    parent.chmod(0o700)
    directory.mkdir(mode=0o755)
    directory.chmod(0o755)
    # The private parent protects these on the host; the mounted child must be
    # readable by Alertmanager's unprivileged container UID.
    for name, value in secrets.items():
        path = directory / name
        path.write_text(value + "\n", encoding="utf-8")
        path.chmod(0o444)
    (directory / "alertmanager.yml").write_text(template, encoding="utf-8")
    (directory / "alertmanager.yml").chmod(0o444)
    return directory


def main():
    if sys.argv[1:] not in ([], ["--email"]):
        print("Usage: python3 bin/configure-alerting.py [--email] (secrets are entered privately)", file=sys.stderr)
        return 2
    os.umask(0o077)
    try:
        if sys.argv[1:] == ["--email"]:
            host = input("SMTP STARTTLS server (hostname:587): ").strip()
            sender = input("SMTP sender email: ").strip()
            recipient = input("Alert recipient email: ").strip()
            username = input("SMTP username: ").strip()
            password = getpass.getpass("SMTP application password (hidden): ")
            saved = ROOT / ".secrets/betterstack-watchdog-url"
            if saved.is_symlink():
                raise ValueError("Refusing a symlink for the saved heartbeat URL.")
            watchdog = saved.read_text().strip() if saved.is_file() else getpass.getpass("Better Stack heartbeat URL (hidden): ")
            directory = configure_email(ROOT / ".secrets", host, sender, recipient, username, password, watchdog)
        else:
            print("This webhook mode requires Better Stack's paid Prometheus integration. Use --email for direct SMTP alerts.")
            operator = getpass.getpass("Better Stack Prometheus integration URL (hidden): ")
            watchdog = getpass.getpass("Better Stack heartbeat URL (hidden): ")
            directory = configure(ROOT / ".secrets", operator, watchdog)
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(f"Saved private configuration. Add ALERTMANAGER_CONFIG_DIR={directory} to .env.")
    print("No requests were sent. Activate email recipients and the external website check in Better Stack.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
