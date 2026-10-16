# OrderFlow — Order and Inventory Management System

A backend simulating a real e-commerce order/inventory flow: product catalog, concurrency-safe
stock reservation, an order state machine, JWT-based auth with role/ownership enforcement, Redis
caching with real resilience guarantees, simulated idempotent payments, and Kafka domain events
published via a transactional outbox — built to be interview-defensible rather than just
"generated." See [docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md) for full status.

## Stack (through this stage)

Java 17 · Spring Boot 3.5.16 · Spring Data JPA / Hibernate · Bean Validation · Spring Security +
JWT (JJWT 0.13.0) · MySQL 8/9 · Redis (Spring Cache + Lettuce) · Apache Kafka 4.x (KRaft, Spring
Kafka) · Flyway · Maven (wrapper included) · JUnit 5 / Mockito / MockMvc / Spring Security Test ·
Testcontainers (integration tests) · Spring Boot Actuator.

## Package structure

```
com.aman.orderflow
├── auth       (register/login DTOs, service, controller)
├── user       (User entity/repository, /api/users/me)
├── security   (JWT issue/validate, filter, SecurityConfig, admin seeding)
├── config     (CacheConfig, LoggingCacheErrorHandler)
├── product    (catalog: entity, DTOs, repository, service, controller)
├── inventory  (atomic stock reservation: entity, repository, service, controller)
├── order      (order + line items, state machine, service, controller, ownership check)
├── payment    (mock payment provider, idempotent via common.idempotency)
├── messaging
│   ├── event    (versioned event envelope + payloads)
│   ├── outbox   (OutboxEventWriter, OutboxEvent, OutboxRelay)
│   ├── audit    (idempotent OrderEventAuditConsumer)
│   └── config   (topic provisioning, retry/dead-letter error handler)
└── common
    ├── exception    (domain exceptions + GlobalExceptionHandler)
    ├── response     (ApiError)
    └── idempotency  (generic Idempotency-Key replay/conflict support)
```

## Run it locally (no Docker required for this stage)

Every piece of this stage was verified against real, locally-installed services (MySQL, Redis,
Kafka — all via Homebrew), not mocks — see [docs/TESTING.md](docs/TESTING.md) for the actual
commands and results, including several real bugs that were found and fixed during that
verification.

```bash
# 1. MySQL: create the app's database/user once
mysql -u root -e "
  CREATE DATABASE IF NOT EXISTS orderflow CHARACTER SET utf8mb4;
  CREATE USER IF NOT EXISTS 'orderflow'@'localhost' IDENTIFIED BY 'orderflow';
  GRANT ALL PRIVILEGES ON orderflow.* TO 'orderflow'@'localhost';
  FLUSH PRIVILEGES;
"

# 2. Redis
brew services start redis   # or: redis-server (foreground)

# 3. Kafka (KRaft mode, single node - no ZooKeeper needed)
#    First time only: kafka-storage format -t "$(kafka-storage random-uuid)" \
#      -c "$(brew --prefix kafka)/libexec/config/server.properties" --standalone
kafka-server-start "$(brew --prefix kafka)/libexec/config/server.properties"

# 4. Build (JAVA_HOME must point at a Java 17 JDK)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./mvnw package -DskipTests

# 5. Generate a JWT secret and run (JWT_SECRET is required - the app refuses to start without it)
export JWT_SECRET=$(openssl rand -base64 64 | tr -d '\n')
# Optional: seed one ADMIN account on first run against an empty database
export ADMIN_EMAIL=admin@orderflow.dev
export ADMIN_PASSWORD='ChangeMe123!'
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home java -jar target/orderflow.jar

# 6. Health check
curl localhost:8080/actuator/health
```

See `.env.example` for the full list of environment variables and their defaults.

## Docker path (once Docker Desktop finishes installing on this machine)

A `Dockerfile` + `docker-compose.yml` (MySQL, Redis, Kafka) land in Stage 5 per the project's
staged build plan — not added yet, to avoid claiming container support before it's been run and
verified.

## Tests

```bash
./mvnw test      # unit + controller tests — no external dependencies, 38/38 passing
./mvnw verify    # adds Testcontainers-based integration tests — needs Docker
```

See [docs/TESTING.md](docs/TESTING.md) for current, actually-verified results, including a
manual proof of the concurrent-stock-reservation guarantee, the full auth/ownership flow, the
Redis cache hit/evict/outage-recovery cycle, and the complete order→payment→Kafka→audit-log
pipeline — all run against real services, not mocks.

## API (through this stage)

```
POST   /api/auth/register             {"email","password"} -> CUSTOMER account + token
POST   /api/auth/login                {"email","password"} -> token
GET    /api/users/me                  [authenticated]

GET    /api/products                  [public]
GET    /api/products/{id}             [public, Redis-cached]
POST   /api/products                  [ADMIN]  create product + initial stock
PUT    /api/products/{id}              [ADMIN]
DELETE /api/products/{id}              [ADMIN]  deactivate (soft)

GET    /api/inventory/{productId}      [public]
PATCH  /api/inventory/{productId}/adjust  [ADMIN]  {"delta": n}

POST   /api/orders                    [authenticated]  {"items":[{"productId","quantity"}]}
GET    /api/orders/me                 [authenticated]  caller's own orders
GET    /api/orders/{id}                [owner or ADMIN]
GET    /api/orders                    [ADMIN]  all orders, optional ?customerEmail=
POST   /api/orders/{id}/cancel         [owner or ADMIN]

POST   /api/payments                  [owner or ADMIN]  header: Idempotency-Key
                                       {"orderId","simulateFailure"(mock-only, optional)}
GET    /api/payments/{orderId}         [owner or ADMIN]

GET    /actuator/health               [public]
```

All requests except `/api/auth/**`, `/actuator/health`, and `GET` on products/inventory require
`Authorization: Bearer <token>` from `/api/auth/login`. Every self-registered account is
`CUSTOMER` — there is no client-settable role anywhere; the only `ADMIN` account comes from the
`ADMIN_EMAIL`/`ADMIN_PASSWORD`-gated seeding described above.

A successful payment confirms its order (`CREATED → CONFIRMED`); a failed one releases its
reserved stock and cancels the order. Both paths publish Kafka events (`orderflow.order-events`,
`orderflow.payment-events`) via a transactional outbox, consumed into a durable, idempotent audit
trail (`event_audit_log`) — see
[docs/learning-guide/07-payments-and-kafka.md](docs/learning-guide/07-payments-and-kafka.md).

## Learning guide

Every module here is documented for interview defensibility — concept, why, internals, code
walkthrough, request flow, interview Q&A, debugging, and a quiz — in
[docs/LEARNING_GUIDE.md](docs/LEARNING_GUIDE.md).

## Known limitations (honest, as of this stage)

- JWT access tokens are short-lived (1 hour default) with **no refresh-token rotation** — once
  expired, the client must log in again. Documented, not hidden — see
  [docs/learning-guide/05-security-jwt-auth.md](docs/learning-guide/05-security-jwt-auth.md).
- `ConcurrentStockReservationIT` (Testcontainers) hasn't run yet on this machine — Docker
  Desktop's install hit a sudo/TTY snag this sandboxed environment can't resolve interactively.
  The same guarantees it tests (stock concurrency, auth/ownership, cache behavior, and the
  payment/Kafka pipeline) were all verified manually against real local services instead (see
  docs/TESTING.md). Re-run `./mvnw verify` once Docker is up.
- Only `GET /api/products/{id}` is cached (Redis) — a deliberate scope decision, not an oversight;
  see [docs/learning-guide/06-redis-caching.md](docs/learning-guide/06-redis-caching.md) for why
  list/search endpoints weren't.
- The mock payment provider resolves synchronously and has no `PENDING` state — a real gateway is
  asynchronous; this is a documented simplification, not an oversight.
- Dead-letter topics (`*.dlt`) exist and are wired up, but nothing automatically reprocesses them
  — in a real deployment that's a human-monitored queue or a separate reprocessing job.
- No Dockerfile/Compose or CI pipeline yet — Stage 5.
