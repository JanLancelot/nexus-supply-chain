#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

# Change directory to the repository root
CDPATH= cd "$(dirname "$0")/.."

# Check if k6 is installed locally
if command -v k6 &> /dev/null; then
    echo "=================================================================="
    echo "Running load tests locally using system K6..."
    echo "=================================================================="
    
    if [ "$1" = "smoke" ]; then
        shift
        echo "Profile: Smoke Test (1 VU, 10s)"
        k6 run --vus 1 --duration 10s "$@" load-tests/k6-test.js
    elif [ "$1" = "stress" ]; then
        shift
        echo "Profile: Stress Test (defined in stress-test.js)"
        k6 run "$@" load-tests/stress-test.js
    else
        echo "Profile: Full Load Test (defined in script)"
        k6 run "$@" load-tests/k6-test.js
    fi
else
    echo "=================================================================="
    echo "K6 not found locally. Running via Docker..."
    echo "=================================================================="
    
    # Check if Docker is running
    if ! docker info &> /dev/null; then
        echo "Error: Docker is not running. Please start Docker and try again."
        exit 1
    fi
    
    # We run within the nexus-supply-chain_default docker network so we can connect to the 'backend' container.
    NETWORK_NAME="nexus-supply-chain_default"
    BASE_URL="http://backend:8080"
    
    if [ "$1" = "smoke" ]; then
        shift
        echo "Profile: Smoke Test (1 VU, 10s)"
        docker run --rm -i \
            --network="$NETWORK_NAME" \
            -e BASE_URL="$BASE_URL" \
            -v "$(pwd)/load-tests:/app" \
            -w /app \
            grafana/k6 run --vus 1 --duration 10s "$@" k6-test.js
    elif [ "$1" = "stress" ]; then
        shift
        echo "Profile: Stress Test (defined in stress-test.js)"
        docker run --rm -i \
            --network="$NETWORK_NAME" \
            -e BASE_URL="$BASE_URL" \
            -v "$(pwd)/load-tests:/app" \
            -w /app \
            grafana/k6 run "$@" stress-test.js
    else
        echo "Profile: Full Load Test (defined in script)"
        docker run --rm -i \
            --network="$NETWORK_NAME" \
            -e BASE_URL="$BASE_URL" \
            -v "$(pwd)/load-tests:/app" \
            -w /app \
            grafana/k6 run "$@" k6-test.js
    fi
fi
