# Development harness

## Setup and verification

Use Java 17 and Node 24 (the version in `.nvmrc`). Maven is provided by
`backend/mvnw`. Docker is needed only for container verification and the local
application stack.

```sh
nvm install
nvm use
npm --prefix frontend ci
./bin/verify.sh
```

The default command runs frontend lint, tests with coverage, TypeScript checking,
the production frontend build, and a clean backend build with tests and JaCoCo.
It stops at the first failed check and works from any working directory. Install
dependencies again with `npm ci` after pulling a changed frontend lockfile.

| Command | Purpose |
| --- | --- |
| `./bin/verify.sh frontend` | Lint, frontend tests/coverage, typecheck, production build |
| `./bin/verify.sh backend` | All backend tests using H2 and the broker fallback paths |
| `./bin/verify.sh containers` | Backend suite with PostgreSQL, Redis, and Kafka for the API/workflow integration tests |
| `npm --prefix frontend run test:watch` | Interactive frontend test loop |
| `npm --prefix frontend test -- src/context/AuthContext.test.tsx` | Focused frontend regression tests |
| `cd backend && ./mvnw -Dtest=OrderServiceTest test` | Focused backend regression tests |

## Backend modes

The default backend run never attempts to discover or launch Docker. The test
configuration uses disposable H2 databases and points Redis/Kafka at ports
16379/19092 to exercise the application's fallback paths. Keep those ports free
during lightweight tests. These checks do not prove PostgreSQL or Kafka behavior.

Start Docker and run `./bin/verify.sh containers` for that additional verification.
The script enables Maven's `integration` profile, which passes
`integration.containers=true` to the test JVM. Classes extending
`BaseIntegrationTest` share Testcontainers-managed PostgreSQL 15, Redis 7, and
Kafka containers on dynamically allocated ports. The remaining tests retain
their existing lightweight configuration. Container startup failures fail the
run instead of silently switching to H2. Testcontainers cleans up its containers
when the test JVM exits. No running development database is required.

The direct equivalent is `cd backend && ./mvnw clean verify -Pintegration`.
Both modes currently use Hibernate-created test schemas with Liquibase disabled;
they do not validate production migrations.

## Frontend tests

Vitest runs React tests in jsdom with Testing Library. Tests exercise the public
session hook, login form, and Axios request/response boundary. Shared setup resets
the DOM, local storage, history, and mocks between tests. Session fixtures contain
unsigned tokens for client tests only. No live API or user credentials are needed.

Use `*.test.ts` or `*.test.tsx` beside the behavior being tested. Prefer observable
behavior and accessible queries; mock HTTP boundaries rather than component
internals. New tests should demonstrate the behavior they protect, including a
failure path where relevant.

Coverage includes untested application files so gaps stay visible. The initial
suite covers login, session restoration/expiry, roles, logout, authentication
headers, and HTTP errors. It does not yet cover the catalog, orders, dashboards,
or full browser workflows. jsdom tests do not verify layout or real navigation.

## Reports and CI

- Frontend: `frontend/coverage/index.html` and `frontend/coverage/lcov.info`.
- Backend: `backend/target/site/jacoco/index.html` and `backend/target/surefire-reports/`.

GitHub Actions runs frontend checks and both backend modes as separate jobs.
Reports are uploaded even after a failing step and retained for 14 days. Staging
deployment requires every verification job to pass and still runs only on pushes
to `main`. Local verification never deploys the application.

The existing lint configuration reports legacy warnings without failing the
build. Treat new warnings as work to resolve; do not weaken rules to hide them.
