# Deployment and operations

## What is provisioned

[Terraform](../terraform/main.tf) defines a resource group, Basic container registry, PostgreSQL Flexible Server, Managed Redis, an S1 Linux App Service plan, one Web App, and a staging slot. The app and slot serve the combined frontend/backend image. They share PostgreSQL and Redis, so staging writes affect the same data as production.

The configuration does not provision Key Vault, a Log Analytics workspace, database zone redundancy, private endpoints, or automated slot swaps. Application logs use the configured Spring console output.

## Secrets and bootstrap accounts

Supply `TF_VAR_postgres_admin_password` and `TF_VAR_jwt_secret` through your secret-management process. Never commit Terraform state, plan files, `.tfvars`, or `.env`. Terraform's sensitive flag hides values in normal output; state still contains secrets. Use a secured backend with access controls and backups. See [HashiCorp's sensitive-data guidance](https://developer.hashicorp.com/terraform/language/manage-sensitive-data).

For first startup, supply `TF_VAR_bootstrap_admin_email` and `TF_VAR_bootstrap_admin_password`. Remove bootstrap values after the account is created. Demo data is disabled in Terraform. Existing known-password accounts are not automatically removed or reset by this code change; rotate or disable them explicitly.

A formerly tracked Terraform backup contained PostgreSQL and ACR credentials. Removing the working copy is not credential rotation or Git-history cleanup. Rotate the affected credentials, rotate the previous JWT signing key, invalidate old access where possible, and coordinate any history rewrite with collaborators. Do not restore the old backup into source control.

## PostgreSQL access

`postgres_allowed_ips` maps rule names to individual approved IPv4 addresses. The default empty map denies client access; the previous all-Azure-services rule has been removed. That rule allowed resources in other Azure subscriptions to reach the database login boundary, as described in [Microsoft's firewall documentation](https://learn.microsoft.com/en-us/azure/postgresql/security/security-firewall-rules).

For an existing deployment, obtain the production and staging app's possible outbound IP addresses from Azure, populate the map, and review the plan. For a new deployment, the app resources may need to be created first to discover these addresses; then apply the allowlist and verify startup. Terraform exposes both address lists as outputs. Review them again when changing the plan or recreating compute resources. Private networking with controlled egress is a future improvement.

Database JDBC URLs use `sslmode=verify-full` with the JVM default trust store to verify certificate trust and hostname. Ensure the runtime JRE trusts the managed database certificate chain before deployment; production connectivity was not exercised locally. See [pgJDBC TLS configuration](https://jdbc.postgresql.org/documentation/ssl/).

## Application and management listeners

App Service and its staging slot require HTTPS and TLS 1.2 or newer. FTP and basic publishing authentication are disabled. The application listener is port 8080. `/api/health` checks a database query and returns a bounded status response.

The management listener defaults to `127.0.0.1:9091`. It exposes health and Prometheus metrics without publishing them through the application listener. Compose sets its address to `0.0.0.0` for internal Prometheus scraping but does not publish that port. Keep this listener on a trusted network if overriding the bind address. No HSTS policy was added.

Login is limited to 30 attempts per minute per socket-peer IP, per application instance (`APP_LOGIN_MAX_ATTEMPTS_PER_MINUTE`). The counter table is bounded at 10,000 peers (`APP_LOGIN_MAX_TRACKED_CLIENTS`). Forwarding headers are not trusted. Users behind a proxy share that peer limit; configure trusted gateway rate limiting and capacity deliberately before rollout. Diagnostic authentication stress tests need a higher limit only in a disposable environment.

## CI and deployment

[ci.yml](../.github/workflows/ci.yml) runs Maven verification, frontend lint, and a production frontend build. On a successful push to `main`, it builds and pushes the combined image and deploys the commit tag to the `staging` slot. It does not promote the slot to production.

GitHub's Azure identity needs `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, and `AZURE_SUBSCRIPTION_ID`, configured for OIDC federation. It also needs permission to push to the registry and deploy the Web App. Registry login uses `az acr login`; the workflow no longer requires `ACR_USERNAME`/`ACR_PASSWORD`. OIDC token permission is restricted to the deployment job. App Service and staging use system-assigned identities with `AcrPull`; registry admin authentication is disabled in Terraform. The Terraform caller needs permission to create those role assignments. Verify identity propagation before removing existing registry credentials from a live deployment.

Verify role assignments and secrets in Azure and GitHub before using the workflow.

## Events and releases

When deploying the stable Kafka consumer-group change to existing topics, initialize group offsets intentionally to avoid replaying historical notifications/audit events. Kafka is not provisioned by the Terraform configuration; without external Kafka, the app uses its Redis/memory fallback. See [event limitations](data-flow-and-integration.md).

After deployment, verify login, staff/admin permissions, a stock adjustment, the order lifecycle, notification counts, and metrics scraping. Check logs for failed migrations or event handlers. Automated build success alone does not prove a deployed environment is healthy.

## Suspend and resume

`bin/suspend.sh` destroys the compute resources and Redis after Terraform presents a plan for confirmation, then stops PostgreSQL. Database storage and registry images remain. Redis cache and queued data are lost; the scripts are not a production availability strategy.

`bin/resume.sh` starts PostgreSQL and recreates compute resources. Both scripts read names from Terraform outputs and stop on failure instead of reporting false success. Older state may need its outputs refreshed before the new `postgres_server_name` output exists.

Stopping PostgreSQL does not eliminate storage costs, and Azure can automatically restart it after its allowed stop interval. See [Microsoft's stop/start documentation](https://learn.microsoft.com/en-us/azure/postgresql/configure-maintain/how-to-stop-server). Recheck the database firewall after recreating App Service.
