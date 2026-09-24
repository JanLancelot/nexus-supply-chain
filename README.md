# Nexus Supply Chain

A supply-chain application with a React/TypeScript UI, a Spring Boot API, PostgreSQL, Redis caching, and Kafka events. The combined Docker image serves the UI and API on port 8080.

For local checks, test modes, and coverage reports, see the [development harness guide](docs/development.md).

## Features

- Staff can browse the catalog, create purchase orders, and submit drafts for approval.
- Administrators can create products, adjust stock with a reason code, manage categories and warehouses, approve orders, and provision users.
- Orders move through `DRAFT → PENDING_APPROVAL → APPROVED → SHIPPED → DELIVERED`. Delivery increments stock in the order transaction. Cancellation rules depend on the current state and role.
- Low-stock events can create draft replenishment orders. An open-order check prevents repeated drafts for the same product.
- Administrators can view aggregate purchasing/inventory metrics and audit records. Notifications belong to individual users.

Audit records are ordinary database rows; they are not a tamper-proof ledger. Event delivery uses Kafka when available, with Redis-list and process-memory fallbacks. Those fallbacks do not provide Kafka's persistence or recovery semantics. See [architecture](docs/architecture.md) for limitations.

## Run with Docker

Install Docker with Compose. From the repository root, generate local credentials and save them in an ignored `.env` file:

```bash
umask 077
cat > .env <<EOF_ENV
SPRING_DATASOURCE_PASSWORD=$(openssl rand -hex 24)
JWT_SECRET=$(openssl rand -hex 32)
GRAFANA_ADMIN_PASSWORD=$(openssl rand -hex 24)
APP_BOOTSTRAP_ADMIN_EMAIL=admin@example.test
APP_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -hex 24)
EOF_ENV

docker compose up --build
```

Read the bootstrap password from your local `.env` and sign in as `admin@example.test`. Keep the file private. Bootstrap configuration creates an account only when appropriate; it does not reset an existing account's password. Remove the bootstrap credentials after provisioning. To load sample catalog data and a staff account in a disposable environment, also set `APP_SEED_DEMO_DATA=true` and `APP_BOOTSTRAP_STAFF_PASSWORD` to a generated password before startup.

All published Compose ports bind to `127.0.0.1`:

| Service | Address |
|---|---|
| Application | `http://localhost:8080` or `http://localhost` |
| PostgreSQL | `localhost:5433` |
| Redis / Kafka | `localhost:6379` / `localhost:9092` |
| Grafana / Prometheus | `http://localhost:3000` / `http://localhost:9090` |

Metrics are scraped on the internal management port 9091, which Compose does not publish. Stop with `docker compose down`. Adding `-v` deletes the database and monitoring volumes.

Existing database volumes retain their original database password; changing `.env` alone does not rotate it.

## Run the application locally

Use Java 17, the included Maven wrapper, and Node.js 24. Start PostgreSQL and Redis with the development Compose file (PostgreSQL uses port 5432 here):

```bash
# Load only the locally generated .env file you created above.
set -a
. ./.env
set +a
docker compose -f docker/dev.docker-compose.yml up -d
cd backend
./mvnw spring-boot:run
```

In a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

Vite serves the UI on port 5173 and proxies `/api` to the backend. If Kafka is absent, the application selects its fallback broker at startup. PostgreSQL is still required.

## Configuration

Runtime settings live in [application.properties](backend/src/main/resources/application.properties).

| Variable | Purpose / default |
|---|---|
| `JWT_SECRET` | Required signing key; generate at least 32 random bytes |
| `SPRING_DATASOURCE_PASSWORD` | Required database password |
| `SPRING_DATASOURCE_URL` | Defaults to `jdbc:postgresql://localhost:5432/supply_db` |
| `SPRING_DATASOURCE_USERNAME` | Defaults to `enterprise_admin` |
| `SPRING_REDIS_HOST`, `SPRING_REDIS_PORT` | Default `localhost`, `6379` |
| `SPRING_REDIS_PASSWORD`, `SPRING_REDIS_SSL_ENABLED` | Managed Redis credentials and TLS setting |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Defaults to `localhost:9092` |
| `APP_BOOTSTRAP_ADMIN_EMAIL`, `APP_BOOTSTRAP_ADMIN_PASSWORD` | Initial administrator provisioning |
| `APP_SEED_DEMO_DATA` | Opt-in sample data; default `false` |
| `APP_BOOTSTRAP_STAFF_PASSWORD` | Required when creating the demo staff account |
| `APP_LOGIN_MAX_ATTEMPTS_PER_MINUTE` | Per-peer, per-instance login limit; default `30` |
| `APP_LOGIN_MAX_TRACKED_CLIENTS` | Maximum active login counters; default `10000` |
| `APP_CORS_ALLOWED_ORIGINS` | Comma-separated browser origins; defaults to localhost and localhost:5173 |
| `MANAGEMENT_SERVER_ADDRESS`, `MANAGEMENT_SERVER_PORT` | Defaults to loopback on port 9091 |

Browser tokens are held in memory. Reloading the page requires signing in again. Signing out clears the browser token; it does not revoke copies of an already issued token.

## Verification

Use Node 24, Java 17, Python 3, and Docker. The [development guide](docs/development.md)
explains individual suites, disposable test data, reports, and reviewed screenshot updates.

```bash
npm --prefix frontend ci --ignore-scripts
cd frontend
npx playwright install --with-deps chromium firefox webkit
cd ..
./bin/verify.sh        # Lint, component/session tests, coverage, builds, backend, tooling
./bin/verify.sh full   # Also real infrastructure, three-browser E2E, and visual tests
```

For load tests, use a disposable stack with sample catalog data and both administrator and staff accounts. Before starting that stack, set `APP_SEED_DEMO_DATA=true` and configure `APP_BOOTSTRAP_ADMIN_PASSWORD` and `APP_BOOTSTRAP_STAFF_PASSWORD` with generated passwords. Export those values into the terminal that runs the commands below. Alternatively, provision equivalent test accounts and catalog data through the API and supply their credentials explicitly:

```bash
export LOAD_TEST_ADMIN_EMAIL=admin@example.test
export LOAD_TEST_ADMIN_PASSWORD="$APP_BOOTSTRAP_ADMIN_PASSWORD"
export LOAD_TEST_STAFF_EMAIL=staff@pg.com
export LOAD_TEST_STAFF_PASSWORD="$APP_BOOTSTRAP_STAFF_PASSWORD"
./load-tests/run.sh smoke
python3 load-tests/run-benchmark.py smoke
```

Load scenarios create or update application data. The benchmark runner does not reset the database unless passed `--reset-disposable-db`; that flag deletes orders, audit records, and notifications before loading fixtures. The reset also requires the named demo categories, warehouses, and active suppliers; it checks these prerequisites before deleting anything. Results are written under `load-tests/results/`. No throughput, coverage, or cost figure is asserted here without a reproducible report.

## Documentation and deployment

- [Architecture](docs/architecture.md)
- [Requirements and implemented scope](docs/requirements-and-scope.md)
- [Data flow and event limitations](docs/data-flow-and-integration.md)
- [Database schema](docs/database-schema.md)
- [OpenAPI contract](docs/api-specification.yaml)
- [Deployment and operations](docs/deployment-and-operations.md)
- [Cost considerations](docs/cost-optimization-results.md)

Terraform describes Azure App Service, PostgreSQL, Managed Redis, and a container registry. GitHub Actions verifies builds and deploys pushes to `main` to the staging slot. Review required secrets, database firewall allowlists, and shared staging data in the operations guide before deployment.
