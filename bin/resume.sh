#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TF_DIR="$SCRIPT_DIR/../terraform"
for tool in terraform az; do
  command -v "$tool" >/dev/null || { echo "Required command missing: $tool" >&2; exit 1; }
done
# Read the selected environment from state instead of targeting a hardcoded deployment.
RG_NAME="$(terraform -chdir="$TF_DIR" output -raw resource_group_name)"
DB_NAME="$(terraform -chdir="$TF_DIR" output -raw postgres_server_name)"
STAGING_DB_NAME="$(terraform -chdir="$TF_DIR" output -raw staging_postgres_server_name)"
if [[ -z "${RG_NAME//[[:space:]]/}" || -z "${DB_NAME//[[:space:]]/}" || -z "${STAGING_DB_NAME//[[:space:]]/}" ]]; then
  printf 'Terraform returned an empty resource group or database name; refresh state outputs before retrying.\n' >&2
  exit 1
fi

az postgres flexible-server start --resource-group "$RG_NAME" --name "$DB_NAME"
az postgres flexible-server start --resource-group "$RG_NAME" --name "$STAGING_DB_NAME"
terraform -chdir="$TF_DIR" apply -var="enable_compute=true"
printf 'Environment resume completed.\n'
