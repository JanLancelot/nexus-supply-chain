#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if ! docker info >/dev/null 2>&1; then
  echo "Visual tests require Docker for the canonical Linux browser and fonts." >&2
  exit 1
fi
docker build --platform linux/amd64 -f "$repo_root/docker/visual-tests.Dockerfile" \
  -t nexus-visual-tests:1.63.0 "$repo_root"
docker run --rm --init --ipc=host --platform linux/amd64 \
  -e CI="${CI:-}" \
  --mount "type=bind,source=$repo_root/frontend,target=/workspace/frontend" \
  --mount type=volume,target=/workspace/frontend/node_modules \
  --mount type=volume,target=/workspace/frontend/dist \
  nexus-visual-tests:1.63.0 \
  bash -c 'npm ci --ignore-scripts && exec npx playwright test --config=playwright.visual.config.ts "$@"' -- "$@"
