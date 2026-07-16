#!/bin/bash
# resume.sh - Start PostgreSQL server and recreate compute resources (App Service, Redis)
set -e

# Determine the directory of this script
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$DIR/../terraform"

echo "================================================================="
echo "🟢 RESUMING AZURE ENVIRONMENT"
echo "================================================================="

# 1. Scale up/recreate compute & database resources using Terraform
echo "-> Recreating compute and database resources (App Service, Redis, ASP, PostgreSQL)..."
cd "$TF_DIR"
terraform apply -var="enable_compute=true" -auto-approve

echo "================================================================="
echo "✅ Environment resumed successfully! Schema & seed data will be automatically populated on startup."
echo "================================================================="
