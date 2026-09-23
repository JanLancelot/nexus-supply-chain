#!/usr/bin/env bash
# Run the same checks locally and in CI, from any working directory.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-all}"
if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [all|frontend|backend|containers]" >&2
  exit 2
fi

frontend() {
  cd "$repo_root/frontend"
  if [[ ! -d node_modules ]]; then
    echo "Install frontend dependencies first: cd $repo_root/frontend && npm ci" >&2
    exit 1
  fi
  npm run check
}

backend() {
  cd "$repo_root/backend"
  ./mvnw -B -ntp clean verify "$@"
}

case "$mode" in
  all) frontend; backend ;;
  frontend) frontend ;;
  backend) backend ;;
  containers)
    if ! docker info >/dev/null 2>&1; then
      echo "Container verification requires a running Docker daemon. Start Docker and retry." >&2
      exit 1
    fi
    backend -Pintegration
    ;;
  *) echo "Usage: $0 [all|frontend|backend|containers]" >&2; exit 2 ;;
esac
