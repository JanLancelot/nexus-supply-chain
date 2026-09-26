# Incident response and local recovery drills

## Why this exists

Dashboards alone do not establish that a failure reaches a responder or that a
recovery action restores a customer's operation. These drills verify the chain
from fault, through Prometheus and Alertmanager, to a real local HTTP receiver,
and finally to a fresh login and inventory read. They reuse existing alerts;
there is no additional monitoring service to operate.

The approach follows [Google SRE's incident-response guidance](https://sre.google/workbook/incident-response/):
record the response as it happens, assign responsibility, communicate impact,
and restore service. A single developer may fill the incident coordinator,
operations, and communications roles during a lab exercise; identify that
explicitly rather than implying a staffed on-call team.

[Google SRE's monitoring guidance](https://sre.google/sre-book/monitoring-distributed-systems/)
distinguishes user-visible symptoms from internal causes. A failed management
scrape does not prove public unavailability. A failed business-metrics snapshot
does not identify PostgreSQL as the cause. The drills therefore verify the
authenticated operation independently and record both observations.

## Run a drill

Prerequisites: local Docker with Compose, Python 3, and sufficient memory for the
backend, PostgreSQL, Kafka, Redis, Prometheus, and Alertmanager. The first run may
build/pull images. No cloud account or paid notification service is required.

```sh
python3 bin/run-incident-drill.py --scenario all
# For a focused repeat:
python3 bin/run-incident-drill.py --scenario backend
python3 bin/run-incident-drill.py --scenario database
```

Allow about 20 minutes after image building for both scenarios. Rule hold times,
scrape/evaluation intervals, and the Alertmanager `group_wait: 30s` and
`group_interval: 5m` are the checked-in production values. A resolved notification
can legitimately arrive several minutes after the application recovers. There
is no accelerated mode that could be mistaken for a production-timing result.

The command always creates a new `nexus-drill-<12 hex characters>` project. It
accepts no existing project, URL, Compose file, credential, or receiver argument.
It discards application/Compose environment overrides, disables `.env` loading,
generates credentials, and pins a validated local Unix Docker endpoint. Remote
Docker contexts, writable host mounts, custom/bind-backed volume drivers, and
privileged device/network modes are refused. Read-only mounts must resolve inside
the repository or this drill's temporary directory. Only newly seeded database
volumes are used. HTTP requests require loopback URLs and status 200, and do not
follow redirects that might otherwise forward an authentication token.
Published ports bind to random loopback ports. Notifications go exclusively to
the disposable receiver on the private notification network. Grafana and its
database are omitted because they do not participate in detection or recovery.

This is a local experiment, not a network-isolation/security certification: the
development bridge permits local host access and image builds can access package
repositories. Existing local application projects are not stopped or modified.

Reports are written below `.artifacts/incidents/<UTC>-<project>/` and excluded
from Git and Docker build contexts. `report.json` includes UTC events, monotonic
elapsed times, one bounded authenticated read every three seconds after the
previous read finishes, receipt timestamps, configuration SHA-256 hashes, source
commit, and cleanup status. Only response success and duration are retained;
tokens, account credentials, response bodies, and receiver payloads are excluded.

Success requires firing and resolved deliveries for the **same alert episode**
(fingerprint and start timestamp), received after this fault/recovery boundary.
Old matching receiver events cannot complete a new scenario. Baseline preparation
waits for pre-existing pending/firing alerts to clear. Success also requires a fresh login and nonempty
inventory response after recovery, and a successful business snapshot taken
after the recovery action. The database scenario additionally requires the
backend process to remain running and its metrics to remain reachable. Exit 0
means all requested scenarios and cleanup passed; exit 1 means failure; exit 130
means interruption. Incomplete results remain incomplete in the report.

All waits and subprocesses are bounded. Startup failure, drill failure, Ctrl-C,
and SIGTERM enter cleanup; cleanup checks that project-labelled containers,
networks, and volumes are gone. Power loss, SIGKILL, or an unavailable Docker
daemon can prevent cleanup. If reported cleanup fails, inspect **only** the
generated project shown in the report. Restore Docker before removing its
containers and attached disposable volumes/networks; never run a global prune.

## Measurement definitions

| Interval | Start | End |
| --- | --- | --- |
| Detection | Stop command completes (`fault_active`) | Runner observes the failure signal in Prometheus |
| Notification | Fault active | Runner observes a matching firing event in the local receiver |
| Recovery action to user recovery | Start command requested | Fresh login and inventory read both pass |
| Fault to user recovery | Fault active | Fresh login and inventory read both pass |
| Recovery notification delay | User operation restored | Runner observes matching resolved delivery |

The report also records the stop request, first observed post-fault user failure, alert
firing, telemetry recovery, and receiver receipt UTC time. Observation intervals
include polling overhead (normally up to two seconds, plus HTTP query time) and local
resource contention; they do not identify the exact first failing request.
The failure signal and user-impact samples are observed independently in the same
loop, so a slow user request does not postpone signal observation. Receiver time
uses the container's host clock. Automated recovery begins after
delivery verification, so its delay is an experiment constraint, not human
acknowledgement or diagnosis time. These are individual **local drill results**,
not production MTTD/MTTR, availability, an SLO, or disaster recovery measurements.
Use multiple documented repetitions before reporting an average.

## Severity and response ownership

The following are proposed operating conventions for this project, not a claim
about a production support contract. Incident severity follows confirmed customer
impact; alert severity is only an initial routing hint.

| Severity | Impact | Response |
| --- | --- | --- |
| SEV1 | Inventory/orders broadly unavailable or possible data-integrity loss | Declare incident, name coordinator and operations lead, engage database/infrastructure owner, publish updates every 15 minutes while impact continues |
| SEV2 | Partial degradation with a usable workaround | Assign owner, assess affected operations, update every 30 minutes while active |
| SEV3 | Telemetry or dependency warning without confirmed user impact | Investigate in a tracked task; escalate if customer impact is confirmed |

For a local drill, the operator fills all roles and updates the local timeline.
Nothing sends customer messages or opens external tickets. In a real incident,
the coordinator delegates work, sets the next update time, and confirms handoffs.
The operations lead makes one reversible change at a time and verifies results.
The communications lead owns a single stakeholder update stream. Record role
owners and timestamps in the incident ticket (the format works in GitHub Issues
or ServiceNow without requiring either integration).

## Backend unavailable runbook

Trigger: `NexusBackendDown` firing. Open the Prometheus target error and correlate
it with API error/latency signals and a permitted authenticated inventory read.

1. Record onset, scope, recent releases, environment, and the affected operation.
   Check whether the backend process is stopped, unhealthy, resource constrained,
   or merely unreachable from the metrics network. Verify ingress and management
   connectivity separately. Do not infer a public outage from `up == 0` alone.
2. If user operations fail broadly, declare SEV1 and notify the application and
   infrastructure owners. If only monitoring fails, begin at SEV3.
3. In the disposable drill the known fault is a stopped backend, so the runner
   starts that service. In an actual incident, inspect termination reason and
   recent deployment before choosing restart, rollback, or network repair. Do
   not restart healthy dependencies or blindly repeat a failed recovery action.
4. Verify a fresh login, nonempty inventory read, fresh business snapshot, and
   resolved alert delivery. Check the original failing route and any affected
   order workflow before declaring a real incident resolved.
5. Preserve sanitized evidence, record customer impact and remediation owner,
   and schedule a postmortem when severity or recurrence warrants it.

## Database unavailable while backend remains running runbook

Trigger in this drill: `NexusSnapshotFailed{component="business"}`. This is a
warning about stale business telemetry, not a database-specific pager. At low
request volume it can reveal database trouble before traffic-based error alerts.
An SLO-based page should be based on affected user operations when available.

1. Confirm the backend process and metrics endpoint are still reachable. Inspect
   the failed snapshot component, database pool pending/active connections,
   PostgreSQL readiness, storage, connection limits, and recent changes. A
   successful HTTP health probe alone is insufficient evidence of recovery.
2. Run an authorized inventory read and fresh login; record failures or timeouts.
   Promote to SEV1 if critical operations are broadly unavailable. Do not expose
   SQL credentials or customer data in the ticket.
3. The lab stops the existing disposable PostgreSQL container without deleting
   its volume; recovery starts that same container. In production, involve the
   database owner before failover or restoration. Do not delete/recreate the
   database volume or enlarge the connection pool as a default response.
4. Confirm login and inventory access recover, then confirm a **new** successful
   snapshot and resolved delivery. Verify pending orders and asynchronous work
   if those operations were affected. This read-only drill does not demonstrate
   write correctness, data-loss tolerance, or backup restoration.
5. Record the cause and a tested preventive action. A cached business gauge may
   retain its last value while the database is down; freshness and success state
   determine whether that value is usable.

## Communications and postmortems

Use this update format during a real incident or rehearse it locally:

> [UTC] [severity] [investigating/mitigating/monitoring/resolved]. Affected
> operation and known scope: __. Confirmed evidence: __. Action and owner: __.
> Workaround: __ or none confirmed. Next update: [UTC].

Separate verified facts from hypotheses, acknowledge unknown impact, and avoid
promising an unmeasured recovery time. Record handoffs and a contact/owner for
follow-up. The local tool writes technical events; it does not measure these
human coordination practices.

Use a blameless review after an actual drill or qualifying incident, following
[Google SRE's postmortem guidance](https://sre.google/workbook/postmortem-culture/).
Include impact, detection/notification/recovery times, trigger and contributing
conditions, what worked, what delayed response, and dated follow-up owners. Each
action needs a verification condition; distinguish completed fixes from proposed
work. Do not turn fabricated timestamps or synthetic fixtures into incident
history. Sanitized completed drill evidence belongs in a reviewed case study;
raw generated reports remain local.
