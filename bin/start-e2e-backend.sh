#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for required_variable in E2E_ADMIN_PASSWORD E2E_STAFF_PASSWORD E2E_JWT_SECRET; do
  if [[ -z "${!required_variable:-}" ]]; then
    printf 'Missing %s. Run through npm run test:e2e to generate temporary credentials.\n' "$required_variable" >&2
    exit 1
  fi
done
# Spring environment properties override profile files. Never inherit a real
# datastore/broker URL, seed account, or injected JVM configuration from the shell.
while IFS= read -r variable; do
  case "$variable" in
    SPRING_*|APP_*|JWT_*|SERVER_*|MANAGEMENT_*|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|MAVEN_OPTS|MAVEN_ARGS)
      unset "$variable"
      ;;
  esac
done < <(compgen -e)
cd "$repo_root/backend"
exec ./mvnw -B -ntp spring-boot:test-run \
  -Dspring-boot.run.main-class=com.nexus.supplychain.DemoApplication \
  -Dspring-boot.run.profiles=e2e
