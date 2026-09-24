#!/usr/bin/env bash
# Run the same checks locally and in CI, from any working directory.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-all}"
if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [all|frontend|backend|containers|monitoring|e2e|visual|full]" >&2
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
  all)
    frontend
    backend
    python3 -m unittest discover -s "$repo_root/load-tests" -p 'test_*.py'
    python3 -m unittest discover -s "$repo_root/tests" -p 'test_*.py'
    node --test "$repo_root/load-tests/lib/order-fixtures.test.mjs"
    ;;
  frontend) frontend ;;
  backend) backend ;;
  containers)
    if ! docker info >/dev/null 2>&1; then
      echo "Container verification requires a running Docker daemon. Start Docker and retry." >&2
      exit 1
    fi
    backend -Pintegration
    ;;
  e2e) cd "$repo_root/frontend"; npm run test:e2e:all ;;
  visual) "$repo_root/bin/visual-tests.sh" ;;
  monitoring) "$repo_root/bin/verify-monitoring.sh" ;;
  full)
    "$repo_root/bin/verify.sh" all
    "$repo_root/bin/verify.sh" containers
    "$repo_root/bin/verify.sh" monitoring
    "$repo_root/bin/verify.sh" e2e
    "$repo_root/bin/verify.sh" visual
    ;;
  *) echo "Usage: $0 [all|frontend|backend|containers|monitoring|e2e|visual|full]" >&2; exit 2 ;;
esac
