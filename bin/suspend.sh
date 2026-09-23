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

terraform -chdir="$TF_DIR" apply -var="enable_compute=false"
az postgres flexible-server stop --resource-group "$RG_NAME" --name "$DB_NAME"
printf 'Environment suspend completed.\n'
