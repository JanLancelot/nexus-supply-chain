# Data flow and integration

## Authentication

1. The UI submits an email and password to `POST /api/v1/auth/login`.
2. The API verifies the active user and BCrypt hash, then returns a signed JWT.
3. The UI retains the JWT in memory and attaches it to API requests as `Authorization: Bearer …`.
4. The API validates the JWT and checks the current user record and role. The UI clears its session on expiry or a 401 response.

There is no refresh endpoint. A page reload requires a new login.

## Inventory adjustment and delivery

A controller validates the request and passes it to a transactional service. The service locks the affected records, checks permissions and quantity bounds, updates data, and records the relevant audit snapshot. Order delivery changes the order and stock in the same transaction.

Domain events are sent after commit. Consumers invalidate caches, create notifications, or evaluate replenishment. An event failure does not roll back a committed stock update. There is no durable outbox to close that gap.

## Broker selection

`SpringKafkaLiteBroker` probes Kafka at startup. If unavailable, it delegates to `RedisKafkaLiteBroker`. The Redis broker uses list push/pop operations and can fall back to process-memory queues. The startup probes are synchronous. The broker does not continuously promote itself back to Kafka after a fallback.

Kafka uses stable consumer groups per topic so a normal restart can resume committed offsets. When migrating from the former random groups, initialize the new group's offsets deliberately for existing topics; otherwise historical events may replay. Handler side effects are not universally idempotent.

Redis list consumers compete for messages across application instances. Process-memory queues only reach consumers in the same process. Neither fallback provides durable acknowledgments/retries comparable to a production event-processing design.

## Caching

Product, category, warehouse, and analytics reads use Spring Cache. Mutation events clear caches through `CacheEvictionListener`. Delivery is asynchronous, so a successful write does not guarantee that the next cached read sees it.

Redis cache entries expire. Caching is disabled when Redis is unavailable at initialization, avoiding stale per-instance cache entries. Restrict access to Redis: it holds application responses and event data, and the application trusts allowed payload types.

## Replenishment

A low-stock event causes the service to reload the product, verify it is active, check for an open order, resolve a supplier and warehouse, and create a draft. Locking serializes competing decisions for the same product. The open-order query prevents another draft after the lock is released.
