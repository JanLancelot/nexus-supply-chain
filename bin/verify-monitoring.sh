#!/usr/bin/env bash
# Real, disposable application -> Prometheus -> Grafana verification.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$repo_root/bin/verify-monitoring.py" "$@"
