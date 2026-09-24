#!/usr/bin/env bash
# Read-only logical backup. Never overwrites an existing backup or modifies the database.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# != 1 || -e "$1" || -L "$1" ]]; then
  echo "Usage: $0 /secure/existing-directory/new-backup.dump (destination must not exist)" >&2
  exit 2
fi
umask 077
backup_tmp="$(mktemp "${1}.partial.XXXXXX")"
trap 'rm -f "$backup_tmp"' EXIT
docker compose --project-directory "$repo_root" -f "$repo_root/docker-compose.yml" \
  exec -T grafana-db pg_dump --username=postgres --format=custom --no-owner grafana > "$backup_tmp"
test -s "$backup_tmp"
# Hard-link creation is atomic and refuses to replace a raced-in destination.
ln "$backup_tmp" "$1"
echo "Grafana database backup saved. Keep its matching GRAFANA_SECRET_KEY in your separate secret backup."
