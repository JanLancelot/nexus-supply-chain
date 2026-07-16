#!/bin/bash
# suspend.sh - Stop PostgreSQL server and scale down compute resources (App Service, Redis)
set -e

# Determine the directory of this script
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$DIR/../terraform"

echo "================================================================="
echo "🔴 SUSPENDING AZURE ENVIRONMENT (STOP COST BLEEDING)"
echo "================================================================="

# 1. Scale down/destroy compute resources using Terraform
echo "-> Destroying compute resources (App Service, Redis, ASP)..."
cd "$TF_DIR"
terraform apply -var="enable_compute=false" -auto-approve

# 2. Database fully destroyed via Terraform
echo "-> PostgreSQL Flexible Server and Storage fully destroyed via Terraform."

echo "================================================================="
echo "✅ Environment suspended successfully! Cost bleeding stopped."
echo "================================================================="
