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

# 2. Stop the PostgreSQL flexible server compute
echo "-> Stopping PostgreSQL Flexible Server (preserving data storage)..."
# Using resource group and name from variables/defaults
RG_NAME="rg-nexus-supply-prod"
DB_NAME="postgres-nexus-supply-prod"

if command -v az &> /dev/null; then
  az postgres flexible-server stop --resource-group "$RG_NAME" --name "$DB_NAME" || {
    echo "⚠️ Warning: Failed to stop PostgreSQL via Azure CLI. Please run manually:"
    echo "  az postgres flexible-server stop --resource-group $RG_NAME --name $DB_NAME"
  }
else
  echo "❌ Error: Azure CLI ('az') not found in PATH."
  echo "Please install Azure CLI or manually stop the database via the portal."
fi

echo "================================================================="
echo "✅ Environment suspended successfully! Cost bleeding stopped."
echo "================================================================="
