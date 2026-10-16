# Module 3 — Orders: transactions & the state machine

## A. Concept

A **database transaction** groups multiple statements so they either all commit or all roll
back — "atomicity" in ACID. A **state machine** restricts an entity to a fixed set of states
and only the transitions you've explicitly allowed between them, instead of letting any code
path set `status` to any string.

## B. Why

An order touches two things that must stay consistent: the `orders`/`order_items` rows and the
`inventory` row. If stock gets reserved but the order row never gets written (say, the app
crashes between the two), you've silently shrunk your sellable inventory for nothing. Wrapping
both in one `@Transactional` method guarantees that either the full set of changes lands, or
none of it does. The state machine exists because "can this order be cancelled?" is a business
rule, not a UI concern — if any code can set `status = "CANCELLED"` directly, nothing stops a bug
from cancelling a `DELIVERED` order.

## C. How it works internally

Spring wraps a `@Transactional` method in a proxy. When `OrderService.createOrder` is called,
the proxy opens a transaction (or joins an existing one, with the default `REQUIRED`
propagation) before the method body runs, and commits it after the method returns normally — or
rolls it back if an unchecked exception propagates out. This is why `InventoryService.reserve`,
called from inside `createOrder`, doesn't need its own separate commit: it's `@Transactional`
too, but with `REQUIRED` propagation it joins the caller's existing transaction rather than
opening a nested one. Concretely: if item 1 reserves successfully and item 2's reservation then
throws `InsufficientStockException`, that exception unwinds out of `createOrder`, Spring's
`TransactionInterceptor` sees it, and issues a `ROLLBACK` — undoing item 1's reservation too,
even though it "succeeded" moments earlier. Nothing in the Java code explicitly undoes it; the
database rollback does.

The state machine itself is deliberately dumb on purpose: `OrderStatus.canCancel()` is a pure
function over an enum (`CREATED` and `CONFIRMED` are cancellable; `SHIPPED`, `DELIVERED`,
`CANCELLED` are not), and `Order.cancel()` is the *only* method that can change an order's
status, and it checks that function before doing so. There's no setter for `status`.

## D. Code walkthrough

- **`Order` constructor** — takes a customer identifier and a list of `OrderItem`s, sets
  `status = CREATED`, and computes `totalAmount` by summing `OrderItem.lineTotal()`. This total
  is never taken from the client — the client sends `productId` + `quantity` only
  (`OrderItemRequest`); `OrderService.toOrderItem` looks up the current `Product.getPrice()` for
  each line, so a tampered or stale client-side price can never reach the database.
- **`Order.cancel()`** — guards with `status.canCancel()`, throws `InvalidOrderStateException`
  otherwise (mapped to `409 Conflict`). This is the whole state machine enforcement — one
  `if` in one place.
- **`OrderService.createOrder`** — orchestrates: resolve each line's real product + price →
  reserve stock for every item → construct and save the `Order`. Note the order of operations:
  stock is reserved *before* the `Order` is constructed, so a failed reservation never leaves a
  half-built `Order` object lying around to accidentally persist.
- **`OrderService.cancel`** — loads the order, calls `order.cancel()` (which throws if not
  allowed, before anything else happens), then releases reserved stock for every item. Because
  this whole method is `@Transactional`, if `release()` for item 2 fails, item 1's already-applied
  release rolls back too — the order isn't left half-cancelled with half its stock back.

## E. Request flow

`POST /api/orders/{id}/cancel` → `OrderController.cancel` → `OrderService.cancel` (transaction
opens) → `OrderRepository.findById` → `order.cancel()` (in-memory state check + enum
transition) → for each item, `InventoryService.release` (atomic UPDATE) → commit → `200 OK` with
the updated `OrderResponse`.

## F. Example

Cancelling a `DELIVERED` order:
```
POST /api/orders/7/cancel
```
```json
409 Conflict
{"status": 409, "error": "Conflict", "message": "Order 7 cannot be cancelled from status DELIVERED"}
```
Cancelling a `CREATED` order instead returns `200` with `"status": "CANCELLED"`, and a
subsequent `GET /api/inventory/{productId}` shows `quantityAvailable` increased by the
cancelled quantity.

## G. Interview preparation

**Q: Why calculate the total on the server instead of trusting the client's total?**
A: A client-supplied total (or even a client-supplied unit price) can be tampered with in a
request the browser dev tools or a tool like Postman sends directly — the server is the only
party that can be trusted to know the real current price. This project never reads a price
from the request body at all; it only reads `productId` + `quantity`.

**Q: Why not just use a plain `String status` field with validation scattered across services?**
A: Because then every call site that touches `status` has to remember the rules, and a new
contributor can introduce an invalid transition without the compiler or a single review point
catching it. Centralizing `canCancel()` on the enum means there's exactly one place to look,
and exactly one place to add a new rule (e.g., a `SHIPPED → RETURNED` transition later).

**Q: What happens if the app crashes mid-way through `createOrder`, after `reserve()` succeeds
for item 1 but before the order row commits?**
A: Nothing is actually committed yet — the whole method is one transaction, and the JDBC
connection + transaction die with the crashed process without a COMMIT ever being issued, so
MySQL rolls back automatically on connection loss. The reservation for item 1 never became
durable. This is exactly the guarantee `@Transactional` is providing.

## H. Debugging

- Enable `logging.level.org.springframework.transaction=DEBUG` to see transaction
  begin/commit/rollback log lines per request.
- If you see stock "stuck" as reserved after a cancel, check whether `cancel()` actually threw
  partway through its loop over `order.getItems()` — since the whole method is transactional, a
  partial failure there should roll back entirely, not leave some items released and others not.
- A `LazyInitializationException` on `order.getItems()` outside a transaction means you're
  accessing a lazy/managed collection after the session closed. **This actually happened** during
  Stage 3's live verification: `OrderController.getById` originally called
  `OrderResponse.from(orderService.getById(id))`, with the DTO mapping (which touches
  `order.getItems()`) happening in the *controller*, after `OrderService.getById`'s own
  `@Transactional(readOnly = true)` method had already returned and closed its Hibernate session
  (`open-in-view` is deliberately `false` — see `application.yml` — so no session lingers past the
  transaction). `cancel()` never showed the bug because it happens to iterate
  `order.getItems()` *inside* its own `@Transactional` method (to release stock), which
  incidentally initializes the collection before the session closes — pure luck, not a working
  design. Stage 2's manual smoke tests never exercised a plain successful `GET
  /api/orders/{id}`, so this shipped unnoticed until Stage 3's end-to-end testing hit it as a real
  `500`. **Fix:** `OrderService` now builds the `OrderResponse` DTO itself, inside each
  `@Transactional` method, and returns DTOs (never raw `Order` entities) to the controller —
  closing off this entire class of bug at once rather than patching each call site. See
  `docs/learning-guide/05-security-jwt-auth.md`'s debugging section for how this surfaced.

## I. Revision notes

- **Key takeaways:** one `@Transactional` boundary per use case, not per repository call;
  state transitions live on the entity, not scattered across services; totals and prices are
  always server-computed.
- **Important annotations:** `@Transactional`, `@Enumerated(EnumType.STRING)`.
- **Important commands:** `./mvnw test -Dtest=OrderDomainTest,OrderServiceTest`.
- **Memory aid:** "One transaction, one story — all of it happens, or none of it did."
- **Mini-quiz:**
  1. Why is `@Enumerated(EnumType.STRING)` used instead of the default (ordinal)? *(Ordinal
     stores the enum's position as an int — reordering or inserting a new `OrderStatus` value
     would silently corrupt existing data's meaning. STRING is self-describing and safe to
     reorder.)*
  2. Why does `OrderService.createOrder` reserve stock before constructing the `Order` object,
     rather than after? *(So a reservation failure never leaves a constructed-but-unsaved `Order`
     around — fail fast, before building the thing you might have to discard.)*
- **Coding task:** `Order.confirm()` (`CREATED → CONFIRMED`) was added for real in Stage 4, driven
  by `PaymentService` on a successful payment - see
  [07-payments-and-kafka.md](07-payments-and-kafka.md). As your own exercise instead: add a
  `SHIPPED` transition, valid only from `CONFIRMED`, and decide (and document) whether `SHIPPED`
  should still be cancellable - `OrderStatus.CANCELLABLE_FROM` currently says no.
