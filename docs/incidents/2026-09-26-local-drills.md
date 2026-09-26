# Local backend and database recovery exercise

This case study records two controlled local Docker exercises, using seeded data
and a receiver that never contacts an external service. It is not a production
incident, a customer availability measurement, or evidence of a staffed on-call
response. The response actions were automated; human acknowledgement,
investigation, and communications time were not measured.

## Scope and hypothesis

The hypothesis was that a stopped backend and an unavailable PostgreSQL service
would produce actionable monitoring evidence, reach the configured local
notification receiver, and recover an authenticated inventory operation when the
stopped service restarted. Grafana was not needed for this exercise: Prometheus
evaluated the existing rules, Alertmanager delivered events, and the HTTP client
checked a user operation independently.

Both faults were deliberate `docker compose stop` operations against a new
`nexus-drill-*` project. The database scenario preserved its volume and kept the
backend running. No fault targeted the developer's running application or a
remote host. New credentials, random loopback bindings, isolated resource names,
and local-only notification destinations bounded the experiment.

The backend alert is a critical management-scrape failure signal. The database
exercise uses the existing business-snapshot warning; that warning does not by
itself diagnose a database incident. Authenticated request failures established
the user-visible symptom in both scenarios. A real outage of both critical
operations would warrant SEV1 under the proposed runbook; this rehearsal had no
real customer impact and did not page anyone.

## Results and evidence

Completed on 26 September 2026, from **01:47:37 to 02:04:29 UTC**, using source
commit `20982bca237d0ceadb246e076e8d1e99014c54cb`. Both scenarios and cleanup
passed. The rule holds, 15-second scrape/evaluation intervals, 30-second initial
notification grouping delay, and five-minute group interval were unchanged.

| Observed interval (seconds) | Backend unavailable | Database unavailable |
| --- | ---: | ---: |
| Fault active to internal signal observed | 12.1 | 48.3 |
| Fault active to firing notification observed | 172.6 | 208.9 |
| Recovery action to fresh login and inventory read | 11.0 | 4.4 |
| Fault active to verified user recovery | 183.6 | 213.3 |
| User recovery to resolved notification observed | 289.0 | 295.5 |

There was **one completed trial per scenario**. Values are individual automated
observations, rounded to a tenth of a second; polling and HTTP query time limit
their precision. They are not averages or production MTTD/MTTR. The fault was
held until firing delivery was verified. Resolution delivery came later than
service recovery because Alertmanager retained its production grouping interval.

| Event (UTC, 26 September 2026) | Backend | Database |
| --- | --- | --- |
| fault_active | 01:48:03.940 | 01:55:57.096 |
| signal_detected | 01:48:15.995 | 01:56:45.358 |
| alert_firing_observed | 01:50:26.413 | 01:58:55.867 |
| notification_observed | 01:50:56.514 | 01:59:26.015 |
| recovery_started | 01:50:56.514 | 01:59:26.022 |
| user_operation_restored | 01:51:07.533 | 01:59:30.445 |
| telemetry_restored | 01:51:15.577 | 01:59:46.553 |
| resolution_observed | 01:55:56.571 | 02:04:25.957 |

The backend trial recorded 158 completed client samples, including 60 failures;
the database trial recorded 125, including 26 failures. These are bounded
observer requests, not counts of affected customers or server-side HTTP 5xx
responses. The database fault left the backend process and metrics endpoint
running while the authenticated operation failed. Both trials delivered firing
and resolved events for the same alert episode to the local receiver.

The private JSON report's SHA-256 is
`aabcea03021879cb1e1d067441cd62bc3af26a40da756880cd5c4a26ca435e9c`.
Its checked-in configuration hashes are:

| Configuration | SHA-256 |
| --- | --- |
| `docker/prometheus/rules/nexus.yml` | `deaa3abf77b4be38229df44620689398b4b72e8aa609baa63f4a40b585e4d38b` |
| `docker/prometheus/prometheus.yml` | `948555224df04d935ed831a821c2f381f726beba6a7b0e10a9b35c0f3ad80d29` |
| `docker/alertmanager/alertmanager.example.yml` | `02559a7c2776359f872dcac9242536209cf0d9a5c5cf60d37096e04fa93fb871` |

The required default verification passed (164 backend tests, with the three
expected default-mode integration skips, plus frontend and tooling checks).
The subsequent endpoint-tracking change passed all 14 incident boundary tests
and this live trial using PostgreSQL, Redis, and Kafka. Dedicated container-mode
backend tests were not rerun for this tooling-only feature.

## Recovery and lessons

The runner restored each stopped service after observing its firing delivery.
It required a fresh login, a nonempty inventory response, a successful business
snapshot taken after recovery began, and a resolved notification matching the
firing episode's fingerprint and start timestamp. A successful process start or
health probe alone could not end the exercise.

Preparation exposed two incorrect assumptions in the verification tool. The
inventory endpoint intentionally returns `totalElements: -1` because it uses
slice pagination without a count query. Verification now checks actual content
instead of requiring a positive total. Docker also assigns a new random host
port when the stopped backend starts again; recovery now rediscovers the owned
container's loopback binding before login and subsequent traffic. Both fixes
have regression tests and were included in the completed trial. Earlier failed
or deliberately interrupted development runs are excluded from the timings.

| Action | Owner | Status and verification |
| --- | --- | --- |
| Prevent stale deliveries from declaring an incident resolved | Repository maintainer | Completed: current fault/recovery boundaries plus matching alert episode; older and unrelated events rejected in regression tests |
| Measure user symptoms and internal signals independently | Repository maintainer | Completed: separate observations in one polling loop, ignoring pre-fault failed requests; regression proves slow user impact does not postpone detection |
| Follow actual inventory pagination and restarted Docker endpoints | Repository maintainer | Completed: public-boundary regressions and the completed recovery exercise |
| Keep experiments away from existing storage and external contacts | Repository maintainer | Completed: random owned resources, local Unix engine, safe mounts, local receiver, redirect rejection, and verified cleanup |
| Establish user-impact SLO paging at representative traffic volume | Repository maintainer | Separate SLO work; the snapshot warning remains diagnostic evidence rather than a claim of customer-impact paging coverage |
| Verify a configured external notification provider after hosting resumes | Repository maintainer | Pending deployment and private receiver configuration; local delivery does not validate email or Better Stack delivery |

The response and follow-up format is based on the principles in
[Google SRE's incident-response guidance](https://sre.google/workbook/incident-response/)
and [postmortem guidance](https://sre.google/workbook/postmortem-culture/): preserve
a factual timeline, distinguish restoration from investigation, and give each
improvement a verification condition.

## Reproduce and communicate

Run `python3 bin/run-incident-drill.py --scenario all` from this branch. The
[runbooks](../incident-response.md) explain preconditions, timings, severity,
ownership, and the recovery checks. Raw JSON/Markdown reports stay below ignored
`.artifacts/incidents/` with private permissions. No raw logs, secrets, tokens,
or database contents are included in this case study.

For an interview demonstration, show the healthy operation, introduce one
bounded fault, identify the relevant alert and user symptom, explain the recovery
action, and verify the operation and resolved delivery. State explicitly that
the fault and response are automated. Use the runbook's stakeholder-update
template as a communication rehearsal; no stakeholder messages were sent during
this exercise.
