# ⚙️ Deployment & Operations Runbook (Azure Optimized)

**Project Name:** Nexus Supply Chain

**Document Target:** DevOps, Site Reliability Engineering (SRE) & Cloud Operations

**Infrastructure Target:** Microsoft Azure

---

## 1. Cloud-Native Architecture Mapping

To match P&G's internal IT standards, the containerized application is mapped to production-ready, fully managed Microsoft Azure services:

* **Compute Plane:** **Azure App Service (Linux Web App for Containers)** or **Azure Container Apps (ACA)** hosting the dockerized backend API and frontend SPA.
* **Data Plane:** **Azure Database for PostgreSQL (Flexible Server)** running PostgreSQL 15 with zone-redundancy and automated backup lifecycles.
* **Identity & Secrets Plane:** **Azure Key Vault (AKV)** acting as the central hardware security module (HSM) for cryptographic keys and configuration secrets.
* **Container Registry:** **Azure Container Registry (ACR)** providing a private, secure, geo-replicated Docker registry with automated vulnerability scanning.

---

## 2. Environment Configuration Matrix

The application follows the Twelve-Factor App methodology. All secrets and environment-specific parameters are injected dynamically into the runtime containers via Azure App Service Application Settings, backed by strict Azure Key Vault references.

### 2.1 Non-Secret Configuration Properties

| Variable Name | Description | Default Dev Value | Azure Production Context |
| --- | --- | --- | --- |
| `SERVER_PORT` | Port hosting the application framework. | `8080` | `8080` |
| `SPRING_PROFILES_ACTIVE` | Dictates runtime environmental profile logic. | `dev` | `prod` |
| `CORS_ALLOWED_ORIGINS` | Permitted origins for web browser traffic. | `http://localhost:5173` | `https://supplychain.pg.com` |
| `LOGGING_LEVEL_APP` | Minimal logging ingestion threshold. | `DEBUG` | `INFO` |

### 2.2 Secret Configuration Properties (Azure Key Vault Managed)

Production values are injected securely via the Azure Key Vault reference syntax: `@Microsoft.KeyVault(SecretUri=https://<vault-name>.vault.azure.net/secrets/<secret-name>/)`.

| Variable Name | Description | Dev Blueprint | Azure Production Source |
| --- | --- | --- | --- |
| `DB_URL` | Explicit connection URL path. | `jdbc:postgresql://db:5432/supply_db` | `jdbc:postgresql://pg-prod-srv.postgres.database.azure.com:5432/supply_db?sslmode=require` |
| `DB_USERNAME` | Credential profile owning DB rights. | `enterprise_admin` | `@Microsoft.KeyVault(SecretUri=...)` |
| `DB_PASSWORD` | Cryptographic database password. | `secure_dev_password` | `@Microsoft.KeyVault(SecretUri=...)` |
| `JWT_SECRET` | Signature key for stateless access tokens. | `DevSecretKeyMustBeAtLeast32BytesLong!` | `@Microsoft.KeyVault(SecretUri=...)` |

---

## 3. Local Infrastructure Orchestration

To maintain environmental parity with Azure Database for PostgreSQL, developers use local Docker environments that mimic cloud configurations, including mandatory SSL behaviors.

### 3.1 Execution Directives

To spin up the isolated local data layer, navigate to the project directory root and execute:

```bash
# Build and execute infrastructure context in detached background threads
docker-compose -f docker/dev.docker-compose.yml up -d

```

### 3.2 Local Verification Baseline

Verify data layer stability by confirming container health markers:

```bash
docker ps --filter "name=pg_enterprise_supply"

```

---

## 4. CI/CD Pipeline Architecture (GitHub Actions + Azure)

Continuous Integration and Continuous Deployment workflows are executed via GitHub Actions, integrating natively with Azure via OpenID Connect (OIDC) service principals to eliminate persistent, hardcoded deployment credentials.

### 4.1 Production Workflow Automation Blueprint (`.github/workflows/deploy.yml`)

```yaml
name: Enterprise Azure Deployment Pipeline

on:
  push:
    branches: [ main ]

permissions:
  id-token: write # Required for Azure OIDC authentication
  contents: read

jobs:
  validate-and-test:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Set up Java/Ecosystem SDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'

      - name: Run Transactional Integration Test Suite
        run: mvn clean test

  build-and-ship:
    needs: validate-and-test
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Log in to Azure via OIDC
        uses: azure/login@v2
        with:
          client-id: ${{ secrets.AZURE_CLIENT_ID }}
          tenant-id: ${{ secrets.AZURE_TENANT_ID }}
          subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}

      - name: Log in to Azure Container Registry (ACR)
        uses: azure/docker-login@v1
        with:
          login-server: pgsupplyregistry.azurecr.io
          username: ${{ secrets.ACR_USERNAME }}
          password: ${{ secrets.ACR_PASSWORD }}

      - name: Build and Push Production Docker Image
        run: |
          docker build -t pgsupplyregistry.azurecr.io/backend:${{ github.sha }} -f docker/backend.Dockerfile .
          docker push pgsupplyregistry.azurecr.io/backend:${{ github.sha }}

      - name: Deploy Container Image to Azure App Service
        uses: azure/webapps-deploy@v3
        with:
          app-name: 'pg-enterprise-supply-api'
          images: 'pgsupplyregistry.azurecr.io/backend:${{ github.sha }}'

```

---

## 5. Observability, Telemetry & Azure Log Runbook

Operating software at enterprise scale requires feeding raw logs and state metrics into centralized ingestion systems without adding processing overhead to the application runtime.

### 5.1 Azure Monitor & Log Analytics Integration

The backend streaming log output is directed completely to standard output (`stdout`) formatting. When running inside Azure App Service or Azure Container Apps, the Azure Diagnostic Logging engine intercepts these streams and pipes them directly into an **Azure Log Analytics Workspace**.

Production logs match a structured JSON layout, making them immediately searchable via Kusto Query Language (KQL) inside the Azure Portal:

```json
{
  "timestamp": "2026-06-12T16:25:00.124Z",
  "level": "WARN",
  "thread": "http-nio-8080-exec-2",
  "logger": "com.pg.supplychain.service.InventoryService",
  "message": "Dynamic safety stock boundary crossed.",
  "context": {
    "product_id": "c3b0a7e3-53d7-466d-a7a5-c6bf2d2c12cd",
    "sku": "PG-TIDE-001",
    "current_stock": 184,
    "reorder_level": 200,
    "low_stock_indicator": true
  }
}

```

### 5.2 Enterprise KQL Operational Query Example

SRE teams can execute the following Kusto query within Azure Log Analytics to generate real-time metrics on automated low-stock warnings across the warehouse footprint:

```kusto
AppServiceConsoleLogs
| extend parsed_log = parse_json(ResultText)
| where parsed_log.level == "WARN" and parsed_log.context.low_stock_indicator == true
| project TimeGenerated, SKU = tostring(parsed_log.context.sku), CurrentStock = toint(parsed_log.context.current_stock)
| order by TimeGenerated desc

```

### 5.3 Live Infrastructure Health Audits

Azure App Service actively checks the application's stability by hitting the embedded orchestration framework path every 10 seconds:

* **Live Traffic Health Probe Context:** `GET /api/v1/actuator/health`

If the database link breaks or memory allocation collapses, the endpoint returns a `503 Service Unavailable` status payload. Azure automatically takes the unhealthy container instance out of rotation and spins up a fresh, isolated node to ensure continuous system availability.