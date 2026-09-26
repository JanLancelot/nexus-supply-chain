# API service objectives

## Decision and scope

Inventory lookup supports warehouse stock decisions; creating a purchase order starts
replenishment. A server that responds quickly but rejects valid work still harms these
operations. Request success and successful requests within a latency bound therefore
measure useful outcomes more directly than CPU thresholds. Dependency and scrape alerts
remain necessary because completed-response metrics cannot observe requests that never
reach the server.

These are proposed engineering objectives for the local lab, not customer agreements
or demonstrated production reliability. Review targets with the service owner against
representative workload and customer expectations before adopting them operationally.

| SLO identifier | Operation | Good event | Proposed target |
| --- | --- | --- | --- |
| `inventory-availability` | `GET /api/v1/inventory/products` | HTTP 2xx | 99.9% |
| `order-availability` | `POST /api/v1/orders` | HTTP 2xx | 99.9% |
| `inventory-latency` | `GET /api/v1/inventory/products` | HTTP 2xx completed within 500 ms inclusive | 99% |

The denominator is completed HTTP 2xx, 429, and 5xx responses on the exact route and
method. Authentication failures, invalid input and other 4xx, redirects, health checks,
management scrapes, other routes and OPTIONS are excluded. This deliberately measures
server response quality for eligible operations, not end-to-end customer success or
business correctness. Unexpected 4xx/redirects during a valid baseline invalidate that
workload and must be investigated rather than hidden by these exclusions. An independent
synthetic check is needed for DNS, TLS, connectivity and total process outages. No
Internet uptime check is provisioned while there is no public deployment.

All counts aggregate by job, application and environment, across instances. Error
ratios and quantiles are never averaged across replicas. Micrometer publishes an exact
`le="0.5"` bucket; the latency numerator counts only successful responses in that bucket.
An absent bucket means unknown latency, not zero slow requests. No user, product, order,
URL parameter or request identifier is added to metric labels.

## Computation and incomplete data

For each indicator, `attainment = 1 - bad / eligible`. The rolling objective window is
30 days. `budget remaining = 1 - (bad / eligible) / (1 - target)`; a negative budget
means the objective has been exceeded and is deliberately not clamped to zero.
Prometheus `increase` handles each counter's resets before aggregation; extrapolation
at scrape boundaries means these are estimates rather than an audit ledger.

The dashboard separates observed partial-window figures from complete 30-day figures.
Minute completeness is rolled up in non-overlapping 15-minute counts to bound
long-window scan cost. Rollups preserve missing and failed observations; their
cadence is part of the measurement contract. Long-window counts and completeness are evaluated hourly to bound query cost; the
short-window burn rules and telemetry validity are evaluated every minute.
No eligible requests gives no ratio (insufficient traffic). Every minute records whether
source series and the required histogram are present and all configured backend targets
are scraping successfully. Full-window results require all 43,200 minute observations,
every observation valid, and a valid current observation. Missing scrapes, removed data,
new installation or failed rule evaluations leave the complete result unavailable.
Coverage is a count of recorded minute observations, not proof of availability between
scrapes. Warm-up and traffic evidence remain visible. Successful scrapes cannot prove
that every request was instrumented; application boundary tests cover that assumption.

The current Compose job contains one environment. If environments share a collector,
use separate jobs or extend discovery labels and coverage aggregation together; a failed
target in this job conservatively invalidates all its SLO results. Target replacement or
long scrape gaps can lose events; this setup cannot reconstruct them. It makes no highly
available monitoring or exact event-accounting claim.

Compose defaults to 32 days / 4 GB retention (the first limit reached wins). Override
`PROMETHEUS_RETENTION_TIME` and `PROMETHEUS_RETENTION_SIZE` after measuring ingestion and
disk capacity. Leave space for WAL, compaction, and other volumes. The time setting alone
does not guarantee 30 days survive the size limit; the completeness gate protects the
report if history is lost. A full observed month must elapse before reporting a monthly
result; synthetic history in tests is explicitly test data.

## Actionable budget alerts

| Alert | Both windows must exceed | Minimum short-window eligible requests | Response |
| --- | --- | --- | --- |
| `NexusSloFastBurn` | 14.4x in 1 hour and 5 minutes | 100 | Critical investigation |
| `NexusSloSustainedBurn` | 6x in 6 hours and 30 minutes | 100 | Scheduled investigation |

Burn rate is the bad-event ratio divided by the allowed bad-event ratio. Under a steady
request rate these thresholds correspond to consuming about 2% and 5% of a 30-day budget
in the longer window. Uneven request volume changes the actual fraction consumed; use
the measured counts and remaining budget when assessing impact. Both windows must be
bad now, so an old spike cannot continue paging once the short window recovers. There
is no extra `for` delay on budget alerts. Initial windows may contain partial history;
check coverage and sample volume when interpreting a new deployment.

The volume gate avoids paging on one failed lab request. It intentionally reduces
sensitivity for low-volume services; independent probes and backend/dependency alerts
remain required. Tune the gate using observed traffic, never only to silence failures.
Warnings and critical alerts use existing receiver routing; critical does not imply a
paid pager integration. Both burn alerts may be visible for a sustained incident; group
by SLO/environment when triaging rather than create two incidents for the same cause.

[Response runbook](runbooks/slo-burn.md). Existing infrastructure alerts remain diagnostic
signals. They are not contractual reliability measures.

## Error-budget policy

The service owner reviews proposed targets and the evidence before production adoption.
For the lab, fast burn triggers a drill investigation; sustained burn creates a follow-up
work item. If a complete rolling budget is exhausted, prioritize corrective reliability
work and defer discretionary releases until the cause is understood and the budget
recovers. Security fixes and recovery changes remain eligible with a recorded rationale.
Incomplete history is not an exhausted budget and must not automatically block releases;
review current incidents, telemetry and baseline results. No automatic CI release freeze
is wired to a short local history.

## Verification and evidence

`./bin/verify-monitoring.sh --config-only` checks all rule fixtures, including weighted
replica counts, no-traffic/missing series, resets, exact buckets, 429 inclusion, short-window
recovery, volume gating and complete/incomplete synthetic months. The full monitoring
verification checks the live histogram, provisioned dashboard and every live rule.
It accelerates only the disposable reporting-group cadence to one minute; overlapping
coverage rollups from that smoke run are not monthly evidence. Production-cadence
fixtures validate the accounting calculations separately. Alert windows are unchanged.
Use the isolated baseline workflow in `../load-tests/SLO-BASELINE.md` for measured client
results. Client latency includes network and scheduling overhead and is reported separately
from the server histogram; a short run does not establish 30-day reliability.

Design references: [Google SRE: implementing SLOs](https://sre.google/workbook/implementing-slos/)
and [multiwindow burn-rate alerting](https://sre.google/workbook/alerting-on-slos/).
