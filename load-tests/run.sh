#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RESULTS_DIR="$SCRIPT_DIR/results"

mkdir -p "$RESULTS_DIR"

run_k6() {
  local script="$1"
  shift
  (cd "$SCRIPT_DIR" && k6 run "$@" "$script")
}

run_k6_docker() {
  local script="$1"
  shift
  local network="${K6_DOCKER_NETWORK:-nexus-supply-chain_default}"
  local base_url="${BASE_URL:-http://backend:8080}"

  docker run --rm -i \
    --memory="${K6_DOCKER_MEMORY:-512m}" \
    --network="$network" \
    -e BASE_URL="$base_url" \
    -e LOAD_TEST_SCALE="${LOAD_TEST_SCALE:-1}" \
    -e DIAG_PHASE_DURATION="${DIAG_PHASE_DURATION:-40s}" \
    -e LOAD_TEST_SAMPLE_SIZE="${LOAD_TEST_SAMPLE_SIZE:-50}" \
    -e K6_FULL_SUMMARY="${K6_FULL_SUMMARY:-0}" \
    -v "$SCRIPT_DIR:/app" \
    -w /app \
    grafana/k6 run "$@" "$script"
}

print_usage() {
  cat <<EOF
Usage: ./load-tests/run.sh [profile] [k6 options...]

Profiles:
  (default)    Full load test — ramp to 300 VUs, mixed staff/admin workflows
  smoke        Quick sanity check — 1 VU for 10 seconds
  stress       High-load stress test — ramp to 1000 VUs
  diagnostic   Endpoint isolation — sequential scenarios (~7 min, memory-safe)

Environment variables:
  LOAD_TEST_SCALE=0.5       Reduce VUs if you still hit OOM (default: 1)
  DIAG_PHASE_DURATION=30s   Duration per diagnostic phase (default: 40s)
  K6_DOCKER_MEMORY=512m     Docker memory limit for k6 container
  K6_FULL_SUMMARY=1         Write full k6 summary JSON (uses more memory)

Results are written to load-tests/results/:
  endpoints-breakdown.json   Per-endpoint latency ranked by p95
  full-summary.json          Complete k6 summary export

Examples:
  ./load-tests/run.sh
  ./load-tests/run.sh diagnostic
  ./load-tests/run.sh smoke --vus 3 --duration 30s
EOF
}

PROFILE="${1:-}"
if [ "$PROFILE" = "-h" ] || [ "$PROFILE" = "--help" ]; then
  print_usage
  exit 0
fi

if [ -n "$PROFILE" ] && [ "$PROFILE" != "smoke" ] && [ "$PROFILE" != "stress" ] && [ "$PROFILE" != "diagnostic" ]; then
  PROFILE=""
  K6_ARGS=("$@")
else
  shift || true
  K6_ARGS=("$@")
fi

if command -v k6 &> /dev/null; then
  echo "=================================================================="
  echo "Running load tests locally using system K6..."
  echo "Results directory: $RESULTS_DIR"
  echo "=================================================================="

  case "$PROFILE" in
    smoke)
      echo "Profile: Smoke Test (1 VU, 10s)"
      run_k6 k6-test.js --vus 1 --duration 10s "${K6_ARGS[@]}"
      ;;
    stress)
      echo "Profile: Stress Test (ramp to 1000 VUs)"
      run_k6 stress-test.js "${K6_ARGS[@]}"
      ;;
    diagnostic)
      echo "Profile: Diagnostic Test (sequential endpoint scenarios, max ~${LOAD_TEST_SCALE:-1}x12 VUs)"
      run_k6 diagnostic-test.js "${K6_ARGS[@]}"
      ;;
    *)
      echo "Profile: Full Load Test (ramp to 300 VUs)"
      run_k6 k6-test.js "${K6_ARGS[@]}"
      ;;
  esac
else
  echo "=================================================================="
  echo "K6 not found locally. Running via Docker..."
  echo "Results directory: $RESULTS_DIR"
  echo "=================================================================="

  if ! docker info &> /dev/null; then
    echo "Error: Docker is not running. Please start Docker and try again."
    exit 1
  fi

  case "$PROFILE" in
    smoke)
      echo "Profile: Smoke Test (1 VU, 10s)"
      run_k6_docker k6-test.js --vus 1 --duration 10s "${K6_ARGS[@]}"
      ;;
    stress)
      echo "Profile: Stress Test (ramp to 1000 VUs)"
      run_k6_docker stress-test.js "${K6_ARGS[@]}"
      ;;
    diagnostic)
      echo "Profile: Diagnostic Test (sequential endpoint scenarios, max ~${LOAD_TEST_SCALE:-1}x12 VUs)"
      run_k6_docker diagnostic-test.js "${K6_ARGS[@]}"
      ;;
    *)
      echo "Profile: Full Load Test (ramp to 300 VUs)"
      run_k6_docker k6-test.js "${K6_ARGS[@]}"
      ;;
  esac
fi

echo ""
echo "Endpoint breakdown saved to: $RESULTS_DIR/endpoints-breakdown.json"
