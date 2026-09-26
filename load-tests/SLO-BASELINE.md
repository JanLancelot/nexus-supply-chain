# Local SLO baseline

`slo-baseline.py` establishes a small repeatable measurement for the inventory
read and order creation journeys. It uses the Python standard library already
required by verification, so running it adds no load-generator service or image.
This is a proposed workload against demo fixtures, not a capacity test or evidence
of production reliability. Validate request rates and targets with real user needs
before adopting them as a service commitment.

The disposable monitoring verification harness supplies the base URL, generated
project name, and administrator credentials. The runner checks the container's
Compose project/service labels, running state, loopback port binding, and demo
environment before sending credentials or creating orders. It refuses the normal
development stack. It never resets a database; cleanup belongs to the harness.

Run the complete isolated workflow from the repository root:

```sh
./bin/verify-monitoring.sh --baseline-report load-tests/results/slo-baseline.json
```

The harness creates the target, supplies private credentials, validates monitoring,
runs the workload, and removes only its disposable resources. Choose a new report
filename for each run. The result directory is already Git-ignored.

Equivalent invocation **inside an existing disposable verification run**:

```sh
python3 load-tests/slo-baseline.py \
  --base-url "http://127.0.0.1:$disposable_backend_port" \
  --disposable-project "$disposable_project" \
  --duration 60 --inventory-rps 10 --order-rps 1 \
  --output load-tests/results/slo-baseline.json
```

Supply `LOAD_TEST_ADMIN_EMAIL` and `LOAD_TEST_ADMIN_PASSWORD` through the process
environment, never command-line arguments or committed files. Use the generated
credentials for that isolated project. Output must be a new file; existing files
and symlinks are rejected. Reports are written with mode `0600` and contain no
response bodies, fixture identifiers, credentials, or authentication tokens.

The default run performs five warm-up operations per journey, then schedules
10 inventory reads and one new order per second for 60 seconds. Inventory reads
request the first 50 products. Each order contains one active product, quantity
one, its warehouse, and an active supplier. Authentication, discovery, and warm-up
are excluded from baseline samples. There are at most 16 requests in flight and
a five-second request timeout. Overload or late scheduling drops arrivals and
fails workload validation instead of silently lowering the arrival rate.

The JSON report records actual counts and latency percentiles with these limits:

- Response availability counts `2xx`, `429`, and `5xx`; only `2xx` is good.
  Other status codes are excluded from that server-response SLI but still reported
  and fail workload validation. Invalid requests must not make a baseline look good.
- Transport failures and dropped arrivals reduce the separate end-to-end contract
  success fraction. A timeout is never reported as a healthy server response.
- Inventory success within 500 ms uses **client-observed elapsed time**, including
  connection setup and response-body transfer. It is distinct from the server-side
  Prometheus histogram used by the SLO dashboard.
- Sample targets are 99.9% response availability for each operation and 99%
  successful inventory responses within 500 ms. A short successful run cannot
  substantiate a 30-day 99.9% guarantee; the report labels its local scope.
- Exit status `0` means workload validation and proposed sample targets passed;
  `1` means a measured failure or missed target; `2` means prerequisites or safe
  execution failed. All completed measured runs retain their report, including
  unsuccessful ones.

Preserve the report alongside the commit ID, host/runtime resources, dataset size,
and verification invocation when presenting results. Compare repeated runs under
the same conditions and retain failures. Do not substitute synthetic rule fixtures
or short baseline samples for a complete observed SLO reporting window.
