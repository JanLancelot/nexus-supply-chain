# Backend development

Run the backend from this directory with Java 17 or newer:

```sh
./mvnw spring-boot:run
```

Set `SPRING_DATASOURCE_PASSWORD` and a randomly generated `JWT_SECRET` of at least 32 bytes before starting. The backend uses PostgreSQL on localhost:5432 by default; the root [README](../README.md) describes the Docker setup.

On an empty database, set `APP_BOOTSTRAP_ADMIN_EMAIL` and `APP_BOOTSTRAP_ADMIN_PASSWORD` to create the first administrator. The password must be at least 12 characters and at most 72 UTF-8 bytes. Bootstrap credentials do not overwrite existing accounts. Remove the bootstrap password from the runtime environment after creating the account.

Demo inventory is disabled by default. To load sample categories, warehouses, suppliers, products, and a `staff@pg.com` demo user, set `APP_SEED_DEMO_DATA=true` and provide `APP_BOOTSTRAP_STAFF_PASSWORD` with the same password limits. Demo seeding belongs in local development environments.

The application listens on port 8080. Health is available at `/api/health`; it returns no exception details. Management health and Prometheus metrics use a separate listener at `127.0.0.1:9091`. Docker overrides the management address on its internal network without publishing that port. Keep that listener private: Prometheus scrapes it without a bearer token.

Browser requests may originate from localhost and localhost:5173 by default. Set the comma-separated `APP_CORS_ALLOWED_ORIGINS` for another frontend origin. Authentication uses bearer tokens in the Authorization header.

Login requests are limited to 30 attempts per minute per socket peer address per application instance, including successful attempts. Excess requests receive HTTP 429 and `Retry-After`. `APP_LOGIN_MAX_ATTEMPTS_PER_MINUTE` and `APP_LOGIN_MAX_TRACKED_CLIENTS` tune the limit and bounded client table (default 10,000). When the table is full, new addresses wait for a window to expire. Forwarding headers are not trusted, so callers behind the same proxy share a limit. Configure a trusted gateway with a shared limit for multiple instances and verify its proxy-address handling before changing these defaults.

Run unit and application-context tests with `./mvnw test`. Test credentials and the signing key in `src/test/resources/application.properties` are test fixtures. Tests under `integration/` require Docker through Testcontainers; their availability depends on the local Docker daemon.
