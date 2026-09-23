# Development harness

## Setup and verification

Use Java 17 and Node 24 (the version in `.nvmrc`). Maven is provided by
`backend/mvnw`. Python 3 runs the benchmark-tooling tests. Docker is needed for
container integration tests, canonical visual tests, and the local application stack.

```sh
nvm install
nvm use
npm --prefix frontend ci --ignore-scripts
./bin/verify.sh
```

The default command runs frontend lint, tests with coverage, TypeScript checking,
the production frontend build, a clean backend build with tests and JaCoCo, and
the Python benchmark-tooling and test-launcher isolation tests.
It stops at the first failed check and works from any working directory. Install
dependencies again with `npm ci --ignore-scripts` after pulling a changed frontend lockfile.

| Command | Purpose |
| --- | --- |
| `./bin/verify.sh frontend` | Lint, frontend tests/coverage, typecheck, production build |
| `./bin/verify.sh backend` | All backend tests using H2 and the broker fallback paths |
| `./bin/verify.sh containers` | Backend suite with PostgreSQL, Redis, and Kafka for the API/workflow integration tests |
| `./bin/verify.sh e2e` | Real browser-to-API workflows in Chromium, Firefox, and WebKit |
| `./bin/verify.sh visual` | Compare screenshots in the canonical Linux/amd64 Docker image |
| `./bin/verify.sh full` | Default checks, container integration, all browsers, then visual comparisons |
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

The upstream session suite runs with Node's test runner before Vitest.
Vitest runs React tests in jsdom with Testing Library. Tests exercise the public
session hook, login form, routed application, catalog, purchase orders, user
management, notifications, dashboard, audit logs, and Axios request/response boundary.
Shared setup resets
the DOM, in-memory sessions, local storage, history, and mocks between tests. Session fixtures contain
unsigned tokens for client tests only. No live API or user credentials are needed.

Use `*.test.ts` or `*.test.tsx` beside the behavior being tested. Prefer observable
behavior and accessible queries; mock HTTP boundaries rather than component
internals. New tests should demonstrate the behavior they protect, including a
failure path where relevant.

Coverage includes untested application files so gaps stay visible. CI enforces
85% statements and lines, and 80% branches and functions. HTTP fixtures fail on
unexpected requests; they do not mock the application's services or components.
jsdom tests do not verify layout or the real backend.

## Browser workflows

Install the pinned browsers once after installing frontend dependencies:

```sh
cd frontend
npx playwright install --with-deps chromium firefox webkit
npm run test:e2e       # Chromium for the quick feedback loop
npm run test:e2e:all   # All three engines
```

Playwright builds the frontend and starts its preview on `127.0.0.1:4173` plus
a real Spring Boot API on `127.0.0.1:18080`. Keep these ports free; an existing
server is never reused. The launcher removes inherited Spring/application and
JVM configuration variables so local database or broker settings cannot override
the disposable profile. The test runner generates temporary admin/staff passwords
and a JWT signing key, inherited by the API and workers. No `.env`, deployed
service, or saved browser session is used. Do not point this harness at production.

The `e2e` Spring profile lives only in test resources and runs through
`spring-boot:test-run`. Each invocation has a fresh in-memory H2 database with
demo reference data and in-memory event delivery when Kafka/Redis are unavailable.
These tests exercise real authentication, HTTP validation, authorization,
services, persistence, and event consumers; the separate container suite proves
the PostgreSQL/Redis/Kafka paths. Neither mode currently verifies Liquibase migrations.

Workflows cover failed login and recovery, logout, reload/expiry, staff/admin
permissions, product creation and duplicate SKU rejection, stock changes and
negative-stock rejection, multi-line orders through submission/approval/shipping/
delivery, cancellation, duplicate lines, notifications, audit trails, dashboards,
and user creation with a subsequent login. Records have unique names; tests do
not depend on another test's output. API assertions also check forbidden writes
and inventory totals so a successful-looking UI cannot conceal a rejected request.

Use `cd frontend && npx playwright test --project=chromium --grep 'catalog'` to
focus a workflow. Browser failures retain traces, videos, and screenshots.

If Firefox cannot create its profile on macOS, run that browser in the same pinned
Linux image while keeping the disposable app on the host. Build the image once
with `./bin/visual-tests.sh`, then run the following from `frontend/` with Node 24
and installed dependencies. Wait for `docker logs nexus-e2e-firefox` to report
`Listening` before starting the tests, and stop the container even if tests fail:

```sh
docker run --rm -d --init --name nexus-e2e-firefox \
  -p 127.0.0.1:18090:3000 \
  --mount "type=bind,source=$PWD,target=/workspace/frontend,readonly" \
  nexus-visual-tests:1.63.0 \
  node node_modules/playwright/cli.js run-server --host 0.0.0.0 --port 3000
PW_TEST_CONNECT_WS_ENDPOINT=ws://127.0.0.1:18090/ \
  PW_TEST_CONNECT_EXPOSE_NETWORK='<loopback>' npx playwright test --project=firefox
docker stop nexus-e2e-firefox
```

The tunnel lets the Linux browser reach the test runner's loopback addresses. CI runs Firefox
directly on Linux and does not need this workaround.

## Visual regression tests

```sh
./bin/verify.sh visual
# Only after reviewing an intentional UI change:
npm --prefix frontend run test:visual:update
```

The visual script builds `docker/visual-tests.Dockerfile` and uses Linux/amd64
for both developer machines and CI. Its Playwright version must match the exact
`@playwright/test` version in `frontend/package.json`; update them together.
Node 24, a locked local font package, viewport, locale, time zone, time, and API
fixtures stabilize rendering. The frontend itself is the production build.
Visual fixtures stub HTTP responses and fail on unhandled API calls or uncaught
browser errors. They complement the real-API browser suite.

Screenshots cover login/errors, dashboard/error, notifications, catalog/empty
results, stock and creation dialogs, order lists/wizard/details, audit history
and changes, user forms/validation, staff permissions, and phone-width screens.
Narrow catalog checks also require contained horizontal overflow, visible primary
controls, and a usable creation dialog.

Baseline PNGs live in `frontend/e2e/visual/__screenshots__/`. Comparisons allow
zero changed pixels and fail when a baseline is missing. Review the actual,
expected, and diff images before updating; never regenerate to conceal an
unexplained regression. Commit reviewed baselines with the corresponding UI/test
changes. The container uses disposable dependency/build volumes and leaves the
host installation intact. Docker must be running; no desktop screenshot tooling
or external API credentials are needed.

## Reports and CI

- Frontend: `frontend/coverage/index.html` and `frontend/coverage/lcov.info`.
- Backend: `backend/target/site/jacoco/index.html` and `backend/target/surefire-reports/`.
- Browser: `frontend/playwright-report/e2e/index.html`; open with
  `npm --prefix frontend run test:report`.
- Visual: `frontend/playwright-report/visual/index.html`; open with
  `cd frontend && npx playwright show-report playwright-report/visual`.
- Failure artifacts: `frontend/test-results/e2e/` and `frontend/test-results/visual/`.

GitHub Actions runs frontend checks, both backend modes, a three-browser matrix,
canonical visual comparisons, and benchmark tooling as separate jobs. Reports
are uploaded even after a failing step (7 days for browser artifacts; 14 days
for coverage and visual artifacts). Browser traces may include disposable test
credentials and request data; never run these suites with real account credentials. Staging
deployment requires every verification job to pass and still runs only on pushes
to `main`. Local verification never deploys the application.

Lint fails on warnings as well as errors. Do not weaken rules to hide them.
