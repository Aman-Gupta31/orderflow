# OrderFlow — Complete Interview Prep Guide

This document assumes you're starting from zero. Read it top to bottom once, then use the
"Rapid-fire Q&A" section at the end to drill yourself before an interview.

---

## 1. The 30-second pitch

> "OrderFlow is a backend I built that simulates how a real e-commerce order system works —
> product catalog, inventory, orders, payments — using Java, Spring Boot, MySQL, Redis, and
> Kafka. The focus wasn't just making it work, but making it *correct* under real-world
> conditions: concurrent customers racing for the same stock, retried payment requests,
> services going down temporarily. I tested all of that live against real local instances of
> every service, found several real bugs in the process, and documented each one — not just
> the code."

That last sentence is your strongest asset. Anyone can copy a tutorial. Finding and explaining
real bugs is what makes this sound like genuine engineering experience.

---

## 2. What the project actually does (plain English)

Imagine a tiny Amazon. A customer:
1. Registers an account, logs in, gets a token proving who they are.
2. Browses products (an admin created these).
3. Places an order — the system reserves stock for them so nobody else can buy the same unit.
4. Pays for the order (simulated — no real money, no real card details).
5. If payment succeeds, the order is confirmed. If it fails, the order is cancelled and the
   stock goes back on the shelf for someone else.
6. Every important thing that happens (order created, payment succeeded/failed, order
   cancelled) is recorded as an "event" that other parts of a larger system could react to later
   — this is what Kafka is for.

An admin can create/update products and adjust stock levels, and see everyone's orders.

## 3. Why build it this way instead of the simplest possible version?

The simplest version of this (one table per thing, no auth, no caching, no events) would take an
afternoon and teach you nothing useful for interviews. Every piece of added complexity here
exists to demonstrate a *specific, nameable* backend engineering problem and its solution:

| Problem | Where it shows up | What you'd say in an interview |
|---|---|---|
| Two people buying the last item at once | Inventory reservation | "I used an atomic SQL UPDATE instead of read-then-write, so there's no window where two requests both think stock is available." |
| A payment retry charging someone twice | Payment idempotency | "I used an Idempotency-Key header plus a hash of the request body, so retrying the exact same request replays the first response instead of reprocessing." |
| Losing data if the app crashes between saving to the database and telling other systems | Kafka + transactional outbox | "I write the 'event to publish' into the same database transaction as the real change, so they can never get out of sync." |
| A customer seeing someone else's order | Security/authorization | "Role checks handle 'can you do this kind of thing at all'; a separate ownership check handles 'is this *specific* order yours'." |
| Slow repeated reads of the same data | Redis caching | "Cache-aside with a short TTL, plus a fallback so a Redis outage degrades speed, never correctness." |

---

## 4. The tech stack, and why each piece is there (not just "because it's popular")

- **Java 17 + Spring Boot** — the dominant enterprise backend stack; Spring's dependency
  injection and auto-configuration let you focus on business logic instead of plumbing.
- **MySQL** — the system of record. Relational, because orders/inventory/payments have strict
  consistency requirements (you cannot "eventually" have correct stock counts).
- **Flyway** — versioned database migrations. The schema's history is in version control, not
  implicit in whatever Hibernate decides to generate.
- **Spring Security + JWT** — stateless authentication. No server-side session storage; the
  token itself proves identity on every request.
- **Redis** — a fast, shared cache in front of MySQL for read-heavy data (product lookups).
- **Apache Kafka** — a durable, ordered event log, so other (hypothetical) systems — shipping,
  notifications, analytics — could react to "an order was placed" without this app needing to
  know they exist.
- **Maven** — build tool; the Maven Wrapper (`./mvnw`) means anyone can build the project without
  installing Maven themselves first.
- **JUnit 5 / Mockito / MockMvc** — the testing stack. Unit tests for business logic, MockMvc for
  testing HTTP behavior without a running server.

---

## 5. How a request actually flows through the system

Walk through this out loud in an interview — it shows you understand the whole stack, not just
one layer.

**Example: `POST /api/orders`**

1. The request arrives with a JWT in the `Authorization` header.
2. `JwtAuthenticationFilter` validates the token's signature and expiry, then looks up the user
   fresh from the database (not trusting the token's embedded role — more on why below) and
   marks the request as authenticated.
3. Spring Security checks: is this URL open to any authenticated user? Yes (orders aren't
   role-restricted, just "must be logged in").
4. `OrderController.create` receives the parsed, validated request body (Bean Validation already
   rejected anything malformed before this method even runs).
5. `OrderService.createOrder` — all inside one database transaction:
   - Looks up each product's **current** price (never trusts a client-supplied price)
   - Calls `InventoryService.reserve` for each item — an atomic SQL `UPDATE` that only succeeds
     if there's enough stock *at that exact instant*
   - If any reservation fails, the whole method throws, and the transaction rolls back — nothing
     partially happens
   - Saves the order, and writes an "OrderCreated" event into an `outbox_events` table — same
     transaction, so it either both happens or neither does
6. Response: the order's DTO, serialized to JSON.
7. **Separately, a few seconds later:** a background poller (`OutboxRelay`) notices the new
   outbox row, publishes it to Kafka, and marks it published.
8. **Separately again:** a Kafka consumer picks up the event and records it in an audit log table
   — this is what a real notification/analytics service would hook into.

Notice steps 7 and 8 happen *after* the customer already has their response — the event
publishing is decoupled from the request, so a slow Kafka broker never slows down order creation.

---

## 6. Module-by-module deep dive

### Module A — Product catalog & layered architecture

**What:** CRUD for products (name, SKU, price, active flag), paginated listing, admin-only
mutation endpoints.

**Why layered architecture (Controller → Service → Repository)?** If a controller queried the
database directly, every endpoint would re-implement rules like "SKU must be unique," and you
couldn't test that rule without starting a web server. Services own business rules; repositories
own persistence; controllers just translate HTTP ↔ Java objects.

**Why DTOs instead of returning the database entity directly?** The entity (`Product`) is shaped
by what the *database* needs (JPA annotations, lazy-loading proxies). The DTO (`ProductResponse`)
is shaped by what the *API contract* needs. Decoupling them means a database refactor doesn't
automatically become an API-breaking change.

**Interview Q&A:**
- *Q: Why constructor injection instead of `@Autowired` on fields?*
  A: It makes dependencies explicit and required — the object can't exist in a half-wired state
  — and it lets you unit test the class with plain `new ClassName(mockA, mockB)`, no Spring
  context needed.
- *Q: What's the N+1 query problem, and does this project have it anywhere?*
  A: It's issuing one query to fetch N rows, then N more queries fetching each row's related
  data one at a time, instead of one join. `OrderItem → Order` is lazy on purpose for this
  reason — see Module C for where this almost bit us.

### Module B — Inventory concurrency (your best interview story)

**What:** Each product has an inventory row (`quantity_available`, `quantity_reserved`).
Reserving stock must never let two customers both "win" the last unit.

**The naive, wrong approach:** read the current quantity in Java, check if it's enough, then
write the new quantity back. Between the read and the write, another request can read the *same*
pre-decrement value and also think it's fine. Both proceed. You've now sold -1 units.

**The actual fix:** a single SQL statement that checks and decrements atomically:
```sql
UPDATE inventory
   SET quantity_available = quantity_available - :qty
 WHERE product_id = :id AND quantity_available >= :qty
```
The database evaluates the `WHERE` clause and performs the write as one atomic, lock-protected
operation. If two requests race, whichever one's `UPDATE` is processed second re-evaluates the
condition against the *already-decremented* value — so it correctly sees not enough stock and
affects zero rows. The application checks "did this affect 0 rows or 1?" to know if it won.

**Why not `synchronized` in Java?** It only coordinates threads inside one JVM process. The
moment you run two copies of this app behind a load balancer (which any real deployment does),
`synchronized` does nothing — the two instances share no memory to synchronize on. The lock has
to live in the database, the one thing both instances actually share.

**Proven, not just claimed:** fired two real concurrent HTTP requests at a product with exactly 1
unit of stock. One got `201 Created`, the other got `409 Conflict`. Final inventory: 0 available,
1 reserved. No overselling.

**Interview Q&A:**
- *Q: Optimistic vs. pessimistic locking — which did you use?*
  A: Neither, really — an atomic conditional UPDATE avoids the read-modify-write cycle both
  strategies are built to guard. Pessimistic locking (`SELECT ... FOR UPDATE`) would also work
  but holds a row lock for your whole service method; optimistic locking (`@Version`) means
  failures surface as "someone else changed this, retry," which is more moving parts than a
  single-row decrement needs.
- *Q: What does it mean that the UPDATE "affected 0 rows"?*
  A: The WHERE condition didn't hold at that exact instant — either no inventory row exists for
  that product, or there wasn't enough stock right then.

### Module C — Orders, transactions, and a real bug

**What:** An order has a state machine: `CREATED → CONFIRMED → SHIPPED/CANCELLED`. Totals are
always computed server-side from current product prices, never trusted from the client.

**Why `@Transactional` matters here:** creating an order touches two things — the order row and
the inventory row. If the app crashed between saving the order and reserving the stock, you'd
have an order with no stock held for it. Wrapping both in one transaction means either the whole
thing commits, or none of it does.

**The real bug (tell this story in interviews):** `GET /api/orders/{id}` initially returned a
`500` error — but only for the order's *own owner*, and only sometimes. The cause:
`OrderResponse.from(order)` (which reads `order.getItems()`, a lazily-loaded collection) was
being called in the *controller*, after the service method's database transaction (and the
Hibernate session that goes with it) had already closed. Touching a lazy collection after its
session is gone throws `LazyInitializationException`. It was masked in the `cancel()` endpoint
purely by coincidence — that method happens to touch `getItems()` *inside* its own transaction for
an unrelated reason (releasing stock), which accidentally initialized the collection early.

**The fix:** moved all entity-to-DTO mapping *inside* the service's `@Transactional` methods, so
it always runs while the session is still open. This wasn't found by reading the code carefully —
it was found by actually running the server and calling the endpoint for real.

**Interview Q&A:**
- *Q: Tell me about a bug you found in your own project.*
  A: (Use the story above. This is a genuinely good answer — it shows you test your own work,
  you understand *why* the bug happened at a framework level, and you fixed the root cause
  rather than patching around it.)
- *Q: Why compute totals on the server instead of trusting a client-supplied total?*
  A: A client-supplied number can be tampered with trivially (browser dev tools, Postman). The
  server is the only party that can be trusted to know the real, current price.

### Module D — Error handling

**What:** One centralized place (`GlobalExceptionHandler`) converts every exception type into a
consistent JSON shape with the right HTTP status code — `400` for bad input, `404` for missing
resources, `409` for state conflicts (duplicate SKU, insufficient stock, invalid state
transition), `401`/`403` for auth failures, `500` for anything unexpected (logged in full
server-side, shown to the client as a generic message — never leak a stack trace).

**Interview Q&A:**
- *Q: 404 vs 409 — what's the difference?*
  A: 404 means the thing you asked about doesn't exist. 409 means it exists, your request is
  well-formed, but applying it conflicts with the current state (e.g., not enough stock, or the
  resource was already modified by someone else).

### Module E — Security: JWT, roles, ownership

**What:** Register/login issue a JWT. Every request (except public endpoints) must carry
`Authorization: Bearer <token>`. Roles (`CUSTOMER`/`ADMIN`) gate entire categories of action;
a separate ownership check gates access to a *specific* resource (your own order, not someone
else's).

**Why hash passwords with BCrypt specifically?** BCrypt is deliberately slow and salts each
password individually. A fast hash (SHA-256, MD5) lets an attacker who steals your password
database try billions of guesses per second; BCrypt makes that computationally expensive on
purpose.

**Why re-look-up the user from the database on every request instead of trusting the JWT's role
claim?** A JWT, once issued, can't be revoked early — if you trust its embedded role, a demoted
or deactivated user keeps acting with old permissions until the token naturally expires (up to an
hour, here). Re-checking the database on every request closes that gap, at the cost of one extra
query per request — an explicit, deliberate trade-off.

**Why does a nonexistent order return 404 even to someone who isn't its owner, instead of 403?**
Authorization and existence are different questions. If a non-owner got 403 for both "this isn't
yours" and "this doesn't exist," they'd be able to tell the two cases apart by nothing — but more
importantly, 403 should mean "I can see something is there, you can't touch it," not "there's
nothing here." Getting this distinction right is a detail interviewers specifically listen for.

**Known, stated limitation:** no refresh-token rotation. Tokens are short-lived (1 hour) and once
expired, you must log in again. This is a real gap, not hidden — a production system would add
refresh tokens.

**Interview Q&A:**
- *Q: Authentication vs authorization — define both.*
  A: Authentication is proving who you are. Authorization is deciding what you're allowed to do,
  which only makes sense once authentication has already happened.
- *Q: Why is there no `role` field in the registration request?*
  A: So a client can never self-grant ADMIN by crafting a request. Every self-registered account
  is CUSTOMER by construction; the only ADMIN account is seeded server-side from environment
  variables.

### Module F — Redis caching (three real bugs, all found live)

**What:** `GET /api/products/{id}` is cached — a classic cache-aside pattern. Writes evict the
specific key; a TTL (5 minutes) bounds staleness for anything else.

**Why only cache get-by-id, not the list/search endpoint?** A list endpoint's cache key would
need to encode every combination of filter/page/sort a client might request — many keys, messy
invalidation ("which list variations might contain this product?"). One key per product id is
simple and has an obvious eviction target.

**Three real bugs found while building this (great interview material — shows you don't just
assume code works):**
1. Caching a product threw a serialization error — the JSON library configured for Redis had no
   support for `java.time.Instant`. Fixed by registering the missing module.
2. After that fix, a cache *hit* (not a miss) threw `ClassCastException`. Turns out fixing bug #1
   required supplying a custom JSON mapper, which accidentally dropped a different default
   setting ("type hints" in the JSON) needed to deserialize the right Java class back out.
3. Stopping Redis to test the fallback behavior revealed the app *did* keep working (correct
   design), but took **120 seconds** per request instead of failing fast — Redis's client library
   defaults to a very long timeout. Fixed with an explicit 1-second timeout; same test afterward
   took ~2 seconds.

**Interview Q&A:**
- *Q: What happens if Redis goes down?*
  A: A custom error handler catches any Redis exception and lets the request fall through to the
  database instead of failing the whole request — degraded speed, never degraded correctness.
  Verified live by actually stopping Redis mid-test.
- *Q: Redis vs. a plain Java `HashMap` cache?*
  A: A `HashMap` is per-process — if you run multiple app instances, each has its own copy, so
  one instance's cache eviction wouldn't clear another's stale entry, and it vanishes on restart.
  Redis is shared and external, so cache state is consistent across instances.

### Module G — Payments, Kafka, and the transactional outbox

**What:** A mock payment provider (no real money). Idempotent — retrying the exact same payment
request replays the original response instead of charging twice. Success confirms the order;
failure releases the reserved stock and cancels the order.

**How idempotency actually works:** the client sends an `Idempotency-Key` header. The server
hashes the request body and stores it alongside the key and the response. A repeat of the *same*
key with the *same* body replays the stored response verbatim, without re-running any business
logic. The same key with a *different* body is rejected (`409`) — that's almost always a client
bug, not a legitimate retry.

**The transactional outbox pattern — this is the single best "I understand distributed systems"
talking point in the whole project:**

The problem: you want to update your database *and* tell Kafka about it, but the database and
Kafka are two separate systems with no shared transaction. If you publish to Kafka first, then
your database write fails — you've told the world about something that never actually happened.
If you commit to the database first, then crash before publishing — the event is silently lost
forever.

The fix: write "there is an event to publish" as a row in your *own* database, in the *same*
transaction as the real change. A separate background process polls that table and publishes to
Kafka, marking rows as published only after Kafka confirms receipt. If that process crashes
between Kafka confirming and the row being marked published, the event gets published again next
time — this gives you "at-least-once" delivery, never "lost."

**Why does the consumer need to handle duplicates, then?** Because "at-least-once" means a
message can arrive twice. The consumer records each event's unique ID in a database table with a
uniqueness constraint — a duplicate either gets caught by a simple existence check, or in a
genuine race, by the database's own constraint. Either way, processing the same event twice has
no extra effect — this is what "idempotent consumption" means, and it's what turns
"at-least-once" into "effectively-once" from the outside.

**A real gap found here too:** the failed-payment path originally published a "PaymentFailed"
event but *not* an "OrderCancelled" event — even though the order genuinely did get cancelled in
that same request. Found by inspecting the audit log and noticing an expected row was missing.
Fixed by publishing both events for that path.

**Interview Q&A:**
- *Q: Does your outbox pattern give exactly-once delivery?*
  A: No — it gives reliable at-least-once delivery. Exactly-once isn't really achievable across
  two separate systems without a shared transaction; what you *can* achieve is "effectively-once"
  by making the consumer idempotent, which is what I did.
- *Q: Why not just publish to Kafka directly inside the service method, right after saving?*
  A: Because the database transaction and the Kafka call aren't atomic together — a crash or a
  brief Kafka outage between the two would silently lose the event even though the database
  change is durable. The outbox table makes "there's an event to publish" part of the same atomic
  write as the business change.
- *Q: Why does a malformed Kafka message get rethrown instead of just logged and ignored?*
  A: So the configured retry/dead-letter mechanism handles it (3 retries, then routed to a
  separate "dead letter" topic) instead of silently vanishing with no trace and no chance to
  reprocess it later.

---

## 7. Rapid-fire Q&A bank (drill yourself before an interview)

1. **What problem does an atomic conditional UPDATE solve that `synchronized` doesn't?**
   Multi-instance deployments — `synchronized` only protects within one JVM process.
2. **Why DTOs instead of returning entities directly?**
   Decouples the API contract from the database schema; avoids leaking lazy-loading internals.
3. **Why BCrypt for passwords?**
   Deliberately slow + per-password salted, making brute-force attacks computationally expensive.
4. **Why re-check the database for a user's role on every request instead of trusting the JWT?**
   A revoked/changed role should take effect immediately, not after the token naturally expires.
5. **What's the difference between 403 and 404, and why does it matter here?**
   403 = exists but not yours; 404 = doesn't exist. Conflating them either leaks existence info or
   misleads the client about what's actually wrong.
6. **What's a cache-aside pattern?**
   Check the cache first; on a miss, read the real source, then populate the cache before
   returning.
7. **What happens to a request if Redis is completely down?**
   It still succeeds — just slower — because a custom error handler catches Redis failures and
   falls through to the database instead of failing the request.
8. **What does "Idempotency-Key" protect against?**
   A client (or network) retrying the same payment request twice, which without this would risk
   double-processing it.
9. **What's the transactional outbox pattern, in one sentence?**
   Writing "an event needs to be published" into the same database transaction as the business
   change it describes, so the two can never get out of sync.
10. **At-least-once vs exactly-once delivery — which does this project achieve, and how do you
    get the practical benefit of exactly-once anyway?**
    At-least-once at the transport level; "effectively-once" in practice by making the consumer
    idempotent (deduplicating by a unique event ID).
11. **Name a real bug you found in this project, the root cause, and the fix.**
    (Use the `LazyInitializationException` story from Module C, or any of the three Redis bugs
    from Module F — know at least one cold.)
12. **Why does the order's total get computed server-side instead of accepting it from the
    client?**
    A client-supplied number can be tampered with; the server is the only trustworthy source for
    current prices.
13. **What's the known limitation of your JWT implementation?**
    No refresh-token rotation — tokens are short-lived (1 hour) and expired tokens require a full
    re-login.
14. **Why is self-registration restricted to the CUSTOMER role, with no client-settable role
    field?**
    So a client can never grant itself ADMIN by crafting a request.

---

## 8. How to use this document

Read it once fully. Then, for each numbered module (A–G), close the laptop and try to explain
it out loud to an empty room in under 90 seconds, covering what it does, why it matters, and one
concrete detail (a bug, a trade-off, a line of code). If you can do that for all seven, you're
ready.
