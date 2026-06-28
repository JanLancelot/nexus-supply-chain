#!/bin/bash
# resume.sh - Start PostgreSQL server and recreate compute resources (App Service, Redis)
set -e

# Determine the directory of this script
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$DIR/../terraform"

echo "================================================================="
echo "🟢 RESUMING AZURE ENVIRONMENT"
echo "================================================================="

# 1. Start the PostgreSQL flexible server compute first
echo "-> Starting PostgreSQL Flexible Server..."
RG_NAME="rg-nexus-supply-prod"
DB_NAME="postgres-nexus-supply-prod"

if command -v az &> /dev/null; then
  az postgres flexible-server start --resource-group "$RG_NAME" --name "$DB_NAME" || {
    echo "⚠️ Warning: Failed to start PostgreSQL via Azure CLI. Will attempt to apply Terraform anyway."
  }
else
  echo "❌ Error: Azure CLI ('az') not found in PATH."
  echo "Please start the PostgreSQL database manually via the portal, then press Enter to continue..."
  read -r
fi

# 2. Scale up/recreate compute resources using Terraform
echo "-> Recreating compute resources (App Service, Redis, ASP)..."
cd "$TF_DIR"
terraform apply -var="enable_compute=true" -auto-approve

echo "================================================================="
echo "✅ Environment resumed successfully!"
echo "================================================================="
