# Data flow and integration

## Authentication

1. The UI submits an email and password to `POST /api/v1/auth/login`.
2. The API verifies the active user and BCrypt hash, then returns a signed JWT.
3. The UI retains the JWT in memory and attaches it to API requests as `Authorization: Bearer …`.
4. The API validates the JWT and checks the current user record and role. The UI clears its session on expiry or a 401 response.

There is no refresh endpoint. A page reload requires a new login.

## Inventory adjustment and delivery

A controller validates the request and passes it to a transactional service. The service locks the affected records, checks permissions and quantity bounds, updates data, and records the relevant audit snapshot. Order delivery changes the order, stock, and audit rows in the same transaction. Product warehouses must match the order destination at creation and receipt. Manual audit snapshots retain `reasonCode`; receipt snapshots use `ACTION_ORDER_RECEIPT` and include `orderId`. Audit insert failure rolls back the business mutation.

Domain events are sent after commit. Consumers invalidate caches, create notifications, or evaluate replenishment. An event failure does not roll back a committed stock update. There is no durable outbox to close that gap.

## Broker selection

`SpringKafkaLiteBroker` probes Kafka at startup. If Kafka is required (`APP_EVENTS_KAFKA_REQUIRED=true`), startup fails when the probe cannot connect. Compose waits for broker health and sets this requirement. Otherwise, if unavailable, it delegates to `RedisKafkaLiteBroker`. The Redis broker uses list push/pop operations and can fall back to process-memory queues. The startup probes are synchronous. The broker does not continuously promote itself back to Kafka after a fallback.

Kafka gives each logical subscriber a stable group. A failed handler is retried up to three times at one-second intervals, then published to `<topic>.<subscriberId>.DLT`. Source recovery completes only after successful dead-letter publication; a failed dead-letter send remains retryable. Operators must monitor and explicitly replay or resolve these topics after correcting the cause. Independent consumers prevent retrying one handler from re-running successful unrelated handlers.

Order notifications use the event's transition status. Notifications carry an event UUID and a unique `(user_id, source_event_id)` key, so redelivery does not create duplicate notifications. Legacy messages use a deterministic identity derived from their payload. The notification and legacy audit consumer groups retain their previous offsets. Cache/replenishment subscribers use new groups and may evaluate historical events against current data; replenishment still locks products and checks for open orders. New audit rows are written synchronously; the audit consumer remains only to drain already queued events.

Redis list consumers compete for messages across application instances. Process-memory queues only reach consumers in the same process. Neither fallback provides durable acknowledgments/retries comparable to a production event-processing design.

## Caching

Product, category, warehouse, and analytics reads use Spring Cache. Mutation events clear caches through `CacheEvictionListener`. Delivery is asynchronous, so a successful write does not guarantee that the next cached read sees it.

Redis cache entries expire. A failed cache is bypassed while database reads continue. After a one-second recovery delay, a read attempts to clear that cache namespace before re-enabling it; failed evictions cannot cause old values to be served after recovery. No local response cache is used. Restrict access to Redis: it holds application responses and event data, and the application trusts allowed payload types.

## Replenishment

A low-stock event causes the service to reload the product, verify it is active, check for an open order, find an active explicitly associated supplier and the product's warehouse, and create a draft. Among assigned suppliers it chooses shortest lead time, then supplier UUID. Missing sourcing or warehouse prevents draft creation; no arbitrary fallback is used. Locking serializes competing decisions for the same product. The open-order query prevents another draft after the lock is released.

## Notifications and health

Unread notifications are retained. The hourly cleanup deletes only read notifications whose creation time is older than `APP_NOTIFICATIONS_READ_RETENTION_DAYS` (30 by default, 0 disables pruning).

`/api/health` reports database, broker-mode, and cache states. Optional fallback or cache bypass is `DEGRADED` with HTTP 200; database failure or loss of required Kafka is `DOWN` with HTTP 503. This is dependency readiness, not a guarantee that downstream business processing has no backlog.
