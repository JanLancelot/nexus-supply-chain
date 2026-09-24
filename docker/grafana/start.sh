#!/bin/sh
set -eu
if [ -f /var/lib/grafana/grafana.db ] && [ "${GRAFANA_SQLITE_MIGRATION_ACKNOWLEDGED:-false}" != true ]; then
  echo 'Existing Grafana SQLite data found. Back up and migrate/recreate accounts before switching stores; see docs/observability.md. Set GRAFANA_SQLITE_MIGRATION_ACKNOWLEDGED=true only after reviewing that migration.' >&2
  exit 1
fi
exec /run.sh "$@"
