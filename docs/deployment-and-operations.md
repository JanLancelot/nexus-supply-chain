# Deployment and operations

## What is provisioned

[Terraform](../terraform/main.tf) defines a resource group, Basic container registry, separate production/staging PostgreSQL Flexible Servers and Managed Redis instances, an S1 Linux App Service plan, one Web App, and a staging slot. The app and slot serve the combined frontend/backend image. Production and staging use separate datastores, database credentials, bootstrap accounts, and JWT signing keys. They share only the App Service plan and image registry. Datastore/signing settings are slot-sticky.

The configuration does not provision Key Vault, a Log Analytics workspace, database zone redundancy, private endpoints, or automated slot swaps. Application logs use the configured Spring console output.

## Secrets and bootstrap accounts

Supply `TF_VAR_postgres_admin_password`, `TF_VAR_jwt_secret`, `TF_VAR_staging_postgres_admin_password`, and `TF_VAR_staging_jwt_secret` through your secret-management process. Staging must use different database and signing secrets; Terraform rejects equality. Never commit Terraform state, plan files, `.tfvars`, or `.env`. Terraform's sensitive flag hides values in normal output; state still contains secrets. Use a secured backend with access controls and backups. See [HashiCorp's sensitive-data guidance](https://developer.hashicorp.com/terraform/language/manage-sensitive-data).

For first startup, supply `TF_VAR_bootstrap_admin_email` and `TF_VAR_bootstrap_admin_password` for production and the corresponding `TF_VAR_staging_bootstrap_admin_email` / `TF_VAR_staging_bootstrap_admin_password` for staging. Remove bootstrap values after the account is created. Demo data is disabled in Terraform. Existing known-password accounts are not automatically removed or reset by this code change; rotate or disable them explicitly.

A formerly tracked Terraform backup contained PostgreSQL and ACR credentials. Removing the working copy is not credential rotation or Git-history cleanup. Rotate the affected credentials, rotate the previous JWT signing key, invalidate old access where possible, and coordinate any history rewrite with collaborators. Do not restore the old backup into source control.

## PostgreSQL access

`postgres_allowed_ips` and `staging_postgres_allowed_ips` map rule names to individual approved IPv4 addresses for their respective servers. The shared App Service plan can share outbound IPs, so these allowlists are not an isolation boundary between slots. Separate database servers and credentials enforce the data boundary; controlled VNet/NAT/private networking is required for stronger network separation. See [App Service outbound addresses](https://learn.microsoft.com/en-us/azure/app-service/networking-features#outbound-addresses). The default empty map denies client access; the previous all-Azure-services rule has been removed. That rule allowed resources in other Azure subscriptions to reach the database login boundary, as described in [Microsoft's firewall documentation](https://learn.microsoft.com/en-us/azure/postgresql/security/security-firewall-rules).

For an existing deployment, obtain the production and staging app's possible outbound IP addresses from Azure, populate each server's map, and review the plan. For a new deployment, the app resources may need to be created first to discover these addresses; then apply the allowlist and verify startup. Terraform exposes both address lists as outputs. Review them again when changing the plan or recreating compute resources. Private networking with controlled egress is a future improvement.

Database JDBC URLs use `sslmode=verify-full` with the JVM default trust store to verify certificate trust and hostname. Ensure the runtime JRE trusts the managed database certificate chain before deployment; production connectivity was not exercised locally. See [pgJDBC TLS configuration](https://jdbc.postgresql.org/documentation/ssl/).

## Application and management listeners

App Service and its staging slot require HTTPS and TLS 1.2 or newer. FTP and basic publishing authentication are disabled. The application listener is port 8080. `/api/health` checks the database, event mode/readiness, and cache availability. Database failure or unavailable required Kafka returns 503; an optional fallback or cache bypass is reported as DEGRADED with HTTP 200.

The management listener defaults to `127.0.0.1:9091`. It exposes health and Prometheus metrics without publishing them through the application listener. Compose sets its address to `0.0.0.0` for internal Prometheus scraping but does not publish that port. Keep this listener on a trusted network if overriding the bind address. No HSTS policy was added.

See [Grafana and monitoring](observability.md) for provisioned dashboards, alert rules,
private access, retention, upgrades, and receiver setup. Terraform's optional
`monitoring_grafana_url` and `staging_monitoring_grafana_url` only configure each slot's
admin website link; they do not create a collector or Grafana. Collection from the
existing single-container App Service still needs a private collector deployment.
Monitoring URLs and metric environment labels remain attached to their slots.

For self-hosting the entire application alongside Grafana, the
[HTTPS Compose overlay](observability.md#production-and-azure) adds Caddy, derives
the browser URLs/CORS settings from separate domains, and publishes only ingress
ports. It is independent of Terraform and requires no Azure resources.

Login is limited to 30 attempts per minute per socket-peer IP, per application instance (`APP_LOGIN_MAX_ATTEMPTS_PER_MINUTE`). The counter table is bounded at 10,000 peers (`APP_LOGIN_MAX_TRACKED_CLIENTS`). Forwarding headers are not trusted. Users behind a proxy share that peer limit; configure trusted gateway rate limiting and capacity deliberately before rollout. Diagnostic authentication stress tests need a higher limit only in a disposable environment.

## CI and deployment

[ci.yml](../.github/workflows/ci.yml) runs Maven verification, frontend lint, and a production frontend build. On a successful push to `main`, it builds and pushes the combined image and deploys the commit tag to the `staging` slot. It does not promote the slot to production or move a shared `latest` tag. `production_image_tag` must identify an existing published commit SHA. `staging_image_tag` optionally sets the initial staging image; after provisioning CI owns that slot's image revision, which Terraform deliberately ignores. Set an explicit staging tag before recreating suspended compute to choose what it resumes with.

Terraform creates a user-assigned deployment identity, OIDC federation limited to `github_repository` on `refs/heads/main`, registry `AcrPush`, parent-app `Reader`, and staging-slot `Website Contributor`. Set GitHub repository variables `AZURE_CLIENT_ID` and `AZURE_TENANT_ID` from the `github_azure_client_id` and `github_azure_tenant_id` outputs, plus `AZURE_SUBSCRIPTION_ID`. Existing secrets with those names remain a fallback. Set `AZURE_CONTAINER_REGISTRY` and `AZURE_WEBAPP_NAME` if using nondefault names. Registry login uses `az acr login`; the workflow no longer requires `ACR_USERNAME`/`ACR_PASSWORD`. OIDC token permission is restricted to the deployment job. App Service and staging use system-assigned identities with `AcrPull`; registry admin authentication is disabled in Terraform. The Terraform caller needs permission to create those role assignments. Verify identity propagation before removing existing registry credentials from a live deployment.

Verify role assignments and secrets in Azure and GitHub before using the workflow.

## Events and releases

Notification and legacy-audit consumers retain their existing Kafka group identities. Cache and replenishment subscribers have separate groups that begin at the earliest retained offset when new; replenishment rechecks current stock, sourcing, and open orders. Review offsets before deployment if retained history should not be replayed. Kafka is not provisioned by the Terraform configuration; without external Kafka, the app uses its Redis/memory fallback. See [event limitations](data-flow-and-integration.md).

After deployment, verify login, staff/admin permissions, a stock adjustment, the order lifecycle, notification counts, and metrics scraping. Check logs for failed migrations or event handlers. Automated build success alone does not prove a deployed environment is healthy.

## Suspend and resume

`bin/suspend.sh` destroys the compute resources and Redis after Terraform presents a plan for confirmation, then stops both PostgreSQL servers. Database storage and registry images remain. Redis cache and queued data are lost; the scripts are not a production availability strategy.

`bin/resume.sh` starts both PostgreSQL servers and recreates compute resources. Both scripts read production and staging server names from Terraform outputs and stop on failure instead of reporting false success. Older state may need its outputs refreshed before the `postgres_server_name` and `staging_postgres_server_name` outputs exist.

Stopping PostgreSQL does not eliminate storage costs, and Azure can automatically restart it after its allowed stop interval. See [Microsoft's stop/start documentation](https://learn.microsoft.com/en-us/azure/postgresql/configure-maintain/how-to-stop-server). Recheck the database firewall after recreating App Service.

## Adopting the isolated staging configuration

New installations default to `nexussupplyregistry` and `nexus-supply-api`. Before upgrading an existing installation, set `acr_name` and `web_app_name` to its actual deployed resource names, and set the matching GitHub `AZURE_CONTAINER_REGISTRY` and `AZURE_WEBAPP_NAME` variables. This preserves existing resources instead of replacing them because a naming default changed.

Application packages use `com.nexus.supplychain`. Redis response caches use the `nexus:v2:` namespace so deployments do not deserialize entries containing obsolete Java class names. Old response entries expire normally; domain event topics and persisted database identifiers are unchanged. Demo staff accounts now use `staff@example.test`.

Review and apply the Terraform plan through the normal deployment process; editing this repository does not migrate a running cloud environment. Existing production resource addresses stay unchanged. New staging PostgreSQL and Redis resources start empty. Provision a staging administrator and use Reference Data for fixtures; no production data is copied automatically. The independent JWT key invalidates previously shared staging sessions. Both environments now incur separate datastore costs.

Before initial provisioning, publish a known commit image to ACR and set `production_image_tag` to its SHA. If the registry/identity do not exist yet, provision those foundational resources first, configure the GitHub variables from outputs, publish the image, then complete app provisioning and each database allowlist. A missing image, credentials, federation, or firewall rule is a deployment prerequisite, not something local tests can establish.

Production schema startup now validates Liquibase-owned tables. Migration 1.6 repairs the previous money-column widening; it halts on out-of-range or fractional-cent legacy values. Back up and inspect an existing database before deployment, resolve invalid values explicitly, and retry. Previously misattributed historical stock is not guessed or rewritten. A legacy open order whose product warehouse differs from its destination is blocked from delivery; cancel it and recreate a compatible order.

Kafka processing failures retry and then enter subscriber-specific dead-letter topics. Monitor those topics and replay messages only after fixing the cause. There is still no transactional outbox for domain-event publication, so this release does not claim guaranteed event delivery.

`terraform test` uses the checked-in mock provider and never creates Azure resources. It verifies datastore/key isolation, slot-sticky settings, scoped deployment permissions, and rejection of a shared signing key. Local Terraform 1.7 or newer is required for these tests; CI pins its tool version.
