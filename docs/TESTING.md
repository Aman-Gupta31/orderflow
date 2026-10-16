# Testing status

## How to run tests

```
./mvnw test              # unit + controller tests (no external dependencies)
./mvnw verify             # adds integration tests (requires Docker running)
```

Unit tests (`*Test.java`) are picked up by Surefire's default pattern and run with `./mvnw test`.
Integration tests (`*IT.java`) are picked up by Failsafe and only run during `./mvnw verify`.
This is a deliberate split: unit/controller tests should run fast, in any environment, with
zero external dependencies; integration tests exercise a real MySQL instance via Testcontainers
and need Docker.

## Current results (verified, 2026-10-15, after Stage 4)

| Suite | Command | Result |
|---|---|---|
| Unit + controller tests | `./mvnw test` | **38/38 passed** — `InventoryServiceTest` (4), `OrderDomainTest` (3), `OrderServiceTest` (3), `ProductControllerTest` (5), `JwtServiceTest` (5), `ProductServiceCacheTest` (2), `CacheResilienceTest` (1), `PaymentServiceTest` (5), `IdempotencyServiceTest` (3), `OutboxRelayTest` (3), `OrderEventAuditConsumerTest` (4) |
| `ConcurrentStockReservationIT` | `./mvnw verify` | **Fails — Docker not available on this machine.** The `brew install --cask docker` attempt also failed (postflight step needed an interactive sudo password this sandboxed shell can't supply — see chat). Testcontainers reports: `Could not find a valid Docker environment ... NoSuchFileException (/var/run/docker.sock)`. This is an environment gap, not a test-logic failure. |

## Manual end-to-end verification (done, 2026-10-15, no Docker involved)

Docker Desktop isn't finished installing on this machine yet (see chat), so rather than leave
the concurrency guarantee unverified, it was proven against a real, locally-installed MySQL 9.0
instance (via Homebrew, already present on this machine) instead of Testcontainers:

1. Built the jar (`./mvnw package -DskipTests`), pointed `application.yml`'s defaults at a real
   `orderflow` database/user created for this purpose, and ran `java -jar target/orderflow.jar`.
2. Confirmed Flyway applied `V1__init_schema.sql` cleanly against real MySQL (log: `Successfully
   validated 1 migration` / `Schema orderflow is up to date`).
3. `GET /actuator/health` → `{"status":"UP"}`.
4. Exercised every documented scenario with `curl` against the live server: product creation,
   duplicate-SKU → `409`, missing-field validation → `400` with field errors, order creation with
   server-computed total, insufficient-stock → `409` with the reservation transaction fully rolled
   back (inventory unchanged), order cancellation releasing reserved stock, cancelling an
   already-cancelled order → `409`, and a nonexistent order → `404`. All matched expected behavior
   exactly.
5. **The actual race scenario**, fired as two real concurrent HTTP requests (backgrounded `curl`
   calls hitting the running server at the same instant) against a product with exactly 1 unit of
   stock: one request got `201 Created`, the other got `409 Conflict` with "Insufficient stock",
   and the final inventory row showed `quantityAvailable: 0, quantityReserved: 1` — exactly one
   reservation succeeded, no overselling, no double-decrement. This is the same guarantee
   `ConcurrentStockReservationIT` automates; it was proven manually first since Docker wasn't
   available for the automated version yet.

This was real execution against a real database and a real running server — not simulated,
not asserted without running.

## Manual end-to-end verification — Stage 3 (security), done 2026-10-15

Same approach: real jar, real MySQL (schema reset and re-migrated clean for the new `users`
table + `orders.user_id` column), real HTTP requests, real JWTs.

1. Registered two customers (`alice@example.com`, `bob@example.com`) and confirmed the seeded
   admin account (`ADMIN_EMAIL`/`ADMIN_PASSWORD` env vars, no committed credentials) logs in.
2. `POST /api/auth/login` with a wrong password → `401`; registering a duplicate email → `409`;
   registering a sub-8-character password → `400` with a field error. All as designed.
3. `GET /api/users/me` with no token → `401`; with a garbage token → `401`; with a valid token →
   `200` with the correct profile.
4. Alice (`CUSTOMER`) attempting `POST /api/products` → `403` (not `401` — she's authenticated,
   just lacking the role). Admin performing the same request → `201`.
5. **Ownership enforcement, the real cross-customer scenario:** Alice placed an order; Bob
   (a different authenticated customer) was correctly refused with `403` on both
   `GET /api/orders/{id}` and `POST /api/orders/{id}/cancel` for Alice's order, while Alice
   herself could view/cancel it (`200`), the admin could list all orders, and requesting a
   genuinely nonexistent order ID as Bob correctly returned `404` (not `403` — confirming
   `OrderSecurity.canAccess` doesn't mask missing resources as ownership violations).
6. **A real bug was found and fixed during this verification**, not before: `GET
   /api/orders/{id}` initially returned `500` (`LazyInitializationException` on
   `Order.items`) for the *order's own owner* — a genuine architectural bug (DTO mapping was
   happening after the transactional session had already closed), caught only because this was
   actually run against a live server and database rather than assumed to work. Fixed by moving
   all entity→DTO mapping inside `OrderService`'s own `@Transactional` methods. Full writeup:
   [docs/learning-guide/03-order-transactions-state-machine.md](learning-guide/03-order-transactions-state-machine.md#h-debugging).
   Re-ran the full scenario afterward and confirmed `200` for the owner, correctly.

## Manual end-to-end verification — Stage 4 (Redis caching), done 2026-10-15

Redis (8.2.1, via Homebrew, already installed and running locally) + the real jar. Three real
bugs were found and fixed during this verification, each caught by actually running the full
outage/recovery cycle rather than assuming the resilience design worked:

1. First attempt at caching a `Product` failed silently (absorbed by the resilience handler,
   which was working correctly) because `GenericJackson2JsonRedisSerializer`'s custom
   `ObjectMapper` had no `java.time` support — `Product.createdAt` (an `Instant`) couldn't
   serialize. Fixed with `findAndRegisterModules()`.
2. After that fix, a cache *hit* (not a miss) threw `ClassCastException: LinkedHashMap cannot be
   cast to Product` — supplying a custom `ObjectMapper` had also silently dropped the "default
   typing" (`@class` type hints) that `GenericJackson2JsonRedisSerializer`'s own default
   constructor normally sets up. Fixed by re-activating it explicitly.
3. Stopping the local Redis service and hitting `GET /api/products/{id}` returned the correct
   `200` (the resilience handler worked), but took **120 seconds** — Lettuce's default command
   timeout is far longer than acceptable for "graceful degradation." Fixed with explicit 1-second
   `spring.data.redis.timeout`/`connect-timeout`; the same outage scenario re-tested afterward at
   ~2 seconds.

After all three fixes, re-verified the full cycle end to end: cold `GET` (`223ms`, populates
cache) → repeat `GET`s (`11ms`, then `4ms`, real measured cache hits, confirmed via
`redis-cli GET` showing the `@class`-tagged JSON) → `PUT` update (`200`, evicts the key,
confirmed via `redis-cli EXISTS` returning `0`) → next `GET` serves the updated value and
repopulates the cache → Redis stopped (`200` in `~2s`, degraded not broken) → Redis restarted →
immediate next `GET` (`200` in `9ms`, the exact sequence that previously threw the
`ClassCastException`, now correct). Full writeup with the actual numbers:
[docs/learning-guide/06-redis-caching.md](learning-guide/06-redis-caching.md).

## Manual end-to-end verification — Stage 4 (payments, Kafka, outbox), done 2026-10-15

Real local Kafka (4.3.1, KRaft mode, single-node, via Homebrew) + real MySQL + real jar.

1. Installed Kafka via `brew install kafka`, confirmed the bundled `server.properties` is
   pre-configured for KRaft (`process.roles=broker,controller`), started it with
   `kafka-server-start`, and proved it was functional (not just listening) by creating and
   listing a topic before ever touching the app.
2. Alice created an order → confirmed a row appeared in `outbox_events`
   (`published` flipped `0→1` within the 2s poll window) and the identical `event_id` then
   appeared in `event_audit_log` - the full outbox → Kafka → consumer pipeline, working for real,
   not just unit-tested in isolation.
3. Paid for the order (`POST /api/payments`, success case): order transitioned
   `CREATED → CONFIRMED`; a second request with the **same** `Idempotency-Key` and body replayed
   the exact original response (same `createdAt`, confirming no reprocessing - only one row ever
   existed in `payments`); the same key with a **different** body correctly returned `409`.
4. Paid for a second order with `simulateFailure: true`: payment recorded `FAILED`, order
   transitioned to `CANCELLED`, and reserved stock was released back
   (`quantityAvailable` returned to its pre-reservation value) - all three effects from one
   request, verified via direct SQL and `GET /api/inventory/{id}`.
5. Cross-customer and state checks: a different customer attempting to pay for or view payments
   on someone else's order → `403`; paying for an order already `CANCELLED` → `409`.
6. **A real gap was found and fixed during this verification**: the failed-payment path published
   a `PaymentFailed` event but not an `OrderCancelled` one, even though the order genuinely did
   transition to `CANCELLED` in that same request - caught by directly inspecting
   `event_audit_log` and noticing the expected row was missing, not by assuming the design was
   complete. Fixed in `PaymentService.pay`; re-verified the corrected trail (`OrderCreated` →
   `PaymentFailed` → `OrderCancelled`, all three published and consumed) afterward. Full writeup:
   [docs/learning-guide/07-payments-and-kafka.md](learning-guide/07-payments-and-kafka.md).

## Why this test isn't weakened or skipped silently

`ConcurrentStockReservationIT` (`src/test/java/.../inventory/ConcurrentStockReservationIT.java`) proves
the core concurrency guarantee of Module C: two "customers" racing for the last unit of stock, exactly
one reservation succeeds. This needs a real database, because the guarantee comes from MySQL evaluating
`UPDATE ... WHERE quantity_available >= :qty` as a single atomic statement — an in-memory stand-in
(H2, or a mocked repository) can't demonstrate that, since there's no real row-level write contention to
resolve. Per the project's testing rules, this is being called out explicitly and left in the `verify`
phase rather than deleted or quietly skipped: once Docker Desktop is finished installing (in progress —
see chat), re-run `./mvnw verify` and this file will be updated with the real result.
