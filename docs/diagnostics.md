# Repeatable local incident diagnostics

Collect the same bounded evidence for each incident before deciding on recovery:

```sh
python3 bin/diagnose.py --project nexus-local
```

The command prints a path to a private Markdown report and writes a JSON report
beside it under `.artifacts/diagnostics/`. No account credentials are required.
Docker Desktop must be running and the named application stack must already
exist. The command does not start, stop, exec into, or modify containers. It makes
read-only Docker requests and Prometheus HTTP GET requests.

## Why this exists

During a dependency failure, an operator repeatedly gathers container health,
scrape health, alerts, pool pressure, and recent logging activity before making a
judgment. Making this collection repeatable reduces omitted steps and produces
consistent evidence for incident handoff. Automating repetitive operational work
is the rationale; collection speed alone is not evidence of reduced human toil.
Google's [Eliminating Toil](https://sre.google/sre-book/eliminating-toil/) separates
manual effort from machine elapsed time and distinguishes repeatable work from
investigation that requires judgment.

This tool deliberately stops at collecting evidence. A failed scrape does not
prove the website is unavailable, an empty alert list does not prove it is
healthy, and a connection count does not identify a slow SQL query. The operator
still verifies the user-facing operation and follows the relevant runbook.

## Scope and result contract

`--project` is mandatory. Supported projects are `nexus-local`,
`nexus-supply-chain`, `nexus-monitoring-test-<10 lowercase hex characters>`, and
`nexus-drill-<12 lowercase hex characters>`. Select the exact local Compose
project; names alone are not accepted as container identity. Every discovered
container's Compose project and service labels are checked. Unsupported service
labels are omitted. At most 32 containers are inspected. The known incident drill
profile expects six services, excluding its intentionally absent Grafana and
Grafana database; every drill container must also have the disposable-drill
label. Other supported projects expect all eight application/monitoring services.

The current Docker context is resolved once and must point to a Unix socket.
Subsequent calls pin that endpoint and remove Docker environment overrides. SSH
and TCP daemons are refused. Prometheus must be the single running Prometheus
service in the selected project, with port 9090 published only on loopback. Its
host port is discovered from Docker, so the tool supports random test ports.
HTTP proxies, redirects, arbitrary URLs and credentials are not accepted. The
production overlay intentionally has no published Prometheus port: the tool
reports those sections as unavailable without opening any port. It is a local
operator tool, not a public support endpoint or remote fleet agent.

| Section | Evidence retained | Interpretation |
| --- | --- | --- |
| Services | Fixed service names; state, health status, restart/exit counts, OOM flag | No healthcheck means unknown application health; inspect the user operation |
| Targets | Known job names, scrape health and presence of a scrape error | Missing jobs are incomplete evidence; error text and target URLs are omitted |
| Alerts | Known Nexus alert names, severity and pending/firing state | Watchdog firing is expected; unknown alerts are counted but their labels are omitted |
| Metrics | Active/max/pending DB connections, cache/event status, snapshot success and age | Pools sum across instances; dependency state takes the worst value; missing/nonfinite samples are explicit errors |
| Logs | Line count and recognized severity counts, per service | Last 200 lines in ten minutes; no messages, stack traces or request data are exported |

Metrics use fixed PromQL queries against the [Prometheus HTTP
API](https://prometheus.io/docs/prometheus/latest/querying/api/). Aggregation
removes instance, environment and pool labels. These are operational signals, not
inventory counts or customer records. A down dependency may have a fresh metric
value of zero: a **complete** report can therefore correctly describe a degraded
service. `collection_status` describes evidence collection, not system health.

Log summaries use Docker's [`--tail` and `--since`
options](https://docs.docker.com/reference/cli/docker/container/logs/). Both
stdout and stderr count; logging without recognized severity is `unclassified`.
A line limit can truncate a stack trace, severity words can occur in messages,
and a quiet log does not imply health. These counts guide further restricted
inspection; they are not error-rate metrics. Free-text removal, rather than
pattern-based masking, prevents unanticipated credentials or personal data from
being copied to the report. Each log command is capped at 256 KiB. Exceeding the
limit makes that service's log evidence unavailable instead of silently sampling
an oversized line.

Docker commands have a ten-second deadline and a one-MiB combined output cap
(except the smaller log cap). HTTP operations have a five-second socket timeout,
a three-second Prometheus query timeout and a one-MiB response cap. No automatic
retries delay an incident report. Partial failures retain independent evidence.
A maximum-size 32-container project takes at most roughly twelve minutes if
all Docker commands hit their deadlines; ordinary collection is much shorter.

Exit statuses are stable:

- `0`: all requested sections collected. This is **not** a health check.
- `1`: a report was written, but evidence is missing or unavailable.
- `2`: target validation/discovery or report creation failed; no complete report
  is promised. Error codes omit source error messages, which may contain secrets.

JSON reports use `schema_version: 1`, with timestamp, project, elapsed seconds,
collection status and named sections. A future schema change must bump the
version. Reports are snapshots, not an atomic transaction: the service can
change between observations. Docker socket access is already privileged; this
command does not provide a security boundary against a malicious local Docker
administrator.

## Private output and tests

Each report is created exclusively in a random timestamped directory with mode
`0700`; files have mode `0600`. Existing reports are never overwritten. Use
`--output-dir /absolute/physical/path` to choose a different destination; symlink
paths are refused. On macOS use `/private/tmp`, not the `/tmp` symlink. Custom
output directories are your responsibility to keep out of Git and backups that
are shared externally. The default report directory is excluded from Git and the Docker build context.

Run the public-boundary tests and repository checks:

```sh
python3 -m unittest discover -s tests -p 'test_diagnostics.py'
./bin/verify.sh
```

Tests invoke the CLI with a disposable Docker executable and actual loopback HTTP
server. They prove target rejection, missing evidence handling, redirect refusal,
proxy isolation, bounded processes, file permissions and removal of generated
sensitive markers from JSON, Markdown and terminal output. They do not prove a
real Docker daemon or application is healthy. A live collection and its measured
results should be reported separately.

## Measuring collection effort without inventing savings

The repeatable machine comparison runs the same five sections either as five
separate CLI invocations or as one combined invocation. Both modes use the same
Docker target, queries, log bounds and report projection. Each mode runs at least
three trials; ordering alternates. Separate commands re-discover the target five
times, while the combined command discovers it once. Snapshots occur at different
times and this measures process/collection overhead, not incident resolution or
human investigation. There is no assertion that the combined mode must be faster.

```sh
python3 bin/benchmark-diagnostics.py --project nexus-local --iterations 3
```

All underlying reports, exit codes, per-trial durations, medians, fixed query
expressions and a hash of the diagnostic source are retained privately under
`.artifacts/diagnostics/benchmarks/`. A missing section makes the comparison
partial and must be investigated before drawing a conclusion. The tool does not
inject failures into the running application.

For a **human** baseline, use the same operator and a repeatable disposable fault:

1. Define the investigation task before timing: identify affected user operation,
   failing dependency, freshness of evidence, and next safe runbook step.
2. Practice each workflow once. Randomize which workflow is first for each of at
   least three paired trials; restore the same fixture between trials.
3. For the separate workflow, run `diagnose.py --section services`, `targets`,
   `alerts`, `metrics`, and `logs`, each with the same explicit `--project`.
   This is a five-command baseline with identical data protections, not a claim
   to reproduce every operator's existing tooling.
4. For the combined workflow, run `diagnose.py --project <same disposable target>`.
   Record hands-on time, total elapsed time, commands, conclusion accuracy,
   missing evidence and any retries for both methods. Time reading and forming
   the conclusion as well as running the commands.
5. Publish all paired observations, the sample size and limitations. Do not infer
   saved human minutes from the machine benchmark or call a single drill's
   duration an average recovery time. If automation does not help, investigate
   the bottleneck before adding more automation.

A separate controlled human study has not yet been performed. Machine elapsed measurements must be recorded with their sampling and scope limitations.

The completed local machine benchmark and a real degraded collection are recorded
with their limitations in [diagnostic collection measurements](diagnostic-measurements.md).
