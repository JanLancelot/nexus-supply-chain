# Repository guide

## Map

- `frontend/`: React/TypeScript, Vite, Tailwind. HTTP services live in
  `src/services/`, session state in `src/context/`, screens in `src/pages/`.
- `backend/`: Spring Boot, Java 17, Maven wrapper. Controllers expose DTOs;
  services own business rules; repositories persist entities. Kafka/Redis broker
  adapters and asynchronous listeners handle domain events.
- `docs/api-specification.yaml`: API contract. `docs/database-schema.md` and
  `docs/architecture.md`: data and architecture references.
- `docker/`, `terraform/`, `bin/resume.sh`, `bin/suspend.sh`: infrastructure and
  operations. `load-tests/`: k6 benchmarks against explicitly selected targets.

## Working loop

Read the relevant implementation and nearby tests before editing. Keep changes
focused on the requested behavior. Preserve unrelated working-tree changes.

Use Java 17 and Node 24 (`.nvmrc`); install frontend dependencies with
`npm --prefix frontend ci`. Run `./bin/verify.sh` before handing off changes.
For API, persistence, or event behavior, also run `./bin/verify.sh containers`
with Docker available. Report which modes ran and any blocked checks accurately.
See `docs/development.md` for focused commands, test boundaries, and reports.

Add regression tests at public boundaries for changed behavior. Keep frontend
HTTP details in the service layer and backend authorization/business rules on
the server. Update API/schema documentation when those contracts change.

Default tests use H2 and fallback brokers; they do not validate real infrastructure
or migrations. Container mode must fail if its infrastructure cannot start.
Do not suppress failures, skip assertions, or relax lint rules to make checks pass.

Keep generated coverage, build output, logs, credentials, and Terraform state out
of commits. Deployment and cloud lifecycle scripts are operational actions, not
verification commands.
