# Respond to an API error-budget alert

## Impact and ownership

The application operator owns initial triage. `inventory-availability` affects stock
lookup; `order-availability` affects replenishment order creation; `inventory-latency`
affects responsive stock lookup. Check which environment is affected before acting.
Fast burn is critical investigation; sustained burn is a warning work item. A local
exercise is not a customer incident.

## Diagnose

1. Open **Nexus · Service objectives** in Grafana. Confirm SLO, environment, request
   count, both burn windows, measurement completeness and first failure time.
2. Check backend reachability and the operations dashboard. A scrape outage invalidates
   response-based conclusions; independently try an authenticated inventory read.
3. Inspect route/status metrics, connection-pool waiters, dependency state and bounded
   logs. Correlate the onset with deployment/configuration changes. Exclude passwords,
   bearer tokens, payloads and customer data from reports.
4. Compare 429 overload with 5xx server failures. Do not increase pool sizes, retry counts
   or resource limits without identifying the bottleneck. Avoid retrying order creation
   blindly: a timeout can occur after the order was stored.

## Recover and verify

Restore the failed dependency or revert the identified bad configuration/image using the
appropriate deployment procedure. Do not change SLO thresholds to clear an incident.
In a disposable drill, restart only the resources that the drill created. In a deployed
environment, coordinate database changes and destructive recovery with the service owner.
Verify authenticated inventory reads and, using approved test data, order creation;
then confirm short-window burn and alert delivery recover. A green scrape alone is
insufficient evidence. Escalate to the service owner when the cause or recovery is unclear.

## Communicate and prevent recurrence

Record scope, user impact, onset, evidence, actions, current status and next update time.
An example update is: "Inventory reads in [environment] are failing; investigation has
identified [observed condition]. [Action] is underway; next update at [time]."
Never claim a root cause before evidence supports it. After recovery, record the causal
chain, contributing conditions and a concrete preventive action with an owner and a
verification method. Measure local drill timing separately from production outcomes.
