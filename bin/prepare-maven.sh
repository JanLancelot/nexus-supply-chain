#!/usr/bin/env bash
# Fetch the pinned Maven distribution before application/test startup.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

for bootstrap_attempt in 1 2 3; do
  if MVNW_VERBOSE=true "$repo_root/backend/mvnw" -B -ntp --version; then
    exit 0
  else
    bootstrap_status=$?
  fi
  if [[ "$bootstrap_attempt" -lt 3 ]]; then
    printf 'Maven bootstrap attempt %s failed (exit %s); retrying distribution setup.\n' \
      "$bootstrap_attempt" "$bootstrap_status" >&2
    sleep "$((bootstrap_attempt * 5))"
  fi
done

printf 'Maven bootstrap failed after 3 attempts; tests have not started.\n' >&2
exit "$bootstrap_status"
