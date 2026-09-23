# Code and security review

Review date: 2026-09-23. Scope: the repository's application code, tests, dependency manifests, Docker/Terraform/CI configuration, operational/load-test scripts, README files, and documentation.

## Summary

The review found exploitable credential defaults, missing authorization checks, stale account permissions, unsafe cache deserialization, inventory race conditions, exposed infrastructure credentials, and misleading behavior/documentation. The working tree contains fixes and regression tests. Existing credentials and deployed resources still require operator action; this local change cannot invalidate previously disclosed secrets.

## Process and boundaries

1. Inventoried tracked files and checked for repository instructions and pre-existing changes. The initial tree had untracked diagnostic archives/directories; they were preserved and excluded from source review.
2. Split review across backend authentication/configuration, backend data/events, frontend, and infrastructure/documentation. Cross-checked API contracts and configuration between areas.
3. Traced permissions, JWT handling, password provisioning, deserialization, transactions, concurrency, event delivery, caches, browser storage, and operational scripts. Checked framework behavior against React, Spring, and Jackson documentation.
4. Fixed confirmed issues and wrote tests that exercise authorization failures, unsafe payload rejection, concurrent mutations, transaction rollback, token lifecycle, and destructive-tool safety.
5. Checked dependencies against npm advisories and OSV, then checked compatible remediation versions. An advisory match is not proof that every affected feature is reachable in this application.
6. Checked documentation against controllers, DTOs, migrations, deployment files, and test results.

No production access, deployment, Terraform apply, live credential rotation, Git-history rewrite, or destructive benchmark run was performed. Source review and automated tests are not a penetration test or a guarantee that no vulnerabilities remain.

## Critical findings

### 1. Committed infrastructure credentials

**Code:** [.gitignore:17](.gitignore#L17), [.dockerignore:7](.dockerignore#L7), [terraform/main.tf:29](terraform/main.tf#L29).

**Impact:** Anyone with access to the tracked Terraform backup could obtain database and container-registry credentials.

The tracked `terraform/terraform.tfstate.1781408654.backup` contained populated PostgreSQL administrator and ACR administrator password fields. The values were not copied into this report. Removed the backup, ignored all state/plan variants, and excluded credentials/state/diagnostic exports from the root Docker build context. Terraform now disables ACR admin authentication and uses managed identities with `AcrPull`; CI registry login uses OIDC-backed Azure CLI authentication.

**Status:** Working-tree exposure fixed. Rotate affected credentials and review repository history/access. Deletion does not remove older commits or copies. Terraform state still needs a secure storage backend.

### 2. Public JWT signing key

**Code:** [backend/src/main/java/com/pg/supplychain/security/JwtService.java:26](backend/src/main/java/com/pg/supplychain/security/JwtService.java#L26), [backend/src/main/resources/application.properties:43](backend/src/main/resources/application.properties#L43).

**Impact:** The former known signing key allowed forged bearer tokens, including administrator claims.

Removed the built-in key. `JWT_SECRET` is now mandatory, minimum key length and expiry are validated at startup, and Compose/Terraform explicitly supply it. Current account lookup additionally prevents a signed token from authenticating a deleted or disabled user.

**Status:** Code fixed. Deploy a new random key; the old key remains compromised even if removed from source.

### 3. Automatic known-password accounts

**Code:** [backend/src/main/java/com/pg/supplychain/config/DatabaseSeeder.java:44](backend/src/main/java/com/pg/supplychain/config/DatabaseSeeder.java#L44), [frontend/src/pages/Login.tsx:52](frontend/src/pages/Login.tsx#L52).

**Impact:** Starting an instance created administrator/staff accounts whose passwords were disclosed in source and on the login page.

Bootstrap now requires externally supplied administrator credentials on an empty user table. Demo data is opt-in and requires an administrator and a supplied staff password. Removed default credentials from the UI and load scripts. Bootstrap does not reset existing accounts.

**Status:** Code fixed. Rotate or disable previously seeded accounts in existing databases.

## High findings

### 4. Missing mutation permissions and stale JWT roles

**Code:** [backend/src/main/java/com/pg/supplychain/config/SecurityConfig.java:75](backend/src/main/java/com/pg/supplychain/config/SecurityConfig.java#L75), [backend/src/main/java/com/pg/supplychain/security/JwtAuthenticationFilter.java:54](backend/src/main/java/com/pg/supplychain/security/JwtAuthenticationFilter.java#L54), [backend/src/main/java/com/pg/supplychain/service/OrderService.java:165](backend/src/main/java/com/pg/supplychain/service/OrderService.java#L165).

Staff could mutate categories/warehouses through a generic authenticated fallback. Added explicit administrator requirements and denied unmatched routes. Staff can no longer cancel approved/shipped orders. Each authenticated request uses the current account status and role instead of trusting an old token's role indefinitely. Login rejects inactive accounts.

**Why:** Navigation controls and signed claims do not replace server-side authorization or account-state checks.

### 5. Unrestricted Redis polymorphic deserialization

**Code:** [backend/src/main/java/com/pg/supplychain/config/JacksonLiteRedisSerializer.java:17](backend/src/main/java/com/pg/supplychain/config/JacksonLiteRedisSerializer.java#L17).

Cache deserialization previously allowed all subclasses of `Object`. Restricted it to the exact response DTOs, collection implementations, and scalar types the caches use; tests reject unexpected classpath types.

**Exploit precondition:** An attacker must be able to influence Redis/cache bytes. No direct public endpoint accepting arbitrary cache payloads was found. Redis still belongs on a trusted network.

### 6. Lost stock updates and repeated delivery

**Code:** [backend/src/main/java/com/pg/supplychain/service/ProductService.java:125](backend/src/main/java/com/pg/supplychain/service/ProductService.java#L125), [backend/src/main/java/com/pg/supplychain/service/OrderService.java:123](backend/src/main/java/com/pg/supplychain/service/OrderService.java#L123), [backend/src/test/java/com/pg/supplychain/service/InventoryConcurrencyTest.java:32](backend/src/test/java/com/pg/supplychain/service/InventoryConcurrencyTest.java#L32).

Read-modify-write stock changes and order delivery lacked row locks. Concurrent operations could overwrite inventory changes or apply receipts more than once. Added product/order locks, deterministic product locking order, checked arithmetic, and item/total bounds. H2 concurrency tests exercise parallel adjustments, repeated delivery, and separate orders delivering to the same product.

**Limit:** PostgreSQL locking behavior needs the container-backed integration run; H2 evidence is not a substitute for that environment.

### 7. Dependency advisories

**Code:** [backend/pom.xml:20](backend/pom.xml#L20), [frontend/package-lock.json:1578](frontend/package-lock.json#L1578).

The frontend audit initially reported nine affected packages (seven high and two moderate). Compatible lockfile updates removed those matches. The Maven review queried 219 resolved test/runtime package coordinates and initially found advisory matches in nine packages. See the verification section for the final scan outcome and dependency changes.

**Scope:** This checks published package advisories, not container OS layers, Maven plugins, image signatures, or every possible runtime configuration. Some affected server/test paths are not exposed by this SPA/application.

## Medium findings

### 8. Login guessing and excessive password work

**Code:** [backend/src/main/java/com/pg/supplychain/security/LoginRateLimitFilter.java:21](backend/src/main/java/com/pg/supplychain/security/LoginRateLimitFilter.java#L21).

Added a bounded login limiter: 30 attempts/minute per socket-peer IP and per process, at most 10,000 active counters, with 429/`Retry-After`. Full tables reject new peers until expiry instead of evicting existing limits. Caller-controlled forwarding headers cannot reset limits. Unknown accounts undergo a dummy BCrypt comparison to reduce timing-based enumeration.

**Operational tradeoff:** Users behind a proxy share its limit; replicas have independent limits. Configure trusted gateway enforcement/capacity for a real deployment. Diagnostic login load tests may need a higher limit in a disposable environment.

### 9. Browser token persistence and credential-bearing errors

**Code:** [frontend/src/services/session.ts:10](frontend/src/services/session.ts#L10), [frontend/src/context/AuthContext.tsx:7](frontend/src/context/AuthContext.tsx#L7), [frontend/src/services/api.ts:16](frontend/src/services/api.ts#L16).

Removed persistent localStorage bearer tokens and cleared legacy tokens. Tokens remain in memory and expire; stale failed requests cannot sign out a newer session. Removed raw Axios error logging that could include submitted login/new-user passwords. Added regression tests for malformed/UTF-8 tokens, expiry, current/stale 401 responses, and safe errors.

**Tradeoff:** Page reload now requires login. In-memory tokens reduce persistence but do not make an active page immune to XSS. Signing out does not revoke stolen token copies.

### 10. Exposed operational endpoints and network defaults

**Code:** [backend/src/main/resources/application.properties:60](backend/src/main/resources/application.properties#L60), [docker-compose.yml:8](docker-compose.yml#L8), [terraform/main.tf:77](terraform/main.tf#L77).

Moved metrics to a separate management listener, default loopback port 9091, and removed exception detail from public health responses. Prometheus scrapes that internal listener. Compose publishes services only on loopback and requires supplied database/Grafana credentials. Terraform replaces the all-Azure-services PostgreSQL rule with explicit single-IP allowlists and requires HTTPS/TLS for the app and staging slot. PostgreSQL uses certificate/hostname verification with the JVM trust store instead of encryption-only `sslmode=require`.

**Rollout:** Populate approved app egress IPs before expecting database connectivity. Defaults deliberately do not admit every Azure tenant. Re-evaluate IPs when recreating or changing App Service plans.

### 11. Browser and container defaults

**Code:** [backend/src/main/java/com/pg/supplychain/config/SecurityConfig.java:47](backend/src/main/java/com/pg/supplychain/config/SecurityConfig.java#L47), [frontend/nginx.conf.template:6](frontend/nginx.conf.template#L6), [backend/Dockerfile:25](backend/Dockerfile#L25), [.github/workflows/ci.yml:79](.github/workflows/ci.yml#L79).

Added compatible CSP/referrer policies to Spring's combined deployment and Nginx, disabled Nginx version disclosure, and enabled HTTPS upstream certificate verification. The combined application container runs as an unprivileged user. CI's OIDC permission is restricted to deployment; frontend install/build/test uses the supported Node runtime.

**Limit:** Docker/Nginx runtime validation requires an available daemon/binary. No HSTS change was made.

### 12. Events before commit, replay, and cache/count falsification

**Code:** [backend/src/main/java/com/pg/supplychain/kafkalite/TransactionEventPublication.java:10](backend/src/main/java/com/pg/supplychain/kafkalite/TransactionEventPublication.java#L10), [backend/src/main/java/com/pg/supplychain/config/CacheConfig.java:46](backend/src/main/java/com/pg/supplychain/config/CacheConfig.java#L46), [backend/src/main/java/com/pg/supplychain/service/NotificationService.java:51](backend/src/main/java/com/pg/supplychain/service/NotificationService.java#L51).

Events now publish after commit and are discarded on rollback. Consumer startup waits for listener registration. Stable Kafka groups replace random groups that replayed retained events after restarts. Replenishment decisions serialize with a database lock instead of a process-local timer.

Restored stock/analytics cache invalidation and real notification counts instead of fixed zero values. If Redis is unavailable at startup, caching is disabled so separate instances cannot retain non-expiring stale values. Category/warehouse changes also invalidate responses that contain their names.

**Remaining design limit:** There is no transactional outbox or durable consumer retry policy. Events can be lost between commit and publish or during fallback/handler failures. Choose initial Kafka group offsets deliberately when migrating existing topics.

### 13. Destructive and misleading benchmark tooling

**Code:** [load-tests/run-benchmark.py:68](load-tests/run-benchmark.py#L68), [docker/scale_data.sql:14](docker/scale_data.sql#L14), [load-tests/lib/common.js:634](load-tests/lib/common.js#L634).

The benchmark runner silently truncated application data when a row-count threshold was low, swallowed telemetry failures, and treated k6 threshold failure as success. It now defaults to smoke, resets fixtures only with an explicit destructive flag, aborts on seed failures, preserves k6's exit code, and reports missing metrics as N/A. The SQL fixture has its own opt-in guard and transaction. Load credentials come from environment variables.

Removed an always-passing write threshold, applied the advertised load scale, corrected the extreme profile count and duration parsing, and stopped repeatedly approving setup orders from multiple virtual users. Benchmark results must be retained with workload/environment details before being presented as evidence.

## Quality and documentation findings

### 14. Documentation and code maintenance

**Code:** [README.md:1](README.md#L1), [docs/api-specification.yaml:1](docs/api-specification.yaml#L1), [docs/database-schema.md:1](docs/database-schema.md#L1), [docs/deployment-and-operations.md:1](docs/deployment-and-operations.md#L1).

Updated setup, architecture, operations, schema, scope, and cost documentation to match the application and deployment configuration. The guides describe event-delivery limitations, configured infrastructure, and how to measure performance and operating costs.

Corrected local ports, environment variable names, combined deployment behavior, relative links, and actual schema types. Rebuilt OpenAPI from all current controllers and DTOs, including pagination envelopes, notification counts, user/category/warehouse endpoints, UUID audit identifiers, and required IDs/fields. Removed unused starter assets, restored strict lint rules, consolidated duplicated order creation, and made dashboard/filter text reflect available data.

### 15. Request and JSON contract gaps

**Code:** [backend/src/main/java/com/pg/supplychain/dto/UserCreateRequest.java:35](backend/src/main/java/com/pg/supplychain/dto/UserCreateRequest.java#L35), [backend/src/main/java/com/pg/supplychain/dto/NotificationResponse.java:24](backend/src/main/java/com/pg/supplychain/dto/NotificationResponse.java#L24), [backend/src/main/java/com/pg/supplychain/model/User.java:31](backend/src/main/java/com/pg/supplychain/model/User.java#L31).

Added request length/precision/collection bounds, explicit invalid-page rejection, BCrypt's 72-byte password bound, password redaction from request `toString()`, and password-hash serialization exclusion. Fixed primitive Boolean accessor names so `isRead` and `isActive` match the frontend contract.

## Verification

| Check | Result |
|---|---|
| `cd backend && ./mvnw verify` | 113 tests, 0 failures, 0 errors, 0 skipped; executable JAR built |
| `cd frontend && npm run lint` | Pass, zero warnings |
| `cd frontend && npm run build` | TypeScript and production bundle pass |
| `cd frontend && npm test` | 8 tests pass |
| `python3 -m unittest discover -s load-tests -p 'test_*.py'` | 5 tests pass |
| Final Maven OSV query | 210 resolved public artifact coordinates checked; 0 advisory matches |
| Final `npm audit` | 0 vulnerabilities reported |
| Terraform format/validate | Pass; no plan/apply |
| Both Compose `config --quiet` checks | Pass with disposable placeholder credentials |
| Shell/JavaScript syntax and `git diff --check` | Pass |
| OpenAPI YAML and references | 18 paths, 27 operations, all 144 local references resolve |
| Browser smoke | Login page renders correctly and contains no demo credentials |

The Maven run covers 29 test classes, including three H2 concurrency tests and real loopback HTTP tests. Docker was unavailable: tests used their H2/in-memory fallback and did not exercise PostgreSQL/Redis/Kafka containers. Nginx runtime syntax/TLS connectivity, SQL fixture execution, authenticated browser workflows, cloud connectivity, container OS/image scanning, and a dedicated OpenAPI semantic validator were not run. No load test or destructive fixture reset was executed.

Frontend findings and version changes are retained in [frontend dependency evidence](docs/review-evidence/frontend-dependency-audit.json). The final Maven scan and public coordinates are retained in [dependency evidence](docs/review-evidence/maven-dependency-audit.json). The nine originally matched Maven artifacts were addressed through Jackson 2 BOM 2.21.5, Jackson 3 BOM 3.1.5, Netty 4.2.17.Final, Tomcat 11.0.25, Log4j 2.25.5, PostgreSQL JDBC 42.7.12, LZ4 1.11.1, and Commons Compress 1.26.0. Commons Compress remains test-transitive. Final graph size differs because updated dependency metadata changes transitives. Advisory databases change over time; rerun checks for releases.

## Remaining operational and design work

- Rotate the old JWT key, seeded account passwords, PostgreSQL administrator password, and ACR credentials; apply infrastructure changes through a reviewed plan. Coordinate history cleanup without destroying collaborators' work.
- Secure Terraform state storage. `sensitive = true` does not encrypt state. The local changes do not migrate existing state to a remote backend.
- Separate staging/production databases and Redis if they must be isolated. The existing Terraform deployment deliberately still shares them.
- Add a durable event outbox/retry/idempotency design if notifications, replenishment, or audit-event delivery must survive all failures.
- PostgreSQL currently runs application requests under the configured administrator account, and `ddl-auto=update` remains alongside Liquibase. A least-privilege runtime role and `validate` migration rollout need real PostgreSQL validation before changing existing deployments.
- Product/order filters operate on the fetched page; product selectors still load a bounded first page. A server-side search/product-picker API is a separate product change.

## References

- [Spring Security request authorization](https://docs.spring.io/spring-security/reference/7.0/servlet/authorization/authorize-http-requests.html)
- [Spring transaction synchronization](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)
- [Jackson polymorphic type validation](https://www.javadoc.io/static/tools.jackson.core/jackson-databind/3.0.0-rc6/tools.jackson.databind/tools/jackson/databind/jsontype/BasicPolymorphicTypeValidator.Builder.html)
- [Terraform sensitive-data handling](https://developer.hashicorp.com/terraform/language/manage-sensitive-data)
- [Azure PostgreSQL firewall behavior](https://learn.microsoft.com/en-us/azure/postgresql/security/security-firewall-rules)
- [pgJDBC certificate and hostname verification](https://jdbc.postgresql.org/documentation/ssl/)
- [OSV package vulnerability API](https://google.github.io/osv.dev/api/)
