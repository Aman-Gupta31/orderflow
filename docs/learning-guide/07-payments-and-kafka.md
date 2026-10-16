# Module 7 — Simulated payments, Kafka events & the transactional outbox

## A. Concept

**Idempotency** means repeating the same request produces the same effect as doing it once -
critical for payments, where a client retrying after a timeout must never risk a double charge.
**Kafka** is a durable, ordered, replayable event log; producers append events to **topics**,
consumers read them independently, and a **consumer group** lets multiple consumer instances
share the work of one topic without double-processing the same message. The **transactional
outbox pattern** solves a specific, otherwise-unsolvable problem: how do you update a database
*and* publish an event about that update, such that either both happen or neither does, when the
database and the message broker are two separate systems with no shared transaction?

## B. Why

Without the outbox pattern, the naive approach - commit the database change, then call
`kafkaTemplate.send(...)` - has an unavoidable gap: if the process crashes (or Kafka is briefly
unreachable) between those two steps, the database change is durable but the event is lost
forever, and nothing else in the system ever learns the order was created. Flipping the order
(publish first, then commit) just moves the same gap to the other side: the event goes out, then
the DB write fails and rolls back, and now a consumer has acted on something that officially
never happened. The outbox pattern closes this gap by writing the *intent to publish* into the
same database, in the same transaction, as the real change - so it can never land without the
other.

## C. How it works internally

**Payments** (`PaymentService.pay`): the amount paid is always `order.getTotalAmount()` - the
client supplies only an order id, never an amount, so a tampered request can't pay a different
amount than the order's real total. The outcome (success/failure) is controlled by
`PaymentRequest.simulateFailure`, an explicit mock-only testing hook (a real gateway integration
would have no such field - the outcome would come from the provider's response). On success, the
order transitions `CREATED → CONFIRMED`; on failure, reserved stock is released and the order is
cancelled outright (see the Javadoc on that branch for why retry-in-place was rejected as more
complex without being more correct).

**Idempotency** (`IdempotencyService`, used by `PaymentController`, not `PaymentService`): the
client sends an `Idempotency-Key` header. Before any business logic runs, the controller asks
`IdempotencyService.findExistingReplay(key, request)` - if that key was used before with the
*same* request body (compared via a SHA-256 hash, not the raw body, so storage stays compact and
the comparison is a simple equality check), the exact previously-stored HTTP status and body are
replayed verbatim, and `PaymentService.pay` never runs again. If the same key shows up with a
*different* body, that's almost certainly a client bug (reusing a key across genuinely different
requests), so it's rejected with `409` rather than silently doing either the old or the new thing.
Handling this at the controller layer, not inside `PaymentService`, matters: a replayed request
should re-serve a response, not re-execute business logic at all.

**The outbox** (`OutboxEventWriter` + `OutboxEvent` + `OutboxRelay`): `OrderService` and
`PaymentService` never talk to Kafka directly. Instead, inside their existing `@Transactional`
methods, they call `OutboxEventWriter.enqueue(...)`, which just inserts a row into
`outbox_events` - an ordinary database write, part of the same transaction as the order/payment
change, with the exact same commit-or-rollback guarantee. A separate, independent component,
`OutboxRelay`, polls that table on a fixed schedule (`@Scheduled`, default every 2 seconds),
publishes each unpublished row to Kafka, and marks it published **only after a confirmed send**
(blocking on the send's future with a short timeout, converting Kafka's normally-async API into a
synchronous one for this one background loop, where blocking briefly is an acceptable trade-off
for simplicity). If the send fails, the row is left unpublished and tried again on the next poll
- the mechanism is self-healing against transient Kafka unavailability, at the cost of
**at-least-once** delivery (a crash between "Kafka confirmed receipt" and "row marked published"
means the next poll sends it again). This is exactly why the consumer has to be idempotent too
(see below) - the outbox pattern on its own only gets you reliable publication, not
exactly-once delivery end to end.

**Consumption** (`OrderEventAuditConsumer`): a single `@KafkaListener` subscribed to both topics,
recording every event into `event_audit_log` keyed by the producer's own `eventId`. The `UNIQUE`
constraint on that column is what makes redelivery safe: a duplicate arrives (consumer restart,
rebalance, the outbox's own at-least-once retries), `existsByEventId` catches the common case,
and a genuine race lands on the database's own constraint, caught and treated identically. A
message that can't even be parsed is deliberately rethrown rather than swallowed, so Kafka's
configured error handler (`KafkaErrorHandlingConfig`) takes over: 3 retries, 500ms apart, then
republished unchanged to a `.dlt` ("dead letter topic") - a human-monitored queue in a real
deployment, not something this app recovers from automatically.

## D. Code walkthrough

- **`DomainEvent<T>`** - a generic envelope (`eventId`, `eventType`, `version`, `occurredAt`,
  `payload`) wrapping every event payload. `version` is explicit data on the wire, independent of
  whatever Java record happens to represent the payload today - a consumer can reason about
  "OrderCreated v1" without caring that the producing code was refactored.
- **`OutboxEventWriter.enqueue`** - generates the event's `eventId` (a fresh UUID) here, once,
  at write time - not regenerated on every publish attempt, so retried publishes of the same row
  carry the same id, which is exactly what makes consumer-side deduplication possible at all.
- **`OutboxRelay.publishOne`** - wrapped in its own try/catch *per event*, not around the whole
  batch, specifically so one bad/slow event can't block every other event in the same poll from
  being published.
- **`PaymentController.pay`** - returns `ResponseEntity<String>`, not `ResponseEntity<PaymentResponse>`,
  because a replayed response must be the *exact bytes* stored earlier, not a fresh re-serialization
  of a (potentially differently-ordered, differently-formatted) object - re-serializing on replay
  would technically still be "correct" JSON but defeats the purpose of proving byte-for-byte replay.

## E. Request flow

`POST /api/payments` (fresh request, success case):
`PaymentController.pay` → `IdempotencyService.findExistingReplay` (miss) → `PaymentService.pay`
(loads order, checks ownership/state, saves `Payment(SUCCEEDED)`, calls `order.confirm()`,
calls `OutboxEventWriter.enqueue("PaymentSucceeded")` - **all inside one transaction**) → back in
the controller, `IdempotencyService.record` stores the response → `201` returned. Up to 2 seconds
later, independently: `OutboxRelay.publishPending` finds the new row, publishes it to
`orderflow.payment-events`, marks it published. Independently again: `OrderEventAuditConsumer`
receives it, records it in `event_audit_log`.

## F. Example — the real failure-path trail (live-verified, 2026-10-15)

Paying for order 1 with `{"orderId": 1, "simulateFailure": true}` produced, in order:

| `outbox_events` | `event_audit_log` |
|---|---|
| `PaymentFailed` (PAYMENT aggregate) | `PaymentFailed` |
| `OrderCancelled` (ORDER aggregate) | `OrderCancelled` |

Both published and consumed within the same 2-second poll window. The order's row showed
`status: CANCELLED` and the product's `quantityAvailable` increased back by the order's quantity
- all three effects (payment record, order cancellation, stock release) from one request. See
`docs/TESTING.md` for the exact commands.

## G. Interview preparation

**Q: Why not just publish to Kafka directly from inside the `@Transactional` service method,
right after saving the order?**
A: Because the database transaction and the Kafka publish are two separate systems with no
shared atomicity - if the process crashes or Kafka is briefly unreachable between the DB commit
and the Kafka call, the event is lost even though the order is durably saved, and nothing else in
the system ever learns about it. The outbox pattern makes "there is an event to publish" part of
the same atomic database write as the business change itself.

**Q: Does the outbox pattern give you exactly-once delivery?**
A: No - it gives you reliable *at-least-once* delivery (the event will eventually be published,
possibly more than once if a crash happens between Kafka confirming receipt and the outbox row
being marked published). Getting to effectively-once requires the consumer to be idempotent,
which is why `event_audit_log.event_id` has a uniqueness constraint - the end-to-end guarantee is
"delivered at least once, processed effectively once," not "delivered exactly once."

**Q: Why does a malformed message get rethrown instead of just logged and dropped?**
A: Dropping it silently means a corrupt or unexpected message just vanishes with no record and no
chance to reprocess it. Rethrowing hands it to the configured error handler, which retries (in
case it was transient) and then routes it to a dead-letter topic - visible and recoverable,
instead of silently gone.

**Q: What's the actual risk this project's idempotency design protects against?**
A: A client (or a flaky network) retrying a payment POST after not receiving the first response,
without any protection, could cause the server to process two separate successful payments for
one order. The `Idempotency-Key` plus stored-response replay means the second attempt gets back
the first attempt's actual result without ever re-running payment logic.

**Common mistake:** computing the idempotency check from the idempotency key alone, without also
hashing the request body - that allows a key to be silently reused across genuinely different
requests, serving one request's cached response for an unrelated one.

## H. Debugging

- `mysql ... -e "SELECT * FROM outbox_events WHERE published = 0"` - anything stuck here for more
  than a couple of poll intervals means `OutboxRelay` is failing to publish; check its ERROR logs
  for the underlying Kafka exception.
- `kafka-console-consumer --bootstrap-server localhost:9092 --topic orderflow.order-events --from-beginning`
  - read the raw JSON on a topic directly, bypassing this app's consumer entirely, to confirm
  what's actually on the wire.
- `SELECT * FROM event_audit_log` - if an event is in `outbox_events` (published) but missing
  here, check the consumer's logs for the exception that sent it to retry/dead-letter.
- `kafka-topics --bootstrap-server localhost:9092 --list` and the `.dlt`-suffixed topics
  specifically - anything there means a message exhausted its retries; read it with
  `kafka-console-consumer` to see what failed and why.

## I. Revision notes

- **Key takeaways:** idempotency keys protect against retried side-effecting requests; the
  outbox pattern makes "DB change" and "event to publish" atomic by writing both to the same
  database; at-least-once delivery requires an idempotent consumer to become effectively-once
  end to end; a malformed message should go to a dead-letter topic, not be silently dropped.
- **Important annotations:** `@KafkaListener`, `@Scheduled`.
- **Important commands:** `kafka-topics --bootstrap-server localhost:9092 --list`,
  `kafka-console-consumer --bootstrap-server localhost:9092 --topic <name> --from-beginning`.
- **Memory aid:** "Write the event where you write the data - publish it from somewhere else,
  later, and make showing up twice harmless."
- **Mini-quiz:**
  1. Why is the idempotency check keyed on a hash of the request body, not just the key alone?
     *(To detect and reject a key being reused for a materially different request, instead of
     silently serving a stale, unrelated cached response.)*
  2. If `OutboxRelay` crashes immediately after Kafka acknowledges a send but before the row is
     marked published, what happens? *(The event gets published again on the next poll - harmless
     because `event_audit_log`'s unique `event_id` constraint makes the consumer idempotent.)*
- **Coding task:** add a `PaymentCancelled`-style reconciliation job: a `@Scheduled` task that
  finds orders stuck in `CREATED` for longer than some threshold with no payment row at all, and
  explain (in a comment, not necessarily in code) what business decision it would need - cancel
  them automatically, or merely flag them for a human to review?
